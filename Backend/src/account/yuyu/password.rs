//! Mot de passe : le changer, ou le retrouver par e-mail quand il est oublié.

use serde_json::json;

use crate::server as api;
use crate::state::SharedState;

/// Change le mot de passe. Lève aussi le mot de passe provisoire donné par le
/// support, et ferme les autres appareils. `mfa_code` : le second facteur,
/// que le serveur redemande (sauf pour un mot de passe provisoire).
#[tauri::command]
pub async fn yuyu_change_password(
    state: tauri::State<'_, SharedState>,
    current: String,
    new_password: String,
    mfa_code: Option<String>,
) -> Result<(), String> {
    api::post(&state, "/me/password", json!({ "current": current, "new": new_password, "mfa_code": mfa_code })).await?;
    // Le serveur renvoie une nouvelle session : on relit le profil pour
    // retomber sur nos pieds (drapeau de mot de passe provisoire levé).
    if let Ok(profile) = api::get(&state, "/me", &[]).await {
        api::store_profile(&state, &profile).await;
    }
    Ok(())
}

/// Mot de passe oublié : demande un code. Le serveur répond la même chose
/// que le compte existe ou non.
#[tauri::command]
pub async fn yuyu_forgot_password(state: tauri::State<'_, SharedState>, login: String) -> Result<(), String> {
    api::post_public(&state, "/auth/password/forgot", json!({ "login": login })).await?;
    Ok(())
}

/// Pose un nouveau mot de passe contre le code reçu. Toutes les sessions du
/// compte sont fermées : il reste à se connecter.
#[tauri::command]
pub async fn yuyu_reset_password(
    state: tauri::State<'_, SharedState>,
    login: String,
    code: String,
    new_password: String,
) -> Result<(), String> {
    api::post_public(&state, "/auth/password/reset", json!({ "login": login, "code": code, "new_password": new_password })).await?;
    Ok(())
}
