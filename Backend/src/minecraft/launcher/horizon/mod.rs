//! Horizon : Minecraft Java Edition réécrit en Rust (github.com/horizon-rust/horizon,
//! licence MIT), proposé comme plateforme d'instance à côté de vanilla et des loaders.
//!
//! Ce n'est pas un loader : il n'y a ni JVM, ni classpath, ni agent. Une
//! instance Horizon a sa propre chaîne de lancement :
//! 1. `release` installe une version stable signée (une seule fois) ;
//! 2. `prepare` lui donne les ressources du vrai Minecraft du joueur (une
//!    seule fois par version d'Horizon) ;
//! 3. ce fichier démarre `horizon-client.exe` dans le dossier de l'instance.
//!
//! Rien de ce qui vit dans le jeu Java ne s'applique : le client intégré, le
//! canal du jeu, la présence en jeu. Les mods d'Horizon (le registre
//! `horizon-mods`) ne sont pas gérés : presque tous doivent être recompilés
//! avec le jeu, ce que seul leur launcher sait faire aujourd'hui.

mod prepare;
mod release;

use anyhow::{anyhow, Result};
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tauri::{Emitter, Manager};
use tokio::io::{AsyncWriteExt, BufReader};
use tokio::sync::watch;

use super::orchestrator::LAUNCH_CANCELLED_MSG;
use super::progress::{log_to_console, set_progress_monotonic, spawn_game_output_pump, ProgressFloor};
use crate::state::MinecraftSession;

/// La valeur de `instances.loader` d'une instance Horizon.
pub const LOADER: &str = "horizon";

/// La version de Minecraft que recrée Horizon. Les versions installées le
/// confirment par leur `research-lock.json` : une instance d'une autre
/// version est refusée au lancement plutôt que de démarrer autre chose.
pub const GAME_VERSION: &str = "26.3";

/// Si Horizon peut être proposé pour cette version du jeu.
pub fn available_for(mc_version: &str) -> bool {
    cfg!(target_os = "windows") && mc_version == GAME_VERSION
}

/// Ce que `play::launch` sait de la partie, et qu'Horizon utilise.
pub struct HorizonLaunch<'a> {
    pub mc_version: &'a str,
    pub session: &'a MinecraftSession,
    pub game_dir: &'a std::path::Path,
    pub console_label: &'a str,
    pub instance_id: &'a str,
    pub session_id: Option<i64>,
    pub connect_server: Option<&'a str>,
    pub window_size: Option<(u32, u32)>,
    /// Version d'Horizon épinglée sur l'instance (`instances.loader_version`),
    /// vide = la recommandée.
    pub pinned: &'a str,
}

/// Les versions stables d'Horizon, pour le choix de l'onglet Installation.
pub async fn list_versions() -> Vec<crate::minecraft::loaders::LoaderVersion> {
    let client = reqwest::Client::builder()
        .user_agent(concat!("YuyuFrame/", env!("CARGO_PKG_VERSION")))
        .timeout(std::time::Duration::from_secs(15))
        .build();
    match client {
        Ok(client) => release::list_versions(&client).await,
        Err(_) => Vec::new(),
    }
}

/// Installe et prépare Horizon si besoin, puis joue jusqu'à la fermeture du jeu.
pub async fn launch(app: tauri::AppHandle, run: HorizonLaunch<'_>, cancel: watch::Receiver<bool>) -> Result<()> {
    let floor = Arc::new(ProgressFloor::new(run.instance_id));
    let client = Arc::new(
        reqwest::Client::builder()
            .user_agent(concat!("YuyuFrame/", env!("CARGO_PKG_VERSION")))
            .connect_timeout(std::time::Duration::from_secs(10))
            .timeout(std::time::Duration::from_secs(120))
            .build()?,
    );

    let pinned = (!run.pinned.is_empty()).then_some(run.pinned);
    let installed = release::ensure_installed(&client, &app, &floor, pinned).await?;
    let lock = prepare::read_lock(&installed.dir)?;
    if lock.version != run.mc_version {
        return Err(anyhow!(
            "Horizon {} recrée Minecraft {}, pas {} : change la version de l'instance",
            installed.tag, lock.version, run.mc_version
        ));
    }
    if *cancel.borrow() {
        return Err(anyhow!(LAUNCH_CANCELLED_MSG));
    }

    let online = run.session.access_token != "offline";
    let resources = prepare::ensure_prepared(
        &installed.dir,
        &lock,
        online,
        &client,
        &app,
        run.console_label,
        &floor,
        &cancel,
    )
    .await?;

    set_progress_monotonic(&app, &floor, 100, 100, &format!("Démarrage d'Horizon {}...", installed.tag));
    log_to_console(&app, run.console_label, &format!("[Horizon] Version {} — Minecraft {}", installed.tag, lock.version), "out");

    // Options de ligne de commande : seulement celles que lit `horizon-client`
    // depuis ses premières versions stables (relevées dans le code de la
    // v0.2.97, `game.rs`). Leur documentation décrit déjà des options plus
    // récentes (`--game-dir`, `--home`, `--session-stdin`, `--fullscreen`)
    // que la v0.2.97 refuse d'un « unknown option » ; ce qui suit marche
    // pour les deux.
    //
    // - Dossier de jeu (mondes, options.txt) : le dossier courant.
    // - Journaux et rapports de plantage : `HORIZON_HOME`, ici dans
    //   l'instance, à part des mondes.
    // - Plein écran : lu dans `options.txt`, que `force_fullscreen` a déjà
    //   écrit avant le lancement.
    let home = run.game_dir.join("horizon");
    tokio::fs::create_dir_all(&home).await?;

    let mut cmd = crate::app::process::hidden_command(installed.dir.join(release::CLIENT));
    cmd.current_dir(run.game_dir)
        .env("HORIZON_HOME", &home)
        .arg("--resources")
        .arg(&resources)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    if let Some((w, h)) = run.window_size {
        cmd.arg("--width").arg(w.to_string()).arg("--height").arg(h.to_string());
    }
    if let Some(server) = run.connect_server.filter(|s| !s.trim().is_empty()) {
        cmd.arg("--quickPlayMultiplayer").arg(server.trim());
    }

    // Le compte passe par un fichier de session : c'est le seul moyen que la
    // v0.2.97 connaisse (l'entrée standard n'existe que dans les versions
    // suivantes). Jamais sur la ligne de commande, lisible par tout programme
    // de la machine. Le jeu le lit puis le supprime aussitôt, refuse un
    // fichier de plus de deux minutes, et on le supprime nous-mêmes s'il est
    // encore là à la fin (jeu tombé avant de le lire).
    let session_file = if online {
        Some(write_session_file(&home, run.session).await?)
    } else {
        cmd.arg("--name").arg(&run.session.username);
        None
    };
    if let Some(path) = &session_file {
        cmd.arg("--session-file").arg(path);
    }

    let spawned = cmd.spawn();
    let mut child = match spawned {
        Ok(child) => child,
        Err(e) => {
            if let Some(path) = &session_file {
                let _ = tokio::fs::remove_file(path).await;
            }
            return Err(anyhow!("Horizon n'a pas démarré : {e}"));
        }
    };

    let stop = Arc::new(AtomicBool::new(false));
    if let Some(id) = run.session_id {
        beat(id, child.id(), app.clone(), stop.clone());
    }

    // Prêt à la première ligne écrite par le jeu : Horizon n'a pas de
    // marqueur à lui, et sa fenêtre s'ouvre en une poignée de secondes.
    let ready = Arc::new(AtomicBool::new(false));
    let last_error = Arc::new(std::sync::Mutex::new(String::new()));
    if let Some(out) = child.stdout.take() {
        let (app_ready, app_log, label, instance, ready) =
            (app.clone(), app.clone(), run.console_label.to_string(), run.instance_id.to_string(), ready.clone());
        spawn_game_output_pump(
            BufReader::new(out),
            move |_| {
                if !ready.swap(true, Ordering::Relaxed) {
                    let _ = app_ready.emit("game_ready", serde_json::json!({ "instance_id": &instance }));
                }
            },
            move |line| log_to_console(&app_log, &label, &line, "out"),
        );
    }
    if let Some(err) = child.stderr.take() {
        let (app_log, label, last) = (app.clone(), run.console_label.to_string(), last_error.clone());
        spawn_game_output_pump(
            BufReader::new(err),
            |_| {},
            move |line| {
                if !line.trim().is_empty() {
                    *last.lock().unwrap() = line.clone();
                }
                log_to_console(&app_log, &label, &line, "err");
            },
        );
    }

    let started = chrono::Utc::now().timestamp();
    let mut cancel = cancel;
    let status = tokio::select! {
        status = child.wait() => Some(status?),
        _ = cancel.changed() => {
            let _ = child.kill().await;
            None
        }
    };
    stop.store(true, Ordering::Relaxed);
    if let Some(path) = &session_file {
        let _ = tokio::fs::remove_file(path).await;
    }

    if let Some(id) = run.session_id {
        let ended = chrono::Utc::now().timestamp();
        let state = app.state::<crate::state::SharedState>();
        let db = state.read().await.db.clone();
        let conn = db.lock().await;
        if let Err(e) = crate::db::session_end(&conn, id, ended, ended - started, "normal", None) {
            tracing::warn!("[Stats] session {id} non close : {e}");
        }
    }

    let Some(status) = status else {
        return Err(anyhow!(LAUNCH_CANCELLED_MSG));
    };
    if status.success() {
        return Ok(());
    }
    // Horizon écrit le chemin de son rapport de plantage en dernière ligne
    // de sa sortie d'erreur : c'est elle qui sert au joueur.
    tokio::time::sleep(std::time::Duration::from_millis(200)).await;
    let last = last_error.lock().unwrap().clone();
    let code = status.code().map(|c| c.to_string()).unwrap_or_else(|| "?".into());
    Err(anyhow!(
        "Horizon s'est arrêté (code {code}){}",
        if last.is_empty() { String::new() } else { format!(" : {last}") }
    ))
}

/// Écrit le compte pour `--session-file` : un nom tiré au hasard, créé neuf
/// (jamais par-dessus un fichier existant), dans le dossier Horizon de
/// l'instance. Format d'Horizon (`auth.rs`) : `name`, `uuid`,
/// `access_token`, `user_type`, `xuid`.
async fn write_session_file(home: &std::path::Path, session: &MinecraftSession) -> Result<std::path::PathBuf> {
    let dir = home.join("sessions");
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
    let mut file = tokio::fs::OpenOptions::new().write(true).create_new(true).open(&path).await?;
    file.write_all(body.as_bytes()).await?;
    file.flush().await?;
    Ok(path)
}

/// La ligne de session reçoit le PID et un battement toutes les trente
/// secondes, comme une partie Java : c'est ce qui permet de ne pas perdre la
/// partie si le launcher disparaît (voir `play::recovery`).
fn beat(session_id: i64, pid: Option<u32>, app: tauri::AppHandle, stop: Arc<AtomicBool>) {
    tauri::async_runtime::spawn(async move {
        let state = app.state::<crate::state::SharedState>();
        let db = state.read().await.db.clone();
        {
            let conn = db.lock().await;
            let _ = crate::db::session_attach_launch(&conn, session_id, pid, "", "", 0);
        }
        while !stop.load(Ordering::Relaxed) {
            tokio::time::sleep(std::time::Duration::from_secs(crate::play::recovery::HEARTBEAT_SECS)).await;
            if stop.load(Ordering::Relaxed) {
                return;
            }
            let conn = db.lock().await;
            let _ = crate::db::session_heartbeat(&conn, session_id);
        }
    });
}
