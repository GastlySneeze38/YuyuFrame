//! Horizon : Minecraft Java Edition réécrit en Rust (github.com/horizon-rust/horizon,
//! licence MIT), proposé comme plateforme d'instance à côté de vanilla et des loaders.
//!
//! Ce n'est pas un loader : il n'y a ni JVM, ni classpath, ni agent. Une
//! instance Horizon a sa propre chaîne de lancement :
//! 1. `release` installe le build signé que désigne l'instance — canal Stable
//!    (une Nightly par semaine), canal Nightly (la dernière) ou un build précis ;
//! 2. `prepare` lui donne les ressources du vrai Minecraft du joueur, ou
//!    reprend celles d'une version déjà préparée quand le jeu épinglé est le même ;
//! 3. ce fichier démarre `horizon-client.exe` dans le dossier de l'instance.
//!
//! Rien de ce qui vit dans le jeu Java ne s'applique : le client intégré, le
//! canal du jeu, la présence en jeu.
//!
//! Le contenu du registre `horizon-mods` (mods, shaders, packs) s'installe par
//! `content` ; les mods natifs sont compilés au lancement par `native`, qui
//! remplace alors l'exécutable d'origine par un jeu assemblé avec eux.

#[cfg(target_os = "android")]
pub mod android;
pub mod content;
#[cfg(any(target_os = "android", target_os = "ios"))]
pub mod engine;
#[cfg(target_os = "ios")]
pub mod ios;
#[cfg(any(target_os = "android", target_os = "ios"))]
mod phone;
pub mod transfer;

/// Sur ordinateur, le jeu est le build officiel d'Horizon : les réglages et les
/// fichiers passent par ses propres écrans. Les commandes existent pour que
/// l'interface reste la même ; elles refusent.
#[cfg(not(any(target_os = "android", target_os = "ios")))]
pub mod engine {
    const PHONE_ONLY: &str = "Disponible sur téléphone seulement";
    #[tauri::command]
    pub async fn horizon_engine_settings(instance_id: String) -> Result<serde_json::Value, String> {
        let _ = instance_id;
        Err(PHONE_ONLY.into())
    }
    #[tauri::command]
    pub async fn horizon_engine_settings_set(instance_id: String, settings: serde_json::Value) -> Result<serde_json::Value, String> {
        let _ = (instance_id, settings);
        Err(PHONE_ONLY.into())
    }
    #[tauri::command]
    pub async fn horizon_files_list(instance_id: String, folder: String, path: Vec<String>) -> Result<Vec<serde_json::Value>, String> {
        let _ = (instance_id, folder, path);
        Err(PHONE_ONLY.into())
    }
    #[tauri::command]
    pub async fn horizon_files_remove(instance_id: String, folder: String, path: Vec<String>) -> Result<(), String> {
        let _ = (instance_id, folder, path);
        Err(PHONE_ONLY.into())
    }
    #[tauri::command]
    pub async fn horizon_files_rename(instance_id: String, folder: String, path: Vec<String>, name: String) -> Result<(), String> {
        let _ = (instance_id, folder, path, name);
        Err(PHONE_ONLY.into())
    }
    #[tauri::command]
    pub async fn horizon_files_mkdir(instance_id: String, folder: String, path: Vec<String>) -> Result<(), String> {
        let _ = (instance_id, folder, path);
        Err(PHONE_ONLY.into())
    }
    #[tauri::command]
    pub async fn horizon_files_path(instance_id: String, folder: String, path: Vec<String>) -> Result<String, String> {
        let _ = (instance_id, folder, path);
        Err(PHONE_ONLY.into())
    }
    #[tauri::command]
    pub async fn horizon_files_import() -> Result<String, String> {
        Err(PHONE_ONLY.into())
    }
    #[tauri::command]
    pub async fn horizon_game_resume() -> Result<(), String> {
        Err(PHONE_ONLY.into())
    }
}
#[cfg(not(any(target_os = "android", target_os = "ios")))]
pub mod native;

/// Sur téléphone, le moteur est celui de l'appli : pas de compilation de mods
/// natifs. Les commandes existent pour que l'interface reste la même.
#[cfg(any(target_os = "android", target_os = "ios"))]
pub mod native {
    #[tauri::command]
    pub async fn horizon_native_status() -> Result<serde_json::Value, String> {
        Ok(serde_json::json!({ "allowed": false, "toolchain": false, "download_bytes": 0, "unsupported": true }))
    }
    #[tauri::command]
    pub async fn horizon_native_allow(allow: bool) -> Result<(), String> {
        let _ = allow;
        Err("Les mods natifs ne sont pas disponibles sur téléphone".into())
    }
    #[tauri::command]
    pub async fn horizon_native_prefetch(loader_version: String) -> Result<(), String> {
        let _ = loader_version;
        Ok(())
    }
    #[tauri::command]
    pub async fn horizon_native_report(instance_id: String) -> Result<Option<serde_json::Value>, String> {
        let _ = instance_id;
        Ok(None)
    }
    #[tauri::command]
    pub async fn horizon_native_clear() -> Result<(), String> {
        Ok(())
    }
}
#[cfg(not(any(target_os = "android", target_os = "ios")))]
mod prepare;
#[cfg(not(any(target_os = "android", target_os = "ios")))]
mod release;

use anyhow::Result;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tauri::Manager;
use tokio::sync::watch;

use crate::state::MinecraftSession;

// Ce que seule la version ordinateur emploie (un processus lancé, sa sortie lue).
#[cfg(not(any(target_os = "android", target_os = "ios")))]
use anyhow::anyhow;
#[cfg(not(any(target_os = "android", target_os = "ios")))]
use std::process::Stdio;
#[cfg(not(any(target_os = "android", target_os = "ios")))]
use tauri::Emitter;
#[cfg(not(any(target_os = "android", target_os = "ios")))]
use tokio::io::{AsyncWriteExt, BufReader};
#[cfg(not(any(target_os = "android", target_os = "ios")))]
use super::orchestrator::LAUNCH_CANCELLED_MSG;
#[cfg(not(any(target_os = "android", target_os = "ios")))]
use super::progress::{log_to_console, set_progress_monotonic, spawn_game_output_pump, ProgressFloor};

/// La valeur de `instances.loader` d'une instance Horizon.
pub const LOADER: &str = "horizon";

/// La version de Minecraft que recrée Horizon. Les versions installées le
/// confirment par leur `research-lock.json` : une instance d'une autre
/// version est refusée au lancement plutôt que de démarrer autre chose.
pub const GAME_VERSION: &str = "26.3";

/// Si Horizon peut être proposé pour cette version du jeu : sur Windows (ses
/// versions publiées), sur Android (le moteur rangé dans l'APK, `android.rs`)
/// et sur iOS (le moteur lié dans l'appli, `ios.rs`).
pub fn available_for(mc_version: &str) -> bool {
    cfg!(any(target_os = "windows", target_os = "android", target_os = "ios")) && mc_version == GAME_VERSION
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
    /// Taille de la fenêtre ; sans objet sur téléphone, où le jeu prend l'écran.
    #[cfg_attr(any(target_os = "android", target_os = "ios"), allow(dead_code))]
    pub window_size: Option<(u32, u32)>,
    /// Version d'Horizon épinglée sur l'instance (`instances.loader_version`),
    /// vide = la recommandée.
    /// Sans objet sur téléphone : le moteur est celui de l'APK.
    #[cfg_attr(any(target_os = "android", target_os = "ios"), allow(dead_code))]
    pub pinned: &'a str,
}

/// Les versions d'Horizon, pour l'onglet Installation. Sur Android, une seule :
/// le moteur rangé dans l'APK (notre build de Starling, publié nulle part),
/// dont la version est écrite dans l'APK par `build_engine.py`. Avant le
/// 2026-11-10 la liste était vide, et l'onglet n'affichait aucune version.
#[cfg(target_os = "android")]
pub async fn list_versions(app: &tauri::AppHandle) -> Vec<crate::minecraft::loaders::LoaderVersion> {
    match android::engine_version(app).await {
        Some(version) => vec![crate::minecraft::loaders::LoaderVersion { version, stable: true, recommended: true }],
        None => Vec::new(),
    }
}

/// Sur iOS, une seule version : le moteur lié dans l'appli (`ios.rs`).
#[cfg(target_os = "ios")]
pub async fn list_versions(_app: &tauri::AppHandle) -> Vec<crate::minecraft::loaders::LoaderVersion> {
    vec![crate::minecraft::loaders::LoaderVersion { version: ios::ENGINE_VERSION.into(), stable: true, recommended: true }]
}

/// Les versions stables d'Horizon, pour le choix de l'onglet Installation.
#[cfg(not(any(target_os = "android", target_os = "ios")))]
pub async fn list_versions(_app: &tauri::AppHandle) -> Vec<crate::minecraft::loaders::LoaderVersion> {
    let client = reqwest::Client::builder()
        .user_agent(concat!("YuyuFrame/", env!("CARGO_PKG_VERSION")))
        .timeout(std::time::Duration::from_secs(15))
        .build();
    match client {
        Ok(client) => release::list_versions(&client).await,
        Err(_) => Vec::new(),
    }
}

/// Joue jusqu'à la fermeture du jeu. Sur ordinateur, installe et prépare
/// Horizon d'abord ; sur Android le moteur est dans l'APK et se prépare seul
/// au premier démarrage (`android.rs`), sur iOS il est lié dans l'appli
/// (`ios.rs`).
pub async fn launch(app: tauri::AppHandle, run: HorizonLaunch<'_>, cancel: watch::Receiver<bool>) -> Result<()> {
    #[cfg(target_os = "android")]
    return android::launch(app, run, cancel).await;
    #[cfg(target_os = "ios")]
    return ios::launch(app, run, cancel).await;
    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    desktop_launch(app, run, cancel).await
}

/// Installe et prépare Horizon si besoin, puis joue jusqu'à la fermeture du jeu.
#[cfg(not(any(target_os = "android", target_os = "ios")))]
async fn desktop_launch(app: tauri::AppHandle, run: HorizonLaunch<'_>, cancel: watch::Receiver<bool>) -> Result<()> {
    let floor = Arc::new(ProgressFloor::new(run.instance_id));
    let client = Arc::new(
        reqwest::Client::builder()
            .user_agent(concat!("YuyuFrame/", env!("CARGO_PKG_VERSION")))
            .connect_timeout(std::time::Duration::from_secs(10))
            .timeout(std::time::Duration::from_secs(120))
            // Téléchargement d'Horizon : la barre lit `Content-Length`, donc
            // pas de compression (voir `crate::net`).
            .no_gzip()
            .no_brotli()
            .build()?,
    );

    // Les versions qu'une instance épingle : une mise à jour par canal ne les
    // supprime jamais.
    let keep = {
        let state = app.state::<crate::state::SharedState>();
        let db = state.read().await.db.clone();
        let conn = db.lock().await;
        crate::db::horizon_pinned_versions(&conn).unwrap_or_default()
    };
    let installed = release::ensure_installed(&client, &app, &floor, run.pinned, &keep).await?;
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

    // Ce que le registre a retiré depuis son installation est éteint, et les
    // mods natifs allumés sont compilés contre ce build : le jeu à lancer est
    // alors celui assemblé avec eux, sinon l'exécutable d'origine.
    for (name, reason) in content::enforce_revocations(run.game_dir).await {
        log_to_console(&app, run.console_label, &format!("[Horizon] {name} retiré du registre, désactivé : {reason}"), "err");
    }
    let game_exe = match native::prepare(
        &app,
        &floor,
        &client,
        &installed.dir,
        &installed.tag,
        run.game_dir,
        run.instance_id,
        run.console_label,
        &cancel,
    )
    .await
    {
        Ok(Some(dir)) => dir.join(release::CLIENT),
        Ok(None) => installed.dir.join(release::CLIENT),
        Err(e) => {
            if *cancel.borrow() {
                return Err(anyhow!(LAUNCH_CANCELLED_MSG));
            }
            // Une panne de la compilation ne doit jamais empêcher de jouer :
            // la partie part sans les mods natifs, et on le dit.
            log_to_console(&app, run.console_label, &format!("[Horizon] Mods natifs non compilés : {e}"), "err");
            let _ = app.emit("horizon_native_error", serde_json::json!({ "instance_id": run.instance_id, "error": e.to_string() }));
            installed.dir.join(release::CLIENT)
        }
    };
    if *cancel.borrow() {
        return Err(anyhow!(LAUNCH_CANCELLED_MSG));
    }

    let online = run.session.access_token != "offline";
    // Une nouvelle Nightly reprend la préparation de la précédente quand le
    // jeu épinglé n'a pas changé : pas de nouvelle préparation à chaque mise à jour.
    set_progress_monotonic(&app, &floor, 70, 100, "Vérification des ressources d'Horizon...");
    prepare::reuse_prepared(&installed.dir, &lock, &app, run.console_label).await;
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

    let shown = release::display_name(&installed.tag);
    set_progress_monotonic(&app, &floor, 100, 100, &format!("Démarrage d'Horizon — {shown}..."));
    log_to_console(&app, run.console_label, &format!("[Horizon] {shown} — Minecraft {}", lock.version), "out");

    // Options de ligne de commande : seulement celles que lisent à la fois la
    // v0.2.97 (`game.rs`) et la Nightly du 2026-11-21
    // (`game/runtime/launch_options.rs`) — `--resources`, `--width`,
    // `--height`, `--quickPlayMultiplayer`, `--name`, `--session-file`. Les
    // Nightly en ont d'autres (`--session-stdin`, `--fullscreen`) que la
    // v0.2.97 refuse d'un « unknown option », et une instance peut encore
    // l'épingler.
    //
    // - Dossier de jeu (mondes, options.txt) : le dossier courant.
    // - Journaux et rapports de plantage : `HORIZON_HOME`, ici dans
    //   l'instance, à part des mondes.
    // - Plein écran : lu dans `options.txt`, que `force_fullscreen` a déjà
    //   écrit avant le lancement.
    let home = run.game_dir.join("horizon");
    tokio::fs::create_dir_all(&home).await?;

    let mut cmd = crate::app::process::hidden_command(&game_exe);
    cmd.current_dir(run.game_dir)
        .env("HORIZON_HOME", &home)
        // Le statut Discord est celui du launcher (`integrations::discord`),
        // comme pour une partie Java : celui d'Horizon le remplaçait pendant
        // la partie, les deux se disputaient l'affichage.
        .env("HORIZON_NO_DISCORD", "1")
        // Le signal « je joue » qu'Horizon envoie à ses serveurs (allumé par
        // défaut chez eux, éteint par leur launcher quand le joueur refuse) :
        // on ne peut pas demander l'accord ici, donc on ne l'envoie pas.
        .env("HORIZON_NO_HEARTBEAT", "1")
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

    // Prêt quand la fenêtre du jeu se montre. Horizon n'a pas de marqueur à
    // nous : la v0.2.97 écrivait sur sa sortie standard dès l'ouverture, les
    // Nightly récentes écrivent tout sur la sortie d'erreur, et la sortie
    // standard reste muette — le lancement ne se terminait alors jamais dans
    // l'interface. On guette donc les deux : la première ligne de la sortie
    // standard (anciennes versions), ou la ligne qui annonce le premier écran
    // (« loading screen shown », « first frame ») sur l'une ou l'autre.
    let ready = Arc::new(AtomicBool::new(false));
    let last_error = Arc::new(std::sync::Mutex::new(String::new()));
    let signal_ready = {
        let (app, instance, ready) = (app.clone(), run.instance_id.to_string(), ready.clone());
        Arc::new(move || {
            if !ready.swap(true, Ordering::Relaxed) {
                let _ = app.emit("game_ready", serde_json::json!({ "instance_id": &instance }));
            }
        })
    };
    let shows_window = |line: &str| {
        let line = line.to_ascii_lowercase();
        line.contains("loading screen shown") || line.contains("first frame")
    };
    if let Some(out) = child.stdout.take() {
        let (app_log, label, signal) = (app.clone(), run.console_label.to_string(), signal_ready.clone());
        spawn_game_output_pump(
            BufReader::new(out),
            move |_| signal(),
            move |line| log_to_console(&app_log, &label, &line, "out"),
        );
    }
    if let Some(err) = child.stderr.take() {
        let (app_log, label, last, signal) = (app.clone(), run.console_label.to_string(), last_error.clone(), signal_ready.clone());
        spawn_game_output_pump(
            BufReader::new(err),
            // Lu ici et pas dans l'affichage, qui peut sauter des lignes
            // quand la console ne suit pas.
            move |line| {
                if shows_window(line) {
                    signal();
                }
            },
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
#[cfg(not(any(target_os = "android", target_os = "ios")))]
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
