//! Ce que le compte a : son plan (relu sur le serveur, ou déduit de la
//! licence signée hors ligne), le paiement, et la liste de ses comptes
//! Minecraft, que le support cherche par pseudo ou UUID.

use serde::Serialize;
use serde_json::json;

use crate::account::minecraft::{self, accounts::AccountInfo};
use crate::security::license;
use crate::server::{self as api, error::ApiError};
use crate::state::SharedState;

#[derive(Serialize)]
pub struct PlanResp {
    pub plan: String,
    pub plan_expires_at: Option<i64>,
}

#[derive(Serialize)]
pub struct CheckoutResp {
    pub checkout_url: String,
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

async fn current_plan(state: &tauri::State<'_, SharedState>) -> Option<String> {
    state.read().await.yuyu_session.as_ref().map(|s| s.plan.clone())
}

/// À appeler après l'ajout ou le retrait d'un compte Minecraft : le serveur
/// garde la liste complète, qui sert au support (recherche par pseudo ou
/// UUID Minecraft). Sans session YuyuFrame, il n'y a rien à envoyer.
#[tauri::command]
pub async fn yuyu_sync_minecraft_accounts(state: tauri::State<'_, SharedState>) -> Result<(), String> {
    if state.read().await.yuyu_session.is_none() {
        return Ok(());
    }
    let accounts = minecraft::list_accounts(&state).await?;
    push_minecraft_accounts(&state, &accounts).await;
    Ok(())
}

/// Liste complète des comptes Minecraft liés (remplace la précédente).
/// Silencieux : un échec ici ne doit jamais empêcher de se connecter.
pub async fn push_minecraft_accounts(state: &SharedState, accounts: &[AccountInfo]) {
    let payload: Vec<_> = accounts.iter().map(|a| json!({ "uuid": a.mc_uuid, "name": a.mc_username })).collect();
    if let Err(e) = api::put(state, "/me/minecraft-accounts", json!({ "accounts": payload })).await {
        tracing::debug!("comptes Minecraft non transmis : {}", e.message);
    }
}

const MOJANG_JOIN_URL: &str = "https://sessionserver.mojang.com/session/minecraft/join";

/// Prouve au serveur YuyuFrame que ce compte Minecraft est bien celui du
/// joueur — la poignée de main des serveurs de jeu, où LauncherAPI tient le
/// rôle du serveur : il donne un identifiant, on l'annonce à Mojang avec le
/// jeton Minecraft, et il demande à Mojang si l'annonce a bien eu lieu. Le
/// jeton Minecraft ne part que chez Mojang, jamais chez nous.
///
/// Sans session YuyuFrame ou pour un compte hors ligne, il n'y a rien à
/// prouver. Appelé au lancement du jeu, en tâche de fond : un échec ne doit
/// jamais empêcher de jouer.
pub async fn prove_minecraft_account(state: &SharedState, session: &crate::state::MinecraftSession) -> Result<(), String> {
    // Un compte hors ligne n'a pas de jeton Microsoft à rafraîchir.
    if session.refresh_token.is_none() || state.read().await.yuyu_session.is_none() {
        return Ok(());
    }
    // Le serveur cherche le compte dans la liste, avec son pseudo actuel.
    let accounts = minecraft::list_accounts(state).await?;
    push_minecraft_accounts(state, &accounts).await;

    let challenge = api::post(state, "/me/minecraft-accounts/proof", json!({ "uuid": session.uuid })).await.map_err(|e| e.message)?;
    let server_id = challenge.get("server_id").and_then(|v| v.as_str()).ok_or("Réponse de preuve inattendue")?;

    let http = state.read().await.http.clone();
    let joined = http
        .post(MOJANG_JOIN_URL)
        .json(&json!({
            "accessToken": session.access_token,
            "selectedProfile": session.uuid.replace('-', ""),
            "serverId": server_id,
        }))
        .timeout(std::time::Duration::from_secs(10))
        .send()
        .await
        .map_err(|e| format!("Mojang injoignable : {e}"))?;
    if !joined.status().is_success() {
        return Err(format!("Mojang a refusé l'annonce ({})", joined.status()));
    }

    api::post(state, "/me/minecraft-accounts/verify", json!({ "uuid": session.uuid })).await.map_err(|e| e.message)?;
    Ok(())
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
