//! L'appel à LauncherAPI, et rien d'autre : `POST /v1/presence/lookup`.

use std::time::Duration;

use super::fingerprint::hex;

/// Hérité du launcher quand il pointe une API locale ; absent en temps normal.
const API_ENV: &str = "YUYU_API_URL";
const DEFAULT_API: &str = "https://api.yuyuframe.eu";

#[derive(Debug, PartialEq, Eq)]
pub enum Failure {
    /// Jeton refusé — le plus souvent expiré : le launcher en enverra un autre.
    Refused,
    /// Quota de l'heure atteint côté serveur.
    RateLimited,
    /// Réseau, serveur en panne, réponse illisible : à retenter plus tard.
    Unavailable,
}

pub struct Client {
    url: String,
    agent: ureq::Agent,
}

/// Le jeton ouvre le compte : il ne part qu'en HTTPS, ou vers la machine
/// elle-même (API lancée en local).
fn is_safe_base(base: &str) -> bool {
    base.starts_with("https://") || base.starts_with("http://localhost") || base.starts_with("http://127.0.0.1")
}

impl Client {
    /// `None` si l'adresse de l'API n'est pas de celles où un jeton peut partir.
    pub fn from_env() -> Option<Self> {
        let base = std::env::var(API_ENV).ok().map(|b| b.trim().trim_end_matches('/').to_string()).filter(|b| !b.is_empty());
        Self::new(base.as_deref().unwrap_or(DEFAULT_API))
    }

    fn new(base: &str) -> Option<Self> {
        if !is_safe_base(base) {
            return None;
        }
        Some(Self {
            url: format!("{base}/v1/presence/lookup"),
            agent: ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(5)).timeout(Duration::from_secs(10)).build(),
        })
    }

    /// Une réponse par empreinte, dans l'ordre.
    pub fn lookup(&self, token: &str, fingerprints: &[[u8; 32]]) -> Result<Vec<bool>, Failure> {
        let body = serde_json::json!({ "fingerprints": fingerprints.iter().map(|f| hex(f)).collect::<Vec<_>>() });
        let response = self
            .agent
            .post(&self.url)
            .set("Authorization", &format!("Bearer {token}"))
            .set("User-Agent", "YuyuFrame-LauncherAgent/0.1")
            .send_json(body)
            .map_err(|e| match e {
                ureq::Error::Status(401 | 403, _) => Failure::Refused,
                ureq::Error::Status(429, _) => Failure::RateLimited,
                _ => Failure::Unavailable,
            })?;
        let parsed: serde_json::Value = response.into_json().map_err(|_| Failure::Unavailable)?;
        let present: Vec<bool> = parsed
            .get("present")
            .and_then(|p| p.as_array())
            .ok_or(Failure::Unavailable)?
            .iter()
            .map(|v| v.as_bool().unwrap_or(false))
            .collect();
        // Une réponse d'une autre longueur ne se lit pas : on ne devine pas à qui va quoi.
        if present.len() != fingerprints.len() {
            return Err(Failure::Unavailable);
        }
        Ok(present)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn le_jeton_ne_part_qu_en_https_ou_en_local() {
        assert!(Client::new("https://api.yuyuframe.eu").is_some());
        assert!(Client::new("http://localhost:3000").is_some());
        assert!(Client::new("http://127.0.0.1:3000").is_some());
        assert!(Client::new("http://api.yuyuframe.eu").is_none());
        assert!(Client::new("ftp://ailleurs").is_none());
        assert_eq!(Client::new("https://api.yuyuframe.eu").unwrap().url, "https://api.yuyuframe.eu/v1/presence/lookup");
    }
}
