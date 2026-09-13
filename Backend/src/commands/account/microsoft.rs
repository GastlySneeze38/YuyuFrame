use serde::Serialize;

use crate::{db, minecraft::auth, state::SharedState};

#[derive(Serialize)]
pub struct DeviceAuthResponse {
    pub user_code: String,
    pub verification_uri: String,
    pub expires_in: i64,
}

#[derive(Serialize)]
pub struct AuthStatusResponse {
    pub authenticated: bool,
    pub username: Option<String>,
    pub uuid: Option<String>,
}

#[derive(Serialize)]
pub struct PollResponse {
    pub status: String,
    pub username: Option<String>,
    pub error: Option<String>,
}

/// Aucun compte YuyuFrame requis : les comptes Minecraft appartiennent au PC
/// (voir db::mc_account).
#[tauri::command]
pub async fn auth_start_device(
    state: tauri::State<'_, SharedState>,
) -> Result<DeviceAuthResponse, String> {
    match auth::start_device_auth().await {
        Ok(resp) => {
            let expires_at = chrono::Utc::now().timestamp() + resp.expires_in;
            state.write().await.auth_device_code = Some(crate::state::AuthDeviceCode {
                device_code: resp.device_code,
                user_code: resp.user_code.clone(),
                verification_uri: resp.verification_uri.clone(),
                expires_at,
            });
            Ok(DeviceAuthResponse {
                user_code: resp.user_code,
                verification_uri: resp.verification_uri,
                expires_in: resp.expires_in,
            })
        }
        Err(e) => Err(e.to_string()),
    }
}

#[tauri::command]
pub async fn auth_poll(state: tauri::State<'_, SharedState>) -> Result<PollResponse, String> {
    let device_code = {
        let s = state.read().await;
        s.auth_device_code.clone()
    };

    let Some(dc) = device_code else {
        return Ok(PollResponse {
            status: "error".into(),
            username: None,
            error: Some("Pas d'authentification en cours".into()),
        });
    };

    if chrono::Utc::now().timestamp() > dc.expires_at {
        state.write().await.auth_device_code = None;
        return Ok(PollResponse {
            status: "error".into(),
            username: None,
            error: Some("Code expiré".into()),
        });
    }

    match auth::poll_device_auth(&dc.device_code).await {
        Ok(Some(session)) => {
            let username = session.username.clone();
            let uuid = session.uuid.clone();
            let ms_refresh = session.refresh_token.clone().unwrap_or_default();
            let expires_at = session.expires_at;

            state.write().await.auth_device_code = None;
            {
                let s = state.read().await;
                let conn = s.db.lock().await;
                // Vérifié AVANT l'upsert — sinon un simple re-login (token expiré,
                // reconnexion manuelle) sur un compte Microsoft déjà connu
                // compterait comme un nouvel ajout à chaque fois (voir
                // offline_account_created dans offline.rs, qui n'a pas ce
                // problème car chaque appel y est une vraie création).
                let is_new_account = db::get_mc_session(&conn, &uuid).map_err(|e| e.to_string())?.is_none();
                // Une reconnexion remplace les tokens révoqués de ce compte.
                db::upsert_mc_session(&conn, &username, &uuid, &session.access_token, &ms_refresh, expires_at, false)
                    .map_err(|e| format!("Enregistrement du compte impossible : {}", e))?;
                if is_new_account {
                    crate::integrations::analytics::capture("microsoft_account_added", serde_json::json!({}));
                }
            }
            super::activate_account(&state, &uuid).await?;

            Ok(PollResponse { status: "success".into(), username: Some(username), error: None })
        }
        Ok(None) => Ok(PollResponse { status: "pending".into(), username: None, error: None }),
        Err(e) => Ok(PollResponse { status: "error".into(), username: None, error: Some(e.to_string()) }),
    }
}

/// Appelée au démarrage puis toutes les 10 minutes par le frontend : garde le
/// token du compte actif à jour même sans lancer de jeu.
#[tauri::command]
pub async fn auth_status(state: tauri::State<'_, SharedState>) -> Result<AuthStatusResponse, String> {
    match super::refresh_active(&state).await? {
        Some(sess) => Ok(AuthStatusResponse {
            authenticated: true,
            username: Some(sess.username),
            uuid: Some(sess.uuid),
        }),
        None => Ok(AuthStatusResponse { authenticated: false, username: None, uuid: None }),
    }
}

#[tauri::command]
pub async fn auth_logout(state: tauri::State<'_, SharedState>) -> Result<(), String> {
    // Plus aucun compte actif, en base comme en mémoire : sinon le compte
    // « déconnecté » revenait tout seul au redémarrage suivant.
    {
        let s = state.read().await;
        let conn = s.db.lock().await;
        db::clear_active_mc(&conn).map_err(|e| e.to_string())?;
    }
    state.write().await.session = None;
    Ok(())
}
