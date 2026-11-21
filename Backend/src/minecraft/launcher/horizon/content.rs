//! Le contenu d'une instance Horizon tiré du registre `horizon-mods` : mods
//! (contenu et WebAssembly), mods natifs, shaders Horizon, packs de ressources.
//!
//! Règles reprises de leur « Content API » (`docs/api/content.md`) et de la
//! référence du registre (`docs/modding/src/reference/registry-index.md`) :
//!
//! | Sorte | Fichier | Dossier | Allumé par |
//! |---|---|---|---|
//! | mod | `<id>.hmod` | `horizon-mods/` | `mod.<id>:true` dans `horizon-mods.txt` |
//! | mod natif | `<id>.hmod` | `horizon-native-mods/` | idem ; compilé au lancement (`native/`) |
//! | shader Horizon | `.hshader` | `shaderpacks/` | `pickedShaderPack:<nom>` dans `horizon-graphics.txt` |
//! | pack de ressources | `.zip` | `resourcepacks/` | `options.txt`, comme vanilla (écran Packs) |
//!
//! Le dossier de jeu d'une instance Horizon est le dossier de l'instance.
//! Avant d'écrire un fichier : adresse d'une forme connue (publication du
//! registre ou du dépôt du projet), taille ≤ 64 Mio et égale à celle annoncée,
//! SHA-256, signature minisign si le projet a une clé, fichier non révoqué, et
//! manifeste conforme à l'entrée. Un fichier révoqué déjà installé est éteint
//! au lancement suivant.

use anyhow::{anyhow, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use tauri::Emitter;

const INDEX_URL: &str = "https://horizon-rust.github.io/horizon-mods/index.json";
const REGISTRY_RELEASES: &str = "https://github.com/horizon-rust/horizon-mods/releases/download/";
const MAX_FILE: u64 = 64 * 1024 * 1024;
const MODS_DIR: &str = "horizon-mods";
/// Où une instance garde ses mods natifs (`<id>.hmod`), allumés ou éteints
/// dans `horizon-mods.txt` comme les autres mods, compilés au lancement.
pub const NATIVE_DIR: &str = "horizon-native-mods";
const MODS_LIST: &str = "horizon-mods.txt";
const GRAPHICS: &str = "horizon-graphics.txt";
const PICKED_SHADER: &str = "pickedShaderPack";

// ── L'index du registre ──────────────────────────────────────────────────────

#[derive(Deserialize, Serialize, Clone, Debug)]
pub struct Index {
    pub schema: u32,
    #[serde(default)]
    pub projects: Vec<Project>,
    #[serde(default)]
    pub revoked: Vec<Revoked>,
}

#[derive(Deserialize, Serialize, Clone, Debug)]
pub struct Project {
    pub id: String,
    pub kind: String,
    pub name: String,
    #[serde(default)]
    pub summary: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub authors: Vec<String>,
    #[serde(default)]
    pub license: String,
    #[serde(default)]
    pub source: String,
    #[serde(default)]
    pub icon: Option<String>,
    #[serde(default)]
    pub key: Option<String>,
    #[serde(default)]
    pub downloads: Option<u64>,
    #[serde(default)]
    pub stars: Option<u64>,
    #[serde(default)]
    pub updated: Option<String>,
    #[serde(default)]
    pub categories: Vec<String>,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub screenshots: Vec<String>,
    #[serde(default)]
    pub versions: Vec<Version>,
}

#[derive(Deserialize, Serialize, Clone, Debug)]
pub struct Version {
    pub version: String,
    #[serde(default)]
    pub published: String,
    #[serde(default)]
    pub changelog: String,
    #[serde(default)]
    pub api: String,
    #[serde(default)]
    pub game: String,
    pub file: FileRef,
}

#[derive(Deserialize, Serialize, Clone, Debug)]
pub struct FileRef {
    pub name: String,
    pub url: String,
    pub size: u64,
    pub sha256: String,
    #[serde(default)]
    pub signature: Option<String>,
}

#[derive(Deserialize, Serialize, Clone, Debug)]
pub struct Revoked {
    pub id: String,
    #[serde(default)]
    pub sha256: Option<String>,
    #[serde(default)]
    pub reason: String,
}

impl Index {
    fn revoked_reason(&self, id: &str, sha256: &str) -> Option<String> {
        self.revoked
            .iter()
            .find(|r| r.id == id && r.sha256.as_deref().map_or(true, |s| s.eq_ignore_ascii_case(sha256)))
            .map(|r| r.reason.clone())
    }
}

/// Les sortes qu'on propose. Les packs de données (par monde) et les modpacks
/// `.hpack` ne le sont pas encore.
const OFFERED: [&str; 5] = ["mod", "native-mod", "shader", "shaderpack", "resourcepack"];

static CACHE: Mutex<Option<(std::time::Instant, Index)>> = Mutex::new(None);
const CACHE_SECS: u64 = 10 * 60;

fn kept_index() -> PathBuf {
    crate::paths::root().join("horizon").join("registry-index.json")
}

/// L'index du registre : en mémoire depuis moins de 10 minutes, sinon
/// téléchargé (et gardé sur le disque), sinon la copie du disque.
pub async fn index(network: bool) -> Result<Index> {
    if let Some((at, index)) = CACHE.lock().unwrap().as_ref() {
        if at.elapsed().as_secs() < CACHE_SECS || !network {
            return Ok(index.clone());
        }
    }
    if network {
        match fetch_index().await {
            Ok((index, bytes)) => {
                let _ = std::fs::create_dir_all(kept_index().parent().unwrap_or(Path::new(".")));
                let _ = std::fs::write(kept_index(), bytes);
                *CACHE.lock().unwrap() = Some((std::time::Instant::now(), index.clone()));
                return Ok(index);
            }
            Err(e) => tracing::warn!("[Horizon] registre injoignable : {e}"),
        }
    }
    let bytes = std::fs::read(kept_index()).map_err(|_| anyhow!("Le registre des mods d'Horizon est injoignable"))?;
    let index = parse_index(&bytes)?;
    *CACHE.lock().unwrap() = Some((std::time::Instant::now() - std::time::Duration::from_secs(CACHE_SECS), index.clone()));
    Ok(index)
}

async fn fetch_index() -> Result<(Index, Vec<u8>)> {
    let bytes = crate::net::api().get(INDEX_URL).send().await?.error_for_status()?.bytes().await?;
    if bytes.len() > 32 * 1024 * 1024 {
        return Err(anyhow!("index du registre trop gros"));
    }
    Ok((parse_index(&bytes)?, bytes.to_vec()))
}

fn parse_index(bytes: &[u8]) -> Result<Index> {
    let index: Index = serde_json::from_slice(bytes)?;
    if index.schema > 1 {
        return Err(anyhow!("Le registre d'Horizon est d'un format plus récent : mets à jour YuyuFrame"));
    }
    Ok(index)
}

// ── Ce que charge un build ───────────────────────────────────────────────────

/// Les API de contenu que charge un build (`horizon-client --launch-api`).
#[derive(Clone, Serialize)]
pub struct Support {
    pub mod_api: Vec<String>,
    pub shader_api: Vec<String>,
    /// Le build complet, pour comparer avec celui d'un mod natif.
    pub commit: Option<String>,
}

static SUPPORT: Mutex<Option<HashMap<PathBuf, Support>>> = Mutex::new(None);

fn default_support() -> Support {
    Support { mod_api: vec!["0.2".into()], shader_api: vec!["0.2".into()], commit: None }
}

/// Ce que charge la version d'Horizon que prendrait une instance épinglée sur
/// `pinned`, si elle est installée ; sinon l'API de contenu actuelle (0.2).
pub async fn support(pinned: &str) -> Support {
    #[cfg(not(target_os = "android"))]
    {
        let Some(dir) = super::release::installed_for(pinned) else { return default_support() };
        if let Some(found) = SUPPORT.lock().unwrap().as_ref().and_then(|m| m.get(&dir).cloned()) {
            return found;
        }
        let mut found = default_support();
        let mut cmd = crate::app::process::hidden_command(dir.join("horizon-client.exe"));
        cmd.arg("--launch-api").stdin(std::process::Stdio::null());
        if let Ok(Ok(out)) = tokio::time::timeout(std::time::Duration::from_secs(10), cmd.output()).await {
            if let Ok(value) = serde_json::from_slice::<serde_json::Value>(&out.stdout) {
                let list = |v: &serde_json::Value| -> Vec<String> {
                    v.as_array().into_iter().flatten().filter_map(|s| s.as_str().map(str::to_string)).collect()
                };
                found.mod_api = list(&value["content"]["mod"]["api"]);
                found.shader_api = list(&value["content"]["shader"]["api"]);
            }
        }
        let version = dir.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        found.commit = super::release::build_commit(crate::net::api(), &dir, &version).await.ok();
        SUPPORT.lock().unwrap().get_or_insert_with(HashMap::new).insert(dir, found.clone());
        found
    }
    #[cfg(target_os = "android")]
    {
        let _ = pinned;
        default_support()
    }
}

/// Le build d'Horizon qu'un mod natif vise (`native-1/<horizon>/<mixins>`).
fn native_build(api: &str) -> Option<&str> {
    api.strip_prefix("native-1/")?.split('/').next().filter(|c| c.len() == 40)
}

fn compatible(kind: &str, version: &Version, support: &Support) -> bool {
    if !version.game.is_empty() && version.game != super::GAME_VERSION {
        return false;
    }
    match kind {
        "mod" => support.mod_api.iter().any(|a| a == &version.api),
        "shader" => support.shader_api.iter().any(|a| a == &version.api),
        "native-mod" => native_build(&version.api).is_some(),
        _ => true,
    }
}

// ── Ce que voit l'interface ──────────────────────────────────────────────────

#[derive(Serialize)]
pub struct ProjectView {
    pub id: String,
    pub kind: String,
    pub name: String,
    pub summary: String,
    pub description: String,
    pub authors: Vec<String>,
    pub license: String,
    pub source: String,
    pub icon: Option<String>,
    pub downloads: u64,
    pub stars: u64,
    pub updated: String,
    pub categories: Vec<String>,
    pub tags: Vec<String>,
    pub screenshots: Vec<String>,
    /// Le projet a une clé : chaque version est signée par son auteur.
    pub signed: bool,
    /// Les versions installables sur cette instance, la plus récente d'abord.
    pub versions: Vec<VersionView>,
}

#[derive(Serialize)]
pub struct VersionView {
    pub version: String,
    pub published: String,
    pub changelog: String,
    pub size: u64,
    /// Mod natif : le build d'Horizon pour lequel il est fait.
    pub made_for: Option<String>,
}

#[derive(Serialize)]
pub struct RegistryView {
    pub projects: Vec<ProjectView>,
    pub support: Support,
}

#[tauri::command]
pub async fn horizon_registry(loader_version: String) -> Result<RegistryView, String> {
    let index = index(true).await.map_err(|e| e.to_string())?;
    let support = support(&loader_version).await;
    let revoked_ids: HashSet<&str> = index.revoked.iter().filter(|r| r.sha256.is_none()).map(|r| r.id.as_str()).collect();
    let projects = index
        .projects
        .iter()
        .filter(|p| OFFERED.contains(&p.kind.as_str()) && !revoked_ids.contains(p.id.as_str()))
        .filter_map(|p| {
            let versions: Vec<VersionView> = p
                .versions
                .iter()
                .filter(|v| compatible(&p.kind, v, &support) && index.revoked_reason(&p.id, &v.file.sha256).is_none())
                .map(|v| VersionView {
                    version: v.version.clone(),
                    published: v.published.clone(),
                    changelog: v.changelog.clone(),
                    size: v.file.size,
                    made_for: native_build(&v.api).map(str::to_string),
                })
                .collect();
            (!versions.is_empty()).then(|| ProjectView {
                id: p.id.clone(),
                kind: p.kind.clone(),
                name: p.name.clone(),
                summary: p.summary.clone(),
                description: p.description.clone(),
                authors: p.authors.clone(),
                license: p.license.clone(),
                source: p.source.clone(),
                icon: p.icon.clone(),
                downloads: p.downloads.unwrap_or(0),
                stars: p.stars.unwrap_or(0),
                updated: p.updated.clone().or_else(|| p.versions.first().map(|v| v.published.clone())).unwrap_or_default(),
                categories: p.categories.clone(),
                tags: p.tags.clone(),
                screenshots: p.screenshots.clone(),
                signed: p.key.is_some(),
                versions,
            })
        })
        .collect();
    Ok(RegistryView { projects, support })
}

// ── Fichiers de réglages du jeu ──────────────────────────────────────────────

/// Les lignes `clé:valeur` d'un fichier de réglages d'Horizon.
fn read_settings(path: &Path) -> Vec<(String, String)> {
    std::fs::read_to_string(path)
        .unwrap_or_default()
        .lines()
        .filter_map(|l| l.split_once(':').map(|(k, v)| (k.to_string(), v.to_string())))
        .collect()
}

/// Pose (ou retire, avec `None`) une ligne, en gardant les autres et leur ordre.
fn write_setting(path: &Path, key: &str, value: Option<&str>) -> Result<()> {
    let text = std::fs::read_to_string(path).unwrap_or_default();
    let mut lines: Vec<String> = Vec::new();
    let mut done = false;
    for line in text.lines() {
        if line.split_once(':').is_some_and(|(k, _)| k == key) {
            if let (Some(v), false) = (value, done) {
                lines.push(format!("{key}:{v}"));
                done = true;
            }
        } else {
            lines.push(line.to_string());
        }
    }
    if let (Some(v), false) = (value, done) {
        lines.push(format!("{key}:{v}"));
    }
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let tmp = path.with_extension("txt.tmp");
    std::fs::write(&tmp, lines.join("\n") + "\n")?;
    std::fs::rename(&tmp, path)?;
    Ok(())
}

/// Les mods allumés d'un dossier de jeu (`mod.<id>:true`).
pub fn enabled_ids(game_dir: &Path) -> HashSet<String> {
    read_settings(&game_dir.join(MODS_LIST))
        .into_iter()
        .filter(|(k, v)| k.starts_with("mod.") && v.trim() == "true")
        .map(|(k, _)| k["mod.".len()..].to_string())
        .collect()
}

fn set_enabled(game_dir: &Path, id: &str, on: bool) -> Result<()> {
    write_setting(&game_dir.join(MODS_LIST), &format!("mod.{id}"), Some(if on { "true" } else { "false" }))
}

fn picked_shader(game_dir: &Path) -> String {
    read_settings(&game_dir.join(GRAPHICS))
        .into_iter()
        .find(|(k, _)| k == PICKED_SHADER)
        .map(|(_, v)| v)
        .unwrap_or_default()
}

// ── Ce qui est installé ──────────────────────────────────────────────────────

#[derive(Serialize, Clone)]
pub struct InstalledView {
    pub file: String,
    /// `mod`, `native-mod` ou `shader`.
    pub kind: String,
    pub id: String,
    pub name: String,
    pub version: String,
    pub enabled: bool,
    pub size: u64,
    pub icon: Option<String>,
    /// Le projet du registre d'où vient ce fichier (par son empreinte), et la
    /// version la plus récente qui s'installe ici si ce n'est pas celle-ci.
    pub project: Option<String>,
    pub update: Option<String>,
    /// Retiré du registre : la raison.
    pub revoked: Option<String>,
    /// Mod natif : le build visé, et s'il modifie le code du jeu.
    pub made_for: Option<String>,
    pub changes_game: bool,
}

struct Manifest {
    id: String,
    name: String,
    version: String,
    api: String,
    native: Option<String>,
    changes_game: bool,
    icon: Option<String>,
    depends: Vec<String>,
}

/// Lit le manifeste (`horizon-mod.toml` ou `horizon-shaders.toml`) et l'icône
/// d'une archive, sans rien extraire.
fn read_manifest(bytes: &[u8], manifest_name: &str) -> Option<Manifest> {
    use base64::Engine as _;
    let mut zip = zip::ZipArchive::new(std::io::Cursor::new(bytes)).ok()?;
    let text = {
        let mut entry = zip.by_name(manifest_name).ok()?;
        let mut text = String::new();
        (&mut entry).take(1024 * 1024).read_to_string(&mut text).ok()?;
        text
    };
    let table: toml::Table = text.parse().ok()?;
    let get = |k: &str| table.get(k).and_then(|v| v.as_str()).unwrap_or_default().to_string();
    let native_table = table.get("native").and_then(|n| n.as_table());
    let icon = zip.by_name("icon.png").ok().and_then(|entry| {
        if entry.size() > 256 * 1024 {
            return None;
        }
        let mut data = Vec::new();
        entry.take(256 * 1024).read_to_end(&mut data).ok()?;
        data.starts_with(b"\x89PNG\r\n\x1a\n")
            .then(|| format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(&data)))
    });
    let depends = table
        .get("dependencies")
        .and_then(|d| d.as_table())
        .map(|deps| {
            deps.iter()
                .filter(|(_, v)| !v.get("optional").and_then(|o| o.as_bool()).unwrap_or(false))
                .filter(|(_, v)| v.get("side").and_then(|s| s.as_str()) != Some("server"))
                .map(|(id, _)| id.clone())
                .collect()
        })
        .unwrap_or_default();
    Some(Manifest {
        id: get("id"),
        name: get("name"),
        version: get("version"),
        api: get("api"),
        native: native_table.and_then(|n| n.get("horizon")).and_then(|h| h.as_str()).map(str::to_string),
        changes_game: native_table.is_some_and(|n| ["mixin", "module", "bridge", "loop", "state"].iter().any(|k| n.contains_key(*k)))
            || table.contains_key("internals"),
        icon,
        depends,
    })
}

fn sha256_hex(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

fn game_dir(instance_id: &str) -> PathBuf {
    crate::instances::crud::instance_dir(instance_id)
}

fn files_in(dir: &Path, ext: &str) -> Vec<PathBuf> {
    let mut out: Vec<PathBuf> = std::fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_file() && p.extension().is_some_and(|e| e.eq_ignore_ascii_case(ext)))
        .collect();
    out.sort();
    out
}

#[tauri::command]
pub async fn horizon_content_list(instance_id: String, loader_version: String) -> Result<Vec<InstalledView>, String> {
    let dir = game_dir(&instance_id);
    let index = index(false).await.ok();
    let support = support(&loader_version).await;
    tokio::task::spawn_blocking(move || list(&dir, index.as_ref(), &support))
        .await
        .map_err(|e| e.to_string())
}

fn list(dir: &Path, index: Option<&Index>, support: &Support) -> Vec<InstalledView> {
    let enabled = enabled_ids(dir);
    let picked = picked_shader(dir);
    let by_hash: HashMap<String, (&Project, &Version)> = index
        .map(|i| {
            i.projects
                .iter()
                .flat_map(|p| p.versions.iter().map(move |v| (v.file.sha256.to_ascii_lowercase(), (p, v))))
                .collect()
        })
        .unwrap_or_default();
    let mut out = Vec::new();
    let sources = [
        (MODS_DIR, "hmod", "horizon-mod.toml"),
        (NATIVE_DIR, "hmod", "horizon-mod.toml"),
        ("shaderpacks", "hshader", "horizon-shaders.toml"),
    ];
    for (folder, ext, manifest_name) in sources {
        for path in files_in(&dir.join(folder), ext) {
            let Ok(bytes) = std::fs::read(&path) else { continue };
            let file = path.file_name().unwrap_or_default().to_string_lossy().into_owned();
            let sha = sha256_hex(&bytes);
            let manifest = read_manifest(&bytes, manifest_name);
            let from_registry = by_hash.get(&sha);
            let kind = match ext {
                "hshader" => "shader",
                _ if manifest.as_ref().is_some_and(|m| m.native.is_some()) || folder != MODS_DIR => "native-mod",
                _ => "mod",
            };
            let id = manifest
                .as_ref()
                .map(|m| m.id.clone())
                .filter(|i| !i.is_empty())
                .or_else(|| from_registry.map(|(p, _)| p.id.clone()))
                .unwrap_or_else(|| file.trim_end_matches(&format!(".{ext}")).to_string());
            let enabled = if kind == "shader" { picked == file } else { enabled.contains(&id) };
            let update = from_registry.and_then(|(p, v)| {
                let newest = p.versions.iter().find(|nv| compatible(&p.kind, nv, support))?;
                (newest.version != v.version).then(|| newest.version.clone())
            });
            out.push(InstalledView {
                kind: kind.to_string(),
                name: manifest
                    .as_ref()
                    .map(|m| m.name.clone())
                    .filter(|n| !n.is_empty())
                    .or_else(|| from_registry.map(|(p, _)| p.name.clone()))
                    .unwrap_or_else(|| id.clone()),
                version: manifest.as_ref().map(|m| m.version.clone()).filter(|v| !v.is_empty())
                    .or_else(|| from_registry.map(|(_, v)| v.version.clone()))
                    .unwrap_or_default(),
                revoked: index.and_then(|i| i.revoked_reason(&id, &sha)),
                made_for: manifest.as_ref().and_then(|m| m.native.clone()),
                changes_game: manifest.as_ref().is_some_and(|m| m.changes_game),
                icon: manifest.as_ref().and_then(|m| m.icon.clone()),
                project: from_registry.map(|(p, _)| p.id.clone()),
                size: bytes.len() as u64,
                enabled,
                update,
                id,
                file,
            });
        }
    }
    out.sort_by_key(|v| (v.kind != "mod", v.kind != "native-mod", v.name.to_lowercase()));
    out
}

// ── Installer ────────────────────────────────────────────────────────────────

/// Une adresse de la forme que donne le registre : sa propre publication, ou
/// une publication du dépôt GitHub du projet. Rien d'autre.
fn allowed_url(url: &str, name: &str) -> bool {
    if url.starts_with(REGISTRY_RELEASES) {
        return url.ends_with(&format!("/{name}")) && !url.contains("..");
    }
    let Some(rest) = url.strip_prefix("https://github.com/") else { return false };
    let parts: Vec<&str> = rest.split('/').collect();
    parts.len() == 6
        && parts[2] == "releases"
        && parts[3] == "download"
        && parts.iter().all(|p| !p.is_empty() && *p != "." && *p != "..")
        && parts[5] == name
}

fn plain_name(name: &str) -> bool {
    !name.is_empty() && !name.contains(['/', '\\', ':']) && name != "." && name != ".." && !name.starts_with('.')
}

/// Télécharge et vérifie le fichier d'une version ; rend ses octets.
async fn fetch_checked(app: &tauri::AppHandle, index: &Index, project: &Project, version: &Version) -> Result<Vec<u8>> {
    use futures::StreamExt;
    let file = &version.file;
    if !plain_name(&file.name) || !allowed_url(&file.url, &file.name) {
        return Err(anyhow!("Adresse refusée pour {} : elle n'est pas de la forme du registre", project.name));
    }
    if file.size > MAX_FILE {
        return Err(anyhow!("{} dépasse 64 Mio", project.name));
    }
    if let Some(reason) = index.revoked_reason(&project.id, &file.sha256) {
        return Err(anyhow!("{} a été retiré du registre : {reason}", project.name));
    }
    let response = crate::net::files().get(&file.url).send().await?.error_for_status()?;
    let mut bytes = Vec::with_capacity(file.size as usize);
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk?;
        if (bytes.len() + chunk.len()) as u64 > file.size {
            return Err(anyhow!("{} : fichier plus gros qu'annoncé, refusé", project.name));
        }
        bytes.extend_from_slice(&chunk);
        let _ = app.emit("horizon_content_progress", serde_json::json!({
            "project": project.id, "downloaded": bytes.len(), "total": file.size,
        }));
    }
    if bytes.len() as u64 != file.size || !sha256_hex(&bytes).eq_ignore_ascii_case(&file.sha256) {
        return Err(anyhow!("{} ne correspond pas à son empreinte du registre : refusé", project.name));
    }
    if let Some(key) = &project.key {
        let signature = file.signature.as_deref().ok_or_else(|| anyhow!("{} n'est pas signé alors que son projet a une clé : refusé", project.name))?;
        let key = minisign_verify::PublicKey::from_base64(key.trim()).map_err(|e| anyhow!("clé de {} illisible : {e}", project.name))?;
        let signature = minisign_verify::Signature::decode(signature).map_err(|e| anyhow!("signature de {} illisible : {e}", project.name))?;
        key.verify(&bytes, &signature, false).map_err(|_| anyhow!("La signature de {} ne correspond pas à la clé de son auteur : refusé", project.name))?;
    }
    Ok(bytes)
}

/// Le fichier dit-il ce qu'annonce le registre ?
fn check_contents(project: &Project, version: &Version, bytes: &[u8]) -> Result<Option<Manifest>> {
    let wrong = |what: &str| anyhow!("{} : {what} ne correspond pas au registre, refusé", project.name);
    match project.kind.as_str() {
        "mod" | "native-mod" => {
            let m = read_manifest(bytes, "horizon-mod.toml").ok_or_else(|| wrong("le manifeste"))?;
            if m.id != project.id || m.version != version.version {
                return Err(wrong("l'identifiant ou la version"));
            }
            match project.kind.as_str() {
                "mod" if m.native.is_some() || m.api != version.api => return Err(wrong("l'API")),
                "native-mod" if m.native.as_deref() != native_build(&version.api) => return Err(wrong("le build visé")),
                _ => {}
            }
            Ok(Some(m))
        }
        "shader" => {
            let m = read_manifest(bytes, "horizon-shaders.toml").ok_or_else(|| wrong("le manifeste"))?;
            if (!m.id.is_empty() && m.id != project.id) || (!m.version.is_empty() && m.version != version.version) {
                return Err(wrong("l'identifiant ou la version"));
            }
            Ok(Some(m))
        }
        "resourcepack" | "shaderpack" => {
            let zip = zip::ZipArchive::new(std::io::Cursor::new(bytes)).map_err(|_| wrong("l'archive"))?;
            let expected = if project.kind == "resourcepack" { "pack.mcmeta" } else { "shaders/" };
            if !zip.file_names().any(|n| n == expected || n.starts_with(expected)) {
                return Err(wrong("le contenu"));
            }
            Ok(None)
        }
        _ => Err(anyhow!("Cette version d'Horizon ne sait pas encore installer ce type de contenu")),
    }
}

/// Écrit `bytes` à `path` d'un coup (fichier temporaire puis renommage).
fn write_whole(path: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let tmp = path.with_extension("part");
    std::fs::write(&tmp, bytes)?;
    std::fs::rename(&tmp, path)?;
    Ok(())
}

/// Installe un projet du registre (et les mods dont il a besoin), l'allume,
/// et rend la liste de ce qui a été installé.
#[tauri::command]
pub async fn horizon_content_install(
    app: tauri::AppHandle,
    instance_id: String,
    project_id: String,
    version: Option<String>,
    loader_version: String,
    replace: Option<String>,
) -> Result<Vec<String>, String> {
    install(&app, &instance_id, &project_id, version.as_deref(), &loader_version, replace.as_deref())
        .await
        .map_err(|e| e.to_string())
}

async fn install(
    app: &tauri::AppHandle,
    instance_id: &str,
    project_id: &str,
    version: Option<&str>,
    loader_version: &str,
    replace: Option<&str>,
) -> Result<Vec<String>> {
    let index = index(true).await?;
    let support = support(loader_version).await;
    let dir = game_dir(instance_id);
    let mut queue: Vec<(String, Option<String>)> = vec![(project_id.to_string(), version.map(str::to_string))];
    let mut done: Vec<String> = Vec::new();
    let mut native = false;
    while let Some((id, wanted)) = queue.pop() {
        if done.contains(&id) || done.len() > 16 {
            continue;
        }
        let project = index
            .projects
            .iter()
            .find(|p| p.id == id && OFFERED.contains(&p.kind.as_str()))
            .ok_or_else(|| anyhow!("« {id} » n'est pas dans le registre d'Horizon"))?;
        let chosen = match &wanted {
            Some(v) => project.versions.iter().find(|x| &x.version == v),
            None => project.versions.iter().find(|x| compatible(&project.kind, x, &support)),
        }
        .ok_or_else(|| anyhow!("Aucune version de {} ne s'installe sur cette instance", project.name))?;
        let bytes = fetch_checked(app, &index, project, chosen).await?;
        let manifest = check_contents(project, chosen, &bytes)?;

        let target = match project.kind.as_str() {
            "mod" => dir.join(MODS_DIR).join(format!("{}.hmod", project.id)),
            "native-mod" => dir.join(NATIVE_DIR).join(format!("{}.hmod", project.id)),
            "shader" | "shaderpack" => dir.join("shaderpacks").join(&chosen.file.name),
            _ => dir.join("resourcepacks").join(&chosen.file.name),
        };
        // Une ancienne version du même projet sous un autre nom de fichier.
        if let Some(old) = replace.filter(|_| id == project_id).filter(|n| plain_name(n)) {
            if let Some(parent) = target.parent() {
                let old_path = parent.join(old);
                if old_path != target {
                    let _ = std::fs::remove_file(old_path);
                }
            }
        }
        write_whole(&target, &bytes)?;
        match project.kind.as_str() {
            "mod" | "native-mod" => {
                set_enabled(&dir, &project.id, true)?;
                native |= project.kind == "native-mod";
                for dep in manifest.map(|m| m.depends).unwrap_or_default() {
                    let have = enabled_ids(&dir).contains(&dep) || done.contains(&dep);
                    if !have && index.projects.iter().any(|p| p.id == dep) {
                        queue.push((dep, None));
                    } else if !have {
                        set_enabled(&dir, &dep, true)?;
                    }
                }
            }
            "shader" => {
                let picked = picked_shader(&dir);
                // Un seul shader tourne à la fois : on prend le nouveau si
                // rien n'était choisi, ou s'il remplace celui qui l'était.
                if picked.is_empty() || replace.is_some_and(|r| r == picked) {
                    write_setting(&dir.join(GRAPHICS), PICKED_SHADER, Some(&chosen.file.name))?;
                }
            }
            _ => {}
        }
        done.push(id);
    }
    if native {
        #[cfg(not(target_os = "android"))]
        super::native::prefetch(app.clone(), loader_version.to_string());
    }
    Ok(done)
}

/// Allume ou éteint un mod (`file` dans `horizon-mods/` ou `horizon-native-mods/`).
#[tauri::command]
pub async fn horizon_content_toggle(app: tauri::AppHandle, instance_id: String, file: String, enabled: bool, loader_version: String) -> Result<(), String> {
    let dir = game_dir(&instance_id);
    let (id, native) = mod_id(&dir, &file).ok_or("Mod introuvable")?;
    set_enabled(&dir, &id, enabled).map_err(|e| e.to_string())?;
    if native && enabled {
        #[cfg(not(target_os = "android"))]
        super::native::prefetch(app, loader_version);
    }
    #[cfg(target_os = "android")]
    let _ = (app, loader_version);
    Ok(())
}

/// L'identifiant d'un mod installé d'après son manifeste, et s'il est natif.
fn mod_id(dir: &Path, file: &str) -> Option<(String, bool)> {
    if !plain_name(file) {
        return None;
    }
    for (folder, native) in [(MODS_DIR, false), (NATIVE_DIR, true)] {
        let path = dir.join(folder).join(file);
        if let Ok(bytes) = std::fs::read(&path) {
            let m = read_manifest(&bytes, "horizon-mod.toml")?;
            return Some((m.id, native || m.native.is_some()));
        }
    }
    None
}

/// Choisit le shader Horizon (`None` : aucun).
#[tauri::command]
pub async fn horizon_shader_pick(instance_id: String, file: Option<String>) -> Result<(), String> {
    let dir = game_dir(&instance_id);
    let value = file.filter(|f| plain_name(f)).unwrap_or_default();
    write_setting(&dir.join(GRAPHICS), PICKED_SHADER, Some(&value)).map_err(|e| e.to_string())
}

/// Supprime un mod ou un shader Horizon installé.
#[tauri::command]
pub async fn horizon_content_delete(instance_id: String, file: String) -> Result<(), String> {
    if !plain_name(&file) {
        return Err("Nom de fichier refusé".into());
    }
    let dir = game_dir(&instance_id);
    if file.to_ascii_lowercase().ends_with(".hshader") {
        let path = dir.join("shaderpacks").join(&file);
        std::fs::remove_file(&path).map_err(|e| e.to_string())?;
        if picked_shader(&dir) == file {
            write_setting(&dir.join(GRAPHICS), PICKED_SHADER, Some("")).map_err(|e| e.to_string())?;
        }
        return Ok(());
    }
    let (id, _) = mod_id(&dir, &file).unwrap_or_default();
    for folder in [MODS_DIR, NATIVE_DIR] {
        let path = dir.join(folder).join(&file);
        if path.is_file() {
            std::fs::remove_file(&path).map_err(|e| e.to_string())?;
            if !id.is_empty() {
                write_setting(&dir.join(MODS_LIST), &format!("mod.{id}"), None).map_err(|e| e.to_string())?;
            }
            return Ok(());
        }
    }
    Err("Fichier introuvable".into())
}

/// Au lancement : éteint ce qui a été retiré du registre depuis son
/// installation (d'après la dernière copie connue de l'index, sans réseau).
/// Rend les noms et raisons, pour la console.
pub async fn enforce_revocations(game_dir: &Path) -> Vec<(String, String)> {
    let Ok(index) = index(false).await else { return Vec::new() };
    let mut turned_off = Vec::new();
    let picked = picked_shader(game_dir);
    for item in list(game_dir, Some(&index), &default_support()) {
        let Some(reason) = item.revoked else { continue };
        if !item.enabled {
            continue;
        }
        let ok = if item.kind == "shader" {
            picked == item.file && write_setting(&game_dir.join(GRAPHICS), PICKED_SHADER, Some("")).is_ok()
        } else {
            set_enabled(game_dir, &item.id, false).is_ok()
        };
        if ok {
            turned_off.push((item.name, reason));
        }
    }
    turned_off
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adresses_acceptees() {
        assert!(allowed_url("https://github.com/horizon-rust/horizon-mods/releases/download/x-1.0/x-1.0.hmod", "x-1.0.hmod"));
        assert!(allowed_url("https://github.com/alice/mymod/releases/download/v1/mymod.hmod", "mymod.hmod"));
        assert!(!allowed_url("https://evil.example/x.hmod", "x.hmod"));
        assert!(!allowed_url("https://github.com/alice/mymod/raw/main/mymod.hmod", "mymod.hmod"));
        assert!(!allowed_url("https://github.com/alice/mymod/releases/download/v1/other.hmod", "mymod.hmod"));
        assert!(!allowed_url("https://github.com/alice/../releases/download/v1/x.hmod", "x.hmod"));
    }

    #[test]
    fn reglages_du_jeu() {
        let dir = std::env::temp_dir().join(format!("yf-horizon-settings-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join(MODS_LIST), "wasm.memory_cap_mib:2048\nmod.a:true\n").unwrap();
        set_enabled(&dir, "b", true).unwrap();
        set_enabled(&dir, "a", false).unwrap();
        let text = std::fs::read_to_string(dir.join(MODS_LIST)).unwrap();
        assert_eq!(text, "wasm.memory_cap_mib:2048\nmod.a:false\nmod.b:true\n");
        assert_eq!(enabled_ids(&dir), HashSet::from(["b".to_string()]));
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Le vrai index du registre : `YF_HORIZON_INDEX=<index.json> cargo test
    /// --lib vrai_index -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn vrai_index() {
        let bytes = std::fs::read(std::env::var("YF_HORIZON_INDEX").unwrap()).unwrap();
        let index = parse_index(&bytes).unwrap();
        let support = default_support();
        let mut counts: HashMap<&str, (usize, usize)> = HashMap::new();
        for p in &index.projects {
            let entry = counts.entry(p.kind.as_str()).or_default();
            entry.0 += 1;
            if p.versions.iter().any(|v| compatible(&p.kind, v, &support)) {
                entry.1 += 1;
            }
            for v in &p.versions {
                assert!(allowed_url(&v.file.url, &v.file.name), "{} : {}", p.id, v.file.url);
            }
        }
        println!("{counts:?}, révoqués : {}", index.revoked.len());
        assert!(index.projects.len() > 100);
    }

    #[test]
    fn build_d_un_mod_natif() {
        assert_eq!(
            native_build("native-1/f037744a79f9982c47876f49c5a3067279f88706/e59da9094f0c2fdda6c627fe9eff22dec6a906f0"),
            Some("f037744a79f9982c47876f49c5a3067279f88706")
        );
        assert_eq!(native_build("0.2"), None);
    }
}
