pub mod account;
pub mod analytics;
pub mod deep_link;
pub mod instance;
pub mod launch;
pub mod modrinth;
pub mod sync;
pub mod system;

/// URL de base de la LauncherAPI (auth YuyuFrame, paiement, cloud sync).
/// `YUYU_API_URL` permet de pointer vers une instance locale en dev.
pub(crate) fn api_base() -> String {
    std::env::var("YUYU_API_URL").unwrap_or_else(|_| "https://api.yuyuframe.eu".into())
}

/// Préfixe reconnu côté frontend (`lib/apiError.ts::isNetworkError`) pour
/// distinguer une panne de transport (DNS, timeout, connexion refusée) d'une
/// erreur métier renvoyée par la LauncherAPI (401, quota, validation...) — les
/// deux arrivent en JS comme un simple `string` rejeté par `invoke()`, donc ce
/// préfixe est le seul moyen de les différencier sans changer la convention
/// `Result<T, String>` de toutes les commandes Tauri existantes. Ne JAMAIS
/// changer ce texte sans mettre à jour `NETWORK_ERROR_PREFIX` côté frontend.
pub(crate) fn network_err(e: impl std::fmt::Display) -> String {
    format!("Serveur inaccessible : {e}")
}
