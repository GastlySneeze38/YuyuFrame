//! E-mail du compte : le changer, le confirmer avec le code reçu.

use serde::Serialize;
use serde_json::json;

use super::{flag, text};
use crate::server as api;
use crate::state::SharedState;

/// État de l'e-mail du compte, tel que le serveur vient de le dire.
#[derive(Serialize)]
pub struct EmailResp {
    pub email: Option<String>,
    pub verification_required: bool,
    pub pending_email: Option<String>,
}

fn email_resp(profile: &serde_json::Value) -> EmailResp {
    EmailResp {
        email: text(profile, "email"),
        verification_required: flag(profile, "email_verification_required"),
        pending_email: text(profile, "pending_email"),
    }
}

/// Demande un changement d'e-mail : le serveur exige le mot de passe et
/// envoie un code à la nouvelle adresse. L'adresse du compte ne change
/// qu'après `yuyu_verify_email`.
#[tauri::command]
pub async fn yuyu_set_email(
    state: tauri::State<'_, SharedState>,
    email: String,
    password: String,
    mfa_code: Option<String>,
) -> Result<EmailResp, String> {
    let profile = api::patch(&state, "/me", json!({ "email": email, "password": password, "mfa_code": mfa_code })).await?;
    api::store_profile(&state, &profile).await;
    Ok(email_resp(&profile))
}

/// Confirme l'e-mail avec le code reçu.
#[tauri::command]
pub async fn yuyu_verify_email(state: tauri::State<'_, SharedState>, code: String) -> Result<EmailResp, String> {
    let profile = api::post(&state, "/me/email/verify", json!({ "code": code })).await?;
    api::store_profile(&state, &profile).await;
    Ok(email_resp(&profile))
}

/// Renvoie le code de confirmation (une fois par minute au plus).
#[tauri::command]
pub async fn yuyu_resend_email_code(state: tauri::State<'_, SharedState>) -> Result<(), String> {
    api::post(&state, "/me/email/resend", json!({})).await?;
    Ok(())
}

/// État de l'e-mail relu sur le serveur — sert à la fenêtre de confirmation
/// quand elle s'ouvre sur une session déjà là (redémarrage du launcher).
#[tauri::command]
pub async fn yuyu_email_status(state: tauri::State<'_, SharedState>) -> Result<EmailResp, String> {
    let profile = api::get(&state, "/me", &[]).await?;
    api::store_profile(&state, &profile).await;
    Ok(email_resp(&profile))
}
