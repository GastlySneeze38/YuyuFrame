//! Connecter un autre appareil sans mot de passe : celui-ci, déjà connecté,
//! demande un code à LauncherAPI (`POST /me/link`) et l'affiche en texte et en
//! QR code ; l'autre le donne à `yuyu_link_redeem` (`session.rs`).
//!
//! Le code vaut 5 minutes et ne sert qu'une fois ; l'écran le suit
//! (`yuyu_link_status`) pour dire tout de suite quel appareil l'a pris.

use serde::Serialize;
use serde_json::json;

use super::text;
use crate::server as api;
use crate::state::SharedState;

#[derive(Serialize)]
pub struct LinkCode {
    pub id: String,
    /// « ABCD2345 », affiché en deux groupes.
    pub code: String,
    pub expires_in: i64,
}

#[derive(Serialize)]
pub struct LinkStatus {
    /// pending | used | expired
    pub status: String,
    /// La session ouverte avec le code, pour pouvoir la fermer d'ici.
    pub session_id: Option<String>,
    pub device_name: Option<String>,
    pub os: Option<String>,
}

#[tauri::command]
pub async fn yuyu_link_create(state: tauri::State<'_, SharedState>) -> Result<LinkCode, String> {
    let v = api::post(&state, "/me/link", json!({})).await?;
    Ok(LinkCode {
        id: text(&v, "id").unwrap_or_default(),
        code: text(&v, "code").unwrap_or_default(),
        expires_in: v.get("expires_in").and_then(|x| x.as_i64()).unwrap_or(300),
    })
}

#[tauri::command]
pub async fn yuyu_link_status(state: tauri::State<'_, SharedState>, id: String) -> Result<LinkStatus, String> {
    let v = api::get(&state, &format!("/me/link/{id}"), &[]).await?;
    Ok(LinkStatus {
        status: text(&v, "status").unwrap_or_else(|| "pending".into()),
        session_id: text(&v, "session_id"),
        device_name: text(&v, "device_name"),
        os: text(&v, "os"),
    })
}

/// Fenêtre fermée avant usage : le code ne doit plus rien ouvrir.
#[tauri::command]
pub async fn yuyu_link_cancel(state: tauri::State<'_, SharedState>, id: String) -> Result<(), String> {
    api::delete(&state, &format!("/me/link/{id}")).await?;
    Ok(())
}
