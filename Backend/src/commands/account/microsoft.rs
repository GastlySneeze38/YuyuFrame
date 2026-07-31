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

#[tauri::command]
pub async fn auth_start_device(
    state: tauri::State<'_, SharedState>,
) -> Result<DeviceAuthResponse, String> {
    if state.read().await.yuyu_session.is_none() {
        return Err("Non authentifié sur YuyuFrame".into());
    }

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
    let yuyu_user_id = {
        let s = state.read().await;
        match &s.yuyu_session {
            Some(y) => y.user_id,
            None => {
                return Ok(PollResponse {
                    status: "error".into(),
                    username: None,
                    error: Some("Non authentifié".into()),
                })
            }
        }
    };

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

            {
                let s = state.read().await;
                let conn = s.db.lock().await;
                // Vérifié AVANT l'upsert — sinon un simple re-login (token expiré,
                // reconnexion manuelle) sur un compte Microsoft déjà connu
                // compterait comme un nouvel ajout à chaque fois (voir
                // offline_account_created dans offline.rs, qui n'a pas ce
                // problème car chaque appel y est une vraie création).
                let is_new_account = db::get_mc_session(&conn, yuyu_user_id, &uuid).ok().flatten().is_none();
                db::upsert_mc_session(&conn, yuyu_user_id, &username, &uuid, &session.access_token, &ms_refresh, expires_at, false).ok();
                db::set_active_mc(&conn, yuyu_user_id, &uuid).ok();
                if is_new_account {
                    crate::integrations::analytics::capture("microsoft_account_added", serde_json::json!({}));
                }
            }

            let mut w = state.write().await;
            w.session = Some(session);
            w.auth_device_code = None;

            Ok(PollResponse { status: "success".into(), username: Some(username), error: None })
        }
        Ok(None) => Ok(PollResponse { status: "pending".into(), username: None, error: None }),
        Err(e) => Ok(PollResponse { status: "error".into(), username: None, error: Some(e.to_string()) }),
    }
}

#[tauri::command]
pub async fn auth_status(state: tauri::State<'_, SharedState>) -> Result<AuthStatusResponse, String> {
    let s = state.read().await;

    let Some(yuyu_user_id) = s.current_yuyu_user_id() else {
        return Ok(AuthStatusResponse { authenticated: false, username: None, uuid: None });
    };

    let session = s.session.clone();
    drop(s);

    let Some(sess) = session else {
        return Ok(AuthStatusResponse { authenticated: false, username: None, uuid: None });
    };

    let now = chrono::Utc::now().timestamp();
    if sess.expires_at - now < 1800 {
        if let Some(ms_ref) = &sess.refresh_token {
            tracing::info!("Auto-rafraîchissement du token MC pour {}", sess.username);
            if let Ok(result) = auth::refresh_session(ms_ref).await {
                let s = state.read().await;
                let conn = s.db.lock().await;
                let new_sess = super::apply_refreshed_tokens(&conn, yuyu_user_id, result);
                drop(conn);
                drop(s);

                let (username, uuid) = (new_sess.username.clone(), new_sess.uuid.clone());
                state.write().await.session = Some(new_sess);
                return Ok(AuthStatusResponse { authenticated: true, username: Some(username), uuid: Some(uuid) });
            }
        }
    }

    Ok(AuthStatusResponse {
        authenticated: true,
        username: Some(sess.username),
        uuid: Some(sess.uuid),
    })
}

#[tauri::command]
pub async fn auth_logout(state: tauri::State<'_, SharedState>) -> Result<(), String> {
    // Pas de garde sur `yuyu_session` : se déconnecter du compte Minecraft
    // n'a jamais nécessité d'être connecté à YuyuFrame.
    state.write().await.session = None;
    Ok(())
}
