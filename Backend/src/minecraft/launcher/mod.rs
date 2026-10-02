mod agent_compat;
mod agent_deploy;
mod agents;
mod appcds;
mod classpath;
mod java;
mod jvm_args;
mod legacy_lwjgl3;
mod loader_setup;
mod mojang_rules;
mod orchestrator;
pub(crate) mod progress;
mod ready_event;
mod servers;

// Le diagnostic d'installation (`commands::instance::repair`) réutilise la
// descente de fichiers du lancement plutôt que d'en écrire une seconde : même
// chemins d'artefacts, même règles d'inclusion, même téléchargement vérifié.
pub use classpath::{artifact_path, download_verified, should_download_library};
pub use agent_compat::{blocked_by as agent_blocked_by, AgentBlock, MIN_JAVA as AGENT_MIN_JAVA};
pub use agent_deploy::deploy_bundled_agent;
pub use orchestrator::{download_and_launch, minecraft_dir, preview_jvm_config, LAUNCH_CANCELLED_MSG};
pub use progress::{register_console_waiter, signal_console_ready};
pub use servers::{read_saved_servers, SavedServer};
