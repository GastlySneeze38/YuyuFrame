use discord_rich_presence::{activity, DiscordIpc, DiscordIpcClient};
use std::sync::{Mutex, OnceLock};

/// ID de l'application Discord YuyuFrame (https://discord.com/developers/applications).
const DISCORD_APP_ID: &str = "1357094158103347301";

/// Client IPC gardé en vie pour toute la durée du process — le crate ferme
/// la connexion dès que le client est droppé, donc un simple appel local
/// dans `setup()` sans le stocker quelque part afficherait la présence une
/// fraction de seconde puis la couperait aussitôt.
static DISCORD_CLIENT: OnceLock<Mutex<DiscordIpcClient>> = OnceLock::new();

/// Ouvre la connexion IPC locale vers Discord — named pipe sur Windows,
/// socket Unix sur Linux/Mac, entièrement géré en interne par le crate,
/// aucune distinction de plateforme à faire ici — et publie l'activité
/// initiale au lancement du launcher.
///
/// Appelée depuis un thread séparé (voir `lib.rs`, `std::thread::spawn`,
/// PAS `tokio::spawn` : le crate fait de l'IPC bloquante, pas async) : le
/// handshake peut prendre un instant, et Discord peut tout simplement ne
/// pas être lancé sur la machine — jamais bloquant/fatal pour le reste du
/// démarrage du launcher, juste un log si ça échoue à une étape ou une autre.
pub fn connect_and_announce() {
    // BUG TROUVÉ (voir cargo check) : contrairement à ce que suggérait la
    // doc générale du crate, `DiscordIpcClient::new` en v1.1.0 renvoie
    // directement `Self`, PAS `Result<Self, _>` — aucune I/O n'a lieu à la
    // construction (juste la préparation du handshake), l'échec potentiel
    // (Discord absent) ne survient qu'au vrai `connect()` juste après.
    let mut client = DiscordIpcClient::new(DISCORD_APP_ID);

    if let Err(e) = client.connect() {
        tracing::warn!("[Discord] connexion IPC échouée (Discord probablement fermé) : {e}");
        return;
    }

    if let Err(e) = client.set_activity(idle_activity()) {
        tracing::warn!("[Discord] envoi de l'activité échoué : {e}");
        return;
    }

    tracing::info!("[Discord] Rich Presence connectée, activité envoyée");
    // set() échoue seulement si déjà initialisé — ne devrait jamais arriver
    // (connect_and_announce n'est appelée qu'une fois au démarrage, voir
    // lib.rs), mais autant ignorer proprement plutôt que paniquer sur un
    // double appel futur.
    let _ = DISCORD_CLIENT.set(Mutex::new(client));
}

/// Activité "dans le menu" — factorisée car réutilisée par
/// `connect_and_announce` (état initial) ET `set_idle` (retour au menu
/// après une partie, voir plus bas).
fn idle_activity() -> activity::Activity<'static> {
    activity::Activity::new()
        .state("Dans le launcher")
        .details("YuyuFrame")
        // Nécessite un asset uploadé sous cette clé exacte dans Discord
        // Developer Portal > Rich Presence > Art Assets — décommenter une
        // fois l'image ajoutée là-bas (sinon Discord ignore juste l'image,
        // sans erreur, mais autant ne pas référencer une clé qui n'existe
        // pas encore).
        // .assets(activity::Assets::new().large_image("yuyuframe_logo"))
        .timestamps(activity::Timestamps::new().start(chrono::Utc::now().timestamp()))
}

/// Bascule la présence sur "en train de jouer" — appelée au lancement
/// effectif d'une instance (voir `commands/launch.rs`, juste après le
/// passage de `running_instances` à non-vide). No-op silencieux si la
/// connexion initiale a échoué ou si Discord n'est pas lancé — jamais
/// fatal pour le lancement du jeu lui-même. `instance_name`/`mc_version`
/// pris par valeur (pas de lifetime à gérer) : appelée depuis
/// `tokio::task::spawn_blocking`, donc déjà dans une closure `'static`.
pub fn set_playing(instance_name: String, mc_version: String) {
    let Some(mutex) = DISCORD_CLIENT.get() else { return };
    let Ok(mut client) = mutex.lock() else { return };

    let details = format!("Joue à Minecraft {mc_version}");
    let activity = activity::Activity::new()
        .state(&instance_name)
        .details(&details)
        // Nouveau timestamp de début à CHAQUE lancement (pas celui de
        // connect_and_announce, qui datait du démarrage du launcher) —
        // Discord affiche un chrono "depuis" basé dessus, doit repartir de
        // 0 à chaque nouvelle partie plutôt que de continuer à courir
        // depuis l'ouverture du launcher.
        .timestamps(activity::Timestamps::new().start(chrono::Utc::now().timestamp()));

    if let Err(e) = client.set_activity(activity) {
        tracing::warn!("[Discord] mise à jour de l'activité (en jeu) échouée : {e}");
    }
}

/// Revient à l'état "dans le launcher" — appelée quand PLUS AUCUNE instance
/// ne tourne (voir `commands/launch.rs`, gardé par `AppState::any_running`
/// pour ne pas repasser en idle si une AUTRE instance est encore en cours).
pub fn set_idle() {
    let Some(mutex) = DISCORD_CLIENT.get() else { return };
    let Ok(mut client) = mutex.lock() else { return };

    if let Err(e) = client.set_activity(idle_activity()) {
        tracing::warn!("[Discord] retour à l'état launcher échoué : {e}");
    }
}
