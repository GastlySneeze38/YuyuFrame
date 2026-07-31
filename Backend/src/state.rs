#![allow(dead_code)]

use rusqlite::Connection;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::sync::{Mutex, RwLock};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MinecraftSession {
    pub username: String,
    pub uuid: String,
    pub access_token: String,
    /// Microsoft OAuth refresh token (long-lived, stored en DB)
    pub refresh_token: Option<String>,
    pub expires_at: i64,
}

#[derive(Debug, Clone)]
pub struct YuyuSession {
    pub user_id: i64,
    pub username: String,
    pub token: String,
    pub plan: String,
    pub plan_expires_at: Option<i64>,
}

impl YuyuSession {
    fn plan_not_expired(&self) -> bool {
        self.plan_expires_at
            .map(|exp| exp > chrono::Utc::now().timestamp())
            .unwrap_or(true)
    }

    pub fn is_premium(&self) -> bool {
        (self.plan == "premium" || self.plan == "ultimate") && self.plan_not_expired()
    }

    pub fn is_ultimate(&self) -> bool {
        self.plan == "ultimate" && self.plan_not_expired()
    }
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct DownloadProgress {
    pub current: u64,
    pub total: u64,
    pub message: String,
}

#[derive(Debug, Clone)]
pub struct AuthDeviceCode {
    pub device_code: String,
    pub user_code: String,
    pub verification_uri: String,
    pub expires_at: i64,
}

pub struct AppState {
    pub db: Arc<Mutex<Connection>>,
    /// Client HTTP partagé pour tous les appels à la LauncherAPI (auth, sync,
    /// paiement) — construit une seule fois au démarrage (voir `lib.rs`) pour
    /// garder les connexions TCP/TLS vivantes entre les appels au lieu de
    /// renégocier une poignée de main complète à chaque commande (mesurable
    /// depuis que l'API tourne en `https://` sur le VPS et plus en local).
    pub http: reqwest::Client,
    pub yuyu_session: Option<YuyuSession>,
    pub session: Option<MinecraftSession>,
    pub download_progress: Option<DownloadProgress>,
    pub running_instances: std::collections::HashSet<String>,
    /// Un `watch::Sender` par instance en cours de lancement — `cancel_launch`
    /// y envoie `true` pour demander l'arrêt (téléchargement en cours ou JVM déjà lancée).
    pub launch_cancel: std::collections::HashMap<String, tokio::sync::watch::Sender<bool>>,
    pub auth_device_code: Option<AuthDeviceCode>,
    /// Migration one-shot des ids d'instance legacy → nouveau format lisible,
    /// calculée une fois au démarrage (voir `migrate_legacy_instance_ids` dans
    /// lib.rs) — jamais modifiée ensuite pendant la session. Consommée par le
    /// frontend via la commande `instance_id_migrations` pour remapper ses
    /// propres clés persistées qui référencent encore l'ancien id.
    pub instance_id_migrations: Vec<(String, String)>,
}

impl AppState {
    pub fn is_instance_running(&self, id: &str) -> bool {
        self.running_instances.contains(id)
    }
    pub fn any_running(&self) -> bool {
        !self.running_instances.is_empty()
    }

    /// Id à utiliser pour les requêtes DB/state liées à un compte YuyuFrame.
    /// `None` tant qu'aucune session YuyuFrame n'est active.
    pub fn current_yuyu_user_id(&self) -> Option<i64> {
        self.yuyu_session.as_ref().map(|y| y.user_id)
    }
}

pub type SharedState = Arc<RwLock<AppState>>;
