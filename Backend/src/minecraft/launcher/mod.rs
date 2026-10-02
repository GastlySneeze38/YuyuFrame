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
// L'écran « Java et mémoire » montre, vérifie et installe le runtime que le
// lancement emploierait — il passe donc par les mêmes fonctions que lui.
pub use java::{
    detect_java_major_version, find_system_java_verified, inspect_java, install_custom_java,
    install_java_runtime, java_requirement, resolve_existing_java,
};
pub use agent_compat::{blocked_by as agent_blocked_by, AgentBlock, MIN_JAVA as AGENT_MIN_JAVA};
// Le partage d'instance (`commands::instance::share`) lit les arguments JVM
// exactement comme le lancement les découpera.
pub(crate) use jvm_args::parse_user_jvm_args;
pub use agent_deploy::deploy_bundled_agent;
pub use orchestrator::{download_and_launch, minecraft_dir, preview_jvm_config, LAUNCH_CANCELLED_MSG};
pub use progress::{register_console_waiter, signal_console_ready};
pub use servers::{merge_saved_servers, read_saved_servers, SavedServer};
