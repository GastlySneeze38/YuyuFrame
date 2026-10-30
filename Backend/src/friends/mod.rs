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

/// Un identifiant public (UUID) qui part dans une adresse : rien d'autre que
/// des chiffres hexadécimaux et des tirets.
fn public_id(id: &str) -> Result<String, String> {
    let id = id.trim();
    if id.len() == 36 && id.chars().all(|c| c.is_ascii_hexdigit() || c == '-') {
        Ok(id.to_string())
    } else {
        Err(String::from(api::error::ApiError::new("bad_request", "Identifiant de joueur invalide")))
    }
}

/// Qui est derrière un code ami (« Ghasty#4821 »), avant de l'ajouter.
#[tauri::command]
pub async fn friends_lookup(state: tauri::State<'_, SharedState>, code: String) -> CmdResult {
    done(api::get(&state, "/friends/lookup", &[("code", code)]).await)
}

/// Demander quelqu'un en ami, par son code ami ou par l'identifiant d'un aperçu.
/// Rend `{ status: pending | friends, id, username }`.
#[tauri::command]
pub async fn friends_request(state: tauri::State<'_, SharedState>, code: Option<String>, id: Option<String>) -> CmdResult {
    let id = id.as_deref().map(public_id).transpose()?;
    done(api::post(&state, "/friends/requests", json!({ "code": code, "id": id })).await)
}

/// Un nouveau numéro de code ami (une fois par jour au plus).
#[tauri::command]
pub async fn friends_regenerate_code(state: tauri::State<'_, SharedState>) -> CmdResult {
    done(api::post(&state, "/friends/code/regenerate", json!({})).await)
}

/// Mon profil, pour le modifier.
#[tauri::command]
pub async fn friends_my_profile(state: tauri::State<'_, SharedState>) -> CmdResult {
    done(api::get(&state, "/friends/profile", &[]).await)
}

/// Enregistrer mon profil (bannière, couleur, bio, vitrine, compte principal, visibilités).
#[tauri::command]
pub async fn friends_set_profile(state: tauri::State<'_, SharedState>, profile: Value) -> CmdResult {
    done(api::put(&state, "/friends/profile", profile).await)
}

/// Le profil d'un joueur, tel que je peux le voir.
#[tauri::command]
pub async fn friends_profile(state: tauri::State<'_, SharedState>, id: String) -> CmdResult {
    done(api::get(&state, &format!("/friends/{}/profile", public_id(&id)?), &[]).await)
}

/// La page compétitive d'un ami : `{ account, summary, recent }`. Refusée
/// (`forbidden`) s'il ne la montre pas à ses amis.
#[tauri::command]
pub async fn friends_competitive(state: tauri::State<'_, SharedState>, id: String) -> CmdResult {
    done(api::get(&state, &format!("/friends/{}/competitive", public_id(&id)?), &[]).await)
}

/// Signaler un joueur : `reason` = `username`, `bio`, `status` ou `other`.
#[tauri::command]
pub async fn friends_report(state: tauri::State<'_, SharedState>, id: String, reason: String, details: String) -> CmdResult {
    done(api::post(&state, &format!("/friends/{}/report", public_id(&id)?), json!({ "reason": reason, "details": details })).await)
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

// Les joueurs sont désignés par leur identifiant public (UUID), jamais par
// l'identifiant interne du serveur.

#[tauri::command]
pub async fn friends_remove(state: tauri::State<'_, SharedState>, id: String) -> CmdResult {
    done(api::delete(&state, &format!("/friends/{}", public_id(&id)?)).await)
}

#[tauri::command]
pub async fn friends_block(state: tauri::State<'_, SharedState>, id: String) -> CmdResult {
    done(api::post(&state, "/friends/blocks", json!({ "id": public_id(&id)? })).await)
}

#[tauri::command]
pub async fn friends_unblock(state: tauri::State<'_, SharedState>, id: String) -> CmdResult {
    done(api::delete(&state, &format!("/friends/blocks/{}", public_id(&id)?)).await)
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
pub async fn friends_set_server_share(state: tauri::State<'_, SharedState>, id: String, shared: bool) -> CmdResult {
    done(api::put(&state, &format!("/friends/{}/server-share", public_id(&id)?), json!({ "shared": shared })).await)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seul_un_identifiant_public_part_dans_une_adresse() {
        assert!(public_id("0f8fad5b-d9cb-469f-a165-70867728950e").is_ok());
        assert!(public_id("../friends").is_err());
        assert!(public_id("0f8fad5b-d9cb-469f-a165-70867728950e/x").is_err());
        assert!(public_id("42").is_err());
    }
}
