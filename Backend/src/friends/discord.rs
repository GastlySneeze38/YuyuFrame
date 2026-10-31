//! Le compte Discord lié (LauncherAPI `/v1/discord/link`).
//!
//! La liaison se fait dans le navigateur : `discord_link_start` rend l'adresse
//! de la connexion Discord, l'interface l'ouvre, et le serveur prévient le
//! launcher par le flux (`discord_changed`) une fois le compte lié. Aucun jeton
//! Discord ne passe par le launcher.

use serde_json::{json, Value};

use crate::server as api;
use crate::state::SharedState;

type CmdResult = Result<Value, String>;

fn done(result: api::error::ApiResult<Value>) -> CmdResult {
    result.map_err(String::from)
}

/// `{ available, dm_available, linked: { username, global_name, show_to_friends, dm_invites, linked_at } | null }`.
#[tauri::command]
pub async fn discord_link_get(state: tauri::State<'_, SharedState>) -> CmdResult {
    done(api::get(&state, "/discord/link", &[]).await)
}

/// `{ url }` : la connexion Discord, à ouvrir dans le navigateur.
#[tauri::command]
pub async fn discord_link_start(state: tauri::State<'_, SharedState>) -> CmdResult {
    let answer = done(api::post(&state, "/discord/link/start", json!({})).await)?;
    // Seule une adresse de Discord part vers le navigateur.
    let url = answer.get("url").and_then(Value::as_str).unwrap_or_default();
    if !url.starts_with("https://discord.com/") {
        return Err(String::from(api::error::ApiError::new("bad_gateway", "Adresse de connexion inattendue")));
    }
    Ok(answer)
}

/// `{ show_to_friends?, dm_invites? }`.
#[tauri::command]
pub async fn discord_link_update(state: tauri::State<'_, SharedState>, settings: Value) -> CmdResult {
    let body = json!({
        "show_to_friends": settings.get("show_to_friends").and_then(Value::as_bool),
        "dm_invites": settings.get("dm_invites").and_then(Value::as_bool),
    });
    done(api::patch(&state, "/discord/link", body).await)
}

#[tauri::command]
pub async fn discord_unlink(state: tauri::State<'_, SharedState>) -> CmdResult {
    done(api::delete(&state, "/discord/link").await)
}
