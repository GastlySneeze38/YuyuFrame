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
