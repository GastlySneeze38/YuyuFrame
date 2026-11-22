//! La route « engine » : les mods natifs qui modifient le code du jeu
//! (`[[native.mixin]]`, modules, ponts, boucles) ou qui en nomment des
//! parties internes.
//!
//! Comme pour les autres, le jeu n'est jamais recompilé : les fonctions visées
//! sont **copiées** hors du source d'Horizon dans une crate de patch,
//! `horizon-mixins` y applique les règles des mods, et la bibliothèque
//! `horizon_patch` installe ces copies à la place des originales au démarrage.
//!
//! Rien du code d'Horizon n'est dans notre dépôt (décision du 2026-11-22) :
//! - le source du build est téléchargé à son commit exact (`source/<commit>/`) ;
//! - `yuyu-horizon-gen` (notre `gen_main.rs`) est compilé ici, avec `cargo`,
//!   contre leurs crates **de ce source** : leur générateur est toujours celui du
//!   build joué, et se met à jour tout seul avec les Nightly ;
//! - `horizon-mixins` est compilé depuis son dépôt, à la révision que donne le
//!   `sdk.json` du build, avec le correctif que porte leur source.
//!
//! Chaque outil n'est construit qu'une fois par build (ou par révision).

use anyhow::{anyhow, Result};
use futures::StreamExt;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use tokio::io::AsyncWriteExt;

use super::sdk::Sdk;
use super::toolchain::Toolchain;

const SOURCE: &str = "https://codeload.github.com/horizon-rust/horizon/tar.gz";
const MIXINS_SOURCE: &str = "https://codeload.github.com/horizon-rust/horizon-mixins/tar.gz";
const GEN_MAIN: &str = include_str!("gen_main.rs");
/// Posé en dernier dans un dossier d'outil ou de source complet.
const MARK: &str = ".complete";

fn cancelled() -> anyhow::Error {
    anyhow!(super::super::super::orchestrator::LAUNCH_CANCELLED_MSG)
}

/// Télécharge une archive `.tar.gz` de GitHub et la décompresse dans `into`,
/// sans son dossier de tête. Fichiers et dossiers seulement, rien qui sorte.
async fn fetch_tar_gz(
    client: &reqwest::Client,
    url: &str,
    into: &Path,
    limit: u64,
    progress: &(dyn Fn(u64) + Send + Sync),
    cancel: &(dyn Fn() -> bool + Send + Sync),
) -> Result<()> {
    let archive = into.with_extension("download.tar.gz");
    if let Some(parent) = archive.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    let response = client.get(url).send().await?.error_for_status()?;
    let mut file = tokio::fs::File::create(&archive).await?;
    let mut done = 0u64;
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        if cancel() {
            drop(file);
            let _ = tokio::fs::remove_file(&archive).await;
            return Err(cancelled());
        }
        let chunk = chunk?;
        done += chunk.len() as u64;
        if done > limit {
            return Err(anyhow!("{url} : archive trop grosse"));
        }
        file.write_all(&chunk).await?;
        progress(done);
    }
    file.flush().await?;
    drop(file);
    let (a, to) = (archive.clone(), into.to_path_buf());
    let unpacked = tokio::task::spawn_blocking(move || unpack_stripped(&a, &to)).await.map_err(|e| anyhow!("{e}"))?;
    let _ = tokio::fs::remove_file(&archive).await;
    unpacked
}

fn unpack_stripped(archive: &Path, to: &Path) -> Result<()> {
    let _ = std::fs::remove_dir_all(to);
    std::fs::create_dir_all(to)?;
    let file = std::fs::File::open(archive)?;
    let mut tar = tar::Archive::new(flate2::read::GzDecoder::new(file));
    for entry in tar.entries()? {
        let mut entry = entry?;
        let kind = entry.header().entry_type();
        let path = entry.path()?.into_owned();
        let mut parts = Vec::new();
        for component in path.components() {
            match component {
                std::path::Component::Normal(p) => parts.push(p.to_owned()),
                std::path::Component::CurDir => {}
                _ => return Err(anyhow!("entrée d'archive refusée : {}", path.display())),
            }
        }
        if parts.len() < 2 {
            continue;
        }
        let target = parts[1..].iter().fold(to.to_path_buf(), |p, part| p.join(part));
        if kind.is_dir() {
            std::fs::create_dir_all(&target)?;
        } else if kind.is_file() {
            if let Some(parent) = target.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let mut out = std::fs::File::create(&target)?;
            std::io::copy(&mut entry, &mut out)?;
        }
    }
    Ok(())
}

/// Le source d'Horizon au commit du build (~60 Mo), gardé pour ce build.
pub async fn ensure_source(
    client: &reqwest::Client,
    commit: &str,
    progress: &(dyn Fn(u64) + Send + Sync),
    cancel: &(dyn Fn() -> bool + Send + Sync),
) -> Result<PathBuf> {
    let dir = super::root().join("source").join(commit);
    if dir.join(MARK).is_file() {
        return Ok(dir);
    }
    let partial = super::root().join("source").join(format!(".{commit}.partial"));
    fetch_tar_gz(client, &format!("{SOURCE}/{commit}"), &partial, 1 << 30, progress, cancel)
        .await
        .map_err(|e| anyhow!("Source d'Horizon : {e}"))?;
    let _ = tokio::fs::remove_dir_all(&dir).await;
    tokio::fs::rename(&partial, &dir).await?;
    tokio::fs::write(dir.join(MARK), commit).await?;
    Ok(dir)
}

/// Lance une commande et rend sa sortie, ou ses dernières lignes d'erreur.
fn run(mut cmd: std::process::Command, what: &str) -> Result<String> {
    let output = cmd.output().map_err(|e| anyhow!("{what} : {e}"))?;
    if output.status.success() {
        return Ok(String::from_utf8_lossy(&output.stdout).into_owned());
    }
    let text = String::from_utf8_lossy(&output.stderr);
    tracing::warn!("[Horizon] {what} a échoué :\n{text}");
    let lines: Vec<&str> = text.lines().filter(|l| !l.trim().is_empty()).collect();
    Err(anyhow!("{what} : {}", lines[lines.len().saturating_sub(8)..].join("\n")))
}

/// `yuyu-horizon-gen`, construit contre les crates du source de ce build.
pub fn ensure_gen(rust: &Toolchain, source: &Path, commit: &str) -> Result<PathBuf> {
    let recipe = format!("{:x}", Sha256::digest(GEN_MAIN.as_bytes()));
    let dir = super::root().join("gen").join(&commit[..12]);
    let exe = dir.join("target").join("release").join("yuyu-horizon-gen.exe");
    if exe.is_file() && std::fs::read_to_string(dir.join(MARK)).ok().as_deref() == Some(recipe.as_str()) {
        return Ok(exe);
    }
    std::fs::create_dir_all(dir.join("src"))?;
    let crates = source.join("crates");
    let path = |name: &str| crates.join(name).to_string_lossy().replace('\\', "/");
    let manifest = format!(
        "[package]\nname = \"yuyu-horizon-gen\"\nversion = \"0.1.0\"\nedition = \"2024\"\n\n\
         [dependencies]\n\
         horizon-mod-api-gen = {{ path = \"{}\" }}\n\
         horizon-mod-package = {{ path = \"{}\", default-features = false }}\n\
         serde = {{ version = \"1\", features = [\"derive\"] }}\n\
         serde_json = \"1\"\ntoml = \"1.1\"\n\
         syn = {{ version = \"2\", features = [\"full\"] }}\nproc-macro2 = \"1\"\n\n[workspace]\n",
        path("horizon-mod-api-gen"),
        path("horizon-mod-package"),
    );
    std::fs::write(dir.join("Cargo.toml"), manifest)?;
    std::fs::write(dir.join("src").join("main.rs"), GEN_MAIN)?;
    // Leur verrou de versions : les dépendances sont celles avec lesquelles
    // leur build a été testé.
    if let Ok(lock) = std::fs::read(source.join("Cargo.lock")) {
        std::fs::write(dir.join("Cargo.lock"), lock)?;
    }
    let mut cmd = rust.program(&rust.cargo());
    cmd.arg("build").arg("--release").arg("--manifest-path").arg(dir.join("Cargo.toml"));
    run(cmd, "Construction de l'outil des mods natifs")?;
    std::fs::write(dir.join(MARK), recipe)?;
    Ok(exe)
}

/// `horizon-mixins` à la révision `rev`, construit depuis son dépôt avec le
/// correctif que porte le source du build (appliqué par `yuyu-horizon-gen`).
pub async fn ensure_mixins(
    client: &reqwest::Client,
    rust: &Toolchain,
    generator: &Path,
    rev: &str,
    cancel: &(dyn Fn() -> bool + Send + Sync),
) -> Result<PathBuf> {
    if rev.len() != 40 || !rev.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(anyhow!("révision de horizon-mixins invalide : {rev}"));
    }
    let dir = super::root().join("tools").join(format!("mixins-{}", &rev[..12]));
    let exe = dir.join("bin").join("horizon-mixins.exe");
    if exe.is_file() && dir.join(MARK).is_file() {
        return Ok(exe);
    }
    let source = dir.join("source");
    fetch_tar_gz(client, &format!("{MIXINS_SOURCE}/{rev}"), &source, 256 << 20, &|_| {}, cancel)
        .await
        .map_err(|e| anyhow!("Source de horizon-mixins : {e}"))?;
    let (rust, generator, dir2, source2, rev) = (rust.clone(), generator.to_path_buf(), dir.clone(), source.clone(), rev.to_string());
    tokio::task::spawn_blocking(move || -> Result<()> {
        let mut patch = std::process::Command::new(&generator);
        patch.arg("mixins-patch").arg(&source2);
        let applied_for = run(patch, "Correctif de horizon-mixins")?;
        if applied_for.trim() != rev {
            return Err(anyhow!("le correctif de ce build vise horizon-mixins {}, pas {rev}", applied_for.trim()));
        }
        let mut cmd = rust.program(&rust.cargo());
        cmd.args(["install", "--locked", "--path"])
            .arg(source2.join("crates").join("horizon-mixins-cli"))
            .arg("--root")
            .arg(&dir2)
            .env("CARGO_TARGET_DIR", dir2.join("target"));
        run(cmd, "Construction de horizon-mixins")?;
        let _ = std::fs::remove_dir_all(dir2.join("target"));
        let _ = std::fs::remove_dir_all(&source2);
        std::fs::write(dir2.join(MARK), &rev)?;
        Ok(())
    })
    .await
    .map_err(|e| anyhow!("{e}"))??;
    Ok(exe)
}

/// Le jeu précompilé entier (`runtime` + `api` + `engine`) dans un seul
/// dossier, par liens physiques : c'est contre lui qu'on compile.
pub fn whole(sdk: &Sdk) -> Result<PathBuf> {
    let dir = sdk.dir.join("whole");
    if dir.join(MARK).is_file() {
        return Ok(dir);
    }
    let partial = sdk.dir.join(".whole.partial");
    let _ = std::fs::remove_dir_all(&partial);
    fn link(base: &Path, dir: &Path, into: &Path) -> Result<()> {
        for entry in std::fs::read_dir(dir)? {
            let path = entry?.path();
            let to = into.join(path.strip_prefix(base).unwrap_or(&path));
            if path.is_dir() {
                link(base, &path, into)?;
            } else if !to.exists() {
                std::fs::create_dir_all(to.parent().unwrap_or(into))?;
                if std::fs::hard_link(&path, &to).is_err() {
                    std::fs::copy(&path, &to)?;
                }
            }
        }
        Ok(())
    }
    for slice in ["runtime", "api", "engine"] {
        let base = sdk.dir.join(slice);
        link(&base, &base, &partial)?;
    }
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::rename(&partial, &dir)?;
    std::fs::write(dir.join(MARK), "")?;
    Ok(dir)
}

#[derive(Deserialize, Debug)]
pub struct Facade {
    pub source: String,
    pub crates: Vec<String>,
}

#[derive(Deserialize, Debug)]
pub struct Patch {
    pub workspace: PathBuf,
    pub mods: PathBuf,
    pub names: Vec<String>,
    pub installs: Vec<String>,
    pub rows: bool,
}

#[derive(Deserialize, Debug)]
pub struct Prepared {
    pub facades: BTreeMap<String, Facade>,
    pub refused: BTreeMap<String, String>,
    pub patch: Option<Patch>,
}

/// Façades des internes et crates de patch des mods `mods` (identifiant,
/// dossier décompressé), par `yuyu-horizon-gen`.
pub fn prepare(generator: &Path, source: &Path, whole: &Path, commit: &str, out: &Path, mods: &[(String, PathBuf)]) -> Result<Prepared> {
    std::fs::create_dir_all(out)?;
    let request = serde_json::json!({
        "commit": commit,
        "source": source,
        "functions": whole.join("functions.json"),
        "loops": whole.join("loops.json"),
        "internals": whole.join("internals.json"),
        "out": out,
        "mods": mods,
    });
    let path = out.join("request.json");
    std::fs::write(&path, request.to_string())?;
    let mut cmd = std::process::Command::new(generator);
    cmd.arg("prepare").arg(&path);
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000);
    }
    let text = run(cmd, "Préparation des mods qui modifient le jeu")?;
    Ok(serde_json::from_str(text.trim())?)
}

/// `horizon-mixins apply` : les règles des mods appliquées aux copies.
pub fn apply(rust: &Toolchain, tool: &Path, patch: &Patch, out: &Path) -> Result<PathBuf> {
    let applied = out.join("patch-applied");
    let _ = std::fs::remove_dir_all(&applied);
    let mut cmd = rust.program(tool);
    cmd.arg("apply").arg("--workspace").arg(&patch.workspace).arg("--mods").arg(&patch.mods).arg("--out").arg(&applied);
    run(cmd, "Application des mixins")?;
    Ok(applied)
}
