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
