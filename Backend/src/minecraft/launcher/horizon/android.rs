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

use anyhow::{anyhow, Context, Result};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tauri::plugin::{Builder, PluginHandle, TauriPlugin};
use tauri::{Emitter, Manager, Runtime, Wry};
use tokio::sync::watch;

use super::HorizonLaunch;
use crate::minecraft::launcher::orchestrator::LAUNCH_CANCELLED_MSG;
use crate::minecraft::launcher::progress::{log_to_console, set_progress_monotonic, ProgressFloor};
use crate::state::MinecraftSession;

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
    let engine = bridge(&app, |b| b.call::<Engine>("engine")).await.map(|e| e.version).unwrap_or_default();

    let files = crate::paths::app_files_dir();
    let home = run.game_dir.join("horizon");
    tokio::fs::create_dir_all(&home).await?;

    let online = run.session.access_token != "offline";
    let session_file = if online {
        Some(write_session_file(&files, run.session).await?)
    } else {
        None
    };
    let args = arguments(run.game_dir, &home, run.session, session_file.as_deref(), run.connect_server);
    write_args(&files, &args).await?;

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

/// La ligne de commande de la partie, une option par élément. Seulement des
/// options du contrat de lancement d'Horizon, que le jeu promet de garder.
///
/// `--resources` n'y est pas : le jeu met d'office ses ressources préparées
/// dans les fichiers de l'appli, et c'est là qu'il les prépare au premier
/// démarrage.
fn arguments(
    game_dir: &Path,
    home: &Path,
    session: &MinecraftSession,
    session_file: Option<&Path>,
    server: Option<&str>,
) -> Vec<String> {
    let mut args = vec![
        "--game-dir".to_string(),
        game_dir.display().to_string(),
        "--home".to_string(),
        home.display().to_string(),
    ];
    match session_file {
        Some(path) => args.extend(["--session-file".to_string(), path.display().to_string()]),
        None => args.extend(["--name".to_string(), session.username.clone()]),
    }
    if let Some(server) = server.map(str::trim).filter(|s| !s.is_empty()) {
        args.extend(["--quickPlayMultiplayer".to_string(), server.to_string()]);
    }
    args
}

/// Écrit `horizon-args.txt` d'un coup (fichier voisin puis renommage) : le
/// jeu ne doit jamais lire une ligne de commande à moitié écrite. Une valeur
/// qui contient un saut de ligne est refusée, elle deviendrait une option de
/// plus.
async fn write_args(files: &Path, args: &[String]) -> Result<()> {
    if args.iter().any(|a| a.contains(['\n', '\r'])) {
        return Err(anyhow!("option de lancement invalide (saut de ligne)"));
    }
    let mut text = String::from("# Écrit par YuyuFrame à chaque lancement : la ligne de commande du jeu.\n");
    // Une variable d'environnement (forme `NOM=valeur`) : le jeu sait qu'il est
    // lancé par YuyuFrame et prépare ses fichiers sans demander de compte
    // Starling (notre patch dans `horizon-android`, game/setup.rs).
    text.push_str("YUYUFRAME=1\n");
    for arg in args {
        text.push_str(arg);
        text.push('\n');
    }
    let target = files.join("horizon-args.txt");
    let partial = files.join("horizon-args.txt.partial");
    tokio::fs::write(&partial, text).await.context("horizon-args.txt")?;
    tokio::fs::rename(&partial, &target).await.context("horizon-args.txt")?;
    Ok(())
}

/// Le compte pour `--session-file` : un nom tiré au hasard, créé neuf et en
/// mode 0600 (le jeu refuse un fichier lisible par d'autres). Format du
/// contrat de lancement : `name`, `uuid`, `access_token`, `user_type`, `xuid`.
async fn write_session_file(files: &Path, session: &MinecraftSession) -> Result<PathBuf> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;

    let dir = files.join("yuyu-sessions");
    tokio::fs::create_dir_all(&dir).await?;
    let random: [u8; 12] = rand::random();
    let name: String = random.iter().map(|b| format!("{b:02x}")).collect();
    let path = dir.join(format!("session-{name}.json"));
    let body = serde_json::json!({
        "name": session.username,
        "uuid": session.uuid.replace('-', ""),
        "access_token": session.access_token,
        "user_type": "msa",
        "xuid": serde_json::Value::Null,
    })
    .to_string();
    let target = path.clone();
    tokio::task::spawn_blocking(move || -> std::io::Result<()> {
        let mut file = std::fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(&target)?;
        file.write_all(body.as_bytes())?;
        file.sync_all()
    })
    .await??;
    Ok(path)
}

async fn remove(path: Option<&Path>) {
    if let Some(path) = path {
        let _ = tokio::fs::remove_file(path).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn session(token: &str) -> MinecraftSession {
        MinecraftSession {
            username: "Alex".into(),
            uuid: "069a79f4-44e9-4726-a5be-fca90e38aaf5".into(),
            access_token: token.into(),
            refresh_token: None,
            expires_at: 0,
        }
    }

    #[test]
    fn hors_ligne_le_pseudo_sinon_le_fichier_de_session() {
        let dir = Path::new("/data/x/instances/a");
        let home = dir.join("horizon");
        let offline = arguments(dir, &home, &session("offline"), None, None);
        assert!(offline.windows(2).any(|w| w == ["--name", "Alex"]));
        let online = arguments(dir, &home, &session("t"), Some(Path::new("/f/s.json")), Some(" play.example.net "));
        assert!(!online.iter().any(|a| a == "--name"));
        assert!(online.windows(2).any(|w| w == ["--session-file", "/f/s.json"]));
        assert!(online.windows(2).any(|w| w == ["--quickPlayMultiplayer", "play.example.net"]));
        assert!(online.windows(2).any(|w| w[0] == "--game-dir"));
    }
}
