use serde::Serialize;

use crate::{db, state::SharedState};

#[derive(Serialize)]
pub struct AccountInfo {
    pub mc_username: String,
    pub mc_uuid: String,
    pub is_active: bool,
    pub is_offline: bool,
}

#[tauri::command]
pub async fn mc_list_accounts(
    state: tauri::State<'_, SharedState>,
) -> Result<Vec<AccountInfo>, String> {
    super::list_accounts(&state).await
}

#[tauri::command]
pub async fn mc_switch(
    state: tauri::State<'_, SharedState>,
    uuid: String,
) -> Result<AccountInfo, String> {
    super::activate_account(&state, &uuid).await
}

/// Supprime un compte. Si c'était le compte actif, le premier compte restant
/// reprend la main — avant, la session était simplement vidée et le lancement
/// suivant échouait sur « Non connecté à Minecraft » alors qu'un autre compte
/// restait affiché.
///
/// @returns le compte actif après suppression, `None` s'il n'en reste aucun
#[tauri::command]
pub async fn mc_delete(
    state: tauri::State<'_, SharedState>,
    uuid: String,
) -> Result<Option<AccountInfo>, String> {
    let (was_active, next_uuid) = {
        let s = state.read().await;
        let conn = s.db.lock().await;
        let was_active = db::get_active_mc_uuid(&conn).map_err(|e| e.to_string())?.as_deref() == Some(uuid.as_str());
        db::delete_mc_session(&conn, &uuid).map_err(|e| e.to_string())?;
        let next_uuid = db::list_mc_sessions(&conn)
            .map_err(|e| e.to_string())?
            .into_iter()
            .next()
            .map(|r| r.mc_uuid);
        if was_active && next_uuid.is_none() {
            db::clear_active_mc(&conn).map_err(|e| e.to_string())?;
        }
        (was_active, next_uuid)
    };

    if !was_active {
        return Ok(super::list_accounts(&state).await?.into_iter().find(|a| a.is_active));
    }
    match next_uuid {
        Some(next) => super::activate_account(&state, &next).await.map(Some),
        None => {
            state.write().await.session = None;
            Ok(None)
        }
    }
}
