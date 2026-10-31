//! Le statut Discord (Rich Presence) : ce que les amis Discord voient du
//! joueur sur son profil.
//!
//! Dans le launcher : « Dans le launcher ». En jeu : la version, ou le duel en
//! cours (« En duel classé · Masse ») avec l'icône du rang du joueur dans ce
//! mode en petite image, et le serveur **seulement si le réglage des amis le
//! permet** (`server_visibility` différent de « personne », donné par
//! l'interface : `discord_presence_configure`). Avec le serveur vient le
//! bouton « Rejoindre », dont le lien porte un ticket à notre nom : celui qui
//! rejoint par là peut ensuite nous ajouter en ami d'un clic
//! (LauncherAPI `/friends/join-ticket`).
//!
//! Les textes suivent la langue de l'interface (`app::locale`).

use base64::Engine as _;
use discord_rich_presence::{activity, DiscordIpc, DiscordIpcClient};
use serde_json::Value;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use crate::friends::presence::GameActivity;
use crate::state::SharedState;

/// ID de l'application Discord YuyuFrame (https://discord.com/developers/applications).
const DISCORD_APP_ID: &str = "1357094158103347301";

/// Même URL que `DOWNLOAD_URL` dans `Server/LauncherAPI/src/web/join.rs` —
/// dupliquée ici plutôt que partagée (crates séparées, pas de dépendance
/// entre le launcher et le serveur), à garder synchronisée manuellement.
const DOWNLOAD_URL: &str = "https://yuyuframe.eu";

/// Page-pont LauncherAPI qui tente `yuyuframe://` nu (ramène juste le
/// launcher au premier plan) et bascule sur `DOWNLOAD_URL` si rien ne s'est
/// ouvert — voir `Server/LauncherAPI/src/web/open.rs`.
const OPEN_URL: &str = "https://api.yuyuframe.eu/open";

/// Les icônes de rang, servies par le site (`Server/Website/Frontend/public/ranks/`).
/// Discord accepte une adresse https à la place d'une image téléversée dans
/// le portail développeur.
const RANK_IMAGE_BASE: &str = "https://yuyuframe.eu/ranks";

/// Coupe tout le statut Discord sans retirer le code.
const DISCORD_ENABLED: bool = true;

/// Délai entre deux tentatives de connexion tant que Discord n'a pas répondu.
const RETRY_DELAY: Duration = Duration::from_secs(30);

/// Réaffirmation périodique de l'activité : Discord affiche l'activité la plus
/// récemment mise à jour parmi toutes les applications locales, et certains
/// mods publient la leur en boucle. 15 s reste sous la limite de l'IPC (~5
/// mises à jour par 20 s).
const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(15);

/// Client IPC gardé en vie pour toute la durée du process (le crate ferme la
/// connexion quand il est libéré).
static DISCORD_CLIENT: OnceLock<Mutex<DiscordIpcClient>> = OnceLock::new();

/// Dernier état demandé, mémorisé indépendamment de la connexion : il est
/// rejoué dès qu'elle aboutit.
static CURRENT_STATE: Mutex<PresenceState> = Mutex::new(PresenceState::Idle);

/// Le réglage des amis permet de montrer le serveur (`server_visibility` ≠
/// « personne »). Faux tant que l'interface ne l'a pas dit : par défaut,
/// personne ne voit le serveur.
static SHOW_SERVER: AtomicBool = AtomicBool::new(false);

/// L'état du launcher, pour les appels au serveur (ticket, rang).
static APP_STATE: OnceLock<SharedState> = OnceLock::new();

#[derive(Clone)]
enum PresenceState {
    Idle,
    Playing(Playing),
}

#[derive(Clone)]
struct Playing {
    instance_name: String,
    mc_version: String,
    loader: String,
    started_at: i64,
    /// Le serveur demandé au lancement (`connect_server`), à défaut de celui
    /// que le jeu annonce.
    launch_server: Option<String>,
    /// Ce que le jeu annonce : serveur réel, duel en cours.
    game: GameActivity,
    /// Le rang du joueur dans le mode du duel : clé d'icône (`gold`) et
    /// division dans le palier (1 à 3, `None` en Champion).
    rank: Option<(String, Option<u8>)>,
    /// Ticket à notre nom pour le lien « Rejoindre ».
    ticket: Option<String>,
}

impl Playing {
    fn server(&self) -> Option<&str> {
        if !SHOW_SERVER.load(Ordering::Relaxed) {
            return None;
        }
        self.game.server.as_deref().or(self.launch_server.as_deref())
    }
}

// ── Textes ───────────────────────────────────────────────────────────────

struct Texts {
    in_launcher: &'static str,
    /// `{version}`.
    playing: &'static str,
    /// `{mode}`.
    ranked_duel: &'static str,
    duel: &'static str,
    mace: &'static str,
    sword: &'static str,
    other: &'static str,
    /// `{server}`.
    on_server: &'static str,
    download: &'static str,
    join: &'static str,
    open: &'static str,
    tiers: [&'static str; 7],
}

fn texts() -> Texts {
    let lang = crate::app::locale::ui_language().unwrap_or_else(|| "fr".into());
    match lang.as_str() {
        "en" => Texts {
            in_launcher: "In the launcher",
            playing: "Playing Minecraft {version}",
            ranked_duel: "In a ranked duel · {mode}",
            duel: "In a duel · {mode}",
            mace: "Mace",
            sword: "Sword",
            other: "Other",
            on_server: "On {server}",
            download: "Download YuyuFrame",
            join: "Join",
            open: "Open YuyuFrame",
            tiers: ["Wood", "Stone", "Iron", "Gold", "Diamond", "Netherite", "Champion"],
        },
        "es" => Texts {
            in_launcher: "En el launcher",
            playing: "Jugando a Minecraft {version}",
            ranked_duel: "En duelo clasificatorio · {mode}",
            duel: "En duelo · {mode}",
            mace: "Maza",
            sword: "Espada",
            other: "Otro",
            on_server: "En {server}",
            download: "Descargar YuyuFrame",
            join: "Unirse",
            open: "Abrir YuyuFrame",
            tiers: ["Madera", "Piedra", "Hierro", "Oro", "Diamante", "Netherita", "Campeón"],
        },
        "de" => Texts {
            in_launcher: "Im Launcher",
            playing: "Spielt Minecraft {version}",
            ranked_duel: "Im Ranglistenduell · {mode}",
            duel: "Im Duell · {mode}",
            mace: "Streitkolben",
            sword: "Schwert",
            other: "Andere",
            on_server: "Auf {server}",
            download: "YuyuFrame herunterladen",
            join: "Beitreten",
            open: "YuyuFrame öffnen",
            tiers: ["Holz", "Stein", "Eisen", "Gold", "Diamant", "Netherit", "Champion"],
        },
        "it" => Texts {
            in_launcher: "Nel launcher",
            playing: "Gioca a Minecraft {version}",
            ranked_duel: "In duello classificato · {mode}",
            duel: "In duello · {mode}",
            mace: "Mazza",
            sword: "Spada",
            other: "Altro",
            on_server: "Su {server}",
            download: "Scarica YuyuFrame",
            join: "Unisciti",
            open: "Apri YuyuFrame",
            tiers: ["Legno", "Pietra", "Ferro", "Oro", "Diamante", "Netherite", "Campione"],
        },
        "pt" => Texts {
            in_launcher: "No launcher",
            playing: "Jogando Minecraft {version}",
            ranked_duel: "Em duelo ranqueado · {mode}",
            duel: "Em duelo · {mode}",
            mace: "Maça",
            sword: "Espada",
            other: "Outro",
            on_server: "Em {server}",
            download: "Baixar YuyuFrame",
            join: "Entrar",
            open: "Abrir YuyuFrame",
            tiers: ["Madeira", "Pedra", "Ferro", "Ouro", "Diamante", "Netherita", "Campeão"],
        },
        "pl" => Texts {
            in_launcher: "W launcherze",
            playing: "Gra w Minecraft {version}",
            ranked_duel: "W pojedynku rankingowym · {mode}",
            duel: "W pojedynku · {mode}",
            mace: "Buzdygan",
            sword: "Miecz",
            other: "Inne",
            on_server: "Na {server}",
            download: "Pobierz YuyuFrame",
            join: "Dołącz",
            open: "Otwórz YuyuFrame",
            tiers: ["Drewno", "Kamień", "Żelazo", "Złoto", "Diament", "Netheryt", "Mistrz"],
        },
        "ru" => Texts {
            in_launcher: "В лаунчере",
            playing: "Играет в Minecraft {version}",
            ranked_duel: "В рейтинговой дуэли · {mode}",
            duel: "В дуэли · {mode}",
            mace: "Булава",
            sword: "Меч",
            other: "Другое",
            on_server: "На {server}",
            download: "Скачать YuyuFrame",
            join: "Присоединиться",
            open: "Открыть YuyuFrame",
            tiers: ["Дерево", "Камень", "Железо", "Золото", "Алмаз", "Незерит", "Чемпион"],
        },
        _ => Texts {
            in_launcher: "Dans le launcher",
            playing: "Joue à Minecraft {version}",
            ranked_duel: "En duel classé · {mode}",
            duel: "En duel · {mode}",
            mace: "Masse",
            sword: "Épée",
            other: "Autre",
            on_server: "Sur {server}",
            download: "Télécharger YuyuFrame",
            join: "Rejoindre",
            open: "Lancer YuyuFrame",
            tiers: ["Bois", "Pierre", "Fer", "Or", "Diamant", "Netherite", "Champion"],
        },
    }
}

/// Clés des icônes de rang, dans l'ordre des paliers (`lib/competitive.ts` `TIER_KEYS`).
const TIER_KEYS: [&str; 7] = ["wood", "stone", "iron", "gold", "diamond", "netherite", "champion"];
const DIVISIONS_PER_TIER: i64 = 3;
const CHAMPION: i64 = (TIER_KEYS.len() as i64 - 1) * DIVISIONS_PER_TIER;
const PLACEMENT_DUELS: i64 = 10;

// ── Le lien « Rejoindre » ────────────────────────────────────────────────

/// Base du site de redirection « Rejoindre » (LauncherAPI `/join/{payload}`) :
/// Discord n'accepte que des liens `https://` sur les boutons.
fn join_base_url() -> String {
    std::env::var("YUYU_API_URL").unwrap_or_else(|_| "https://api.yuyuframe.eu".into())
}

/// `{ip, mc_version, loader, host?}` en base64 URL pour `/join/{payload}`.
/// `host` : le ticket à notre nom, de quoi nous ajouter en ami après coup.
pub fn build_join_url(ip: &str, mc_version: &str, loader: &str, host: Option<&str>) -> String {
    let mut payload = serde_json::json!({ "ip": ip, "mc_version": mc_version, "loader": loader });
    if let Some(host) = host {
        payload["host"] = Value::String(host.to_string());
    }
    let encoded = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(payload.to_string());
    format!("{}/join/{}", join_base_url(), encoded)
}

// ── L'activité ───────────────────────────────────────────────────────────

fn apply_state(client: &mut DiscordIpcClient, state: &PresenceState) {
    let t = texts();
    let download = activity::Button::new(t.download, DOWNLOAD_URL);
    let logo = || activity::Assets::new().large_image("yuyuframe_logo").large_text("YuyuFrame");

    let result = match state {
        PresenceState::Idle => client.set_activity(
            activity::Activity::new()
                .state(t.in_launcher)
                .details("YuyuFrame")
                .assets(logo())
                .timestamps(activity::Timestamps::new().start(chrono::Utc::now().timestamp()))
                .buttons(vec![download, activity::Button::new(t.open, OPEN_URL)]),
        ),
        PresenceState::Playing(p) => {
            let details = match &p.game.duel {
                Some((ranked, mode)) => {
                    let mode = match mode.as_str() {
                        "mace" => t.mace,
                        "sword" => t.sword,
                        _ => t.other,
                    };
                    (if *ranked { t.ranked_duel } else { t.duel }).replace("{mode}", mode)
                }
                None => t.playing.replace("{version}", &p.mc_version),
            };
            let server = p.server();
            let state_line = match server {
                Some(server) => t.on_server.replace("{server}", server),
                None => p.instance_name.clone(),
            };
            // L'icône du rang, seulement pendant un duel classé où l'on est placé.
            let rank_image;
            let rank_text;
            let mut assets = logo();
            if let (Some((true, _)), Some((tier, division))) = (&p.game.duel, &p.rank) {
                rank_image = format!("{RANK_IMAGE_BASE}/{tier}.png");
                let index = TIER_KEYS.iter().position(|k| k == tier).unwrap_or(0);
                rank_text = match division {
                    Some(d) => format!("{} {d}", t.tiers[index]),
                    None => t.tiers[index].to_string(),
                };
                assets = assets.small_image(&rank_image).small_text(&rank_text);
            }
            let join_url = server.map(|ip| build_join_url(ip, &p.mc_version, &p.loader, p.ticket.as_deref()));
            let second = match &join_url {
                Some(url) => activity::Button::new(t.join, url),
                None => activity::Button::new(t.open, OPEN_URL),
            };
            client.set_activity(
                activity::Activity::new()
                    .state(&state_line)
                    .details(&details)
                    .assets(assets)
                    .timestamps(activity::Timestamps::new().start(p.started_at))
                    .buttons(vec![download, second]),
            )
        }
    };
    if let Err(e) = result {
        tracing::warn!("[Discord] mise à jour de l'activité échouée : {e}");
    }
}

/// Ouvre la connexion IPC locale vers Discord et publie l'activité courante,
/// puis la réaffirme toutes les `HEARTBEAT_INTERVAL`. Retente indéfiniment
/// tant que Discord n'est pas lancé. Sur un thread natif dédié (IPC bloquante).
pub fn connect_and_announce() {
    if !DISCORD_ENABLED {
        return;
    }

    loop {
        // `new` ne fait aucune entrée-sortie : l'échec possible est au `connect`.
        let mut client = DiscordIpcClient::new(DISCORD_APP_ID);
        match client.connect() {
            Ok(()) => {
                let state = CURRENT_STATE.lock().unwrap_or_else(|p| p.into_inner()).clone();
                apply_state(&mut client, &state);
                tracing::info!("[Discord] Rich Presence connectée, activité envoyée");
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

    loop {
        std::thread::sleep(HEARTBEAT_INTERVAL);
        push();
    }
}

/// Pousse l'état courant sur le client, s'il est connecté.
fn push() {
    let Some(mutex) = DISCORD_CLIENT.get() else { return };
    let Ok(mut client) = mutex.lock() else { return };
    let state = CURRENT_STATE.lock().unwrap_or_else(|p| p.into_inner()).clone();
    apply_state(&mut client, &state);
}

/// Pousse depuis n'importe quel contexte : l'IPC bloque, jamais sur le runtime async.
fn push_soon() {
    std::thread::spawn(push);
}

fn set_state(state: PresenceState) {
    *CURRENT_STATE.lock().unwrap_or_else(|p| p.into_inner()) = state;
    push();
}

/// Modifie la partie en cours, s'il y en a une. Rend vrai si elle existait.
fn update_playing(change: impl FnOnce(&mut Playing)) -> bool {
    let mut current = CURRENT_STATE.lock().unwrap_or_else(|p| p.into_inner());
    match &mut *current {
        PresenceState::Playing(p) => {
            change(p);
            true
        }
        PresenceState::Idle => false,
    }
}

/// Donne au statut l'état du launcher (appels au serveur). Au démarrage.
pub fn bind(state: SharedState) {
    let _ = APP_STATE.set(state);
}

/// L'interface dit si le réglage des amis permet de montrer le serveur.
#[tauri::command]
pub fn discord_presence_configure(show_server: bool) {
    if SHOW_SERVER.swap(show_server, Ordering::Relaxed) != show_server {
        push_soon();
    }
}

/// Bascule sur « en jeu » au lancement effectif d'une instance
/// (`play/launch.rs`). Appelée depuis `spawn_blocking`.
pub fn set_playing(instance_name: String, mc_version: String, server_ip: Option<String>, loader: String) {
    set_state(PresenceState::Playing(Playing {
        instance_name,
        mc_version,
        loader,
        // Le chrono de Discord repart de zéro à chaque partie.
        started_at: chrono::Utc::now().timestamp(),
        launch_server: server_ip,
        game: GameActivity::default(),
        rank: None,
        ticket: None,
    }));
    // Le ticket du lien « Rejoindre » arrive un peu après (un appel au serveur).
    if let Some(state) = APP_STATE.get().cloned() {
        tauri::async_runtime::spawn(async move {
            if let Some(ticket) = crate::friends::join_ticket(&state).await {
                if update_playing(|p| p.ticket = Some(ticket)) {
                    push_soon();
                }
            }
        });
    }
}

/// Revient à « dans le launcher » quand plus aucune instance ne tourne.
pub fn set_idle() {
    set_state(PresenceState::Idle);
}

/// Le jeu annonce ce qu'il fait (`friends::presence::set_game`).
pub fn set_game_activity(activity: GameActivity) {
    let ranked_mode = activity.duel.as_ref().filter(|(ranked, _)| *ranked).map(|(_, mode)| mode.clone());
    let mut fetch = None;
    let known = update_playing(|p| {
        let was = p.game.duel.as_ref().filter(|(ranked, _)| *ranked).map(|(_, mode)| mode.clone());
        if ranked_mode != was {
            p.rank = None;
            fetch = ranked_mode.clone();
        }
        p.game = activity;
    });
    if !known {
        return;
    }
    push_soon();
    // Un duel classé commence : on va chercher le rang de ce mode.
    let (Some(mode), Some(state)) = (fetch, APP_STATE.get().cloned()) else { return };
    tauri::async_runtime::spawn(async move {
        let Some(rank) = rank_of(&state, &mode).await else { return };
        let still = update_playing(|p| {
            if p.game.duel.as_ref().is_some_and(|(ranked, m)| *ranked && *m == mode) {
                p.rank = Some(rank);
            }
        });
        if still {
            push_soon();
        }
    });
}

/// Le rang du compte Minecraft actif dans `mode` (`mace`, `sword`), lu sur
/// LauncherAPI. `None` : pas placé, compte non prouvé, serveur muet.
async fn rank_of(state: &SharedState, mode: &str) -> Option<(String, Option<u8>)> {
    let code = match mode {
        "mace" => 1,
        "sword" => 2,
        _ => return None,
    };
    let uuid = {
        let db = state.read().await.db.clone();
        let conn = db.lock().await;
        crate::db::get_active_mc_uuid(&conn).ok().flatten()?
    };
    let uuid: String = uuid.chars().filter(|c| c.is_ascii_hexdigit()).collect();
    if uuid.len() != 32 {
        return None;
    }
    let answer = crate::server::get(state, &format!("/ranked/state/{uuid}"), &[]).await.ok()?;
    rank_in(&answer, code)
}

/// Lit la position d'un mode dans `{ state: { ladder: { standings } } }`.
fn rank_in(answer: &Value, mode_code: i64) -> Option<(String, Option<u8>)> {
    let standings = answer.pointer("/state/ladder/standings")?.as_array()?;
    let row = standings.iter().find(|r| r.get("mode").and_then(Value::as_i64) == Some(mode_code))?;
    if row.get("duels").and_then(Value::as_i64).unwrap_or(0) < PLACEMENT_DUELS {
        return None;
    }
    let division = row.get("division").and_then(Value::as_i64).unwrap_or(0).clamp(0, CHAMPION);
    let tier = (division / DIVISIONS_PER_TIER).min(TIER_KEYS.len() as i64 - 1) as usize;
    let number = (division < CHAMPION).then(|| (division % DIVISIONS_PER_TIER + 1) as u8);
    Some((TIER_KEYS[tier].to_string(), number))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn le_rang_se_lit_seulement_une_fois_place() {
        let answer = serde_json::json!({ "state": { "ladder": { "standings": [
            { "mode": 1, "division": 10, "points": 120, "duels": 25 },
            { "mode": 2, "division": 4, "points": 0, "duels": 3 },
        ] } } });
        assert_eq!(rank_in(&answer, 1), Some(("gold".into(), Some(2))));
        assert_eq!(rank_in(&answer, 2), None, "en placement");
        let champion = serde_json::json!({ "state": { "ladder": { "standings": [{ "mode": 1, "division": 99, "duels": 40 }] } } });
        assert_eq!(rank_in(&champion, 1), Some(("champion".into(), None)));
    }

    #[test]
    fn le_lien_rejoindre_porte_le_ticket() {
        let url = build_join_url("play.example.net:25565", "1.21.4", "fabric", Some("abc"));
        let payload = url.rsplit('/').next().unwrap();
        let json: Value = serde_json::from_slice(&base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(payload).unwrap()).unwrap();
        assert_eq!(json["host"], "abc");
        assert_eq!(json["ip"], "play.example.net:25565");
    }
}
