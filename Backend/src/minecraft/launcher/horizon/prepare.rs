//! La préparation d'une version d'Horizon.
//!
//! Horizon ne contient rien de Mojang : `horizon-prepare` tire du vrai jeu du
//! joueur ce dont il a besoin (textures, modèles, sons, données du jeu) et
//! l'écrit dans `<version>/.local/horizon-client/<mc>/`, le dossier que le
//! client reçoit par `--resources`. Il lit le dossier du jeu au format du
//! launcher officiel — `versions/<mc>/<mc>.json` et `.jar`, `libraries/`,
//! `assets/` —, qui est déjà celui de notre `.minecraft` partagé ; il lui faut
//! aussi le jar serveur de la même version et un Java de la version que fixe
//! `research-lock.json` (25 pour la 26.3). Il compare chaque fichier aux
//! empreintes de ce même fichier et refuse ce qui ne correspond pas.
//!
//! Une préparation sert à toutes les instances Horizon de cette version : on
//! ne la refait que si son manifeste manque.

use anyhow::{anyhow, Result};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::sync::watch;

use super::super::classpath::{artifact_path, download_verified, file_matches, should_download_library};
use super::super::java::ensure_java;
use super::super::jvm_args::JvmVendor;
use super::super::orchestrator::{download_assets, minecraft_dir, LAUNCH_CANCELLED_MSG};
use super::super::progress::{log_to_console, set_progress_monotonic, ProgressFloor};
use super::release::PREPARER;
use crate::minecraft::versions::{fetch_version_list, VersionDetails};

/// Ce que la version d'Horizon fixe du jeu, lu dans son `research-lock.json`.
pub(super) struct Lock {
    /// La version de Minecraft que cette version d'Horizon recrée.
    pub version: String,
    java_major: u32,
}

pub(super) fn read_lock(dir: &Path) -> Result<Lock> {
    let text = std::fs::read_to_string(dir.join("research-lock.json"))
        .map_err(|e| anyhow!("research-lock.json d'Horizon illisible : {e}"))?;
    let json: serde_json::Value = serde_json::from_str(&text)?;
    let mc = &json["minecraft"];
    Ok(Lock {
        version: mc["version"]
            .as_str()
            .ok_or_else(|| anyhow!("research-lock.json ne fixe pas la version du jeu"))?
            .to_string(),
        // Les versions d'avant ce champ demandaient Java 25, comme leur launcher le suppose.
        java_major: mc["java_major"].as_u64().unwrap_or(25) as u32,
    })
}

/// Le dossier que `horizon-client --resources` attend.
pub(super) fn resources_dir(dir: &Path, lock: &Lock) -> PathBuf {
    dir.join(".local").join("horizon-client").join(&lock.version)
}

/// Écrit par `horizon-prepare` à la toute fin : présent = préparation complète.
fn is_prepared(resources: &Path) -> bool {
    resources.join("horizon-resources.json").is_file()
}

/// Rend le dossier de ressources, en préparant la version s'il le faut.
///
/// `can_download_game` : la première préparation télécharge le jeu et son
/// jar serveur chez Mojang. Horizon ne le fait que pour un compte qui possède
/// Java Edition, et on suit la même règle : pas de préparation depuis un
/// compte hors ligne.
#[allow(clippy::too_many_arguments)]
pub(super) async fn ensure_prepared(
    dir: &Path,
    lock: &Lock,
    can_download_game: bool,
    client: &Arc<reqwest::Client>,
    app: &tauri::AppHandle,
    console: &str,
    floor: &Arc<ProgressFloor>,
    cancel: &watch::Receiver<bool>,
) -> Result<PathBuf> {
    let resources = resources_dir(dir, lock);
    if is_prepared(&resources) {
        return Ok(resources);
    }
    if !can_download_game {
        return Err(anyhow!(
            "La première préparation d'Horizon lit ton propre Minecraft {} : elle demande un compte Microsoft qui possède le jeu",
            lock.version
        ));
    }

    let mc_dir = minecraft_dir();
    let details = game_files(&mc_dir, &lock.version, client, app, floor, cancel).await?;
    let server_jar = server_jar(&lock.version, &details, client, app, floor).await?;

    let (component, major) = match &details.java_version {
        Some(j) => (j.component.clone(), lock.java_major),
        None => ("java-runtime-epsilon".to_string(), lock.java_major),
    };
    let (java, _) = ensure_java(&component, major, &mc_dir, client, app, floor, JvmVendor::Temurin, None).await?;
    // `java` est `<home>/bin/java(w).exe` : Horizon veut le dossier au-dessus de `bin`.
    let java_home = Path::new(&java)
        .parent()
        .and_then(Path::parent)
        .ok_or_else(|| anyhow!("Dossier Java introuvable pour {java}"))?
        .to_path_buf();

    set_progress_monotonic(app, floor, 75, 100, &format!("Préparation d'Horizon depuis Minecraft {}...", lock.version));
    log_to_console(app, console, "[Horizon] Préparation des ressources depuis ton Minecraft (une seule fois, quelques minutes)", "out");
    let preparer = dir.join(PREPARER);
    let mut args: Vec<std::ffi::OsString> = vec!["--prepare".into(), "--server-jar".into(), server_jar.into()];
    args.extend(["--minecraft-dir".into(), mc_dir.clone().into_os_string(), "--root".into(), dir.as_os_str().to_owned()]);
    run_preparer(&preparer, dir, &java_home, &args, app, console, cancel).await?;

    // Les sons ne font pas partie de la préparation : sans eux le jeu est
    // muet, mais il démarre. Un échec ici ne bloque donc rien.
    set_progress_monotonic(app, floor, 92, 100, "Copie des sons de Minecraft...");
    let sounds: Vec<std::ffi::OsString> = vec![
        "sounds".into(),
        "--minecraft-dir".into(),
        mc_dir.into_os_string(),
        "--root".into(),
        dir.as_os_str().to_owned(),
    ];
    if let Err(e) = run_preparer(&preparer, dir, &java_home, &sounds, app, console, cancel).await {
        if e.to_string() == LAUNCH_CANCELLED_MSG {
            return Err(e);
        }
        log_to_console(app, console, &format!("[Horizon] Sons non copiés, le jeu sera muet : {e}"), "err");
    }

    if !is_prepared(&resources) {
        return Err(anyhow!("La préparation d'Horizon s'est terminée sans écrire ses ressources"));
    }
    Ok(resources)
}

/// Le JSON de version, le jar client, les bibliothèques et les assets de
/// `version`, au format qu'attend `horizon-prepare`. Le JSON est gardé tel que
/// Mojang le sert : Horizon vérifie son empreinte.
async fn game_files(
    mc_dir: &Path,
    version: &str,
    client: &Arc<reqwest::Client>,
    app: &tauri::AppHandle,
    floor: &Arc<ProgressFloor>,
    cancel: &watch::Receiver<bool>,
) -> Result<VersionDetails> {
    let versions_dir = mc_dir.join("versions").join(version);
    tokio::fs::create_dir_all(&versions_dir).await?;
    let json_path = versions_dir.join(format!("{version}.json"));
    let details: VersionDetails = match tokio::fs::read_to_string(&json_path).await {
        Ok(text) => serde_json::from_str(&text)?,
        Err(_) => {
            set_progress_monotonic(app, floor, 22, 100, &format!("Récupération de Minecraft {version}..."));
            let list = fetch_version_list().await?;
            let info = list
                .iter()
                .find(|v| v.id == version)
                .ok_or_else(|| anyhow!("Minecraft {version} introuvable chez Mojang"))?;
            let raw = client.get(&info.url).send().await?.error_for_status()?.text().await?;
            tokio::fs::write(&json_path, &raw).await?;
            serde_json::from_str(&raw)?
        }
    };

    let assets = tokio::spawn(download_assets(
        client.clone(),
        mc_dir.join("assets"),
        details.asset_index.clone(),
        cancel.clone(),
        app.clone(),
        floor.clone(),
    ));

    let client_jar = versions_dir.join(format!("{version}.jar"));
    if !file_matches(&client_jar, Some(details.downloads.client.size)).await {
        set_progress_monotonic(app, floor, 25, 100, "Téléchargement du client Minecraft...");
        download_verified(client, &details.downloads.client.url, &client_jar, Some(&details.downloads.client.sha1)).await?;
    }

    let libraries_dir = mc_dir.join("libraries");
    for lib in details.libraries.iter().filter(|l| should_download_library(l)) {
        if *cancel.borrow() {
            assets.abort();
            return Err(anyhow!(LAUNCH_CANCELLED_MSG));
        }
        let Some(art) = lib.downloads.as_ref().and_then(|d| d.artifact.as_ref()) else { continue };
        let path = artifact_path(&libraries_dir, art, &lib.name);
        if !file_matches(&path, Some(art.size)).await {
            download_verified(client, &art.url, &path, Some(&art.sha1)).await?;
        }
    }

    assets.await.map_err(|e| anyhow!("Assets : {e}"))??;
    Ok(details)
}

/// Le jar serveur de `version`, téléchargé chez Mojang et vérifié par son
/// SHA-1. Gardé à part : il ne sert qu'à la préparation.
async fn server_jar(
    version: &str,
    details: &VersionDetails,
    client: &reqwest::Client,
    app: &tauri::AppHandle,
    floor: &ProgressFloor,
) -> Result<PathBuf> {
    let server = details
        .downloads
        .server
        .as_ref()
        .ok_or_else(|| anyhow!("Minecraft {version} ne publie pas de jar serveur"))?;
    let path = crate::paths::root().join("horizon").join("server-jars").join(format!("{version}.jar"));
    if !file_matches(&path, Some(server.size)).await {
        set_progress_monotonic(app, floor, 60, 100, "Téléchargement du serveur Minecraft (pour Horizon)...");
        download_verified(client, &server.url, &path, Some(&server.sha1)).await?;
    }
    Ok(path)
}

/// Lance `horizon-prepare` avec ce Java, sa sortie dans la console.
async fn run_preparer(
    preparer: &Path,
    dir: &Path,
    java_home: &Path,
    args: &[std::ffi::OsString],
    app: &tauri::AppHandle,
    console: &str,
    cancel: &watch::Receiver<bool>,
) -> Result<()> {
    let mut path = vec![java_home.join("bin")];
    path.extend(std::env::var_os("PATH").iter().flat_map(std::env::split_paths));
    let mut cmd = crate::app::process::hidden_command(preparer);
    cmd.current_dir(dir)
        .args(args)
        .env("JAVA_HOME", java_home)
        .env("PATH", std::env::join_paths(path)?)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .kill_on_drop(true);
    let mut child = cmd.spawn().map_err(|e| anyhow!("horizon-prepare n'a pas démarré : {e}"))?;

    let last_error = Arc::new(std::sync::Mutex::new(String::new()));
    if let Some(out) = child.stdout.take() {
        let (app, console) = (app.clone(), console.to_string());
        tokio::spawn(async move {
            let mut lines = BufReader::new(out).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                log_to_console(&app, &console, &format!("[Horizon] {line}"), "out");
            }
        });
    }
    if let Some(err) = child.stderr.take() {
        let (app, console, last) = (app.clone(), console.to_string(), last_error.clone());
        tokio::spawn(async move {
            let mut lines = BufReader::new(err).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                if !line.trim().is_empty() {
                    *last.lock().unwrap() = line.clone();
                }
                log_to_console(&app, &console, &format!("[Horizon] {line}"), "err");
            }
        });
    }

    let mut cancel = cancel.clone();
    let status = tokio::select! {
        status = child.wait() => status?,
        _ = cancel.changed() => {
            let _ = child.kill().await;
            return Err(anyhow!(LAUNCH_CANCELLED_MSG));
        }
    };
    if status.success() {
        return Ok(());
    }
    // Laisse le temps aux dernières lignes d'arriver : c'est la dernière qui
    // dit pourquoi.
    tokio::time::sleep(std::time::Duration::from_millis(200)).await;
    let reason = last_error.lock().unwrap().trim_start_matches("error: ").to_string();
    Err(anyhow!(
        "La préparation d'Horizon a échoué{}",
        if reason.is_empty() { String::new() } else { format!(" : {reason}") }
    ))
}
