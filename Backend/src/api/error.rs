// Erreurs de la LauncherAPI /v1. Le serveur répond `{ "error": "...",
// "code": "..." }` (Server/LauncherAPI/src/error.rs) : le `code` est la seule
// chose stable, le texte peut changer.
//
// Les commandes Tauri gardent leur convention `Result<T, String>` : une erreur
// part donc en JSON compact, que le frontend relit avec `parseApiError`
// (Frontend/src/lib/apiError.ts). Les deux doivent rester d'accord sur les
// noms de champs.

use serde::{Deserialize, Serialize};

/// Panne de transport (DNS, timeout, connexion refusée) : jamais renvoyée par
/// le serveur, fabriquée ici.
pub const CODE_NETWORK: &str = "network";
/// Aucune session locale : l'appelant doit se connecter d'abord.
pub const CODE_NOT_SIGNED_IN: &str = "not_signed_in";
/// Session fermée côté serveur (refresh refusé, appareil déconnecté, vol de
/// jeton détecté) : il faut revenir à l'écran de connexion.
pub const CODE_SESSION_REVOKED: &str = "session_revoked";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiError {
    /// Code stable, sur lequel le launcher décide quoi faire.
    pub code: String,
    /// Message lisible, déjà en français côté serveur.
    pub message: String,
    /// Champs supplémentaires de certaines erreurs : `min_version`, `reason`,
    /// `until`, `revision`… Transmis tels quels au frontend.
    #[serde(default, skip_serializing_if = "serde_json::Map::is_empty")]
    pub extra: serde_json::Map<String, serde_json::Value>,
}

impl ApiError {
    pub fn new(code: &str, message: impl Into<String>) -> Self {
        Self { code: code.into(), message: message.into(), extra: serde_json::Map::new() }
    }

    pub fn network(e: impl std::fmt::Display) -> Self {
        Self::new(CODE_NETWORK, format!("Serveur inaccessible : {e}"))
    }

    pub fn not_signed_in() -> Self {
        Self::new(CODE_NOT_SIGNED_IN, "Connecte-toi à ton compte YuyuFrame")
    }

    /// Lit la réponse d'erreur du serveur. Une réponse illisible (proxy en
    /// panne, HTML d'erreur) ne doit pas faire perdre le statut HTTP.
    pub async fn from_response(resp: reqwest::Response) -> Self {
        let status = resp.status();
        let body = resp.text().await.unwrap_or_default();
        let parsed: Option<serde_json::Value> = serde_json::from_str(&body).ok();
        let obj = parsed.as_ref().and_then(|v| v.as_object());

        let code = obj
            .and_then(|o| o.get("code"))
            .and_then(|c| c.as_str())
            .map(str::to_string)
            .unwrap_or_else(|| default_code(status).to_string());
        let message = obj
            .and_then(|o| o.get("error"))
            .and_then(|m| m.as_str())
            .map(str::to_string)
            .unwrap_or_else(|| {
                if body.trim().is_empty() || body.trim_start().starts_with('<') {
                    format!("Erreur {} du serveur", status.as_u16())
                } else {
                    body.clone()
                }
            });

        // Tout le reste du corps (min_version, reason, until, revision…).
        let mut extra = obj.cloned().unwrap_or_default();
        extra.remove("code");
        extra.remove("error");

        Self { code, message, extra }
    }

    /// Le jeton d'accès est simplement périmé : un rafraîchissement suffit,
    /// la session reste valable.
    pub fn is_expired_access(&self) -> bool {
        self.code == "unauthorized" || self.code == "token_rotated"
    }

    /// Plus aucune session utilisable : retour à l'écran de connexion.
    pub fn is_session_lost(&self) -> bool {
        self.code == CODE_SESSION_REVOKED || self.code == CODE_NOT_SIGNED_IN
    }
}

fn default_code(status: reqwest::StatusCode) -> &'static str {
    match status.as_u16() {
        401 => "unauthorized",
        402 => "payment_required",
        403 => "forbidden",
        404 => "not_found",
        409 => "conflict",
        422 => "quota_exceeded",
        426 => "update_required",
        429 => "rate_limited",
        502 | 503 | 504 => "unavailable",
        _ => "internal",
    }
}

impl std::fmt::Display for ApiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.message)
    }
}

/// Forme envoyée au frontend à travers `Result<T, String>`.
impl From<ApiError> for String {
    fn from(e: ApiError) -> String {
        serde_json::to_string(&e).unwrap_or_else(|_| e.message.clone())
    }
}

pub type ApiResult<T> = Result<T, ApiError>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn error_body_is_parsed_with_its_extra_fields() {
        let json = r#"{"error":"Mets à jour le launcher","code":"update_required","min_version":"0.2.0"}"#;
        let value: serde_json::Value = serde_json::from_str(json).unwrap();
        let obj = value.as_object().unwrap();
        let mut extra = obj.clone();
        extra.remove("code");
        extra.remove("error");
        let e = ApiError {
            code: obj["code"].as_str().unwrap().into(),
            message: obj["error"].as_str().unwrap().into(),
            extra,
        };
        assert_eq!(e.extra["min_version"], "0.2.0");
        // Aller-retour vers le frontend : rien ne se perd.
        let round: ApiError = serde_json::from_str(&String::from(e.clone())).unwrap();
        assert_eq!(round.code, "update_required");
        assert_eq!(round.extra["min_version"], "0.2.0");
    }

    #[test]
    fn expired_access_differs_from_lost_session() {
        assert!(ApiError::new("unauthorized", "").is_expired_access());
        assert!(ApiError::new("token_rotated", "").is_expired_access());
        assert!(!ApiError::new("session_revoked", "").is_expired_access());
        assert!(ApiError::new("session_revoked", "").is_session_lost());
    }
}
