// Pilotage du launcher depuis le back-office (`GET /v1/config`).
//
// C'est le fil qui relie le panneau d'administration à chaque launcher
// installé : version minimale, version interdite, interrupteurs de
// fonctionnalités et bannières. Relu au démarrage puis toutes les 15 minutes.
//
// Règle importante : une configuration qu'on n'a pas réussi à charger ne doit
// jamais bloquer le launcher. Sans réponse du serveur, on garde la dernière
// connue, et à défaut tout est considéré comme activé.

use std::sync::Arc;

use serde::{Deserialize, Serialize};
use tokio::sync::RwLock;

use crate::state::SharedState;

/// Intervalle de relecture (le serveur garde de son côté un cache de 60 s,
/// vidé dès que le back-office change quelque chose).
pub const REFRESH_EVERY: std::time::Duration = std::time::Duration::from_secs(15 * 60);

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct BlockedVersion {
    pub version: String,
    pub reason: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigFlag {
    pub key: String,
    pub enabled: bool,
    pub message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Announcement {
    pub id: String,
    /// Ligne de détail ; seul texte d'une annonce de bandeau.
    pub message: String,
    /// info | warning | critical
    pub level: String,
    /// notice (bandeau) | home (bannière du tableau d'accueil). Une valeur
    /// inconnue d'un serveur plus récent doit être traitée comme un bandeau,
    /// d'où le repli plutôt qu'une énumération.
    #[serde(default = "notice")]
    pub placement: String,
    #[serde(default)]
    pub kicker: Option<String>,
    #[serde(default)]
    pub title: Option<String>,
    /// none | festive
    #[serde(default = "none")]
    pub theme: String,
    pub ends_at: Option<String>,
}

fn notice() -> String {
    "notice".into()
}

fn none() -> String {
    "none".into()
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct FleetConfig {
    pub min_launcher_version: Option<String>,
    pub min_version_message: Option<String>,
    /// Ce launcher est sous la version minimale.
    #[serde(default)]
    pub update_required: bool,
    /// Cette version est interdite : ne plus lancer le jeu.
    pub launcher_blocked: Option<String>,
    #[serde(default)]
    pub blocked_agent_versions: Vec<BlockedVersion>,
    #[serde(default)]
    pub flags: Vec<ConfigFlag>,
    #[serde(default)]
    pub announcements: Vec<Announcement>,
}

impl FleetConfig {
    /// Une clé inconnue vaut « activée » : le back-office ne liste que ce
    /// qu'il coupe, et un launcher plus récent que le back-office doit
    /// continuer de fonctionner.
    /// Le message qui accompagne un interrupteur coupé voyage avec la
    /// configuration : c'est l'interface qui l'affiche, pas ce module.
    pub fn is_enabled(&self, key: &str) -> bool {
        self.flags.iter().find(|f| f.key == key).is_none_or(|f| f.enabled)
    }
}

/// Dernière configuration connue. Partagée par tout le launcher.
fn cache() -> &'static RwLock<Arc<FleetConfig>> {
    static CACHE: std::sync::OnceLock<RwLock<Arc<FleetConfig>>> = std::sync::OnceLock::new();
    CACHE.get_or_init(|| RwLock::new(Arc::new(FleetConfig::default())))
}

pub async fn current() -> Arc<FleetConfig> {
    cache().read().await.clone()
}

/// La fenêtre à prévenir quand la configuration change.
///
/// ── Pourquoi une poignée globale ──────────────────────────────────────────
/// `reload` est appelée depuis trois endroits (la boucle de démarrage, la
/// relecture toutes les quinze minutes, et la commande `fleet_refresh` juste
/// après une connexion) dont aucun n'a de `AppHandle` sous la main. Plutôt
/// que de faire passer la poignée dans trois signatures, on la retient une
/// fois pour toutes ici, au démarrage.
///
/// Absente en test, et absente tant que `spawn_refresh` n'a pas tourné : dans
/// les deux cas on se contente de ne prévenir personne.
fn app_handle() -> &'static std::sync::OnceLock<tauri::AppHandle> {
    static APP: std::sync::OnceLock<tauri::AppHandle> = std::sync::OnceLock::new();
    &APP
}

/// Nom de l'événement écouté par le frontend (`stores/useFleet.ts`).
pub const CONFIG_EVENT: &str = "fleet_config";

/// Recharge depuis le serveur. Renvoie la configuration en place ensuite :
/// la nouvelle si l'appel a réussi, la précédente sinon.
pub async fn reload(state: &SharedState) -> Arc<FleetConfig> {
    let query = [
        ("launcher_version", env!("CARGO_PKG_VERSION").to_string()),
        ("os", std::env::consts::OS.to_string()),
    ];

    // Jeton facultatif (il sert aux bannières ciblées par abonnement) : sans
    // session, on interroge la route en anonyme plutôt que d'échouer.
    let signed_in = state.read().await.yuyu_session.is_some();
    let result = if signed_in {
        super::get(state, "/config", &query).await
    } else {
        anonymous_get(state, "/config", &query).await
    };

    match result {
        Ok(value) => match serde_json::from_value::<FleetConfig>(value) {
            Ok(config) => {
                let config = Arc::new(config);
                *cache().write().await = config.clone();
                // Prévenir tout de suite plutôt que d'attendre que l'interface
                // repasse demander. Elle relit aussi toutes les minutes, mais
                // au démarrage cette minute se voyait : le launcher s'ouvrait,
                // et la bannière d'accueil comme les modales de notes de
                // version n'arrivaient qu'une minute plus tard — souvent après
                // que la partie soit lancée, donc jamais.
                if let Some(app) = app_handle().get() {
                    use tauri::Emitter;
                    if let Err(e) = app.emit(CONFIG_EVENT, &*config) {
                        tracing::warn!("configuration de flotte non transmise à l'interface : {e}");
                    }
                }
                config
            }
            Err(e) => {
                tracing::warn!("configuration de flotte illisible : {e}");
                current().await
            }
        },
        Err(e) => {
            tracing::debug!("configuration de flotte non rechargée : {}", e.message);
            current().await
        }
    }
}

async fn anonymous_get(
    state: &SharedState,
    path: &str,
    query: &[(&str, String)],
) -> super::error::ApiResult<serde_json::Value> {
    let client = state.read().await.http.clone();
    let resp = client
        .get(format!("{}{path}", super::base()))
        .query(query)
        .send()
        .await
        .map_err(super::error::ApiError::network)?;
    super::read_json(resp).await
}

/// Relecture périodique, lancée une fois au démarrage (voir `lib.rs`).
///
/// `tauri::async_runtime::spawn` et pas `tokio::spawn` : le `setup` de Tauri
/// s'exécute sur le fil principal, hors de toute boucle asynchrone — un
/// `tokio::spawn` y plante aussitôt (« there is no reactor running »).
pub fn spawn_refresh(state: SharedState, app: tauri::AppHandle) {
    let _ = app_handle().set(app);
    tauri::async_runtime::spawn(async move {
        loop {
            // Rien à rafraîchir sans fenêtre : cette configuration ne sert
            // qu'à l'affichage (version minimale, interrupteurs, bannières).
            // Pendant une partie, launcher fermé, c'était une requête réseau
            // toutes les quinze minutes pour personne — la première chose à
            // taire quand on veut ne rien peser.
            if crate::state::window_open() {
                reload(&state).await;
            }
            tokio::time::sleep(REFRESH_EVERY).await;
        }
    });
}

// ── Vue par le frontend ──────────────────────────────────────────────────────
//
// Le launcher s'en sert pour : bloquer une version, imposer une mise à jour,
// couper une fonctionnalité à distance et afficher des bannières. Ces
// réglages remplacent les constantes en dur de `Frontend/src/config/features.ts`.

/// Dernière configuration connue, sans appel réseau.
#[tauri::command]
pub async fn fleet_config() -> Result<Arc<FleetConfig>, String> {
    Ok(current().await)
}

/// Force une relecture (au retour de veille, après une reconnexion réseau,
/// ou juste après s'être connecté pour recevoir les bannières ciblées).
#[tauri::command]
pub async fn fleet_refresh(state: tauri::State<'_, SharedState>) -> Result<Arc<FleetConfig>, String> {
    Ok(reload(&state).await)
}

/// Une fonctionnalité est-elle autorisée ? Une clé inconnue vaut « oui ».
#[tauri::command]
pub async fn fleet_flag(key: String) -> Result<bool, String> {
    Ok(current().await.is_enabled(&key))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config(flags: Vec<ConfigFlag>) -> FleetConfig {
        FleetConfig { flags, ..Default::default() }
    }

    #[test]
    fn unknown_flag_is_enabled() {
        let c = config(vec![ConfigFlag { key: "cloud_sync".into(), enabled: false, message: Some("Maintenance".into()) }]);
        assert!(!c.is_enabled("cloud_sync"));
        // Un launcher plus récent que le back-office ne doit pas se couper
        // une fonctionnalité que celui-ci ne connaît pas encore.
        assert!(c.is_enabled("fonctionnalite_inconnue"));
    }

    #[test]
    fn empty_config_blocks_nothing() {
        let c = FleetConfig::default();
        assert!(!c.update_required);
        assert!(c.launcher_blocked.is_none());
        assert!(c.is_enabled("cloud_sync"));
    }
}
