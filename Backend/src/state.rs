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

/// Session YuyuFrame (LauncherAPI /v1). Le jeton d'accès ne vit que 15
/// minutes : c'est `refresh_token` qui porte la session, et il est remplacé à
/// chaque rafraîchissement. Voir `crate::api`.
#[derive(Debug, Clone)]
pub struct YuyuSession {
    pub user_id: i64,
    pub username: String,
    pub email: Option<String>,
    pub plan: String,
    pub plan_expires_at: Option<i64>,
    /// Mot de passe provisoire donné par le support : à changer avant tout le
    /// reste (le serveur refuse les autres routes).
    pub password_reset_required: bool,
    /// Licence signée, vérifiable sans réseau (`crate::api::license`).
    pub license: Option<String>,
    pub access_token: String,
    /// Date (secondes Unix) au-delà de laquelle `access_token` est périmé.
    pub access_expires_at: i64,
    pub refresh_token: String,
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
    pub instance_id: String,
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
    /// Sérialise les rafraîchissements de jeton : le refresh token est à
    /// usage unique, deux appels concurrents le consommeraient deux fois et
    /// le serveur fermerait la session (réutilisation = vol présumé).
    pub yuyu_refresh: Arc<Mutex<()>>,
    pub session: Option<MinecraftSession>,
    pub running_instances: std::collections::HashSet<String>,
    /// Les instances dont un essai de compatibilité tient la JVM (voir
    /// `commands::instance::compat`). Volontairement séparé de
    /// `running_instances` : une partie se voit dans l'interface (bouton « EN
    /// JEU », présence Discord, ligne de statistiques), un essai n'est rien de
    /// tout cela. Les deux se bloquent mutuellement, et c'est le seul point
    /// commun qu'on leur veut — deux JVM sur le même dossier d'instance se
    /// marcheraient dessus.
    pub compat_running: std::collections::HashSet<String>,
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

/// Nombre de parties en cours, lisible sans `await`.
///
/// Double bien sûr `running_instances`, mais la boucle d'événements de Tauri
/// (fermeture de fenêtre, demande de sortie) est synchrone : elle ne peut pas
/// prendre le `RwLock` du state, et c'est pourtant là qu'il faut savoir si le
/// launcher a le droit de s'éteindre. Toujours mis à jour au même endroit que
/// le `HashSet` (voir `commands::launch`).
pub static RUNNING_GAMES: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);

/// Une partie tourne-t-elle ? Utilisable depuis n'importe où, y compris hors
/// contexte asynchrone.
pub fn any_game_running() -> bool {
    RUNNING_GAMES.load(std::sync::atomic::Ordering::Relaxed) > 0
}

/// Le launcher a-t-il le droit de rester en vie sans fenêtre pendant une
/// partie ? Réglable (« Continuer en arrière-plan »), poussé par l'interface
/// au démarrage. Vrai par défaut : c'est ce qui permet de compter la session
/// et de construire un rapport si le jeu plante.
static BACKGROUND_ALLOWED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(true);

pub fn background_allowed() -> bool {
    BACKGROUND_ALLOWED.load(std::sync::atomic::Ordering::Relaxed)
}

pub fn set_background_allowed(allowed: bool) {
    BACKGROUND_ALLOWED.store(allowed, std::sync::atomic::Ordering::Relaxed);
}

/// Le launcher doit s'effacer dès que le jeu est réellement là.
///
/// Sans arrière-plan autorisé, fermer la fenêtre au clic sur « Jouer »
/// tuerait le processus — et avec lui le téléchargement en cours, donc le
/// lancement lui-même. On garde donc le processus en vie le temps que la JVM
/// démarre, puis on s'éteint : la personne a demandé à ne rien laisser
/// derrière, pas à ne pas pouvoir jouer.
static EXIT_WHEN_READY: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub fn exit_when_ready() -> bool {
    EXIT_WHEN_READY.load(std::sync::atomic::Ordering::Relaxed)
}

pub fn set_exit_when_ready(pending: bool) {
    EXIT_WHEN_READY.store(pending, std::sync::atomic::Ordering::Relaxed);
}

/// Une fenêtre principale est-elle ouverte ?
///
/// Sert à taire tout ce qui ne sert qu'à l'afficher : sans elle, émettre des
/// événements, sérialiser des lignes de journal ou rafraîchir la
/// configuration de flotte, c'est du travail pour personne. C'est le cœur de
/// « le moins de poids possible » pendant une partie.
static WINDOW_OPEN: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(true);

pub fn window_open() -> bool {
    WINDOW_OPEN.load(std::sync::atomic::Ordering::Relaxed)
}

pub fn set_window_open(open: bool) {
    WINDOW_OPEN.store(open, std::sync::atomic::Ordering::Relaxed);
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

    /// Contrôle de plan unique du launcher : les commandes payantes et la
    /// garde d'écran (`commands::plan`) passent toutes par là, donc elles ne
    /// peuvent pas diverger. Pas de session = pas d'abonnement.
    pub fn require_plan(&self, plan: &str) -> Result<(), crate::api::error::ApiError> {
        use crate::api::error::ApiError;
        let session = self.yuyu_session.as_ref().ok_or_else(ApiError::not_signed_in)?;
        let granted = match plan {
            "ultimate" => session.is_ultimate(),
            _ => session.is_premium(),
        };
        if granted {
            Ok(())
        } else {
            Err(ApiError::plan_required(plan))
        }
    }
}

pub type SharedState = Arc<RwLock<AppState>>;
