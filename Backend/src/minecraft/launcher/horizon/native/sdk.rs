//! Le SDK d'un build d'Horizon : ce qu'il faut pour compiler un mod natif
//! contre ce build exact, sans recompiler le jeu.
//!
//! Chaque build a sa publication `sdk-<commit>` sur le dépôt d'Horizon
//! (`docs/modding/src/reference/sdk/01-release.md`). On en prend :
//! - `sdk.json` (l'index) et `SHA256SUMS`, signé par la même clé que les
//!   versions d'Horizon, pour le tag `sdk-<commit>` : la signature d'un autre
//!   build ou d'une version du jeu ne passe jamais ;
//! - `surface.json` : tout ce que l'API des mods expose, avec une empreinte par
//!   élément — c'est lui qui dit si un mod fait pour un autre build va ici ;
//! - deux tranches compilées pour Windows x64 : `runtime` (l'exécutable du jeu
//!   et ses bibliothèques) et `api` (de quoi compiler un mod).
//!
//! Chaque fichier est comparé à l'empreinte que donne `sdk.json` avant d'être
//! ouvert ; une tranche décompressée est reprise ensuite sur un simple tampon
//! (noms et tailles des fichiers), sans tout relire.

use anyhow::{anyhow, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

const REPO: &str = "horizon-rust/horizon";
const INDEX: &str = "sdk.json";
const SUMS: &str = "SHA256SUMS";
const SIGNATURE: &str = "SHA256SUMS.sig";
const SURFACE: &str = "surface.json";
/// Le format de paquet que ce code sait lire (`package_format` de `sdk.json`).
pub const PACKAGE_FORMAT: u32 = 6;
/// Taille maximale d'une tranche une fois décompressée : une archive
/// corrompue ou hostile s'arrête là.
const UNPACKED_LIMIT: u64 = 2 * 1024 * 1024 * 1024;

#[derive(Deserialize, Clone, Debug)]
pub struct Index {
    pub schema: u32,
    pub build: Build,
    pub package_format: u32,
    pub toolchain: Toolchain,
    /// La version de horizon-mixins avec laquelle ce build se modifie.
    #[serde(default)]
    pub mixins: Option<Mixins>,
    #[serde(default)]
    pub assets: HashMap<String, Asset>,
}

#[derive(Deserialize, Clone, Debug)]
pub struct Build {
    pub commit: String,
}

#[derive(Deserialize, Clone, Debug)]
pub struct Toolchain {
    pub rust: String,
}

#[derive(Deserialize, Clone, Debug)]
pub struct Mixins {
    pub rev: String,
}

#[derive(Deserialize, Clone, Debug)]
pub struct Asset {
    pub sha256: String,
    pub size: u64,
}

/// Un SDK prêt : son index et le dossier où il est rangé.
#[derive(Clone)]
pub struct Sdk {
    pub index: Index,
    pub dir: PathBuf,
}

impl Sdk {
    pub fn runtime(&self) -> PathBuf {
        self.dir.join("runtime")
    }
    pub fn api(&self) -> PathBuf {
        self.dir.join("api")
    }
}

fn url(commit: &str, name: &str) -> String {
    format!("https://github.com/{REPO}/releases/download/sdk-{commit}/{name}")
}

pub fn dir_for(commit: &str) -> PathBuf {
    super::root().join("sdk").join(commit)
}

fn is_commit(text: &str) -> bool {
    text.len() == 40 && text.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

async fn fetch_small(client: &reqwest::Client, url: &str, limit: usize) -> Result<Vec<u8>> {
    let bytes = client.get(url).send().await?.error_for_status()?.bytes().await?;
    if bytes.len() > limit {
        return Err(anyhow!("{url} : fichier trop gros"));
    }
    Ok(bytes.to_vec())
}

fn sha256(bytes: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    format!("{:x}", Sha256::digest(bytes))
}

/// L'index vérifié du SDK de `commit` : la copie gardée, sinon téléchargé et
/// vérifié (signature, empreinte, build, formats).
pub async fn index(client: &reqwest::Client, commit: &str) -> Result<Index> {
    if !is_commit(commit) {
        return Err(anyhow!("« {commit} » n'est pas un build d'Horizon"));
    }
    let dir = dir_for(commit);
    let kept = dir.join(INDEX);
    if let Ok(bytes) = tokio::fs::read(&kept).await {
        if let Ok(index) = checked(&bytes, commit) {
            return Ok(index);
        }
    }
    let bytes = fetch_small(client, &url(commit, INDEX), 1024 * 1024).await.map_err(|e| {
        anyhow!("Le SDK de ce build d'Horizon n'est pas publié ({e}) : les mods natifs ne peuvent pas être compilés")
    })?;
    let sums = fetch_small(client, &url(commit, SUMS), 64 * 1024).await?;
    let signature = fetch_small(client, &url(commit, SIGNATURE), 64 * 1024).await?;
    let tag = format!("sdk-{commit}");
    let expected = super::super::release::signed_digest(&tag, INDEX, &sums, &String::from_utf8_lossy(&signature), &[super::super::release::release_key()])?;
    if sha256(&bytes) != expected {
        return Err(anyhow!("sdk.json ne correspond pas à sa somme signée : SDK refusé"));
    }
    let index = checked(&bytes, commit)?;
    tokio::fs::create_dir_all(&dir).await?;
    tokio::fs::write(&kept, &bytes).await?;
    Ok(index)
}

/// `bytes` lu comme l'index du build `commit`, dans des formats que ce code connaît.
fn checked(bytes: &[u8], commit: &str) -> Result<Index> {
    let index: Index = serde_json::from_slice(bytes)?;
    if index.schema != 1 {
        return Err(anyhow!("Ce SDK d'Horizon est d'un format plus récent : mets à jour YuyuFrame"));
    }
    if index.build.commit != commit {
        return Err(anyhow!("Ce SDK est celui d'un autre build : refusé"));
    }
    if index.package_format != PACKAGE_FORMAT {
        return Err(anyhow!(
            "Ce SDK d'Horizon est au format {} (YuyuFrame lit le {PACKAGE_FORMAT}) : mets à jour YuyuFrame",
            index.package_format
        ));
    }
    if index.toolchain.rust != super::toolchain::VERSION {
        return Err(anyhow!(
            "Ce build d'Horizon se compile avec Rust {} (YuyuFrame connaît {}) : mets à jour YuyuFrame",
            index.toolchain.rust,
            super::toolchain::VERSION
        ));
    }
    Ok(index)
}

/// Les empreintes de tout ce qu'expose l'API des mods dans ce build
/// (`surface.json`), par identifiant, éléments imbriqués compris (champs
/// d'un hook, méthodes d'un type).
pub async fn surface(client: &reqwest::Client, index: &Index) -> Result<HashMap<String, String>> {
    let dir = dir_for(&index.build.commit);
    let kept = dir.join(SURFACE);
    let asset = index.assets.get(SURFACE).ok_or_else(|| anyhow!("Ce SDK n'a pas de surface.json"))?;
    let bytes = match tokio::fs::read(&kept).await {
        Ok(bytes) if sha256(&bytes) == asset.sha256 => bytes,
        _ => {
            let bytes = fetch_small(client, &url(&index.build.commit, SURFACE), 64 * 1024 * 1024).await?;
            if bytes.len() as u64 != asset.size || sha256(&bytes) != asset.sha256 {
                return Err(anyhow!("surface.json ne correspond pas au SDK : refusé"));
            }
            tokio::fs::create_dir_all(&dir).await?;
            tokio::fs::write(&kept, &bytes).await?;
            bytes
        }
    };
    tokio::task::spawn_blocking(move || -> Result<HashMap<String, String>> {
        let value: serde_json::Value = serde_json::from_slice(&bytes)?;
        let mut out = HashMap::new();
        collect_hashes(&value, &mut out);
        Ok(out)
    })
    .await
    .map_err(|e| anyhow!("{e}"))?
}

#[cfg(test)]
pub fn tests_collect(value: &serde_json::Value, out: &mut HashMap<String, String>) {
    collect_hashes(value, out)
}

fn collect_hashes(value: &serde_json::Value, out: &mut HashMap<String, String>) {
    match value {
        serde_json::Value::Object(map) => {
            if let (Some(id), Some(hash)) = (map.get("id").and_then(|v| v.as_str()), map.get("hash").and_then(|v| v.as_str())) {
                out.insert(id.to_string(), hash.to_string());
            }
            for v in map.values() {
                collect_hashes(v, out);
            }
        }
        serde_json::Value::Array(list) => list.iter().for_each(|v| collect_hashes(v, out)),
        _ => {}
    }
}

/// Ce que dit le tampon d'une tranche décompressée.
#[derive(Serialize, Deserialize, PartialEq)]
struct Stamp {
    index: String,
    asset: String,
    files: Vec<(String, u64)>,
}

/// Le SDK de `commit` prêt à compiler : index, tranches `runtime` et `api`.
/// `progress` reçoit l'avancement de 0 à 1.
pub async fn ensure(
    client: &reqwest::Client,
    commit: &str,
    engine: bool,
    progress: &(dyn Fn(f32) + Send + Sync),
    cancel: &(dyn Fn() -> bool + Send + Sync),
) -> Result<Sdk> {
    let index = index(client, commit).await?;
    let dir = dir_for(commit);
    // `engine` : le reste du jeu précompilé (~190 Mo), pour les mods qui le
    // modifient ; aussi l'index des fonctions qu'on peut copier.
    let slices: &[&str] = if engine { &["runtime", "api", "engine"] } else { &["runtime", "api"] };
    let names: Vec<String> = slices.iter().map(|s| format!("{s}-{}.tar.xz", super::toolchain::TARGET)).collect();
    let total: u64 = names.iter().filter_map(|n| index.assets.get(n)).map(|a| a.size).sum::<u64>().max(1);
    let mut before = 0u64;
    for (slice, name) in slices.iter().zip(&names) {
        let asset = index.assets.get(name).ok_or_else(|| anyhow!("Ce SDK n'a pas de version Windows ({name})"))?;
        let into = dir.join(slice);
        let stamp_path = dir.join(format!("{slice}.stamp"));
        if stamp_holds(&stamp_path, &into, &index, name) {
            before += asset.size;
            continue;
        }
        let archive = dir.join(format!(".{name}"));
        let base = before;
        super::toolchain::download(client, &url(commit, name), &archive, &asset.sha256, asset.size, &|done| {
            progress((base + done) as f32 / total as f32 * 0.9)
        }, cancel)
        .await
        .map_err(|e| anyhow!("SDK d'Horizon ({slice}) : {e}"))?;
        before += asset.size;
        let partial = dir.join(format!(".{slice}.partial"));
        let (a, p) = (archive.clone(), partial.clone());
        tokio::task::spawn_blocking(move || unpack(&a, &p))
            .await
            .map_err(|e| anyhow!("{e}"))??;
        let _ = tokio::fs::remove_file(&archive).await;
        let _ = tokio::fs::remove_dir_all(&into).await;
        tokio::fs::rename(&partial, &into).await?;
        let stamp = Stamp { index: index_hash(&index), asset: name.clone(), files: listing(&into) };
        tokio::fs::write(&stamp_path, serde_json::to_vec(&stamp)?).await?;
    }
    progress(1.0);
    Ok(Sdk { index, dir })
}

/// L'index n'a pas d'autre identité que son build et ses fichiers.
fn index_hash(index: &Index) -> String {
    let mut assets: Vec<_> = index.assets.iter().map(|(n, a)| format!("{n}:{}", a.sha256)).collect();
    assets.sort();
    sha256(format!("{}\n{}", index.build.commit, assets.join("\n")).as_bytes())
}

fn stamp_holds(stamp: &Path, into: &Path, index: &Index, name: &str) -> bool {
    let Ok(bytes) = std::fs::read(stamp) else { return false };
    let Ok(stamp) = serde_json::from_slice::<Stamp>(&bytes) else { return false };
    stamp.index == index_hash(index) && stamp.asset == name && stamp.files == listing(into)
}

/// Les fichiers d'un dossier, chemin relatif et taille, triés.
fn listing(dir: &Path) -> Vec<(String, u64)> {
    fn walk(dir: &Path, base: &Path, out: &mut Vec<(String, u64)>) {
        let Ok(entries) = std::fs::read_dir(dir) else { return };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                walk(&path, base, out);
            } else if let Ok(meta) = entry.metadata() {
                let rel = path.strip_prefix(base).unwrap_or(&path).to_string_lossy().replace('\\', "/");
                out.push((rel, meta.len()));
            }
        }
    }
    let mut out = Vec::new();
    walk(dir, dir, &mut out);
    out.sort();
    out
}

/// Décompresse une tranche (`.tar.xz`) dans `to`, en refusant toute entrée
/// qui n'est pas un simple fichier ou dossier à l'intérieur, et au-delà de
/// `UNPACKED_LIMIT`.
fn unpack(archive: &Path, to: &Path) -> Result<()> {
    let _ = std::fs::remove_dir_all(to);
    std::fs::create_dir_all(to)?;
    let file = std::fs::File::open(archive)?;
    let mut tar = tar::Archive::new(xz2::read::XzDecoder::new(file));
    let mut total = 0u64;
    for entry in tar.entries()? {
        let mut entry = entry?;
        let kind = entry.header().entry_type();
        let path = entry.path()?.into_owned();
        let mut relative = PathBuf::new();
        for component in path.components() {
            match component {
                std::path::Component::Normal(part) => relative.push(part),
                std::path::Component::CurDir => {}
                _ => return Err(anyhow!("entrée d'archive refusée : {}", path.display())),
            }
        }
        if relative.as_os_str().is_empty() {
            continue;
        }
        let target = to.join(&relative);
        if kind.is_dir() {
            std::fs::create_dir_all(&target)?;
            continue;
        }
        if !kind.is_file() {
            return Err(anyhow!("entrée d'archive refusée : {}", path.display()));
        }
        total += entry.size();
        if total > UNPACKED_LIMIT {
            return Err(anyhow!("archive du SDK trop grosse une fois décompressée"));
        }
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut out = std::fs::File::create(&target)?;
        std::io::copy(&mut entry, &mut out)?;
    }
    Ok(())
}

/// Ce que `horizon-prebuilt.json` dit de la tranche `runtime`.
#[derive(Deserialize)]
pub struct Prebuilt {
    pub commit: String,
    pub rustc: String,
    pub target: String,
    pub side: String,
    #[serde(default)]
    pub facade_crates: Vec<String>,
    /// Les fonctionnalités avec lesquelles le jeu a été compilé : les copies
    /// de ses fonctions se compilent avec les mêmes.
    #[serde(default)]
    pub game_features: Vec<String>,
}

pub fn prebuilt(runtime: &Path) -> Result<Prebuilt> {
    let bytes = std::fs::read(runtime.join("horizon-prebuilt.json"))?;
    let prebuilt: Prebuilt = serde_json::from_slice(&bytes)?;
    let release = format!("release: {}", super::toolchain::VERSION);
    if prebuilt.target != super::toolchain::TARGET || prebuilt.side != "client" || !prebuilt.rustc.lines().any(|l| l == release) {
        return Err(anyhow!("Le jeu précompilé du SDK n'est pas celui attendu ({}, {})", prebuilt.target, prebuilt.side));
    }
    Ok(prebuilt)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// La vraie publication `sdk-f037744…` du 2026-11-21 : sa signature passe
    /// pour son tag, et `sdk.json` est bien celui que liste la somme signée.
    #[test]
    fn signature_du_sdk_du_21_novembre() {
        let commit = "f037744a79f9982c47876f49c5a3067279f88706";
        let sums = include_bytes!("testdata/sdk-f037744-SHA256SUMS");
        let sig = include_str!("testdata/sdk-f037744-SHA256SUMS.sig");
        let key = super::super::super::release::release_key();
        let digest = super::super::super::release::signed_digest(&format!("sdk-{commit}"), INDEX, sums, sig, &[key]).unwrap();
        assert_eq!(digest, "ad260eb2903c9fd1eed9bc9fddd6a8fea44dc40f4b027d74fb53aa2c6872d70a");
        // Pas pour un autre tag : ni la version du jeu, ni un autre build.
        assert!(super::super::super::release::signed_digest("nightly-2026-11-21-f037744", INDEX, sums, sig, &[key]).is_err());
    }

    #[test]
    fn empreintes_imbriquees() {
        let value = serde_json::json!({
            "hooks": [{ "id": "hook:a", "hash": "1", "fields": [{ "id": "hook:a.x", "hash": "2" }] }],
            "types": [{ "id": "type:T", "hash": "3" }]
        });
        let mut out = HashMap::new();
        collect_hashes(&value, &mut out);
        assert_eq!(out.get("hook:a.x").map(String::as_str), Some("2"));
        assert_eq!(out.len(), 3);
    }
}
