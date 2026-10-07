//! « Je suis en jeu » : ce qui permet aux autres joueurs YuyuFrame de
//! reconnaître celui-ci dans un monde (LauncherAPI `PUT /v1/presence`).
//!
//! Une tâche par partie, coupée quand le jeu s'arrête. Elle prouve d'abord au
//! serveur que le compte Minecraft est bien celui du joueur (voir
//! `account::yuyu::profile::prove_minecraft_account`), puis renvoie le signal
//! à la cadence que le serveur demande. Rien d'autre ne part : ni le serveur
//! de jeu, ni l'instance, ni les joueurs croisés.
//!
//! Sans compte YuyuFrame ou avec un compte hors ligne, la tâche ne fait rien.
//! Le réglage « apparaître comme joueur YuyuFrame » coupe le signal — pas la
//! preuve, qui ne montre rien à personne. Aucun arrêt n'est annoncé : sans
//! signal, le serveur oublie le joueur de lui-même en quelques minutes.
//!
//! Rien ici ne doit jamais gêner une partie : tout échec est noté et retenté
//! au tour suivant.

use std::time::Duration;

use serde_json::json;
use tokio::task::JoinHandle;

use crate::account::yuyu::profile::prove_minecraft_account;
use crate::db::prefs;
use crate::server as api;
use crate::state::{MinecraftSession, SharedState};

/// Cadence tant que le serveur n'a pas donné la sienne (`renew_in`).
const DEFAULT_RENEW: Duration = Duration::from_secs(120);
/// Bornes de la cadence annoncée par le serveur : une valeur absurde ne doit
/// ni marteler l'API ni faire disparaître le joueur.
const MIN_RENEW_SECS: u64 = 30;
const MAX_RENEW_SECS: u64 = 240;

async fn visible(state: &SharedState) -> bool {
    let s = state.read().await;
    let conn = s.db.lock().await;
    prefs::get_bool(&conn, prefs::PRESENCE_VISIBLE, true)
}

async fn signed_in(state: &SharedState) -> bool {
    state.read().await.yuyu_session.is_some()
}

/// Lance le signal pour la partie qui démarre. À couper (`abort`) à sa fin.
pub fn spawn(state: SharedState, session: MinecraftSession) -> JoinHandle<()> {
    tokio::spawn(async move {
        // Un compte hors ligne n'est pas reconnu en jeu : rien à prouver, rien à annoncer.
        if session.refresh_token.is_none() {
            return;
        }
        let mut proven = match prove_minecraft_account(&state, &session).await {
            Ok(()) => true,
            Err(e) => {
                tracing::debug!("compte Minecraft non prouvé : {e}");
                false
            }
        };
        let mut renew = DEFAULT_RENEW;
        loop {
            // Relus à chaque tour : le réglage et la connexion peuvent changer en cours de partie.
            if signed_in(&state).await && visible(&state).await {
                if !proven {
                    proven = prove_minecraft_account(&state, &session).await.is_ok();
                }
                if proven {
                    match api::put(&state, "/presence", json!({ "uuid": session.uuid })).await {
                        Ok(resp) => {
                            if let Some(secs) = resp.get("renew_in").and_then(|v| v.as_u64()) {
                                renew = Duration::from_secs(secs.clamp(MIN_RENEW_SECS, MAX_RENEW_SECS));
                            }
                        }
                        Err(e) => {
                            tracing::debug!("signal de présence refusé : {}", e.message);
                            // Preuve trop ancienne ou compte inconnu du serveur : à refaire.
                            // Une panne de réseau, elle, ne remet pas la preuve en cause.
                            if e.code == "bad_request" || e.code == "not_found" {
                                proven = false;
                            }
                        }
                    }
                }
            }
            tokio::time::sleep(renew).await;
        }
    })
}

/// Posée dans l'environnement du jeu : dit à `content_core.dll` que son
/// entrée standard est bien ce canal, et pas la console d'un lancement à la main.
pub const TOKEN_CHANNEL_ENV: &str = "YUYU_PRESENCE_STDIN";
/// Début de chaque ligne écrite au jeu. Le même dans
/// `content-core/src/presence/token.rs`.
const TOKEN_LINE_PREFIX: &str = "yf-presence-token ";
/// Le jeton d'accès vit 15 minutes et le launcher le renouvelle une minute
/// avant sa fin : regarder plus souvent que cette minute garantit que le jeu
/// reçoit le suivant avant que le sien n'expire.
const TOKEN_CHECK_EVERY: Duration = Duration::from_secs(20);

/// Donne au jeu de quoi demander lui-même quels joueurs croisés utilisent
/// YuyuFrame (`POST /v1/presence/lookup`, appelé par `content_core.dll`) : le
/// jeton d'accès courant, réécrit à chaque renouvellement.
///
/// Le canal est l'entrée standard du jeu — un tube que seul ce processus
/// enfant reçoit. Rien sur le disque, rien sur la ligne de commande ni dans
/// l'environnement, donc rien dans un rapport de plantage. Le jeton de
/// rafraîchissement, lui, ne quitte jamais le launcher : sans le launcher, le
/// jeu garde au plus un jeton de 15 minutes.
///
/// S'arrête avec la partie (`stop`), ou dès que le tube ne se laisse plus
/// écrire. Sans compte YuyuFrame, rien n'est envoyé.
pub fn feed_tokens(state: SharedState, mut stdin: tokio::process::ChildStdin, stop: std::sync::Arc<std::sync::atomic::AtomicBool>) {
    use tokio::io::AsyncWriteExt;
    tokio::spawn(async move {
        let mut sent = String::new();
        while !stop.load(std::sync::atomic::Ordering::Relaxed) {
            if signed_in(&state).await {
                match api::valid_access_token(&state).await {
                    Ok(token) if token != sent => {
                        let line = format!("{TOKEN_LINE_PREFIX}{token}\n");
                        // Borné : si personne ne lit en face (DLL absente), le
                        // tube finit par se remplir et l'écriture attendrait sans fin.
                        let written = tokio::time::timeout(Duration::from_secs(5), async {
                            stdin.write_all(line.as_bytes()).await?;
                            stdin.flush().await
                        })
                        .await;
                        if !matches!(written, Ok(Ok(()))) {
                            return;
                        }
                        sent = token;
                    }
                    Ok(_) => {}
                    Err(e) => tracing::debug!("jeton de présence indisponible : {}", e.message),
                }
            }
            tokio::time::sleep(TOKEN_CHECK_EVERY).await;
        }
    });
}

/// Réglage « apparaître comme joueur YuyuFrame » (Paramètres → Confidentialité).
#[tauri::command]
pub async fn presence_get_visible(state: tauri::State<'_, SharedState>) -> Result<bool, String> {
    Ok(visible(&state).await)
}

/// Pris en compte au prochain signal d'une partie en cours ; éteint, le
/// serveur oublie le joueur en quelques minutes.
#[tauri::command]
pub async fn presence_set_visible(state: tauri::State<'_, SharedState>, visible: bool) -> Result<(), String> {
    let s = state.read().await;
    let conn = s.db.lock().await;
    prefs::set_bool(&conn, prefs::PRESENCE_VISIBLE, visible).map_err(|e| e.to_string())
}
