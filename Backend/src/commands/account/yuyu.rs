use serde::{Deserialize, Serialize};

use crate::commands::api_base;
use crate::{db, state::SharedState};
use super::minecraft::AccountInfo;

// ── Types retournés au frontend ───────────────────────────────────────────────

#[derive(Serialize)]
pub struct StatusResp {
    pub has_account: bool,
}

#[derive(Serialize)]
pub struct LoginResp {
    pub token: String,
    pub username: String,
    pub plan: String,
    pub plan_expires_at: Option<i64>,
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

// ── Réponse de la LauncherAPI ─────────────────────────────────────────────────

#[derive(Deserialize)]
struct ApiAuthResponse {
    token: String,
    user_id: i64,
    username: String,
    #[serde(default = "default_plan")]
    plan: String,
    plan_expires_at: Option<i64>,
}

fn default_plan() -> String { "free".into() }

// ── Commands ──────────────────────────────────────────────────────────────────

#[tauri::command]
pub async fn yuyu_status(state: tauri::State<'_, SharedState>) -> Result<StatusResp, String> {
    let s = state.read().await;
    Ok(StatusResp { has_account: s.yuyu_session.is_some() })
}

#[tauri::command]
pub async fn yuyu_register(
    state: tauri::State<'_, SharedState>,
    username: String,
    password: String,
) -> Result<LoginResp, String> {
    let client = reqwest::Client::new();
    let resp = client
        .post(format!("{}/auth/register", api_base()))
        .json(&serde_json::json!({ "username": username, "password": password }))
        .send()
        .await
        .map_err(|e| format!("Serveur inaccessible : {e}"))?;

    if !resp.status().is_success() {
        let msg = resp.text().await.unwrap_or_default();
        return Err(msg);
    }

    let data: ApiAuthResponse = resp.json().await.map_err(|e| e.to_string())?;
    save_session(&state, data.user_id, &data.username, &data.token, &data.plan, data.plan_expires_at).await?;

    Ok(LoginResp { token: data.token, username: data.username, plan: data.plan, plan_expires_at: data.plan_expires_at, accounts: vec![] })
}

#[tauri::command]
pub async fn yuyu_login(
    state: tauri::State<'_, SharedState>,
    username: String,
    password: String,
) -> Result<LoginResp, String> {
    use crate::minecraft::auth as mc_auth;
    use crate::state::MinecraftSession;

    let client = reqwest::Client::new();
    let resp = client
        .post(format!("{}/auth/login", api_base()))
        .json(&serde_json::json!({ "username": username, "password": password }))
        .send()
        .await
        .map_err(|e| format!("Serveur inaccessible : {e}"))?;

    if !resp.status().is_success() {
        let msg = resp.text().await.unwrap_or_default();
        return Err(msg);
    }

    let data: ApiAuthResponse = resp.json().await.map_err(|e| e.to_string())?;
    save_session(&state, data.user_id, &data.username, &data.token, &data.plan, data.plan_expires_at).await?;

    // Charger les sessions Minecraft locales pour cet utilisateur
    let (rows, active_uuid) = {
        let s = state.read().await;
        let conn = s.db.lock().await;
        let rows = db::list_mc_sessions(&conn, data.user_id).map_err(|e| e.to_string())?;
        let active_uuid =
            db::get_active_mc_uuid(&conn, data.user_id).map_err(|e| e.to_string())?;
        (rows, active_uuid)
    };

    let now = chrono::Utc::now().timestamp();
    let mut accounts: Vec<AccountInfo> = Vec::new();
    let mut active_session: Option<MinecraftSession> = None;

    for row in &rows {
        let is_active = active_uuid.as_deref() == Some(&row.mc_uuid);
        accounts.push(AccountInfo {
            mc_username: row.mc_username.clone(),
            mc_uuid: row.mc_uuid.clone(),
            is_active,
        });

        if is_active {
            if row.expires_at - now < 1800 {
                tracing::info!("Rafraîchissement du token pour {}", row.mc_username);
                match mc_auth::refresh_session(&row.ms_refresh_token).await {
                    Ok(result) => {
                        let s = state.read().await;
                        let conn = s.db.lock().await;
                        active_session = Some(super::apply_refreshed_tokens(&conn, data.user_id, result));
                    }
                    Err(e) => {
                        tracing::warn!("Échec du rafraîchissement : {}", e);
                        active_session = Some(MinecraftSession {
                            username: row.mc_username.clone(),
                            uuid: row.mc_uuid.clone(),
                            access_token: row.access_token.clone(),
                            refresh_token: Some(row.ms_refresh_token.clone()),
                            expires_at: row.expires_at,
                        });
                    }
                }
            } else {
                active_session = Some(MinecraftSession {
                    username: row.mc_username.clone(),
                    uuid: row.mc_uuid.clone(),
                    access_token: row.access_token.clone(),
                    refresh_token: Some(row.ms_refresh_token.clone()),
                    expires_at: row.expires_at,
                });
            }
        }
    }

    state.write().await.session = active_session;

    Ok(LoginResp { token: data.token, username: data.username, plan: data.plan, plan_expires_at: data.plan_expires_at, accounts })
}

#[tauri::command]
pub async fn yuyu_logout(state: tauri::State<'_, SharedState>) -> Result<(), String> {
    {
        let s = state.read().await;
        let conn = s.db.lock().await;
        db::delete_yuyu_jwt(&conn).ok();
    }
    let mut w = state.write().await;
    w.yuyu_session = None;
    w.session = None;
    Ok(())
}

// ── Plan refresh ─────────────────────────────────────────────────────────────

#[tauri::command]
pub async fn yuyu_refresh_plan(state: tauri::State<'_, SharedState>) -> Result<PlanResp, String> {
    let token = {
        let s = state.read().await;
        s.yuyu_session
            .as_ref()
            .ok_or_else(|| "Non connecté à YuyuFrame".to_string())?
            .token
            .clone()
    };

    let client = reqwest::Client::new();
    let resp = client
        .get(format!("{}/auth/me", api_base()))
        .header("Authorization", format!("Bearer {}", token))
        .send()
        .await
        .map_err(|e| format!("Serveur inaccessible : {e}"))?;

    if !resp.status().is_success() {
        let msg = resp.text().await.unwrap_or_default();
        return Err(msg);
    }

    #[derive(Deserialize)]
    struct MeResp {
        plan: String,
        plan_expires_at: Option<i64>,
    }

    let data: MeResp = resp.json().await.map_err(|e| e.to_string())?;

    {
        let s = state.read().await;
        let conn = s.db.lock().await;
        db::update_yuyu_plan(&conn, &data.plan, data.plan_expires_at)
            .map_err(|e| e.to_string())?;
    }

    {
        let mut s = state.write().await;
        if let Some(session) = s.yuyu_session.as_mut() {
            // Détecte une transition free → premium/ultimate pour approximer
            // "checkout terminé" côté client — le webhook Lemon Squeezy qui
            // confirme réellement le paiement arrive sur LauncherAPI, pas ici,
            // donc ce launcher ne peut qu'observer le résultat après coup, au
            // prochain refresh_plan (polling déjà en place côté Plans.tsx).
            let was_free = session.plan != "premium" && session.plan != "ultimate";
            let now_paid = data.plan == "premium" || data.plan == "ultimate";
            if was_free && now_paid {
                crate::integrations::analytics::capture("checkout_completed", serde_json::json!({ "plan": &data.plan }));
            }
            session.plan = data.plan.clone();
            session.plan_expires_at = data.plan_expires_at;
        }
    }

    Ok(PlanResp { plan: data.plan, plan_expires_at: data.plan_expires_at })
}

// ── Checkout Lemon Squeezy ────────────────────────────────────────────────────

#[tauri::command]
pub async fn yuyu_create_checkout(
    state: tauri::State<'_, SharedState>,
    plan: String,
) -> Result<CheckoutResp, String> {
    let token = {
        let s = state.read().await;
        s.yuyu_session
            .as_ref()
            .ok_or_else(|| "Non connecté à YuyuFrame".to_string())?
            .token
            .clone()
    };

    let client = reqwest::Client::new();
    let resp = client
        .post(format!("{}/payments/create-checkout", api_base()))
        .header("Authorization", format!("Bearer {}", token))
        .json(&serde_json::json!({ "plan": plan }))
        .send()
        .await
        .map_err(|e| format!("Serveur inaccessible : {e}"))?;

    if !resp.status().is_success() {
        let msg = resp.text().await.unwrap_or_default();
        return Err(msg);
    }

    #[derive(Deserialize)]
    struct ApiCheckoutResp {
        checkout_url: String,
    }

    let data: ApiCheckoutResp = resp.json().await.map_err(|e| e.to_string())?;
    crate::integrations::analytics::capture("checkout_started", serde_json::json!({ "plan": &plan }));
    Ok(CheckoutResp { checkout_url: data.checkout_url })
}

// ── Dev : simulation de paiement ──────────────────────────────────────────────

#[tauri::command]
pub async fn yuyu_dev_simulate_payment(
    state: tauri::State<'_, SharedState>,
    plan: String,
) -> Result<PlanResp, String> {
    let token = {
        let s = state.read().await;
        s.yuyu_session
            .as_ref()
            .ok_or_else(|| "Non connecté à YuyuFrame".to_string())?
            .token
            .clone()
    };

    let client = reqwest::Client::new();
    let resp = client
        .post(format!("{}/dev/simulate-payment", api_base()))
        .header("Authorization", format!("Bearer {}", token))
        .json(&serde_json::json!({ "plan": plan }))
        .send()
        .await
        .map_err(|e| format!("Serveur inaccessible : {e}"))?;

    if !resp.status().is_success() {
        let msg = resp.text().await.unwrap_or_default();
        return Err(msg);
    }

    #[derive(Deserialize)]
    struct SimResp {
        plan: String,
        plan_expires_at: Option<i64>,
    }

    let data: SimResp = resp.json().await.map_err(|e| e.to_string())?;

    {
        let s = state.read().await;
        let conn = s.db.lock().await;
        db::update_yuyu_plan(&conn, &data.plan, data.plan_expires_at)
            .map_err(|e| e.to_string())?;
    }
    {
        let mut s = state.write().await;
        if let Some(session) = s.yuyu_session.as_mut() {
            session.plan = data.plan.clone();
            session.plan_expires_at = data.plan_expires_at;
        }
    }

    Ok(PlanResp { plan: data.plan, plan_expires_at: data.plan_expires_at })
}

// ── Helper ────────────────────────────────────────────────────────────────────

async fn save_session(
    state: &tauri::State<'_, SharedState>,
    user_id: i64,
    username: &str,
    jwt: &str,
    plan: &str,
    plan_expires_at: Option<i64>,
) -> Result<(), String> {
    {
        let s = state.read().await;
        let conn = s.db.lock().await;
        db::save_yuyu_jwt(&conn, user_id, username, jwt, plan, plan_expires_at)
            .map_err(|e| e.to_string())?;
        // Adopte les instances créées avant la connexion (yuyu_user_id = 0)
        db::instance_claim_unclaimed(&conn, user_id).ok();
    }
    state.write().await.yuyu_session = Some(crate::state::YuyuSession {
        user_id,
        username: username.to_string(),
        token: jwt.to_string(),
        plan: plan.to_string(),
        plan_expires_at,
    });
    Ok(())
}
