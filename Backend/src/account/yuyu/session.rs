//! Ouvrir et fermer la session : inscription, connexion (mot de passe puis
//! second facteur), déconnexion.

use serde::Serialize;
use serde_json::json;

use super::{flag, profile::push_minecraft_accounts, text};
use crate::account::minecraft::{self, accounts::AccountInfo};
use crate::security::license;
use crate::server as api;
use crate::state::SharedState;

#[derive(Serialize)]
pub struct StatusResp {
    pub has_account: bool,
}

#[derive(Serialize)]
pub struct SessionResp {
    pub username: String,
    pub email: Option<String>,
    pub plan: String,
    pub plan_expires_at: Option<i64>,
    /// Mot de passe provisoire : le frontend doit imposer le changement.
    pub password_reset_required: bool,
    /// E-mail à confirmer avant tout le reste : le frontend impose la saisie
    /// du code reçu.
    pub email_verification_required: bool,
    /// Adresse à laquelle le code a été envoyé.
    pub pending_email: Option<String>,
    /// « valid », « grace » ou « expired » — sert au bandeau d'information
    /// quand le launcher tourne sur une licence périmée (serveur injoignable).
    pub license_state: String,
    pub accounts: Vec<AccountInfo>,
}

#[tauri::command]
pub async fn yuyu_status(state: tauri::State<'_, SharedState>) -> Result<StatusResp, String> {
    let s = state.read().await;
    Ok(StatusResp { has_account: s.yuyu_session.is_some() })
}

/// Ping léger (`GET /health`) pour l'indicateur de connectivité — ne renvoie
/// jamais d'erreur : une panne réseau donne juste `false`, pour un badge
/// discret et surtout aucun toast d'erreur.
#[tauri::command]
pub async fn yuyu_ping(state: tauri::State<'_, SharedState>) -> Result<bool, ()> {
    let client = state.read().await.http.clone();
    let ok = client
        .get(format!("{}/health", api::root()))
        .timeout(std::time::Duration::from_secs(5))
        .send()
        .await
        .map(|r| r.status().is_success())
        .unwrap_or(false);
    Ok(ok)
}

#[tauri::command]
pub async fn yuyu_register(
    state: tauri::State<'_, SharedState>,
    username: String,
    password: String,
    email: String,
) -> Result<SessionResp, String> {
    let body = json!({
        "username": username,
        "password": password,
        "email": email,
        "device": api::device_info(),
    });
    let value = api::post_public(&state, "/auth/register", body).await?;
    finish_sign_in(&state, &value).await
}

/// `login` accepte le pseudo **ou** l'e-mail depuis la refonte.
#[tauri::command]
pub async fn yuyu_login(
    state: tauri::State<'_, SharedState>,
    login: String,
    password: String,
) -> Result<SessionResp, String> {
    let body = json!({ "login": login, "password": password, "device": api::device_info() });
    let value = api::post_public(&state, "/auth/login", body).await?;
    finish_sign_in(&state, &value).await
}

#[tauri::command]
pub async fn yuyu_logout(state: tauri::State<'_, SharedState>) -> Result<(), String> {
    // Ferme la session côté serveur pour qu'elle disparaisse de la liste des
    // appareils ; si le réseau manque, on oublie quand même la session ici.
    if let Err(e) = api::post(&state, "/auth/logout", json!({})).await {
        tracing::debug!("déconnexion côté serveur impossible : {}", e.message);
    }
    api::clear_session(&state).await;
    // Le compte Minecraft actif reste connecté : il ne dépend pas de YuyuFrame.
    Ok(())
}

/// Seconde étape de la connexion : le second facteur (code de l'application,
/// code de secours ou code reçu par e-mail) contre une vraie session.
/// `mfa_token` vient de l'erreur `mfa_required` rendue par `yuyu_login`.
#[tauri::command]
pub async fn yuyu_mfa_verify(state: tauri::State<'_, SharedState>, mfa_token: String, code: String) -> Result<SessionResp, String> {
    let body = json!({ "mfa_token": mfa_token, "code": code, "device": api::device_info() });
    let value = api::post_public(&state, "/auth/mfa/verify", body).await?;
    finish_sign_in(&state, &value).await
}

/// Renvoie le code de connexion par e-mail.
#[tauri::command]
pub async fn yuyu_mfa_resend(state: tauri::State<'_, SharedState>, mfa_token: String) -> Result<(), String> {
    api::post_public(&state, "/auth/mfa/resend", json!({ "mfa_token": mfa_token })).await?;
    Ok(())
}

/// Après une connexion ou une inscription : session enregistrée, comptes
/// Minecraft envoyés au serveur (le support les cherche par pseudo ou UUID).
async fn finish_sign_in(
    state: &tauri::State<'_, SharedState>,
    value: &serde_json::Value,
) -> Result<SessionResp, String> {
    let session = api::store_token_response(state, value).await?;
    let accounts = minecraft::list_accounts(state).await?;
    // Ni la confirmation d'e-mail ni l'adresse en attente ne sont gardées
    // dans la session locale : c'est le serveur qui sait, à chaque fois.
    let profile = value.get("profile").unwrap_or(&serde_json::Value::Null);
    let email_verification_required = flag(profile, "email_verification_required");
    // Tant que l'e-mail n'est pas confirmé, le serveur refuserait la liste.
    if !email_verification_required {
        push_minecraft_accounts(state, &accounts).await;
    }

    Ok(SessionResp {
        username: session.username,
        email: session.email,
        plan: session.plan,
        plan_expires_at: session.plan_expires_at,
        password_reset_required: session.password_reset_required,
        email_verification_required,
        pending_email: text(profile, "pending_email"),
        license_state: license_state(state).await,
        accounts,
    })
}

async fn license_state(state: &tauri::State<'_, SharedState>) -> String {
    let Some(session) = state.read().await.yuyu_session.clone() else { return "expired".into() };
    let Some(payload) = session.license.as_deref().and_then(license::verify) else {
        // Pas de licence (serveur sans clé de signature) : rien à signaler,
        // le plan vient alors du serveur à chaque démarrage.
        return "valid".into();
    };
    match license::plan_at(&payload, chrono::Utc::now().timestamp()).1 {
        license::LicenseState::Valid => "valid".into(),
        license::LicenseState::Grace => "grace".into(),
        license::LicenseState::Expired => "expired".into(),
    }
}
