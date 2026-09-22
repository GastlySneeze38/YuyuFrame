// Couche d'accès à la LauncherAPI /v1 (refonte serveur du 2026-09-19).
//
// Tout passe par ici : l'URL de base, les erreurs typées, et surtout la
// rotation des jetons. Le serveur ne délivre plus un jeton de 30 jours mais
// un jeton d'accès de 15 minutes accompagné d'un refresh token à usage
// unique — chaque rafraîchissement en renvoie un nouveau, et réutiliser
// l'ancien fait fermer la session (protection contre un jeton volé).
//
// Conséquence : aucune commande ne manipule de jeton à la main. Elle appelle
// `api::get`/`post`/… qui rafraîchit toute seule quand il faut, une seule
// fois même si dix appels partent en même temps (verrou `yuyu_refresh`).

pub mod error;
pub mod fleet;
pub mod license;

use error::{ApiError, ApiResult};
use reqwest::Method;
use serde_json::Value;

use crate::state::{SharedState, YuyuSession};

/// Racine du serveur. `YUYU_API_URL` permet de pointer une instance locale.
pub fn root() -> String {
    std::env::var("YUYU_API_URL").unwrap_or_else(|_| "https://api.yuyuframe.eu".into())
}

/// Base des routes versionnées. Tout est sous `/v1` depuis la refonte.
pub fn base() -> String {
    format!("{}/v1", root())
}

/// Marge avant expiration : on rafraîchit un peu en avance plutôt que de
/// prendre un 401 en pleine requête.
const REFRESH_MARGIN_SECS: i64 = 60;

/// Identifie cette installation auprès du serveur : la liste « Appareils
/// connectés » du compte, et le pilotage de flotte (version, OS), s'en
/// servent. `device_id` est le même identifiant anonyme que les statistiques.
pub fn device_info() -> Value {
    serde_json::json!({
        "device_id": crate::integrations::analytics::install_id(),
        "device_name": hostname(),
        "os": std::env::consts::OS,
        "launcher_version": env!("CARGO_PKG_VERSION"),
    })
}

fn hostname() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "PC".into())
}

fn now() -> i64 {
    chrono::Utc::now().timestamp()
}

// ── Appels sans session ──────────────────────────────────────────────────────

/// Route publique (connexion, inscription, rafraîchissement, santé).
pub async fn post_public(state: &SharedState, path: &str, body: Value) -> ApiResult<Value> {
    let client = state.read().await.http.clone();
    let resp = client
        .post(format!("{}{path}", base()))
        .json(&body)
        .send()
        .await
        .map_err(ApiError::network)?;
    read_json(resp).await
}

// ── Appels authentifiés ──────────────────────────────────────────────────────

pub async fn get(state: &SharedState, path: &str, query: &[(&str, String)]) -> ApiResult<Value> {
    authed(state, Method::GET, path, query, None).await
}

pub async fn post(state: &SharedState, path: &str, body: Value) -> ApiResult<Value> {
    authed(state, Method::POST, path, &[], Some(body)).await
}

pub async fn patch(state: &SharedState, path: &str, body: Value) -> ApiResult<Value> {
    authed(state, Method::PATCH, path, &[], Some(body)).await
}

pub async fn put(state: &SharedState, path: &str, body: Value) -> ApiResult<Value> {
    authed(state, Method::PUT, path, &[], Some(body)).await
}

pub async fn delete(state: &SharedState, path: &str) -> ApiResult<Value> {
    authed(state, Method::DELETE, path, &[], None).await
}

// ── Corps binaires ───────────────────────────────────────────────────────────
//
// Les morceaux de la sync et des sauvegardes ne sont pas du JSON : ils
// partent et reviennent tels quels, par paquets de 16 Mio. Ils passent par
// les mêmes garde-fous que le reste (jeton valide, rafraîchissement, un seul
// nouvel essai) — un envoi de plusieurs minutes traverse forcément une
// expiration de jeton, et c'est exactement le cas qu'on ne veut pas gérer à
// la main dans chaque appelant.

/// Envoie un corps binaire brut. Le délai est propre à l'appel : un morceau
/// de 16 Mio sur une connexion lente dépasse largement les 30 secondes du
/// client partagé.
pub async fn put_bytes(state: &SharedState, path: &str, body: Vec<u8>, timeout: std::time::Duration) -> ApiResult<()> {
    let mut token = valid_access_token(state).await?;

    for attempt in 0..2 {
        let client = state.read().await.http.clone();
        let resp = client
            .put(format!("{}{path}", base()))
            .bearer_auth(&token)
            .header(reqwest::header::CONTENT_TYPE, "application/octet-stream")
            .timeout(timeout)
            .body(body.clone())
            .send()
            .await
            .map_err(ApiError::network)?;

        if resp.status().is_success() {
            return Ok(());
        }
        let e = ApiError::from_response(resp).await;
        if attempt == 0 && e.is_expired_access() {
            token = refresh(state).await?;
            continue;
        }
        if e.is_session_lost() {
            clear_session(state).await;
        }
        return Err(e);
    }
    unreachable!("la boucle rend la main aux deux tours")
}

/// Récupère un corps binaire brut.
pub async fn get_bytes(state: &SharedState, path: &str, timeout: std::time::Duration) -> ApiResult<Vec<u8>> {
    let mut token = valid_access_token(state).await?;

    for attempt in 0..2 {
        let client = state.read().await.http.clone();
        let resp = client
            .get(format!("{}{path}", base()))
            .bearer_auth(&token)
            .timeout(timeout)
            .send()
            .await
            .map_err(ApiError::network)?;

        if resp.status().is_success() {
            return resp.bytes().await.map(|b| b.to_vec()).map_err(ApiError::network);
        }
        let e = ApiError::from_response(resp).await;
        if attempt == 0 && e.is_expired_access() {
            token = refresh(state).await?;
            continue;
        }
        if e.is_session_lost() {
            clear_session(state).await;
        }
        return Err(e);
    }
    unreachable!("la boucle rend la main aux deux tours")
}

/// Un appel authentifié, avec au plus un rafraîchissement puis un seul
/// nouvel essai : si le second échoue encore en 401, la session est perdue.
async fn authed(
    state: &SharedState,
    method: Method,
    path: &str,
    query: &[(&str, String)],
    body: Option<Value>,
) -> ApiResult<Value> {
    let mut token = valid_access_token(state).await?;

    for attempt in 0..2 {
        let client = state.read().await.http.clone();
        let mut req = client.request(method.clone(), format!("{}{path}", base())).bearer_auth(&token);
        if !query.is_empty() {
            req = req.query(query);
        }
        if let Some(body) = &body {
            req = req.json(body);
        }

        let resp = req.send().await.map_err(ApiError::network)?;
        match read_json(resp).await {
            Ok(value) => return Ok(value),
            Err(e) if attempt == 0 && e.is_expired_access() => {
                // Jeton périmé, ou déjà remplacé par un appel concurrent :
                // on rafraîchit et on rejoue une fois.
                token = refresh(state).await?;
            }
            Err(e) => {
                if e.is_session_lost() {
                    clear_session(state).await;
                }
                return Err(e);
            }
        }
    }
    unreachable!("la boucle rend la main aux deux tours")
}

pub(crate) async fn read_json(resp: reqwest::Response) -> ApiResult<Value> {
    if !resp.status().is_success() {
        return Err(ApiError::from_response(resp).await);
    }
    // 204 et corps vides sont légitimes (déconnexion, suppression).
    let text = resp.text().await.map_err(ApiError::network)?;
    if text.trim().is_empty() {
        return Ok(Value::Null);
    }
    serde_json::from_str(&text).map_err(|e| ApiError::new("internal", format!("Réponse illisible du serveur : {e}")))
}

/// Jeton d'accès utilisable : celui en mémoire s'il a encore de la marge,
/// sinon un neuf.
async fn valid_access_token(state: &SharedState) -> ApiResult<String> {
    let session = state.read().await.yuyu_session.clone().ok_or_else(ApiError::not_signed_in)?;
    if session.access_expires_at - REFRESH_MARGIN_SECS > now() {
        return Ok(session.access_token);
    }
    refresh(state).await
}

/// Rafraîchit la session. Le verrou garantit qu'un seul appel parle au
/// serveur : les autres attendent et repartent avec le nouveau jeton, sans
/// réutiliser un refresh token déjà consommé (ce qui fermerait la session).
pub async fn refresh(state: &SharedState) -> ApiResult<String> {
    let lock = state.read().await.yuyu_refresh.clone();
    let _guard = lock.lock().await;

    let session = state.read().await.yuyu_session.clone().ok_or_else(ApiError::not_signed_in)?;
    // Quelqu'un d'autre vient de le faire pendant qu'on attendait le verrou.
    if session.access_expires_at - REFRESH_MARGIN_SECS > now() {
        return Ok(session.access_token);
    }

    let body = serde_json::json!({ "refresh_token": session.refresh_token, "device": device_info() });
    match post_public(state, "/auth/refresh", body).await {
        Ok(value) => {
            let session = store_token_response(state, &value).await?;
            Ok(session.access_token)
        }
        Err(e) => {
            // Refresh refusé : la session est bel et bien perdue côté
            // serveur. Une simple panne réseau, elle, ne doit RIEN effacer.
            if e.code != error::CODE_NETWORK {
                clear_session(state).await;
            }
            Err(e)
        }
    }
}

// ── Session locale ───────────────────────────────────────────────────────────

/// Enregistre un `TokenResponse` (connexion, inscription, rafraîchissement) :
/// mémoire + base locale, pour retrouver la session au prochain démarrage.
pub async fn store_token_response(state: &SharedState, value: &Value) -> ApiResult<YuyuSession> {
    let profile = value.get("profile").ok_or_else(|| ApiError::new("internal", "Réponse sans profil"))?;
    let access_expires_in = value.get("access_expires_in").and_then(Value::as_i64).unwrap_or(900);

    let session = YuyuSession {
        user_id: profile.get("user_id").and_then(Value::as_i64).unwrap_or(0),
        username: string_at(profile, "username"),
        email: opt_string_at(profile, "email"),
        plan: profile.get("plan").and_then(Value::as_str).unwrap_or("free").to_string(),
        plan_expires_at: profile.get("plan_expires_at").and_then(Value::as_i64),
        password_reset_required: profile.get("password_reset_required").and_then(Value::as_bool).unwrap_or(false),
        license: opt_string_at(profile, "license"),
        access_token: string_at(value, "access_token"),
        access_expires_at: now() + access_expires_in,
        refresh_token: string_at(value, "refresh_token"),
    };

    persist(state, &session).await;
    state.write().await.yuyu_session = Some(session.clone());
    Ok(session)
}

/// Met à jour le profil (plan, licence, e-mail…) sans toucher aux jetons —
/// après `GET /v1/me`, par exemple.
pub async fn store_profile(state: &SharedState, profile: &Value) -> Option<YuyuSession> {
    let mut session = state.read().await.yuyu_session.clone()?;
    session.username = string_at(profile, "username");
    session.email = opt_string_at(profile, "email");
    session.plan = profile.get("plan").and_then(Value::as_str).unwrap_or("free").to_string();
    session.plan_expires_at = profile.get("plan_expires_at").and_then(Value::as_i64);
    session.password_reset_required =
        profile.get("password_reset_required").and_then(Value::as_bool).unwrap_or(false);
    if let Some(license) = opt_string_at(profile, "license") {
        session.license = Some(license);
    }
    persist(state, &session).await;
    state.write().await.yuyu_session = Some(session.clone());
    Some(session)
}

async fn persist(state: &SharedState, session: &YuyuSession) {
    let s = state.read().await;
    let conn = s.db.lock().await;
    if let Err(e) = crate::db::save_yuyu_session(&conn, session) {
        tracing::warn!("session YuyuFrame non enregistrée localement : {e}");
    }
    // Adopte les instances créées avant la connexion (yuyu_user_id = 0).
    crate::db::instance_claim_unclaimed(&conn, session.user_id).ok();
}

/// Oublie la session ici : le serveur l'a déjà fermée de son côté.
pub async fn clear_session(state: &SharedState) {
    {
        let s = state.read().await;
        let conn = s.db.lock().await;
        crate::db::delete_yuyu_session(&conn).ok();
    }
    state.write().await.yuyu_session = None;
}

fn string_at(value: &Value, key: &str) -> String {
    value.get(key).and_then(Value::as_str).unwrap_or_default().to_string()
}

fn opt_string_at(value: &Value, key: &str) -> Option<String> {
    value.get(key).and_then(Value::as_str).filter(|s| !s.is_empty()).map(str::to_string)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base_url_is_versioned() {
        assert!(base().ends_with("/v1"), "toutes les routes vivent sous /v1");
    }

    #[test]
    fn device_info_identifies_this_install() {
        let d = device_info();
        assert_eq!(d["launcher_version"], env!("CARGO_PKG_VERSION"));
        assert_eq!(d["os"], std::env::consts::OS);
        assert!(d["device_id"].as_str().is_some_and(|id| !id.is_empty()));
    }
}
