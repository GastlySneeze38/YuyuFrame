//! Appareils connectés au compte : les voir, en fermer un.

use serde::Serialize;

use super::text;
use crate::server as api;
use crate::state::SharedState;

#[derive(Serialize)]
pub struct DeviceResp {
    pub id: String,
    pub device_name: Option<String>,
    pub os: Option<String>,
    pub launcher_version: Option<String>,
    pub last_used_at: Option<String>,
    pub current: bool,
}

/// Appareils connectés au compte.
#[tauri::command]
pub async fn yuyu_list_devices(state: tauri::State<'_, SharedState>) -> Result<Vec<DeviceResp>, String> {
    let value = api::get(&state, "/me/sessions", &[]).await?;
    let list = value.as_array().cloned().unwrap_or_default();
    Ok(list
        .iter()
        .map(|s| DeviceResp {
            id: s.get("id").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
            device_name: text(s, "device_name"),
            os: text(s, "os"),
            launcher_version: text(s, "launcher_version"),
            last_used_at: text(s, "last_used_at"),
            current: s.get("current").and_then(|v| v.as_bool()).unwrap_or(false),
        })
        .collect())
}

#[tauri::command]
pub async fn yuyu_revoke_device(state: tauri::State<'_, SharedState>, id: String) -> Result<(), String> {
    api::delete(&state, &format!("/me/sessions/{id}")).await?;
    Ok(())
}
