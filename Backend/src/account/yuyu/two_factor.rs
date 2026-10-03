//! Second facteur : application d'authentification, codes de secours, et le
//! code de reconfirmation demandé avant un geste sensible. Rien ne se
//! désactive d'ici — activer l'application est un aller simple, seul le
//! support revient en arrière.

use serde::Serialize;
use serde_json::json;

use super::text;
use crate::server as api;
use crate::state::SharedState;

/// Second facteur du compte, relu sur le serveur.
#[derive(Serialize)]
pub struct MfaResp {
    /// totp | email | none
    pub second_factor: String,
    pub backup_codes_left: i64,
}

#[tauri::command]
pub async fn yuyu_mfa_status(state: tauri::State<'_, SharedState>) -> Result<MfaResp, String> {
    let profile = api::get(&state, "/me", &[]).await?;
    Ok(MfaResp {
        second_factor: text(&profile, "second_factor").unwrap_or_else(|| "none".into()),
        backup_codes_left: profile.get("backup_codes_left").and_then(|v| v.as_i64()).unwrap_or(0),
    })
}

/// Demande par e-mail le code de reconfirmation (changement d'e-mail, de mot
/// de passe, activation de l'application) d'un compte sans application.
#[tauri::command]
pub async fn yuyu_mfa_step_up(state: tauri::State<'_, SharedState>) -> Result<(), String> {
    api::post(&state, "/me/mfa/step-up", json!({})).await?;
    Ok(())
}

#[derive(Serialize)]
pub struct TotpSetupResp {
    pub otpauth_url: String,
    pub secret: String,
}

/// Commence l'activation de l'application d'authentification : de quoi
/// afficher le QR code. Rien ne change tant que `yuyu_totp_enable` n'a pas
/// reçu un premier code.
#[tauri::command]
pub async fn yuyu_totp_setup(state: tauri::State<'_, SharedState>, password: String, mfa_code: Option<String>) -> Result<TotpSetupResp, String> {
    let value = api::post(&state, "/me/mfa/totp/setup", json!({ "password": password, "mfa_code": mfa_code })).await?;
    Ok(TotpSetupResp {
        otpauth_url: text(&value, "otpauth_url").unwrap_or_default(),
        secret: text(&value, "secret").unwrap_or_default(),
    })
}

fn backup_codes(value: &serde_json::Value) -> Vec<String> {
    value
        .get("backup_codes")
        .and_then(|v| v.as_array())
        .map(|codes| codes.iter().filter_map(|c| c.as_str().map(str::to_string)).collect())
        .unwrap_or_default()
}

/// Termine l'activation. Rend les codes de secours, que le serveur ne
/// remontrera jamais.
#[tauri::command]
pub async fn yuyu_totp_enable(state: tauri::State<'_, SharedState>, code: String) -> Result<Vec<String>, String> {
    let value = api::post(&state, "/me/mfa/totp/enable", json!({ "code": code })).await?;
    Ok(backup_codes(&value))
}

/// Nouveaux codes de secours contre un code de l'application.
#[tauri::command]
pub async fn yuyu_backup_codes(state: tauri::State<'_, SharedState>, code: String) -> Result<Vec<String>, String> {
    let value = api::post(&state, "/me/mfa/backup-codes", json!({ "code": code })).await?;
    Ok(backup_codes(&value))
}
