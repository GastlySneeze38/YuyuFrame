//! Horizon sur Android : le moteur de Starling, rangé dans l'APK (module Gradle
//! `starling`, compilé depuis `horizon-android/`), joué dans un écran à lui
//! (`YuyuGameActivity`) et dans un processus à lui (« :game »).
//!
//! Le launcher ne parle au jeu que par des fichiers, les deux processus étant
//! la même appli donc voyant les mêmes fichiers privés :
//!
//! - `horizon-args.txt` dans les fichiers de l'appli : le jeu le lit au
//!   démarrage comme sa ligne de commande (Starling, `android.rs`,
//!   `command_line`), une option par ligne. Ce sont les options du contrat de
//!   lancement d'Horizon (`docs/api/launch.md` chez eux) : `--game-dir`,
//!   `--home`, `--session-file`, `--name`, `--quickPlayMultiplayer`.
//! - le fichier de session : le compte, lu une fois puis effacé par le jeu,
//!   refusé s'il a plus de deux minutes ou n'est pas en mode 0600.
//!
//! Ouvrir l'écran, savoir s'il tourne encore et l'arrêter passent par le
//! plugin Kotlin `GamePlugin` (`gen/android/app`). Il n'y a pas de sortie du
//! jeu à lire : la partie est finie quand son processus a disparu.
//!
//! Les ressources du jeu (préparées à partir des fichiers de Mojang) ne sont
//! pas l'affaire du launcher ici : au premier démarrage, le jeu les télécharge
//! et les prépare lui-même dans les fichiers de l'appli, avec son propre écran.

use anyhow::{anyhow, Result};
use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tauri::plugin::{Builder, PluginHandle, TauriPlugin};
use tauri::{Emitter, Manager, Runtime, Wry};
use tokio::sync::watch;

use super::phone::{launch_line, remove, wear_skin, write_session_file};
use super::HorizonLaunch;
use crate::minecraft::launcher::orchestrator::LAUNCH_CANCELLED_MSG;
use crate::minecraft::launcher::progress::{log_to_console, set_progress_monotonic, ProgressFloor};

/// Le temps laissé au processus du jeu pour apparaître après l'ouverture de
/// son écran. Au-delà, c'est qu'Android ne l'a pas démarré.
const START_TIMEOUT: Duration = Duration::from_secs(30);

/// Toutes les combien on regarde si le jeu tourne encore.
const POLL: Duration = Duration::from_secs(1);

/// Le plugin Kotlin, gardé dans l'état de Tauri.
pub struct GameBridge<R: Runtime>(PluginHandle<R>);

/// Le pont vers `GamePlugin`, enregistré par `lib.rs`.
pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("yuyu-game")
        .setup(|app, api| {
            let handle = api.register_android_plugin("fr.yuyuframe.launcher", "GamePlugin")?;
            app.manage(GameBridge(handle));
            Ok(())
        })
        .build()
}

#[derive(Serialize)]
struct NoArgs {}

#[derive(Deserialize)]
struct Running {
    running: bool,
}

#[derive(Deserialize)]
struct Engine {
    version: String,
}

impl<R: Runtime> GameBridge<R> {
    fn call<T: serde::de::DeserializeOwned>(&self, command: &str) -> Result<T> {
        self.0
            .run_mobile_plugin::<T>(command, NoArgs {})
            .map_err(|e| anyhow!("écran du jeu ({command}) : {e}"))
    }
}

/// Appelle le plugin hors du runtime async : la réponse vient du fil
/// principal d'Android, l'attendre sur un fil de tokio le bloquerait.
async fn bridge<T, F>(app: &tauri::AppHandle, call: F) -> Result<T>
where
    T: Send + 'static,
    F: FnOnce(&GameBridge<Wry>) -> Result<T> + Send + 'static,
{
    let app = app.clone();
    tokio::task::spawn_blocking(move || {
        let bridge = app.state::<GameBridge<Wry>>();
        call(&bridge)
    })
    .await
    .map_err(|e| anyhow!("écran du jeu : {e}"))?
}

/// La version du moteur rangé dans l'APK (`yuyu_engine_version`, écrite par
/// `horizon-android/yuyu/build_engine.py`) ; `None` si le plugin ne répond pas.
pub async fn engine_version(app: &tauri::AppHandle) -> Option<String> {
    bridge(app, |b| b.call::<Engine>("engine"))
        .await
        .ok()
        .map(|e| e.version)
        .filter(|v| !v.trim().is_empty())
}

/// Ramène l'écran du jeu, déjà ouvert, au premier plan (`GamePlugin.resume`).
pub async fn resume(app: &tauri::AppHandle) -> Result<()> {
    bridge(app, |b| b.call::<serde_json::Value>("resume")).await.map(|_| ())
}

/// Le téléphone se décrit (nom, modèle, ANDROID_ID) : `app::device`. Passe
/// par ce pont, le seul vers Android, bien que rien n'y concerne le jeu.
pub async fn device(app: &tauri::AppHandle) -> Result<crate::app::device::AndroidDevice> {
    bridge(app, |b| b.call::<crate::app::device::AndroidDevice>("device")).await
}

/// Lance l'écran du jeu avec les options de la partie, puis attend qu'il se
/// ferme. Même contrat que la version ordinateur : `Ok` à une fin normale,
/// `LAUNCH_CANCELLED_MSG` si on l'a arrêtée.
pub async fn launch(app: tauri::AppHandle, run: HorizonLaunch<'_>, cancel: watch::Receiver<bool>) -> Result<()> {
    let floor = Arc::new(ProgressFloor::new(run.instance_id));
    if run.mc_version != super::GAME_VERSION {
        return Err(anyhow!(
            "Horizon recrée Minecraft {}, pas {} : change la version de l'instance",
            super::GAME_VERSION,
            run.mc_version
        ));
    }
    // Une partie à la fois : l'écran du jeu est unique, en ouvrir un second
    // ramènerait celui qui tourne avec d'autres options.
    if bridge(&app, |b| b.call::<Running>("running")).await?.running {
        return Err(anyhow!("Une partie est déjà en cours : reviens-y depuis les applis récentes"));
    }
    let engine = engine_version(&app).await.unwrap_or_default();

    let files = crate::paths::app_files_dir();
    let home = run.game_dir.join("horizon");
    tokio::fs::create_dir_all(&home).await?;

    let online = run.session.access_token != "offline";
    let session_file = if online {
        Some(write_session_file(&files, run.session).await?)
    } else {
        None
    };
    wear_skin(&app, run.game_dir, run.session).await;
    let mut launch = launch_line(run.game_dir, &home, run.session, session_file.as_deref(), run.connect_server);
    // La ligne « Launcher » du menu pause : la partie se sauvegarde, l'écran du
    // jeu se ferme, et le launcher, resté dessous, revient.
    launch.launcher_button();
    let written = {
        let files = files.clone();
        tokio::task::spawn_blocking(move || launch.write_args_file(&files)).await?
    };
    if let Err(e) = written {
        remove(session_file.as_deref()).await;
        return Err(anyhow!("horizon-args.txt : {e}"));
    }

    let label = if engine.is_empty() { "Horizon".to_string() } else { format!("Horizon (Starling {engine})") };
    set_progress_monotonic(&app, &floor, 100, 100, &format!("Démarrage de {label}..."));
    log_to_console(&app, run.console_label, &format!("[Horizon] {label} — Minecraft {}", super::GAME_VERSION), "out");

    if let Err(e) = bridge(&app, |b| b.call::<serde_json::Value>("start")).await {
        remove(session_file.as_deref()).await;
        return Err(e);
    }

    let stop = Arc::new(AtomicBool::new(false));
    if let Some(id) = run.session_id {
        super::beat(id, None, app.clone(), stop.clone());
    }
    let started = chrono::Utc::now().timestamp();
    let ended = watch_game(&app, run.instance_id, cancel).await;
    stop.store(true, Ordering::Relaxed);
    // Le jeu efface le fichier en le lisant ; il reste si le jeu est tombé avant.
    remove(session_file.as_deref()).await;

    if let Some(id) = run.session_id {
        let finished = chrono::Utc::now().timestamp();
        let state = app.state::<crate::state::SharedState>();
        let db = state.read().await.db.clone();
        let conn = db.lock().await;
        if let Err(e) = crate::db::session_end(&conn, id, finished, finished - started, "normal", None) {
            tracing::warn!("[Stats] session {id} non close : {e}");
        }
    }
    ended
}

/// Attend que le processus du jeu apparaisse (« prêt »), puis qu'il
/// disparaisse (fin de la partie), ou qu'on annule.
async fn watch_game(app: &tauri::AppHandle, instance_id: &str, mut cancel: watch::Receiver<bool>) -> Result<()> {
    let opened = tokio::time::Instant::now();
    let mut seen = false;
    loop {
        tokio::select! {
            _ = tokio::time::sleep(POLL) => {}
            _ = cancel.changed() => {
                if *cancel.borrow() {
                    let _ = bridge(app, |b| b.call::<serde_json::Value>("stop")).await;
                    return Err(anyhow!(LAUNCH_CANCELLED_MSG));
                }
            }
        }
        let running = bridge(app, |b| b.call::<Running>("running")).await?.running;
        if running && !seen {
            seen = true;
            let _ = app.emit("game_ready", serde_json::json!({ "instance_id": instance_id }));
        }
        if !running && seen {
            return Ok(());
        }
        if !seen && opened.elapsed() > START_TIMEOUT {
            return Err(anyhow!("Le jeu ne s'est pas ouvert : Android n'a pas démarré son écran"));
        }
    }
}

