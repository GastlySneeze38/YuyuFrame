//! Les amis (LauncherAPI `/v1/friends/…`) : liste, demandes, blocages,
//! statut, qui voit quoi — et ce qui les tient à jour en direct.
//!
//! - `presence` : le signal « je suis là, et voici ce que je fais », envoyé
//!   toutes les 30 s et à chaque changement (partie, serveur, duel) ;
//! - `stream` : le flux temps réel du serveur, relayé à l'interface en
//!   événements `friends_event`.
//!
//! Les commandes ci-dessous ne font que relayer : les règles (qui voit quoi,
//! limites, ce qui ne se dit pas) sont sur le serveur. Pas d'écran Amis pour
//! l'instant : l'interface ne s'en sert que pour tenir sa liste et prévenir
//! (demande reçue, nouvel ami, ami connecté).

pub mod presence;
pub mod stream;

use serde_json::{json, Value};

use crate::server as api;
use crate::state::SharedState;

type CmdResult = Result<Value, String>;

fn done(result: api::error::ApiResult<Value>) -> CmdResult {
    result.map_err(String::from)
}

/// Amis (avec leur présence), demandes reçues et envoyées, bloqués, mon statut, mes réglages.
#[tauri::command]
pub async fn friends_list(state: tauri::State<'_, SharedState>) -> CmdResult {
    done(api::get(&state, "/friends", &[]).await)
}

/// Demander quelqu'un en ami, par son pseudo YuyuFrame ou Minecraft.
/// Rend `{ status: pending | friends, user_id, username }`.
#[tauri::command]
pub async fn friends_request(state: tauri::State<'_, SharedState>, name: String) -> CmdResult {
    done(api::post(&state, "/friends/requests", json!({ "name": name })).await)
}

#[tauri::command]
pub async fn friends_accept(state: tauri::State<'_, SharedState>, id: i64) -> CmdResult {
    done(api::post(&state, &format!("/friends/requests/{id}/accept"), json!({})).await)
}

#[tauri::command]
pub async fn friends_decline(state: tauri::State<'_, SharedState>, id: i64) -> CmdResult {
    done(api::post(&state, &format!("/friends/requests/{id}/decline"), json!({})).await)
}

/// Annuler une demande envoyée.
#[tauri::command]
pub async fn friends_cancel(state: tauri::State<'_, SharedState>, id: i64) -> CmdResult {
    done(api::delete(&state, &format!("/friends/requests/{id}")).await)
}

#[tauri::command]
pub async fn friends_remove(state: tauri::State<'_, SharedState>, user_id: i64) -> CmdResult {
    done(api::delete(&state, &format!("/friends/{user_id}")).await)
}

#[tauri::command]
pub async fn friends_block(state: tauri::State<'_, SharedState>, user_id: i64) -> CmdResult {
    done(api::post(&state, "/friends/blocks", json!({ "user_id": user_id })).await)
}

#[tauri::command]
pub async fn friends_unblock(state: tauri::State<'_, SharedState>, user_id: i64) -> CmdResult {
    done(api::delete(&state, &format!("/friends/blocks/{user_id}")).await)
}

/// `status` : `online`, `away`, `dnd` ou `invisible`. `custom_text` vide :
/// pas de statut personnalisé ; `custom_minutes` absent : sans fin.
#[tauri::command]
pub async fn friends_set_status(
    state: tauri::State<'_, SharedState>,
    status: String,
    custom_text: Option<String>,
    custom_minutes: Option<i64>,
) -> CmdResult {
    done(api::put(&state, "/friends/status", json!({ "status": status, "custom_text": custom_text, "custom_minutes": custom_minutes })).await)
}

/// `{ accept_requests, stats_visibility, activity_visibility, server_visibility }`.
#[tauri::command]
pub async fn friends_set_settings(state: tauri::State<'_, SharedState>, settings: Value) -> CmdResult {
    done(api::put(&state, "/friends/settings", settings).await)
}

/// Montrer (ou non) mon serveur à cet ami, quand ma visibilité du serveur vaut « certains amis ».
#[tauri::command]
pub async fn friends_set_server_share(state: tauri::State<'_, SharedState>, user_id: i64, shared: bool) -> CmdResult {
    done(api::put(&state, &format!("/friends/{user_id}/server-share"), json!({ "shared": shared })).await)
}
