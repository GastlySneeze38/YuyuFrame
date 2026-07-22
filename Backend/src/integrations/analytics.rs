// Intégration PostHog (analytics produit) — capture d'événements en
// fire-and-forget (tokio::spawn), jamais bloquant et jamais fatal pour le
// launcher : un envoi raté (clé absente, pas de réseau, timeout PostHog...)
// ne doit jamais impacter un lancement, un téléchargement ou toute autre
// action utilisateur. Volontairement aucun événement câblé pour l'instant —
// seulement la plomberie, prête à être appelée une fois la liste des
// événements à suivre décidée.
//
// Clé de projet PostHog — faite pour être embarquée côté client (write-only,
// ne donne accès à rien côté dashboard/compte — même statut que MS_CLIENT_ID
// dans minecraft/auth.rs), overridable via `POSTHOG_API_KEY` si besoin de
// pointer vers un autre projet (ex: tests).

use std::path::PathBuf;
use std::sync::OnceLock;

const DEFAULT_POSTHOG_API_KEY: &str = "phc_pFzmeuGWLt9Fcqb5AK4MqbHubjPerfhjzkeAU2eVtzvF";

fn api_key() -> Option<&'static str> {
    static KEY: OnceLock<Option<String>> = OnceLock::new();
    KEY.get_or_init(|| {
        std::env::var("POSTHOG_API_KEY")
            .ok()
            .filter(|k| !k.is_empty())
            .or_else(|| Some(DEFAULT_POSTHOG_API_KEY.to_string()))
    })
    .as_deref()
}

fn host() -> String {
    // Projet PostHog en région EU (voir --region eu du wizard) — override
    // possible via POSTHOG_HOST pour du self-hosted ou un autre projet.
    std::env::var("POSTHOG_HOST").unwrap_or_else(|_| "https://eu.i.posthog.com".to_string())
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

/// Fusionne les propriétés communes à tous les événements (équivalent des
/// "super properties" du SDK JS — ici pas de SDK, donc fusionné à la main à
/// chaque envoi) : version du launcher (PAS la version Minecraft) et OS,
/// pour pouvoir segmenter n'importe quel funnel par version d'app sans avoir
/// à y penser événement par événement.
fn with_base_properties(mut properties: serde_json::Value) -> serde_json::Value {
    if !properties.is_object() {
        properties = serde_json::json!({});
    }
    let obj = properties.as_object_mut().expect("vérifié juste au-dessus");
    obj.insert("app_version".to_string(), serde_json::Value::String(env!("CARGO_PKG_VERSION").to_string()));
    obj.insert("os".to_string(), serde_json::Value::String(std::env::consts::OS.to_string()));
    properties
}

/// Capture un événement PostHog — no-op silencieux si `POSTHOG_API_KEY`
/// n'est pas définie. `properties` : les métadonnées propres à l'événement
/// (ex: `serde_json::json!({ "instance_id": id, "loader": "fabric" })`) —
/// `app_version`/`os` sont ajoutées automatiquement, pas besoin de les inclure.
pub fn capture(event: &str, properties: serde_json::Value) {
    let Some(key) = api_key() else { return };

    let event = event.to_string();
    let key = key.to_string();
    let distinct_id = distinct_id().to_string();
    let host = host();
    let properties = with_base_properties(properties);

    tokio::spawn(async move {
        let body = serde_json::json!({
            "api_key": key,
            "event": event,
            "distinct_id": distinct_id,
            "properties": properties,
        });
        match reqwest::Client::new()
            .post(format!("{}/capture/", host))
            .json(&body)
            .send()
            .await
        {
            // reqwest ne renvoie une Err que sur un échec de transport (DNS,
            // connexion refusée, timeout...) — un 401/400 de PostHog (clé
            // invalide, payload malformé) atterrit ici en Ok(resp) avec un
            // statut non-success, jamais dans le bras Err ci-dessous.
            Ok(resp) if resp.status().is_success() => {
                tracing::debug!("[Analytics] événement '{}' envoyé (200)", event);
            }
            Ok(resp) => {
                let status = resp.status();
                let body = resp.text().await.unwrap_or_default();
                tracing::warn!("[Analytics] PostHog a rejeté l'événement '{}' : {} — {}", event, status, body);
            }
            Err(e) => {
                tracing::debug!("[Analytics] envoi de l'événement '{}' échoué : {}", event, e);
            }
        }
    });
}
