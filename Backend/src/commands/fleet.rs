// Configuration pilotée par le back-office, vue par le frontend.
//
// Le launcher s'en sert pour : bloquer une version, imposer une mise à jour,
// couper une fonctionnalité à distance et afficher des bannières. Ces
// réglages remplacent les constantes en dur de `Frontend/src/config/features.ts`.

use std::sync::Arc;

use crate::api::fleet::{self, FleetConfig};
use crate::state::SharedState;

/// Dernière configuration connue, sans appel réseau.
#[tauri::command]
pub async fn fleet_config() -> Result<Arc<FleetConfig>, String> {
    Ok(fleet::current().await)
}

/// Force une relecture (au retour de veille, après une reconnexion réseau,
/// ou juste après s'être connecté pour recevoir les bannières ciblées).
#[tauri::command]
pub async fn fleet_refresh(state: tauri::State<'_, SharedState>) -> Result<Arc<FleetConfig>, String> {
    Ok(fleet::reload(&state).await)
}

/// Une fonctionnalité est-elle autorisée ? Une clé inconnue vaut « oui ».
#[tauri::command]
pub async fn fleet_flag(key: String) -> Result<bool, String> {
    Ok(fleet::current().await.is_enabled(&key))
}
