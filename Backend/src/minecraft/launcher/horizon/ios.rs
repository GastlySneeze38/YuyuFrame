//! Horizon sur iOS : le moteur de `horizon-android/` (dépôt public
//! YuyuFrame-Mobile), lié dans l'appli elle-même. iOS ne laisse lancer aucun
//! autre processus : le jeu tourne dans celui du launcher, sa fenêtre posée
//! au-dessus de la nôtre (`docs/launcher/ios.md`).
//!
//! Même partie qu'Android (`phone.rs` : ligne de lancement, compte, skin),
//! mais donnée au moteur directement : ses arguments par
//! `horizon_game::start_in_app`, ses variables d'environnement posées dans ce
//! processus, qui est aussi le sien.
//!
//! **Une partie par ouverture de l'appli** : la boucle de winit et l'état du
//! moteur ne se créent qu'une fois par processus. Le jeu ne se ferme pas non
//! plus : la ligne « Launcher » de son menu pause cache sa fenêtre, et
//! « Revenir au jeu » (`resume`) la remontre.

use anyhow::{anyhow, Result};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tauri::{Emitter, Manager};
use tokio::sync::{oneshot, watch};

use super::phone::{launch_line, remove, wear_skin, write_session_file};
use super::HorizonLaunch;
use crate::minecraft::launcher::orchestrator::LAUNCH_CANCELLED_MSG;
use crate::minecraft::launcher::progress::{log_to_console, set_progress_monotonic, ProgressFloor};

/// La version de Starling sur laquelle repose le moteur lié ici, la même que
/// celle de l'APK Android (`horizon-android/yuyu/UPSTREAM.md`). À changer avec
/// lui.
pub const ENGINE_VERSION: &str = "0.9.0";

/// Les fichiers à lui du jeu, hors de toute instance : caches, et la boîte de
/// l'importation (`HORIZON_PRIVATE`, comme sur Android).
pub fn private_dir() -> std::path::PathBuf {
    crate::paths::app_files_dir().join("horizon")
}

/// Les ressources du vrai Minecraft préparées sur un PC (`transfer.rs`), que le
/// jeu reçoit par `--resources`. iOS ne peut pas les préparer lui-même.
pub fn resources_dir() -> std::path::PathBuf {
    private_dir().join("resources")
}

/// Les ressources sont installées : `horizon-prepare` écrit ce fichier en
/// dernier, et l'importation ne met le dossier en place qu'une fois vérifié.
pub fn resources_ready() -> bool {
    resources_dir().join("horizon-resources.json").is_file()
}

/// Fait tourner `f` sur le fil principal, celui d'UIKit et de la boucle de
/// l'appli, et attend sa réponse : le moteur et ses fenêtres n'acceptent
/// rien d'ailleurs.
async fn on_main_thread<T: Send + 'static>(app: &tauri::AppHandle, f: impl FnOnce() -> T + Send + 'static) -> Result<T> {
    let (sent, received) = oneshot::channel();
    app.run_on_main_thread(move || {
        let _ = sent.send(f());
    })
    .map_err(|e| anyhow!("fil principal : {e}"))?;
    received.await.map_err(|_| anyhow!("fil principal : pas de réponse"))
}

/// Ramène la fenêtre du jeu, déjà démarré, devant celle du launcher.
pub async fn resume(app: &tauri::AppHandle) -> Result<()> {
    if on_main_thread(app, horizon_client::ios::show_game).await? {
        Ok(())
    } else {
        Err(anyhow!("Le jeu n'est pas démarré"))
    }
}

/// Démarre le jeu dans l'appli avec les options de la partie. Même contrat
/// que les autres plateformes, sauf la fin : le jeu ne se ferme qu'avec
/// l'appli, donc la partie reste « en cours » jusqu'à ce qu'on l'annule, ce
/// qui ne fait que revenir au launcher.
pub async fn launch(app: tauri::AppHandle, run: HorizonLaunch<'_>, mut cancel: watch::Receiver<bool>) -> Result<()> {
    let floor = Arc::new(ProgressFloor::new(run.instance_id));
    if run.mc_version != super::GAME_VERSION {
        return Err(anyhow!(
            "Horizon recrée Minecraft {}, pas {} : change la version de l'instance",
            super::GAME_VERSION,
            run.mc_version
        ));
    }

    if !resources_ready() {
        return Err(anyhow!(
            "Il manque les fichiers du jeu : prépare-les sur ton PC (Paramètres → Stockage → Exporter pour iPhone), puis importe-les ici (Paramètres → Stockage)"
        ));
    }

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
    // La ligne « Launcher » du menu pause : la fenêtre du jeu se cache et le
    // launcher revient devant, le jeu restant ouvert dessous.
    launch.launcher_button();

    let label = format!("Horizon (Starling {ENGINE_VERSION})");
    set_progress_monotonic(&app, &floor, 100, 100, &format!("Démarrage de {label}..."));
    log_to_console(&app, run.console_label, &format!("[Horizon] {label} — Minecraft {}", super::GAME_VERSION), "out");

    // Ce qu'Android pose de lui-même au démarrage du jeu (`android.rs`, `main`) :
    // ses fichiers à lui, et ni le compteur de joueurs d'Horizon ni Discord.
    let private = private_dir();
    tokio::fs::create_dir_all(&private).await?;
    let mut env = launch.env.clone();
    env.extend([
        ("HORIZON_PRIVATE".to_owned(), private.display().to_string()),
        ("HORIZON_TELEMETRY_URL".to_owned(), "off".to_owned()),
        ("HORIZON_NO_DISCORD".to_owned(), "1".to_owned()),
    ]);
    let mut args = launch.arguments();
    args.extend(["--resources".to_owned(), resources_dir().display().to_string()]);
    let started = on_main_thread(&app, move || {
        // Le moteur lit ses variables dans le processus, qui est le nôtre ; le
        // fil principal est le seul à y toucher pendant ce temps.
        for (name, value) in env {
            std::env::set_var(name, value);
        }
        horizon_game::start_in_app(args)
    })
    .await;
    let started = match started {
        Ok(started) => started,
        Err(e) => {
            remove(session_file.as_deref()).await;
            return Err(e);
        }
    };
    if !started {
        remove(session_file.as_deref()).await;
        return Err(anyhow!(
            "Le jeu tourne déjà dans l'appli : reviens-y par « Revenir au jeu ». Pour lancer une autre instance, ferme puis rouvre l'appli"
        ));
    }
    let _ = app.emit("game_ready", serde_json::json!({ "instance_id": run.instance_id }));

    let stop = Arc::new(AtomicBool::new(false));
    if let Some(id) = run.session_id {
        super::beat(id, None, app.clone(), stop.clone());
    }
    let started_at = chrono::Utc::now().timestamp();
    // Le jeu ne rend jamais la main : la partie dure jusqu'à l'annulation.
    while !*cancel.borrow() {
        if cancel.changed().await.is_err() {
            break;
        }
    }
    stop.store(true, Ordering::Relaxed);
    // Le jeu efface le fichier en le lisant ; il reste si le jeu est tombé avant.
    remove(session_file.as_deref()).await;

    if let Some(id) = run.session_id {
        let finished = chrono::Utc::now().timestamp();
        let state = app.state::<crate::state::SharedState>();
        let db = state.read().await.db.clone();
        let conn = db.lock().await;
        if let Err(e) = crate::db::session_end(&conn, id, finished, finished - started_at, "normal", None) {
            tracing::warn!("[Stats] session {id} non close : {e}");
        }
    }
    Err(anyhow!(LAUNCH_CANCELLED_MSG))
}
