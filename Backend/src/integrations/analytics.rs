// `capture()` n'est appelée nulle part encore (voir plus bas) — la liste des
// événements à suivre reste à définir avant de câbler les premiers appels.
#![allow(dead_code)]

// Intégration PostHog (analytics produit) — capture d'événements en
// fire-and-forget (tokio::spawn), jamais bloquant et jamais fatal pour le
// launcher : un envoi raté (clé absente, pas de réseau, timeout PostHog...)
// ne doit jamais impacter un lancement, un téléchargement ou toute autre
// action utilisateur. Volontairement aucun événement câblé pour l'instant —
// seulement la plomberie, prête à être appelée une fois la liste des
// événements à suivre décidée.
//
// `POSTHOG_API_KEY` (clé de projet PostHog, faite pour être embarquée
// côté client — même statut que MS_CLIENT_ID dans minecraft/auth.rs) : tant
// qu'elle n'est pas définie, capture() ne fait strictement rien, aucun
// comportement à activer/désactiver ailleurs dans le code.

use std::path::PathBuf;
use std::sync::OnceLock;

fn api_key() -> Option<&'static str> {
    static KEY: OnceLock<Option<String>> = OnceLock::new();
    KEY.get_or_init(|| std::env::var("POSTHOG_API_KEY").ok().filter(|k| !k.is_empty()))
        .as_deref()
}

fn host() -> String {
    std::env::var("POSTHOG_HOST").unwrap_or_else(|_| "https://us.i.posthog.com".to_string())
}

fn device_id_path() -> PathBuf {
    dirs::data_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("YuyuFrame")
        .join("device_id")
}

/// Identifiant anonyme persisté localement (aucun lien avec le compte
/// YuyuFrame ni le compte Minecraft) — généré une seule fois par poste,
/// réutilisé à chaque lancement pour que PostHog puisse relier les
/// événements d'une même installation entre deux sessions.
fn distinct_id() -> &'static str {
    static ID: OnceLock<String> = OnceLock::new();
    ID.get_or_init(|| {
        let path = device_id_path();
        if let Ok(existing) = std::fs::read_to_string(&path) {
            let trimmed = existing.trim();
            if !trimmed.is_empty() {
                return trimmed.to_string();
            }
        }
        let id = uuid::Uuid::new_v4().to_string();
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let _ = std::fs::write(&path, &id);
        id
    })
}

/// Capture un événement PostHog — no-op silencieux si `POSTHOG_API_KEY`
/// n'est pas définie. `properties` : les métadonnées de l'événement (ex:
/// `serde_json::json!({ "instance_loader": "fabric" })`).
pub fn capture(event: &str, properties: serde_json::Value) {
    let Some(key) = api_key() else { return };

    let event = event.to_string();
    let key = key.to_string();
    let distinct_id = distinct_id().to_string();
    let host = host();

    tokio::spawn(async move {
        let body = serde_json::json!({
            "api_key": key,
            "event": event,
            "distinct_id": distinct_id,
            "properties": properties,
        });
        if let Err(e) = reqwest::Client::new()
            .post(format!("{}/capture/", host))
            .json(&body)
            .send()
            .await
        {
            tracing::debug!("[Analytics] envoi de l'événement '{}' échoué : {}", event, e);
        }
    });
}
