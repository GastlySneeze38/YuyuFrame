//! Le compilateur Rust des mods natifs : la version exacte avec laquelle
//! Horizon publie ses SDK, plus l'éditeur de liens MinGW (Windows x64).
//!
//! Tout vient des sources officielles — static.rust-lang.org pour Rust, la
//! publication WinLibs pour MinGW — et chaque archive est comparée à son
//! SHA-256 figé ici avant d'être ouverte. Valeurs recopiées de leur
//! `launcher/src/rust_toolchain.rs` (Rust 1.99.1, publication du 2026-10-15) et
//! vérifiées sur les fichiers téléchargés le 2026-11-21.
//!
//! Un SDK ne se lit qu'avec le compilateur qui l'a produit (`horizon-prebuilt.json`
//! le nomme) : quand Horizon change de version de Rust, `VERSION` et les
//! empreintes changent ici, et le compilateur suivant s'installe à côté.
//!
//! Le compilateur n'est jamais embarqué dans le launcher (décision du
//! 2026-11-21) : ~510 Mo pour tout le monde alors que peu de joueurs ont des
//! mods natifs, une version du launcher à chaque changement de Rust chez
//! Horizon, et la GPL de GCC à redistribuer.

use anyhow::{anyhow, Result};
use futures::StreamExt;
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use tokio::io::AsyncWriteExt;

/// La version de Rust des SDK d'Horizon.
pub const VERSION: &str = "1.99.1";
const DIST: &str = "https://static.rust-lang.org/dist/2026-10-15";
pub const TARGET: &str = "x86_64-pc-windows-gnu";

/// (composant, SHA-256, taille) de `<composant>-<VERSION>-<TARGET>.tar.gz`.
/// Pas `cargo` : les mods sont compilés par `rustc` seul.
const COMPONENTS: [(&str, &str, u64); 3] = [
    ("rustc", "43b17e9a9134ea72562bfbb23728a830f671ea9be1db6a72597a8d240e986b35", 161_274_138),
    ("rust-std", "6546651238a3ac092b763511be524ca6eda6103e5d47342416923be1b3ce837c", 44_957_351),
    ("rust-mingw", "30263d7bb4fd0bf63a153afe36d7fc102f929a7d42781651c6b3a03a414ba428", 9_594_887),
];

/// GCC et binutils de WinLibs (MSVCRT, threads POSIX, SEH) : l'éditeur de
/// liens qu'attend la cible `x86_64-pc-windows-gnu`.
const MINGW_URL: &str = "https://github.com/brechtsanders/winlibs_mingw/releases/download/16.2.0posix-23.1.3-14.0.0-msvcrt-r3/winlibs-x86_64-posix-seh-gcc-16.2.0-mingw-w64msvcrt-14.0.0-r3.zip";
const MINGW_SHA256: &str = "62af32ea58acdea1318ea5dd8eb7017eaee694dd40b269d52a59c769dcfefb82";
const MINGW_BYTES: u64 = 277_203_392;

/// Posé en dernier : sans lui, le dossier est une installation interrompue.
const COMPLETE: &str = ".installed";

/// Un compilateur installé.
#[derive(Clone)]
pub struct Toolchain {
    pub dir: PathBuf,
}

impl Toolchain {
    pub fn rustc(&self) -> PathBuf {
        self.dir.join("bin").join("rustc.exe")
    }

    pub fn mingw_bin(&self) -> PathBuf {
        self.dir.join("mingw64").join("bin")
    }

    /// Les bibliothèques de Rust pour la cible : la bibliothèque standard en
    /// DLL, que le jeu charge à côté de son exécutable.
    pub fn target_libdir(&self) -> PathBuf {
        self.dir.join("lib").join("rustlib").join(TARGET).join("lib")
    }

    /// `rustc` réglé pour n'utiliser que ce compilateur : son éditeur de
    /// liens d'abord dans le `PATH`, aucune variable Rust du joueur, pas de
    /// fenêtre de console.
    pub fn command(&self) -> std::process::Command {
        let mut cmd = std::process::Command::new(self.rustc());
        #[cfg(target_os = "windows")]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            const BELOW_NORMAL_PRIORITY_CLASS: u32 = 0x0000_4000;
            cmd.creation_flags(CREATE_NO_WINDOW | BELOW_NORMAL_PRIORITY_CLASS);
        }
        let path = std::env::var_os("PATH").unwrap_or_default();
        let paths = [self.dir.join("bin"), self.mingw_bin()]
            .into_iter()
            .chain(std::env::split_paths(&path));
        if let Ok(joined) = std::env::join_paths(paths) {
            cmd.env("PATH", joined);
        }
        for (name, _) in std::env::vars_os() {
            let Some(name) = name.to_str() else { continue };
            if name.starts_with("RUST") || name.starts_with("CARGO") {
                cmd.env_remove(name);
            }
        }
        cmd
    }
}

fn root() -> PathBuf {
    super::root().join(format!("rust-{VERSION}"))
}

/// Le compilateur, s'il est déjà installé.
pub fn installed() -> Option<Toolchain> {
    let dir = root();
    (dir.join(COMPLETE).is_file() && dir.join("bin").join("rustc.exe").is_file()).then_some(Toolchain { dir })
}

/// Taille totale à télécharger, pour l'annoncer avant.
pub fn download_bytes() -> u64 {
    COMPONENTS.iter().map(|(_, _, b)| b).sum::<u64>() + MINGW_BYTES
}

/// Installe le compilateur s'il manque. `progress` reçoit l'avancement de 0 à 1.
pub async fn ensure(client: &reqwest::Client, progress: &(dyn Fn(f32) + Send + Sync), cancel: &(dyn Fn() -> bool + Send + Sync)) -> Result<Toolchain> {
    if let Some(found) = installed() {
        return Ok(found);
    }
    let dir = root();
    let partial = super::root().join(format!(".rust-{VERSION}.partial"));
    let _ = tokio::fs::remove_dir_all(&partial).await;
    tokio::fs::create_dir_all(&partial).await?;

    let total = download_bytes() as f32;
    let mut before = 0u64;
    for (name, sha256, bytes) in COMPONENTS {
        let file = format!("{name}-{VERSION}-{TARGET}.tar.gz");
        let archive = partial.join(&file);
        let base = before;
        download(client, &format!("{DIST}/{file}"), &archive, sha256, bytes, &|done| {
            progress((base + done) as f32 / total * 0.95)
        }, cancel)
        .await
        .map_err(|e| anyhow!("Rust {VERSION} ({name}) : {e}"))?;
        before += bytes;
        let (archive, into) = (archive.clone(), partial.clone());
        tokio::task::spawn_blocking(move || unpack_component(&archive, &into))
            .await
            .map_err(|e| anyhow!("{e}"))??;
        let _ = tokio::fs::remove_file(partial.join(&file)).await;
    }

    let archive = partial.join("mingw64.zip");
    let base = before;
    download(client, MINGW_URL, &archive, MINGW_SHA256, MINGW_BYTES, &|done| {
        progress((base + done) as f32 / total * 0.95)
    }, cancel)
    .await
    .map_err(|e| anyhow!("MinGW : {e}"))?;
    {
        let (archive, into) = (archive.clone(), partial.clone());
        tokio::task::spawn_blocking(move || unpack_mingw(&archive, &into))
            .await
            .map_err(|e| anyhow!("{e}"))??;
    }
    let _ = tokio::fs::remove_file(&archive).await;
    progress(0.98);

    let toolchain = Toolchain { dir: partial.clone() };
    if !toolchain.rustc().is_file() || !toolchain.mingw_bin().join("gcc.exe").is_file() {
        return Err(anyhow!("Le compilateur téléchargé est incomplet"));
    }
    let _ = tokio::fs::remove_dir_all(&dir).await;
    tokio::fs::rename(&partial, &dir).await?;
    tokio::fs::write(dir.join(COMPLETE), VERSION).await?;
    progress(1.0);
    Ok(Toolchain { dir })
}

/// Télécharge `url` dans `to` en vérifiant taille et SHA-256 au fil de l'eau.
/// Un fichier déjà là et juste n'est pas retéléchargé.
pub(super) async fn download(
    client: &reqwest::Client,
    url: &str,
    to: &Path,
    sha256: &str,
    bytes: u64,
    progress: &(dyn Fn(u64) + Send + Sync),
    cancel: &(dyn Fn() -> bool + Send + Sync),
) -> Result<()> {
    if let Some(parent) = to.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    let response = client.get(url).send().await?.error_for_status()?;
    if response.content_length().is_some_and(|len| len != bytes) {
        return Err(anyhow!("taille inattendue ({:?} octets au lieu de {bytes})", response.content_length()));
    }
    let mut file = tokio::fs::File::create(to).await?;
    let mut hash = Sha256::new();
    let mut done = 0u64;
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        if cancel() {
            drop(file);
            let _ = tokio::fs::remove_file(to).await;
            return Err(anyhow!(super::super::super::orchestrator::LAUNCH_CANCELLED_MSG));
        }
        let chunk = chunk?;
        done += chunk.len() as u64;
        if done > bytes {
            drop(file);
            let _ = tokio::fs::remove_file(to).await;
            return Err(anyhow!("fichier plus gros qu'annoncé"));
        }
        hash.update(&chunk);
        file.write_all(&chunk).await?;
        progress(done);
    }
    file.flush().await?;
    drop(file);
    let got = format!("{:x}", hash.finalize());
    if done != bytes || !got.eq_ignore_ascii_case(sha256) {
        let _ = tokio::fs::remove_file(to).await;
        return Err(anyhow!("empreinte SHA-256 inattendue : fichier refusé"));
    }
    Ok(())
}

/// Un composant Rust : `<haut>/<composant>/<fichiers>`, posés sous `into`
/// comme le ferait son installeur, sans ses scripts ni `manifest.in`.
fn unpack_component(archive: &Path, into: &Path) -> Result<()> {
    let file = std::fs::File::open(archive)?;
    let mut tar = tar::Archive::new(flate2::read::GzDecoder::new(file));
    for entry in tar.entries()? {
        let mut entry = entry?;
        let path = entry.path()?.into_owned();
        let parts: Vec<_> = path
            .components()
            .map(|c| match c {
                std::path::Component::Normal(p) => Some(p.to_owned()),
                _ => None,
            })
            .collect::<Option<_>>()
            .unwrap_or_default();
        if parts.len() < 3 || (parts.len() == 3 && parts[2] == "manifest.in") {
            continue;
        }
        if !entry.header().entry_type().is_file() {
            continue;
        }
        let to = parts[2..].iter().fold(into.to_path_buf(), |p, part| p.join(part));
        if let Some(parent) = to.parent() {
            std::fs::create_dir_all(parent)?;
        }
        entry.unpack(&to)?;
    }
    Ok(())
}

/// Le dossier `mingw64/` de WinLibs, sans sa documentation (`share/`, 50 Mo).
fn unpack_mingw(archive: &Path, into: &Path) -> Result<()> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(archive)?)?;
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i)?;
        let Some(relative) = entry.enclosed_name() else { continue };
        let mut parts = relative.components();
        let first = parts.next().map(|c| c.as_os_str().to_owned());
        if first.as_deref() != Some(std::ffi::OsStr::new("mingw64")) || entry.is_dir() {
            continue;
        }
        if parts.next().is_some_and(|c| c.as_os_str() == "share") {
            continue;
        }
        let to = into.join(&relative);
        if let Some(parent) = to.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut out = std::fs::File::create(&to)?;
        std::io::copy(&mut entry, &mut out)?;
    }
    Ok(())
}
