//! Les appels à LauncherAPI, et rien d'autre : `POST /v1/presence/lookup`
//! (comptes Microsoft) et `POST /v1/presence/offline` (serveurs de jeu en
//! mode hors ligne : s'annoncer et demander sa liste d'un seul coup).

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
    /// La route n'existe pas sur ce serveur (API plus ancienne que le jeu) :
    /// inutile d'insister pendant cette partie.
    Unsupported,
}

pub struct Client {
    url: String,
    offline_url: String,
    agent: ureq::Agent,
}

/// Le jeton ouvre le compte : il ne part qu'en HTTPS, ou vers la machine
/// elle-même (API lancée en local).
fn is_safe_base(base: &str) -> bool {
    base.starts_with("https://") || base.starts_with("http://localhost") || base.starts_with("http://127.0.0.1")
}

/// L'adresse de LauncherAPI, sans `/` final — `None` si elle n'est pas de
/// celles où un jeton peut partir. Partagée avec l'envoi des duels
/// (`combat::share`) : une seule règle pour tout ce qui part avec le jeton.
pub fn api_base() -> Option<String> {
    let base = std::env::var(API_ENV).ok().map(|b| b.trim().trim_end_matches('/').to_string()).filter(|b| !b.is_empty());
    let base = base.unwrap_or_else(|| DEFAULT_API.to_string());
    is_safe_base(&base).then_some(base)
}

impl Client {
    /// `None` si l'adresse de l'API n'est pas de celles où un jeton peut partir.
    pub fn from_env() -> Option<Self> {
        Self::new(&api_base()?)
    }

    fn new(base: &str) -> Option<Self> {
        if !is_safe_base(base) {
            return None;
        }
        Some(Self {
            url: format!("{base}/v1/presence/lookup"),
            offline_url: format!("{base}/v1/presence/offline"),
            agent: ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(5)).timeout(Duration::from_secs(10)).build(),
        })
    }

    fn post(&self, url: &str, token: &str, body: serde_json::Value) -> Result<serde_json::Value, Failure> {
        let response = self
            .agent
            .post(url)
            .set("Authorization", &format!("Bearer {token}"))
            .set("User-Agent", "YuyuFrame-LauncherAgent/0.1")
            .send_json(body)
            .map_err(|e| match e {
                ureq::Error::Status(401 | 403, _) => Failure::Refused,
                ureq::Error::Status(429, _) => Failure::RateLimited,
                ureq::Error::Status(404 | 405, _) => Failure::Unsupported,
                _ => Failure::Unavailable,
            })?;
        response.into_json().map_err(|_| Failure::Unavailable)
    }

    /// Les réponses d'un corps `{ "present": [...] }`, une par empreinte demandée.
    fn present(parsed: &serde_json::Value, asked: usize) -> Result<Vec<bool>, Failure> {
        let present: Vec<bool> = parsed
            .get("present")
            .and_then(|p| p.as_array())
            .ok_or(Failure::Unavailable)?
            .iter()
            .map(|v| v.as_bool().unwrap_or(false))
            .collect();
        // Une réponse d'une autre longueur ne se lit pas : on ne devine pas à qui va quoi.
        if present.len() != asked {
            return Err(Failure::Unavailable);
        }
        Ok(present)
    }

    /// Une réponse par empreinte, dans l'ordre.
    pub fn lookup(&self, token: &str, fingerprints: &[[u8; 32]]) -> Result<Vec<bool>, Failure> {
        let body = serde_json::json!({ "fingerprints": fingerprints.iter().map(|f| hex(f)).collect::<Vec<_>>() });
        let parsed = self.post(&self.url, token, body)?;
        Self::present(&parsed, fingerprints.len())
    }

    /// Serveur de jeu hors ligne : annonce `own` (vide = ne pas s'annoncer)
    /// et demande `players`. Rend une réponse par empreinte de `players`, et
    /// la cadence d'annonce que le serveur attend, en secondes.
    pub fn sync_offline(&self, token: &str, own: &[[u8; 32]], players: &[[u8; 32]]) -> Result<(Vec<bool>, Option<u64>), Failure> {
        let body = serde_json::json!({
            "own": own.iter().map(|f| hex(f)).collect::<Vec<_>>(),
            "players": players.iter().map(|f| hex(f)).collect::<Vec<_>>(),
        });
        let parsed = self.post(&self.offline_url, token, body)?;
        let present = Self::present(&parsed, players.len())?;
        Ok((present, parsed.get("renew_in").and_then(|v| v.as_u64())))
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
        let client = Client::new("https://api.yuyuframe.eu").unwrap();
        assert_eq!(client.url, "https://api.yuyuframe.eu/v1/presence/lookup");
        assert_eq!(client.offline_url, "https://api.yuyuframe.eu/v1/presence/offline");
    }
}
