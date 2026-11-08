//! Le journal du launcher : la console et le fichier `yuyuframe.log`.
//!
//! Sans filtre, `tracing` écrit tout au niveau TRACE, pour toutes les
//! bibliothèques. Mesuré le 2026-11-08 sur un Galaxy A32 : ~10 000 lignes par
//! seconde au démarrage. Sur Android, la bibliothèque `jni` écrit quatre lignes
//! par opération et chaque ligne coûtait 0,3 à 0,9 ms. Résultat : 130 ms pour
//! servir un fichier à l'interface, 60 ms pour un appel au Rust qui ne fait
//! rien, et 11 s avant le premier affichage. Sur PC, le fichier avait atteint
//! 490 Mo, surtout du réseau (hyper, reqwest).
//!
//! Le filtre se règle sans recompiler par la variable `YUYU_LOG`, à la syntaxe
//! de `tracing_subscriber::filter::Targets` (`debug`, `info,hyper=trace`…).

use std::path::Path;

use tracing_appender::non_blocking::WorkerGuard;
use tracing_subscriber::filter::{LevelFilter, Targets};
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;
use tracing_subscriber::Layer;

/// Au-delà, le fichier est mis de côté au démarrage (`yuyuframe.old.log`) et un
/// nouveau commence : on garde le lancement en cours et le précédent.
const MAX_FILE_BYTES: u64 = 20 * 1024 * 1024;

/// Ce qu'on garde : nos propres messages à partir d'`info`, et des
/// bibliothèques seulement ce qui ne va pas. `jni` est coupé : il ne dit rien
/// d'utile et c'est lui qui débitait le plus.
fn default_filter() -> Targets {
    Targets::new()
        .with_default(LevelFilter::INFO)
        .with_target("jni", LevelFilter::OFF)
        .with_target("h2", LevelFilter::WARN)
        .with_target("hyper", LevelFilter::WARN)
        .with_target("hyper_util", LevelFilter::WARN)
        .with_target("reqwest", LevelFilter::WARN)
        .with_target("rustls", LevelFilter::WARN)
        .with_target("tao", LevelFilter::WARN)
        .with_target("wry", LevelFilter::WARN)
        .with_target("keyring", LevelFilter::WARN)
        .with_target("tauri", LevelFilter::WARN)
        .with_target("tauri_plugin_updater", LevelFilter::WARN)
}

fn filter() -> Targets {
    std::env::var("YUYU_LOG")
        .ok()
        .and_then(|v| v.parse::<Targets>().ok())
        .unwrap_or_else(default_filter)
}

/// Met de côté un fichier devenu trop gros. Un échec n'empêche rien : on
/// continue simplement d'écrire à la suite.
fn rotate_if_large(dir: &Path, name: &str) {
    let path = dir.join(name);
    let too_big = std::fs::metadata(&path).map(|m| m.len() > MAX_FILE_BYTES).unwrap_or(false);
    if too_big {
        let _ = std::fs::rename(&path, dir.join("yuyuframe.old.log"));
    }
}

/// Démarre le journal. Le gardien rendu doit vivre aussi longtemps que
/// l'application : le lâcher arrête l'écriture du fichier.
pub fn init(log_dir: &Path) -> WorkerGuard {
    std::fs::create_dir_all(log_dir).ok();
    rotate_if_large(log_dir, "yuyuframe.log");
    let file_appender = tracing_appender::rolling::never(log_dir, "yuyuframe.log");
    let (non_blocking, guard) = tracing_appender::non_blocking(file_appender);

    // Sur Android, la console est le journal du système (logcat) : on n'y met
    // que les problèmes, le détail reste dans le fichier.
    #[cfg(target_os = "android")]
    let console = tracing_subscriber::fmt::layer()
        .with_ansi(false)
        .with_filter(filter().with_default(LevelFilter::WARN));
    #[cfg(not(target_os = "android"))]
    let console = tracing_subscriber::fmt::layer().with_filter(filter());

    tracing_subscriber::registry()
        .with(console)
        .with(tracing_subscriber::fmt::layer().with_writer(non_blocking).with_ansi(false).with_filter(filter()))
        .init();
    guard
}
