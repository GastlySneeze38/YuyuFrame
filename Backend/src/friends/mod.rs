//! Les amis (LauncherAPI `/v1/friends/…`) : liste, demandes, blocages,
//! statut, qui voit quoi — et ce qui les tient à jour en direct.
//!
//! - `presence` : le signal « je suis là, et voici ce que je fais », envoyé
//!   toutes les 30 s et à chaque changement (partie, serveur, duel) ;
//! - `stream` : le flux temps réel du serveur, relayé à l'interface en
//!   événements `friends_event` (amis, mon statut, et le ping du support) ;
//! - `notify` : les notifications Windows tirées de ce flux, et la pastille
//!   de la barre des tâches.
//!
//! Les commandes ci-dessous ne font que relayer : les règles (qui voit quoi,
//! limites, ce qui ne se dit pas) sont sur le serveur. Pas d'écran Amis pour
//! l'instant : l'interface ne s'en sert que pour tenir sa liste et prévenir
//! (demande reçue, nouvel ami, ami connecté).

pub mod discord;
pub mod notify;
pub mod presence;
pub mod stream;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

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
    let list = done(api::get(&state, "/friends", &[]).await)?;
    notify::remember_list(&list);
    remember(&list);
    Ok(list)
}

// ── La liste gardée pour le jeu ─────────────────────────────────────────
//
// Le menu en jeu redemande la liste toutes les 2 s (`game_link`, `friends2`).
// Elle est servie d'ici, sans appel au serveur, tant que le flux temps réel
// (`stream`) n'a rien signalé : un événement la rend périmée, et la demande
// suivante la recharge. Filet si le flux est coupé : 15 s au plus.

/// Âge au-delà duquel la liste est rechargée même sans événement.
const LIST_MAX_AGE: Duration = Duration::from_secs(15);

static LIST: Mutex<Option<(Value, Instant)>> = Mutex::new(None);
static STALE: AtomicBool = AtomicBool::new(true);

fn remember(list: &Value) {
    *LIST.lock().unwrap_or_else(|p| p.into_inner()) = Some((list.clone(), Instant::now()));
    STALE.store(false, Ordering::Relaxed);
}

/// Un événement du flux (présence d'un ami, nouvel ami…) : la liste gardée ne vaut plus.
pub fn mark_stale() {
    STALE.store(true, Ordering::Relaxed);
}

/// La liste des amis, gardée ou rechargée (voir plus haut).
pub async fn current_list(state: &SharedState) -> api::error::ApiResult<Value> {
    if !STALE.load(Ordering::Relaxed) {
        if let Some((list, at)) = LIST.lock().unwrap_or_else(|p| p.into_inner()).as_ref() {
            if at.elapsed() < LIST_MAX_AGE {
                return Ok(list.clone());
            }
        }
    }
    let list = api::get(state, "/friends", &[]).await?;
    remember(&list);
    Ok(list)
}

/// Le serveur public où je joue en ce moment (celui qu'une invitation
/// enverrait), ou rien : en solo, au menu, sur un serveur privé.
#[tauri::command]
pub fn friends_my_server() -> Option<String> {
    presence::current_server()
}

/// Demander à un ami de me montrer son serveur (mon « Rejoindre » est grisé).
#[tauri::command]
pub async fn friends_request_server(state: tauri::State<'_, SharedState>, id: String) -> CmdResult {
    done(api::post(&state, &format!("/friends/{}/server-request", public_id(&id)?), json!({})).await)
}

/// Inviter un ami sur le serveur où je joue (le serveur le lit dans ma présence).
#[tauri::command]
pub async fn friends_invite(state: tauri::State<'_, SharedState>, id: String) -> CmdResult {
    done(api::post(&state, &format!("/friends/{}/invite", public_id(&id)?), json!({})).await)
}

/// Refuser une invitation reçue : l'ami qui l'a envoyée en est prévenu.
#[tauri::command]
pub async fn friends_decline_invite(state: tauri::State<'_, SharedState>, invite: String) -> CmdResult {
    done(api::post(&state, &format!("/friends/invites/{}/decline", public_id(&invite)?), json!({})).await)
}

/// Relire une invitation reçue — ouverte depuis son message privé Discord
/// (`yuyuframe://invite/<invitation>`). Erreur `not_found` : expirée ou refusée.
#[tauri::command]
pub async fn friends_get_invite(state: tauri::State<'_, SharedState>, invite: String) -> CmdResult {
    done(api::get(&state, &format!("/friends/invites/{}", public_id(&invite)?), &[]).await)
}

/// Qui m'a fait rejoindre par le bouton « Rejoindre » de son statut Discord
/// (le ticket glissé dans ce lien). Même réponse que `friends_lookup`.
#[tauri::command]
pub async fn friends_ticket_lookup(state: tauri::State<'_, SharedState>, ticket: String) -> CmdResult {
    done(api::post(&state, "/friends/join-ticket/lookup", json!({ "ticket": ticket })).await)
}

/// Un ticket à mon nom pour le lien « Rejoindre » de mon statut Discord.
/// `None` sans compte, ou si le serveur ne répond pas : le lien part sans.
pub async fn join_ticket(state: &SharedState) -> Option<String> {
    let answer = api::post(state, "/friends/join-ticket", json!({})).await.ok()?;
    answer.get("ticket").and_then(Value::as_str).map(str::to_string)
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
    let answer = done(api::put(&state, "/friends/status", json!({ "status": status, "custom_text": custom_text, "custom_minutes": custom_minutes })).await)?;
    // « Ne pas déranger » coupe les notifications Windows tout de suite.
    notify::remember_my_status(&status);
    Ok(answer)
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
