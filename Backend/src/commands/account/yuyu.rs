use serde::{Deserialize, Serialize};

use crate::commands::{api_base, network_err};
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

/// Ping léger de la LauncherAPI (`GET /health`) pour l'indicateur de
/// connectivité du frontend — ne renvoie jamais d'erreur (une panne réseau
/// donne juste `false`), volontairement : ce check ne doit jamais déclencher
/// de toast d'erreur, juste un petit badge discret côté UI. Timeout court
/// (5s, largement sous celui du client partagé) pour ne pas retarder le
/// prochain check périodique si l'API traîne à répondre.
#[tauri::command]
pub async fn yuyu_ping(state: tauri::State<'_, SharedState>) -> Result<bool, ()> {
    let client = state.read().await.http.clone();
    let ok = client
        .get(format!("{}/health", api_base()))
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
) -> Result<LoginResp, String> {
    let client = state.read().await.http.clone();
    let resp = client
        .post(format!("{}/auth/register", api_base()))
        .json(&serde_json::json!({ "username": username, "password": password }))
        .send()
        .await
        .map_err(network_err)?;

    if !resp.status().is_success() {
        let msg = resp.text().await.unwrap_or_default();
        return Err(msg);
    }

    let data: ApiAuthResponse = resp.json().await.map_err(|e| e.to_string())?;
    save_session(&state, data.user_id, &data.username, &data.token, &data.plan, data.plan_expires_at).await?;
    let accounts = super::list_accounts(&state).await?;

    Ok(LoginResp { token: data.token, username: data.username, plan: data.plan, plan_expires_at: data.plan_expires_at, accounts })
}

#[tauri::command]
pub async fn yuyu_login(
    state: tauri::State<'_, SharedState>,
    username: String,
    password: String,
) -> Result<LoginResp, String> {
    let client = state.read().await.http.clone();
    let resp = client
        .post(format!("{}/auth/login", api_base()))
        .json(&serde_json::json!({ "username": username, "password": password }))
        .send()
        .await
        .map_err(network_err)?;

    if !resp.status().is_success() {
        let msg = resp.text().await.unwrap_or_default();
        return Err(msg);
    }

    let data: ApiAuthResponse = resp.json().await.map_err(|e| e.to_string())?;
    save_session(&state, data.user_id, &data.username, &data.token, &data.plan, data.plan_expires_at).await?;

    // Les comptes Minecraft appartiennent au PC : la connexion YuyuFrame ne
    // change ni la liste ni le compte actif, elle les renvoie simplement.
    let accounts = super::list_accounts(&state).await?;

    Ok(LoginResp { token: data.token, username: data.username, plan: data.plan, plan_expires_at: data.plan_expires_at, accounts })
}

#[tauri::command]
pub async fn yuyu_logout(state: tauri::State<'_, SharedState>) -> Result<(), String> {
    {
        let s = state.read().await;
        let conn = s.db.lock().await;
        db::delete_yuyu_jwt(&conn).ok();
    }
    // Le compte Minecraft actif reste connecté : il ne dépend pas de YuyuFrame.
    state.write().await.yuyu_session = None;
    Ok(())
}

// ── Plan refresh ─────────────────────────────────────────────────────────────

#[tauri::command]
pub async fn yuyu_refresh_plan(state: tauri::State<'_, SharedState>) -> Result<PlanResp, String> {
    let (token, client) = {
        let s = state.read().await;
        let token = s.yuyu_session
            .as_ref()
            .ok_or_else(|| "Non connecté à YuyuFrame".to_string())?
            .token
            .clone();
        (token, s.http.clone())
    };

    let resp = client
        .get(format!("{}/auth/me", api_base()))
        .header("Authorization", format!("Bearer {}", token))
        .send()
        .await
        .map_err(network_err)?;

    if !resp.status().is_success() {
        return Err(crate::commands::bearer_call_error(resp).await);
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
    let (token, client) = {
        let s = state.read().await;
        let token = s.yuyu_session
            .as_ref()
            .ok_or_else(|| "Non connecté à YuyuFrame".to_string())?
            .token
            .clone();
        (token, s.http.clone())
    };

    let resp = client
        .post(format!("{}/payments/create-checkout", api_base()))
        .header("Authorization", format!("Bearer {}", token))
        .json(&serde_json::json!({ "plan": plan }))
        .send()
        .await
        .map_err(network_err)?;

    if !resp.status().is_success() {
        return Err(crate::commands::bearer_call_error(resp).await);
    }

    #[derive(Deserialize)]
    struct ApiCheckoutResp {
        checkout_url: String,
    }

    let data: ApiCheckoutResp = resp.json().await.map_err(|e| e.to_string())?;
    crate::integrations::analytics::capture("checkout_started", serde_json::json!({ "plan": &plan }));
    Ok(CheckoutResp { checkout_url: data.checkout_url })
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
