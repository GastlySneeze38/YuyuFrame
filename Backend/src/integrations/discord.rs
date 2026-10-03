use base64::Engine as _;
use discord_rich_presence::{activity, DiscordIpc, DiscordIpcClient};
use std::sync::Mutex;
use std::sync::OnceLock;
use std::time::Duration;

/// ID de l'application Discord YuyuFrame (https://discord.com/developers/applications).
const DISCORD_APP_ID: &str = "1357094158103347301";

/// Même URL que `DOWNLOAD_URL` dans `Server/LauncherAPI/src/web/join.rs` —
/// dupliquée ici plutôt que partagée (crates séparées, pas de dépendance
/// entre le launcher et le serveur), à garder synchronisée manuellement si
/// jamais elle change. Le site plutôt qu'un lien GitHub direct : meilleure
/// vitrine (page d'accueil soignée) qu'une page de releases brute.
const DOWNLOAD_URL: &str = "https://yuyuframe.eu";

/// Page-pont LauncherAPI qui tente `yuyuframe://` nu (ramène juste le
/// launcher au premier plan) et bascule sur `DOWNLOAD_URL` si rien ne s'est
/// ouvert — voir `Server/LauncherAPI/src/web/open.rs`.
const OPEN_URL: &str = "https://api.yuyuframe.eu/open";

/// Coupe tout le pipeline Discord (Rich Presence + funnel "Rejoindre") sans retirer le code —
/// `false` = `connect_and_announce` ne tente jamais la connexion IPC, `set_playing`/`set_idle`
/// restent no-op (ils mettent juste `CURRENT_STATE` à jour, jamais poussé nulle part puisque
/// `DISCORD_CLIENT` n'est jamais initialisé). Remettre à `true` pour réactiver.
const DISCORD_ENABLED: bool = true;

/// Délai entre deux tentatives de connexion tant que Discord n'a pas répondu
/// (pas encore lancé au démarrage du launcher, lancé après coup par
/// l'utilisateur, etc.) — voir `connect_and_announce`.
const RETRY_DELAY: Duration = Duration::from_secs(30);

/// Intervalle de réaffirmation périodique de l'activité une fois connecté.
/// Discord n'a aucune notion de "priorité" entre apps : il affiche l'activité
/// la plus récemment mise à jour parmi toutes les connexions IPC locales
/// (client_id différent par app). Des mods comme `essential` (voir
/// `PvP-Mod/README.md`) ont leur propre Rich Presence et la poussent pendant
/// la partie — sans réémission régulière ici, la présence YuyuFrame envoyée
/// une seule fois au lancement se fait silencieusement écraser dès qu'un
/// autre client republie la sienne, pour le reste de la session. 15s reste
/// sous la limite de rate-limit de l'IPC Discord (~5 updates/20s) tout en
/// reprenant la main assez vite face à des mods qui republient aussi souvent.
const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(15);

/// Client IPC gardé en vie pour toute la durée du process — le crate ferme
/// la connexion dès que le client est droppé, donc un simple appel local
/// dans `setup()` sans le stocker quelque part afficherait la présence une
/// fraction de seconde puis la couperait aussitôt.
static DISCORD_CLIENT: OnceLock<Mutex<DiscordIpcClient>> = OnceLock::new();

/// Dernier état demandé par `set_playing`/`set_idle`, mémorisé indépendamment
/// de la connexion IPC. Nécessaire car `connect_and_announce` tourne sur son
/// propre thread (voir plus bas) : rien ne garantit qu'il ait fini son
/// handshake avant qu'un lancement de jeu n'appelle déjà `set_playing` — sans
/// ce cache, ce `set_playing` se contentait de ne rien faire (client pas
/// encore prêt), puis `connect_and_announce` écrasait tout avec l'activité
/// idle une fois connecté, laissant la présence bloquée sur "Dans le
/// launcher" alors qu'une partie tournait déjà.
static CURRENT_STATE: Mutex<PresenceState> = Mutex::new(PresenceState::Idle);

#[derive(Clone)]
enum PresenceState {
    Idle,
    Playing { instance_name: String, details: String, started_at: i64, join_url: Option<String> },
}

/// Base du site de redirection "Rejoindre" (LauncherAPI, route `/join/{payload}`
/// — voir `LauncherAPI/src/web/join.rs`) : Discord n'accepte que des URLs
/// `https://` sur les boutons d'activité, jamais un schéma custom directement
/// (voir `yuyuframe://`, enregistré côté OS pour le launcher lui-même) — cette
/// page fait le pont entre les deux.
fn join_base_url() -> String {
    std::env::var("YUYU_API_URL").unwrap_or_else(|_| "https://api.yuyuframe.eu".into())
}

/// Encode `{ip, mc_version, loader}` en base64 URL-safe pour la route
/// `/join/{payload}` de LauncherAPI — stateless : pas besoin de stocker quoi
/// que ce soit côté serveur, la page de redirection réutilise ce même payload
/// tel quel pour construire le lien `yuyuframe://join?data=...` (voir
/// join.rs) : mêmes caractères des deux côtés, jamais besoin de
/// ré-encoder/échapper quoi que ce soit. Retourne `None` si le JSON échoue à
/// sérialiser (ne devrait jamais arriver avec ces trois chaînes).
pub fn build_join_url(ip: &str, mc_version: &str, loader: &str) -> Option<String> {
    let payload = serde_json::json!({ "ip": ip, "mc_version": mc_version, "loader": loader });
    let encoded = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(payload.to_string());
    Some(format!("{}/join/{}", join_base_url(), encoded))
}

/// Construit l'activité Discord correspondant à `state` et l'envoie sur
/// `client`. Factorisé pour être appelé à la fois lors d'un changement d'état
/// (`set_playing`/`set_idle`) et lors d'une (re)connexion, qui doit rejouer le
/// dernier état demandé plutôt que de toujours repartir sur idle.
/// Discord limite à 2 boutons max par activité. Design retenu : un bouton FIXE
/// (téléchargement direct, ne dépend jamais de l'état — celui-là ne ment
/// jamais, tout le monde peut télécharger) + un bouton qui change selon l'état
/// (rejoindre le serveur en jeu, ou lancer le launcher au repos). Contrairement
/// à un unique bouton "générique", ce découpage évite le dilemme "Ouvrir" vs
/// "Télécharger" (voir historique) : le bouton fixe est toujours honnête, et le
/// bouton dynamique délègue la détection installé/pas installé à la page-pont
/// (`/open` ou `/join/{payload}`, voir Server/LauncherAPI/src/routes/), qui,
/// elle, peut réagir en JS (blur/visibilitychange) — impossible à faire
/// depuis Discord lui-même (juste un lien statique, aucun JS exécuté).
fn download_button() -> activity::Button<'static> {
    activity::Button::new("Télécharger YuyuFrame", DOWNLOAD_URL)
}

/// Asset uploadé dans Discord Developer Portal > Rich Presence > Art Assets
/// sous cette clé exacte — affiché dans les deux états (idle et en jeu), le
/// logo représente l'app elle-même, pas une activité en particulier.
fn logo_assets() -> activity::Assets<'static> {
    activity::Assets::new().large_image("yuyuframe_logo")
}

fn apply_state(client: &mut DiscordIpcClient, state: &PresenceState) {
    let activity = match state {
        PresenceState::Idle => activity::Activity::new()
            .state("Dans le launcher")
            .details("YuyuFrame")
            .assets(logo_assets())
            .timestamps(activity::Timestamps::new().start(chrono::Utc::now().timestamp()))
            .buttons(vec![download_button(), activity::Button::new("Lancer YuyuFrame", OPEN_URL)]),
        PresenceState::Playing { instance_name, details, started_at, join_url } => {
            // "Rejoindre" seulement si le lancement s'est fait avec une IP de
            // serveur connue (voir set_playing / play::launch) — sinon
            // (singleplayer, IP inconnue) rien à proposer à un ami qui
            // cliquerait dessus, donc repli sur le même bouton "Lancer" que
            // l'état idle plutôt que de n'afficher qu'un seul bouton.
            let second = match join_url {
                Some(url) => activity::Button::new("Rejoindre", url),
                None => activity::Button::new("Lancer YuyuFrame", OPEN_URL),
            };
            activity::Activity::new()
                .state(instance_name)
                .details(details)
                .assets(logo_assets())
                .timestamps(activity::Timestamps::new().start(*started_at))
                .buttons(vec![download_button(), second])
        }
    };

    if let Err(e) = client.set_activity(activity) {
        tracing::warn!("[Discord] mise à jour de l'activité échouée : {e}");
    }
}

/// Ouvre la connexion IPC locale vers Discord — named pipe sur Windows,
/// socket Unix sur Linux/Mac, entièrement géré en interne par le crate,
/// aucune distinction de plateforme à faire ici — et publie l'activité
/// courante (idle ou en jeu, selon `CURRENT_STATE`) une fois connectée.
///
/// Retente indéfiniment toutes les `RETRY_DELAY` tant que la connexion
/// échoue : Discord peut ne pas encore être lancé au démarrage du launcher
/// (auto-start plus lent, ou l'utilisateur le lance après coup) — sans ce
/// retry, un seul échec initial laissait la Rich Presence muette pour tout le
/// reste de la session, aucun mécanisme ne retentant la connexion plus tard.
///
/// Appelée depuis un thread séparé (voir `lib.rs`, `std::thread::spawn`,
/// PAS `tokio::spawn` : le crate fait de l'IPC bloquante, pas async) : le
/// handshake peut prendre un instant et cette boucle peut tourner tant que
/// Discord n'est pas disponible — jamais bloquant/fatal pour le reste du
/// démarrage du launcher, ce thread lui est entièrement dédié. Une fois
/// connecté, ce même thread reste vivant pour porter le heartbeat (voir
/// `HEARTBEAT_INTERVAL`) plutôt que de rendre la main.
pub fn connect_and_announce() {
    if !DISCORD_ENABLED {
        return;
    }

    loop {
        // BUG TROUVÉ (voir cargo check) : contrairement à ce que suggérait la
        // doc générale du crate, `DiscordIpcClient::new` en v1.1.0 renvoie
        // directement `Self`, PAS `Result<Self, _>` — aucune I/O n'a lieu à la
        // construction (juste la préparation du handshake), l'échec potentiel
        // (Discord absent) ne survient qu'au vrai `connect()` juste après.
        let mut client = DiscordIpcClient::new(DISCORD_APP_ID);

        match client.connect() {
            Ok(()) => {
                let state = CURRENT_STATE.lock().unwrap().clone();
                apply_state(&mut client, &state);
                tracing::info!("[Discord] Rich Presence connectée, activité envoyée");
                // set() échoue seulement si déjà initialisé — ne devrait
                // jamais arriver (une seule boucle de connexion à la fois,
                // voir lib.rs), mais autant ignorer proprement plutôt que
                // paniquer sur un double appel futur.
                let _ = DISCORD_CLIENT.set(Mutex::new(client));
                break;
            }
            Err(e) => {
                tracing::warn!(
                    "[Discord] connexion IPC échouée (Discord probablement fermé) : {e} — nouvelle tentative dans {}s",
                    RETRY_DELAY.as_secs(),
                );
                std::thread::sleep(RETRY_DELAY);
            }
        }
    }

    // Heartbeat : réémet l'activité courante à intervalle régulier pour
    // reprendre la main sur l'affichage face aux autres apps/mods qui
    // republient la leur (voir HEARTBEAT_INTERVAL). `continue` plutôt que de
    // paniquer si le mutex est empoisonné ou le client pas encore posé —
    // jamais fatal pour le reste du launcher.
    loop {
        std::thread::sleep(HEARTBEAT_INTERVAL);
        let Some(mutex) = DISCORD_CLIENT.get() else { continue };
        let Ok(mut client) = mutex.lock() else { continue };
        let state = CURRENT_STATE.lock().unwrap().clone();
        apply_state(&mut client, &state);
    }
}

/// Applique `state` : mémorise le nouvel état demandé (rejoué plus tard par
/// `connect_and_announce` si la connexion n'est pas encore prête) et le
/// pousse immédiatement sur le client si déjà connecté.
fn set_state(state: PresenceState) {
    *CURRENT_STATE.lock().unwrap() = state.clone();

    let Some(mutex) = DISCORD_CLIENT.get() else { return };
    let Ok(mut client) = mutex.lock() else { return };
    apply_state(&mut client, &state);
}

/// Bascule la présence sur "en train de jouer" — appelée au lancement
/// effectif d'une instance (voir `play/launch.rs`, juste après le
/// passage de `running_instances` à non-vide). No-op silencieux côté IPC si
/// la connexion n'est pas encore établie ou si Discord n'est pas lancé —
/// jamais fatal pour le lancement du jeu lui-même ; l'état est cependant
/// toujours mémorisé (voir `CURRENT_STATE`) et sera appliqué dès que la
/// connexion aboutira. `instance_name`/`mc_version` pris par valeur (pas de
/// lifetime à gérer) : appelée depuis `tokio::task::spawn_blocking`, donc
/// déjà dans une closure `'static`.
pub fn set_playing(instance_name: String, mc_version: String, server_ip: Option<String>, loader: String) {
    // Bouton "Rejoindre" seulement si on connaît l'IP du serveur rejoint au
    // lancement (voir `connect_server` dans play::launch) — sans IP, rien
    // à proposer à un ami qui cliquerait dessus.
    let join_url = server_ip.and_then(|ip| build_join_url(&ip, &mc_version, &loader));
    set_state(PresenceState::Playing {
        details: format!("Joue à Minecraft {mc_version}"),
        instance_name,
        // Nouveau timestamp de début à CHAQUE lancement (pas celui de
        // connect_and_announce, qui datait du démarrage du launcher) —
        // Discord affiche un chrono "depuis" basé dessus, doit repartir de
        // 0 à chaque nouvelle partie plutôt que de continuer à courir
        // depuis l'ouverture du launcher.
        started_at: chrono::Utc::now().timestamp(),
        join_url,
    });
}

/// Revient à l'état "dans le launcher" — appelée quand PLUS AUCUNE instance
/// ne tourne (voir `play/launch.rs`, gardé par `AppState::any_running`
/// pour ne pas repasser en idle si une AUTRE instance est encore en cours).
pub fn set_idle() {
    set_state(PresenceState::Idle);
}
