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
    /// Le JSON de version que cette version d'Horizon épingle (SHA-1).
    manifest_sha1: String,
    /// Les autres republications de ce JSON par Mojang qu'elle accepte.
    accepted_manifests: Vec<String>,
    /// Les jars client et serveur épinglés : deux versions d'Horizon qui
    /// épinglent les mêmes peuvent partager une préparation (`reuse_prepared`).
    client_sha1: String,
    server_sha1: String,
}

impl Lock {
    fn manifest_accepted(&self, sha1: &str) -> bool {
        self.manifest_sha1.eq_ignore_ascii_case(sha1)
            || self.accepted_manifests.iter().any(|s| s.eq_ignore_ascii_case(sha1))
    }
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
        manifest_sha1: mc["manifest_sha1"].as_str().unwrap_or_default().to_string(),
        accepted_manifests: mc["accepted_manifest_sha1"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(|v| v.as_str().map(str::to_string))
            .collect(),
        client_sha1: mc["client_sha1"].as_str().unwrap_or_default().to_string(),
        server_sha1: mc["server_sha1"].as_str().unwrap_or_default().to_string(),
    })
}

/// Le fichier que la préparation écrit à côté de ses ressources et qui nomme
/// son propre dossier : il ne se recopie pas, le jeu le refait.
const DATA_FILE: &str = "horizon-data.bin";

/// Après l'installation d'une nouvelle version : reprend la préparation d'une
/// autre version installée au lieu de tout refaire depuis le Minecraft du
/// joueur — plusieurs minutes, et un compte Microsoft exigé, à chaque Nightly
/// sinon. Même règle que leur launcher (`backend::reuse_prepared`), avec un
/// juge plus sûr : la copie n'est gardée que si le préparateur de la NOUVELLE
/// version la déclare complète et à jour (`horizon-prepare --check`, qui ne
/// lance pas Java et ne lit aucun fichier du jeu). Sinon elle est effacée et
/// la préparation normale suit.
///
/// Candidates : les versions préparées qui recréent le même Minecraft avec
/// les mêmes jars épinglés. Copiée à côté puis renommée : une copie
/// interrompue n'est jamais prise pour une préparation.
pub(super) async fn reuse_prepared(dir: &Path, lock: &Lock, app: &tauri::AppHandle, console: &str) -> bool {
    let target = resources_dir(dir, lock);
    if is_prepared(&target) || lock.client_sha1.is_empty() || lock.server_sha1.is_empty() {
        return false;
    }
    let Some(versions) = dir.parent() else { return false };
    let mut candidates: Vec<PathBuf> = std::fs::read_dir(versions)
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| p != dir && !p.file_name().is_some_and(|n| n.to_string_lossy().starts_with('.')))
        .collect();
    // Les plus récentes d'abord : leur préparation a le plus de chances
    // d'être celle que la nouvelle version attend.
    candidates.sort();
    candidates.reverse();

    for other in candidates {
        let Ok(other_lock) = read_lock(&other) else { continue };
        if other_lock.version != lock.version
            || other_lock.client_sha1 != lock.client_sha1
            || other_lock.server_sha1 != lock.server_sha1
        {
            continue;
        }
        let source = resources_dir(&other, &other_lock);
        if !is_prepared(&source) {
            continue;
        }
        log_to_console(
            app,
            console,
            &format!("[Horizon] Reprise de la préparation de {}", other.file_name().unwrap_or_default().to_string_lossy()),
            "out",
        );
        let partial = target.with_file_name(format!(".{}.reuse", lock.version));
        let copied = {
            let (source, partial, target) = (source.clone(), partial.clone(), target.clone());
            tokio::task::spawn_blocking(move || -> std::io::Result<()> {
                let _ = std::fs::remove_dir_all(&partial);
                copy_dir(&source, &partial)?;
                let _ = std::fs::remove_dir_all(&target);
                std::fs::rename(&partial, &target)
            })
            .await
        };
        if !matches!(copied, Ok(Ok(()))) {
            let _ = tokio::fs::remove_dir_all(&partial).await;
            continue;
        }
        if check_prepared(dir).await {
            return true;
        }
        log_to_console(app, console, "[Horizon] Cette préparation ne convient pas à la nouvelle version : préparation complète", "out");
        let _ = tokio::fs::remove_dir_all(&target).await;
    }
    false
}

/// `horizon-prepare --check` : la préparation est-elle complète et à jour pour
/// ce préparateur ? Faux s'il ne sait pas répondre (ancienne version sans
/// l'option, programme absent).
async fn check_prepared(dir: &Path) -> bool {
    let mut cmd = crate::app::process::hidden_command(dir.join(PREPARER));
    cmd.current_dir(dir)
        .arg("--check")
        .arg("--root")
        .arg(dir)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .kill_on_drop(true);
    matches!(cmd.status().await, Ok(status) if status.success())
}

/// Copie récursive, sans le fichier de données qui nomme son dossier d'origine.
fn copy_dir(from: &Path, to: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(to)?;
    for entry in std::fs::read_dir(from)? {
        let entry = entry?;
        let target = to.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            copy_dir(&entry.path(), &target)?;
        } else if entry.file_name() != DATA_FILE {
            std::fs::copy(entry.path(), &target)?;
        }
    }
    Ok(())
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
    pinned_manifest(dir, lock, &mc_dir, client).await?;
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

    let label = format!("Préparation d'Horizon depuis Minecraft {}", lock.version);
    set_progress_monotonic(app, floor, 75, 100, &format!("{label}..."));
    log_to_console(app, console, "[Horizon] Préparation des ressources depuis ton Minecraft (une seule fois, quelques minutes)", "out");
    let preparer = dir.join(PREPARER);
    let mut args: Vec<std::ffi::OsString> = vec!["--prepare".into(), "--server-jar".into(), server_jar.into()];
    args.extend(["--minecraft-dir".into(), mc_dir.clone().into_os_string(), "--root".into(), dir.as_os_str().to_owned()]);
    // L'avancement de la préparation dans la barre du launcher, de 75 à 91 %,
    // quand le préparateur sait le donner.
    let bar = if supports_json_progress(&preparer).await {
        args.extend(["--progress".into(), "json".into()]);
        Some(Bar { floor: floor.clone(), from: 75, to: 91, label })
    } else {
        None
    };
    run_preparer(&preparer, dir, &java_home, &args, app, console, cancel, bar).await?;

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
    if let Err(e) = run_preparer(&preparer, dir, &java_home, &sounds, app, console, cancel, None).await {
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

/// Le JSON de version tel qu'Horizon l'épingle, quand le nôtre est une autre
/// republication.
///
/// Mojang republie le JSON d'une version sous le même nom (traductions,
/// index d'assets), et notre `.minecraft` garde celui qu'il a téléchargé la
/// première fois. Horizon n'accepte que les republications qu'il connaît ;
/// pour les autres, il lit une copie de remplacement dans
/// `<version>/.local/minecraft/<mc>/manifest.json`. On y pose celui qu'il
/// épingle, téléchargé chez Mojang par son SHA-1 et vérifié. Notre propre JSON
/// n'est pas touché : le jeu Java le lit.
async fn pinned_manifest(dir: &Path, lock: &Lock, mc_dir: &Path, client: &reqwest::Client) -> Result<()> {
    use sha1::{Digest, Sha1};
    let sha1_of = |bytes: &[u8]| format!("{:x}", Sha1::digest(bytes));

    let ours = mc_dir.join("versions").join(&lock.version).join(format!("{}.json", lock.version));
    if let Ok(bytes) = tokio::fs::read(&ours).await {
        if lock.manifest_accepted(&sha1_of(&bytes)) {
            return Ok(());
        }
    }
    let target = dir.join(".local").join("minecraft").join(&lock.version).join("manifest.json");
    if let Ok(bytes) = tokio::fs::read(&target).await {
        if lock.manifest_accepted(&sha1_of(&bytes)) {
            return Ok(());
        }
    }
    let pinned = &lock.manifest_sha1;
    if pinned.len() != 40 || !pinned.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(anyhow!("research-lock.json d'Horizon n'épingle pas de JSON de version"));
    }
    let url = format!("https://piston-meta.mojang.com/v1/packages/{pinned}/{}.json", lock.version);
    let bytes = client.get(&url).send().await?.error_for_status()?.bytes().await?;
    if !sha1_of(&bytes).eq_ignore_ascii_case(pinned) {
        return Err(anyhow!("Le JSON de Minecraft {} reçu de Mojang ne correspond pas à celui qu'Horizon attend", lock.version));
    }
    if let Some(parent) = target.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    tokio::fs::write(&target, &bytes).await?;
    Ok(())
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

/// Où poser l'avancement de `--progress json` dans la barre de lancement.
struct Bar {
    floor: Arc<ProgressFloor>,
    /// Pourcentage de la barre au début et à la fin de cette étape.
    from: u64,
    to: u64,
    /// Ce que la barre affiche, suivi du pourcentage de l'étape.
    label: String,
}

/// Le préparateur de cette version connaît-il `--progress json` ? La v0.2.97,
/// qu'une instance peut encore épingler, refuserait l'option et échouerait.
async fn supports_json_progress(preparer: &Path) -> bool {
    let mut cmd = crate::app::process::hidden_command(preparer);
    cmd.arg("--help")
        .stdin(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .kill_on_drop(true);
    match cmd.output().await {
        Ok(out) => String::from_utf8_lossy(&out.stdout).contains("--progress json"),
        Err(_) => false,
    }
}

/// Une ligne de `--progress json` : `{"fraction": F}` (de 0 à 1),
/// `{"message": "…"}` ou `{"error": "…"}` — le contrat de leur
/// `docs/api/launch.md`, figé par un test chez eux. `None` pour toute autre ligne.
enum ProgressLine {
    Fraction(f64),
    Message(String),
    Error(String),
}

fn parse_progress_line(line: &str) -> Option<ProgressLine> {
    let value: serde_json::Value = serde_json::from_str(line.trim()).ok()?;
    if let Some(f) = value["fraction"].as_f64() {
        return Some(ProgressLine::Fraction(f.clamp(0.0, 1.0)));
    }
    if let Some(m) = value["message"].as_str() {
        return Some(ProgressLine::Message(m.to_string()));
    }
    value["error"].as_str().map(|e| ProgressLine::Error(e.to_string()))
}

/// Lance `horizon-prepare` avec ce Java, sa sortie dans la console. Avec
/// `bar`, sa sortie est en `--progress json` : l'avancement va dans la barre
/// du launcher, les messages dans la console.
#[allow(clippy::too_many_arguments)]
async fn run_preparer(
    preparer: &Path,
    dir: &Path,
    java_home: &Path,
    args: &[std::ffi::OsString],
    app: &tauri::AppHandle,
    console: &str,
    cancel: &watch::Receiver<bool>,
    bar: Option<Bar>,
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
        let (app, console, last) = (app.clone(), console.to_string(), last_error.clone());
        tokio::spawn(async move {
            let mut lines = BufReader::new(out).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                let Some(bar) = &bar else {
                    log_to_console(&app, &console, &format!("[Horizon] {line}"), "out");
                    continue;
                };
                match parse_progress_line(&line) {
                    Some(ProgressLine::Fraction(f)) => {
                        let at = bar.from + ((bar.to - bar.from) as f64 * f).round() as u64;
                        let percent = (f * 100.0).round() as u64;
                        set_progress_monotonic(&app, &bar.floor, at, 100, &format!("{} — {percent} %", bar.label));
                    }
                    Some(ProgressLine::Message(m)) => log_to_console(&app, &console, &format!("[Horizon] {m}"), "out"),
                    Some(ProgressLine::Error(e)) => {
                        *last.lock().unwrap() = e.clone();
                        log_to_console(&app, &console, &format!("[Horizon] {e}"), "err");
                    }
                    None if line.trim().is_empty() => {}
                    None => log_to_console(&app, &console, &format!("[Horizon] {line}"), "out"),
                }
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

#[cfg(test)]
mod tests {
    use super::*;

    /// Les trois formes que leur test `progress_json_lines_keep_their_shape`
    /// fige (Nightly du 2026-11-21).
    #[test]
    fn lignes_de_progression() {
        assert!(matches!(parse_progress_line(r#"{"fraction": 0.050}"#), Some(ProgressLine::Fraction(f)) if (f - 0.05).abs() < 1e-9));
        assert!(matches!(parse_progress_line(r#"{"fraction": 1.000}"#), Some(ProgressLine::Fraction(f)) if f == 1.0));
        assert!(matches!(parse_progress_line(r#"{"message": "Copying \"sounds\"\nnext"}"#), Some(ProgressLine::Message(m)) if m == "Copying \"sounds\"\nnext"));
        assert!(matches!(parse_progress_line(r#"{"error": "no Java 25"}"#), Some(ProgressLine::Error(e)) if e == "no Java 25"));
        assert!(parse_progress_line("Checked the game files").is_none());
    }
}
