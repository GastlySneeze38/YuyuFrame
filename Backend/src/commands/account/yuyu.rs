// Compte YuyuFrame — LauncherAPI /v1.
//
// Les jetons ne sont plus manipulés ici : `crate::api` garde la session,
// rafraîchit le jeton d'accès quand il expire et remonte des erreurs à
// `code` stable, que le frontend traduit en écran (mise à jour obligatoire,
// compte suspendu, mot de passe à changer…).

use serde::Serialize;
use serde_json::json;

use crate::api::{self, error::ApiError, license};
use crate::state::SharedState;
use super::minecraft::AccountInfo;

// ── Types retournés au frontend ───────────────────────────────────────────────

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
    /// « valid », « grace » ou « expired » — sert au bandeau d'information
    /// quand le launcher tourne sur une licence périmée (serveur injoignable).
    pub license_state: String,
    pub accounts: Vec<AccountInfo>,
}

#[derive(Serialize)]
pub struct PlanResp {
    pub plan: String,
    pub plan_expires_at: Option<i64>,
}

#[derive(Serialize)]
pub struct CheckoutResp {
    pub checkout_url: String,
}

#[derive(Serialize)]
pub struct DeviceResp {
    pub id: String,
    pub device_name: Option<String>,
    pub os: Option<String>,
    pub launcher_version: Option<String>,
    pub last_used_at: Option<String>,
    pub current: bool,
}

// ── Commandes ─────────────────────────────────────────────────────────────────

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
    email: Option<String>,
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

/// Profil à jour (`GET /v1/me`) : plan réellement actif, licence renouvelée.
#[tauri::command]
pub async fn yuyu_refresh_plan(state: tauri::State<'_, SharedState>) -> Result<PlanResp, String> {
    let before = current_plan(&state).await;

    let profile = match api::get(&state, "/me", &[]).await {
        Ok(profile) => profile,
        // Hors ligne : la licence signée fait foi (30 jours + 7 de grâce).
        Err(e) if e.code == api::error::CODE_NETWORK => {
            let (plan, expires) = offline_plan(&state).await.ok_or_else(|| String::from(e))?;
            return Ok(PlanResp { plan, plan_expires_at: expires });
        }
        Err(e) => return Err(e.into()),
    };

    let session = api::store_profile(&state, &profile).await.ok_or_else(|| String::from(ApiError::not_signed_in()))?;

    // Passage gratuit → payant : le paiement vient d'aboutir (le webhook
    // arrive sur le serveur, jamais ici — on ne peut qu'observer après coup).
    let now_paid = session.plan == "premium" || session.plan == "ultimate";
    if now_paid && !matches!(before.as_deref(), Some("premium") | Some("ultimate")) {
        crate::integrations::analytics::capture("checkout_completed", json!({ "plan": &session.plan }));
    }

    Ok(PlanResp { plan: session.plan, plan_expires_at: session.plan_expires_at })
}

#[tauri::command]
pub async fn yuyu_create_checkout(
    state: tauri::State<'_, SharedState>,
    plan: String,
) -> Result<CheckoutResp, String> {
    let value = api::post(&state, "/payments/checkout", json!({ "plan": plan })).await?;
    let checkout_url = value
        .get("checkout_url")
        .and_then(|v| v.as_str())
        .ok_or_else(|| String::from(ApiError::new("internal", "Réponse de paiement inattendue")))?
        .to_string();
    crate::integrations::analytics::capture("checkout_started", json!({ "plan": &plan }));
    Ok(CheckoutResp { checkout_url })
}

/// Change le mot de passe. Lève aussi le mot de passe provisoire donné par le
/// support, et ferme les autres appareils.
#[tauri::command]
pub async fn yuyu_change_password(
    state: tauri::State<'_, SharedState>,
    current: String,
    new_password: String,
) -> Result<(), String> {
    api::post(&state, "/me/password", json!({ "current": current, "new": new_password })).await?;
    // Le serveur renvoie une nouvelle session : on relit le profil pour
    // retomber sur nos pieds (drapeau de mot de passe provisoire levé).
    if let Ok(profile) = api::get(&state, "/me", &[]).await {
        api::store_profile(&state, &profile).await;
    }
    Ok(())
}

/// E-mail du compte (facturation, support). Chaîne vide = retirer.
#[tauri::command]
pub async fn yuyu_set_email(state: tauri::State<'_, SharedState>, email: String) -> Result<(), String> {
    let profile = api::patch(&state, "/me", json!({ "email": email })).await?;
    api::store_profile(&state, &profile).await;
    Ok(())
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

// ── Helpers ───────────────────────────────────────────────────────────────────

fn text(value: &serde_json::Value, key: &str) -> Option<String> {
    value.get(key).and_then(|v| v.as_str()).filter(|s| !s.is_empty()).map(str::to_string)
}

async fn current_plan(state: &tauri::State<'_, SharedState>) -> Option<String> {
    state.read().await.yuyu_session.as_ref().map(|s| s.plan.clone())
}

/// Après une connexion ou une inscription : session enregistrée, comptes
/// Minecraft envoyés au serveur (le support les cherche par pseudo ou UUID).
async fn finish_sign_in(
    state: &tauri::State<'_, SharedState>,
    value: &serde_json::Value,
) -> Result<SessionResp, String> {
    let session = api::store_token_response(state, value).await?;
    let accounts = super::list_accounts(state).await?;
    push_minecraft_accounts(state, &accounts).await;

    Ok(SessionResp {
        username: session.username,
        email: session.email,
        plan: session.plan,
        plan_expires_at: session.plan_expires_at,
        password_reset_required: session.password_reset_required,
        license_state: license_state(state).await,
        accounts,
    })
}

/// À appeler après l'ajout ou le retrait d'un compte Minecraft : le serveur
/// garde la liste complète, qui sert au support (recherche par pseudo ou
/// UUID Minecraft). Sans session YuyuFrame, il n'y a rien à envoyer.
#[tauri::command]
pub async fn yuyu_sync_minecraft_accounts(state: tauri::State<'_, SharedState>) -> Result<(), String> {
    if state.read().await.yuyu_session.is_none() {
        return Ok(());
    }
    let accounts = super::list_accounts(&state).await?;
    push_minecraft_accounts(&state, &accounts).await;
    Ok(())
}

/// Liste complète des comptes Minecraft liés (remplace la précédente).
/// Silencieux : un échec ici ne doit jamais empêcher de se connecter.
pub async fn push_minecraft_accounts(state: &tauri::State<'_, SharedState>, accounts: &[AccountInfo]) {
    let payload: Vec<_> = accounts.iter().map(|a| json!({ "uuid": a.mc_uuid, "name": a.mc_username })).collect();
    if let Err(e) = api::put(state, "/me/minecraft-accounts", json!({ "accounts": payload })).await {
        tracing::debug!("comptes Minecraft non transmis : {}", e.message);
    }
}

/// Plan déduit de la licence signée, sans réseau.
async fn offline_plan(state: &tauri::State<'_, SharedState>) -> Option<(String, Option<i64>)> {
    let session = state.read().await.yuyu_session.clone()?;
    let payload = license::verify(session.license.as_deref()?)?;
    let (plan, _) = license::plan_at(&payload, chrono::Utc::now().timestamp());
    {
        let s = state.read().await;
        let conn = s.db.lock().await;
        crate::db::update_yuyu_plan(&conn, &plan, payload.plan_ends_at).ok();
    }
    if let Some(s) = state.write().await.yuyu_session.as_mut() {
        s.plan = plan.clone();
    }
    Some((plan, payload.plan_ends_at))
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
