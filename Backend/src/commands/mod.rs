pub mod account;
pub mod analytics;
pub mod instance;
pub mod launch;
pub mod sync;
pub mod system;

/// URL de base de la LauncherAPI (auth YuyuFrame, paiement, cloud sync).
pub(crate) fn api_base() -> String {
    std::env::var("YUYU_API_URL").unwrap_or_else(|_| "http://localhost:3000".into())
}
