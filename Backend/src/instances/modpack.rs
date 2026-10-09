use serde::{Deserialize, Serialize};
use std::io::Read;

use super::crud::instance_dir;
use crate::security::safe_paths::{join_relative, safe_relative};
use crate::server::curseforge::post_json;
use crate::minecraft::mod_files::is_jar_file;
use crate::minecraft::versions::predicate::read_fabric_mod_json;
use crate::state::SharedState;

/// Indexe les mods déjà présents par leur id `fabric.mod.json` — sert à détecter
/// les doublons (ex: "Fabric API" déjà installé en extra + ré-installé par le pack).
/// Best-effort : un mod sans `fabric.mod.json` (Forge, plugin...) n'est pas dédupliqué.
fn collect_mod_ids(mods_dir: &std::path::Path) -> std::collections::HashMap<String, std::path::PathBuf> {
    let mut map = std::collections::HashMap::new();
    let Ok(entries) = std::fs::read_dir(mods_dir) else { return map };
    for entry in entries.flatten() {
        let path = entry.path();
        let name = path.file_name().unwrap_or_default().to_string_lossy().to_string();
        if !is_jar_file(&name) {
            continue;
        }
        if let Some(meta) = read_fabric_mod_json(&path) {
            map.insert(meta.id, path);
        }
    }
    map
}

/// Dossier caché (ignoré par `mods_list`, qui ne liste que les `.jar`) où sont
/// déplacés — au lieu d'être supprimés — les mods "extra" en conflit avec le pack.
fn backup_dir(mods_dir: &std::path::Path) -> std::path::PathBuf {
    mods_dir.join(".pack_backup")
}

#[derive(Serialize, Deserialize, Default)]
struct ModpackBackup {
    files: Vec<String>,
}

fn backup_json_path(dir: &std::path::Path) -> std::path::PathBuf {
    dir.join("modpack_backup.json")
}

/// Remet en place les mods "extra" déplacés lors d'une précédente installation
/// de pack (conflits d'id), puis efface la trace de sauvegarde. Best-effort.
fn restore_backup(dir: &std::path::Path) {
    let backup_path = backup_json_path(dir);
    let Ok(json) = std::fs::read_to_string(&backup_path) else { return };
    let Ok(backup) = serde_json::from_str::<ModpackBackup>(&json) else { return };
    let mods_dir = dir.join("mods");
    let bdir = backup_dir(&mods_dir);
    for file in &backup.files {
        let src = bdir.join(file);
        if !src.exists() {
            continue;
        }
        let dst = mods_dir.join(file);
        let _ = std::fs::remove_file(&dst);
        let _ = std::fs::rename(&src, &dst);
    }
    let _ = std::fs::remove_file(&backup_path);
    let _ = std::fs::remove_dir(&bdir);
}

/// Métadonnées persistées dans `modpack.json` à la racine d'une instance créée
/// depuis un modpack Modrinth (.mrpack). Pilote la bannière + la séparation
/// "contenu du modpack" / "contenu supplémentaire" côté UI.
#[derive(Serialize, Deserialize, Clone)]
pub struct ModpackMeta {
    pub project_id: String,
    pub version_id: String,
    pub name: String,
    pub author: String,
    pub summary: String,
    pub icon_url: Option<String>,
    pub version_number: String,
    pub downloads: u64,
    pub date_modified: Option<String>,
    pub categories: Vec<String>,
    /// Noms de fichiers (basename) installés par le modpack — sert à distinguer
    /// le contenu du pack du contenu ajouté manuellement par l'utilisateur.
    pub mod_files: Vec<String>,
    /// Fichiers référencés par le pack qui n'ont pas pu être téléchargés
    /// (réseau, URL invalide...) — le pack reste installé avec ce qui a
    /// réussi, mais l'utilisateur doit savoir qu'il manque des fichiers.
    #[serde(default)]
    pub failed_files: Vec<String>,
}

#[derive(Deserialize)]
struct IndexFile {
    path: String,
    downloads: Vec<String>,
}

#[derive(Deserialize)]
struct ModrinthIndex {
    #[serde(default)]
    name: String,
    #[serde(default, rename = "versionId")]
    version_id: String,
    #[serde(default)]
    summary: String,
    files: Vec<IndexFile>,
    #[serde(default)]
    dependencies: std::collections::HashMap<String, String>,
}

#[derive(Serialize)]
pub struct ModpackIndexInfo {
    pub mc_version: Option<String>,
    pub loader: String,
    /// Noms de fichiers `mods/` référencés par le pack — sert uniquement à
    /// l'aperçu (ModpackDetailModal côté frontend), pas à l'installation.
    pub mods: Vec<String>,
}

fn loader_from_dependencies(deps: &std::collections::HashMap<String, String>) -> String {
    if deps.contains_key("quilt-loader") {
        "quilt".to_string()
    } else if deps.contains_key("fabric-loader") {
        "fabric".to_string()
    } else if deps.contains_key("neoforge") {
        "neoforge".to_string()
    } else if deps.contains_key("forge") {
        "forge".to_string()
    } else {
        "vanilla".to_string()
    }
}

fn check_modrinth_url(url: &str) -> Result<(), String> {
    if !url.starts_with("https://cdn.modrinth.com/") {
        return Err("URL non autorisée".into());
    }
    Ok(())
}

async fn download_mrpack(url: &str) -> Result<Vec<u8>, String> {
    check_modrinth_url(url)?;
    let client = reqwest::Client::builder()
        .user_agent("YuyuFrame/1.0")
        .build()
        .map_err(|e| e.to_string())?;
    let resp = client.get(url).send().await.map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("Téléchargement échoué: {}", resp.status()));
    }
    Ok(resp.bytes().await.map_err(|e| e.to_string())?.to_vec())
}

fn read_index(bytes: &[u8]) -> Result<ModrinthIndex, String> {
    let cursor = std::io::Cursor::new(bytes);
    let mut archive = zip::ZipArchive::new(cursor).map_err(|e| e.to_string())?;
    let mut entry = archive
        .by_name("modrinth.index.json")
        .map_err(|_| "modrinth.index.json introuvable dans le .mrpack".to_string())?;
    let mut content = String::new();
    entry.read_to_string(&mut content).map_err(|e| e.to_string())?;
    serde_json::from_str(&content).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn modpack_fetch_index(file_url: String) -> Result<ModpackIndexInfo, String> {
    let bytes = download_mrpack(&file_url).await?;
    let index = tokio::task::spawn_blocking(move || read_index(&bytes))
        .await
        .map_err(|e| e.to_string())??;
    let mods = index
        .files
        .iter()
        .filter(|f| f.path.starts_with("mods/"))
        .filter_map(|f| f.path.rsplit('/').next().map(|s| s.to_string()))
        .collect();
    Ok(ModpackIndexInfo {
        mc_version: index.dependencies.get("minecraft").cloned(),
        loader: loader_from_dependencies(&index.dependencies),
        mods,
    })
}

// ── CurseForge (manifest.json + overrides/) ─────────────────────────────────
// Format distinct de Modrinth : le manifest ne référence chaque mod que par
// `{projectID, fileID}`, jamais une URL directe — il faut résoudre ces ids en
// URLs de téléchargement via l'API CurseForge (voir `resolve_cf_files`), et
// TOUS les fichiers vont dans `mods/` (contrairement à l'index Modrinth qui
// donne un chemin explicite par fichier).

const CF_CDN_HOSTS: [&str; 3] = [
    "https://edge.forgecdn.net/",
    "https://media.forgecdn.net/",
    "https://mediafilez.forgecdn.net/",
];

fn check_cf_cdn_url(url: &str) -> Result<(), String> {
    if !CF_CDN_HOSTS.iter().any(|prefix| url.starts_with(prefix)) {
        return Err("URL non autorisée".into());
    }
    Ok(())
}

#[derive(Deserialize)]
struct CfManifestFile {
    #[serde(rename = "projectID")]
    project_id: u64,
    #[serde(rename = "fileID")]
    file_id: u64,
}

#[derive(Deserialize, Default)]
struct CfModLoaderEntry {
    id: String,
    #[serde(default)]
    primary: bool,
}

#[derive(Deserialize, Default)]
struct CfManifestMinecraft {
    #[serde(default)]
    version: String,
    #[serde(default, rename = "modLoaders")]
    mod_loaders: Vec<CfModLoaderEntry>,
}

/// Dérive un nom de loader générique ("forge"/"fabric"/"neoforge"/"quilt") à
/// partir des ids CurseForge (ex: "forge-47.2.0", "fabric-0.16.9") — prend
/// l'entrée `primary` s'il y en a une, sinon la première. Même sortie que
/// `loader_from_dependencies` côté Modrinth, pour un rendu identique dans
/// ModpackDetailModal quelle que soit la source.
fn loader_from_cf_manifest(minecraft: &CfManifestMinecraft) -> String {
    let entry = minecraft.mod_loaders.iter().find(|m| m.primary).or_else(|| minecraft.mod_loaders.first());
    let Some(entry) = entry else { return "vanilla".to_string() };
    entry.id.split('-').next().unwrap_or("vanilla").to_lowercase()
}

fn default_cf_overrides() -> String {
    "overrides".to_string()
}

#[derive(Deserialize)]
struct CfManifest {
    #[serde(default)]
    minecraft: CfManifestMinecraft,
    #[serde(default)]
    name: String,
    #[serde(default)]
    version: String,
    #[serde(default)]
    author: String,
    files: Vec<CfManifestFile>,
    #[serde(default = "default_cf_overrides")]
    overrides: String,
}

fn read_cf_manifest(bytes: &[u8]) -> Result<CfManifest, String> {
    let cursor = std::io::Cursor::new(bytes);
    let mut archive = zip::ZipArchive::new(cursor).map_err(|e| e.to_string())?;
    let mut entry = archive
        .by_name("manifest.json")
        .map_err(|_| "manifest.json introuvable dans l'archive".to_string())?;
    let mut content = String::new();
    entry.read_to_string(&mut content).map_err(|e| e.to_string())?;
    serde_json::from_str(&content).map_err(|e| e.to_string())
}

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct CfFileMeta {
    id: u64,
    mod_id: u64,
    file_name: String,
    download_url: Option<String>,
}

#[derive(Deserialize)]
struct CfFilesResponse {
    data: Vec<CfFileMeta>,
}

/// Résout en un seul appel batch (voir Server/LauncherAPI `/curseforge/files`)
/// les métadonnées de tous les fichiers référencés par un manifest — un appel
/// par mod serait beaucoup trop lent (un modpack référence souvent 100+ mods).
async fn resolve_cf_files(
    state: &tauri::State<'_, SharedState>,
    file_ids: &[u64],
) -> Result<Vec<CfFileMeta>, String> {
    if file_ids.is_empty() {
        return Ok(Vec::new());
    }
    let body = serde_json::json!({ "file_ids": file_ids });
    let value = post_json(state, "/curseforge/files", body).await?;
    let parsed: CfFilesResponse = serde_json::from_value(value).map_err(|e| e.to_string())?;
    Ok(parsed.data)
}

/// Extrait un dossier `prefix/` (ex: `overrides/`) directement dans `dir` —
/// même logique best-effort que `extract_into_instance` (une entrée illisible
/// ne doit jamais faire échouer tout l'install), généralisée pour accepter
/// n'importe quel nom de dossier (CurseForge n'a qu'un seul `overrides`,
/// configurable via `manifest.json`, contrairement au overrides/client-overrides
/// fixes de Modrinth).
fn extract_prefixed_dir(bytes: &[u8], prefix_name: &str, dir: &std::path::Path) -> Result<(), String> {
    let cursor = std::io::Cursor::new(bytes);
    let mut archive = zip::ZipArchive::new(cursor).map_err(|e| e.to_string())?;
    let prefix = format!("{}/", prefix_name.trim_matches('/'));

    for i in 0..archive.len() {
        let mut entry = match archive.by_index(i) {
            Ok(e) => e,
            Err(e) => {
                tracing::warn!("[Modpack CF] entrée d'archive #{} illisible : {}", i, e);
                continue;
            }
        };
        let name = entry.name().to_string();
        let Some(rel) = name.strip_prefix(&prefix) else { continue };
        if rel.is_empty() || name.ends_with('/') {
            continue;
        }
        // Archive venue d'ailleurs : pas de `..`, pas de fichier interne du launcher.
        let Some(rel) = safe_relative(rel) else {
            tracing::warn!("[Modpack CF] chemin refusé : {}", name);
            continue;
        };
        let dest = join_relative(dir, &rel);
        if let Some(parent) = dest.parent() {
            if let Err(e) = std::fs::create_dir_all(parent) {
                tracing::warn!("[Modpack CF] création du dossier pour {} échouée : {}", name, e);
                continue;
            }
        }
        let mut out = match std::fs::File::create(&dest) {
            Ok(f) => f,
            Err(e) => {
                tracing::warn!("[Modpack CF] écriture de {} échouée : {}", name, e);
                continue;
            }
        };
        if let Err(e) = std::io::copy(&mut entry, &mut out) {
            tracing::warn!("[Modpack CF] copie de {} échouée : {}", name, e);
        }
    }
    Ok(())
}

/// Les mods que l'archive livre elle-même, dans `<dossier>/mods/`.
///
/// Un pack ne se contente pas de pointer vers Modrinth ou CurseForge : il
/// embarque aussi des jars qu'aucune plateforme n'héberge — builds maison,
/// mods modifiés pour le pack. Ils sont copiés avec le reste des `overrides`,
/// et font partie du pack au même titre que les autres. Les oublier ici les
/// faisait apparaître parmi les mods « ajoutés à la main », d'où un pack
/// installé à l'instant qui montrait déjà du contenu supplémentaire.
///
/// Seulement les jars posés directement dans `mods/` : un sous-dossier n'est
/// pas lu par les loaders, et l'écran des mods ne le liste pas non plus.
fn bundled_mod_names(bytes: &[u8], overrides: &[&str]) -> Vec<String> {
    let Ok(mut archive) = zip::ZipArchive::new(std::io::Cursor::new(bytes)) else { return Vec::new() };
    let prefixes: Vec<String> = overrides.iter().map(|o| format!("{}/mods/", o.trim_matches('/'))).collect();
    let mut names = Vec::new();
    for i in 0..archive.len() {
        let Ok(entry) = archive.by_index_raw(i) else { continue };
        let name = entry.name();
        let Some(file) = prefixes.iter().find_map(|p| name.strip_prefix(p.as_str())) else { continue };
        if file.is_empty() || file.contains('/') || !is_jar_file(file) || names.iter().any(|n| n == file) {
            continue;
        }
        names.push(file.to_string());
    }
    names
}

/// Tous les mods d'un `.mrpack` : ceux que son index fait télécharger, puis
/// ceux qu'il livre dans ses `overrides`. C'est la liste écrite dans
/// `modpack.json`, et celle annoncée à l'écran avant l'installation — une
/// seule fonction pour que les deux ne puissent pas diverger.
fn mrpack_mod_files(bytes: &[u8], index: &ModrinthIndex) -> Vec<String> {
    let mut files: Vec<String> = index
        .files
        .iter()
        .filter(|f| f.path.starts_with("mods/"))
        .filter_map(|f| f.path.rsplit('/').next().map(str::to_string))
        .collect();
    for name in bundled_mod_names(bytes, &["overrides", "client-overrides"]) {
        if !files.contains(&name) {
            files.push(name);
        }
    }
    files
}

/// Annonce, avant le premier téléchargement, les mods que le pack va poser.
///
/// `modpack.json` n'est écrit qu'à la toute fin — c'est lui qui dit au reste
/// du launcher qu'un pack est installé, et un fichier écrit trop tôt
/// décrirait un pack à moitié là si l'installation échouait. Mais l'écran des
/// mods en a besoin tout de suite : sans cette liste, chaque mod du pack
/// apparaissait en vrac parmi les mods ajoutés à la main pendant tout le
/// téléchargement, pour n'être regroupé qu'à la dernière seconde.
fn emit_plan(app: &tauri::AppHandle, instance_id: &str, mod_files: Vec<String>) {
    use tauri::Emitter;
    let _ = app.emit("modpack_install_plan", serde_json::json!({
        "instance_id": instance_id,
        "mod_files": mod_files,
    }));
}

#[allow(clippy::too_many_arguments)]
async fn install_curseforge_pack(
    app: &tauri::AppHandle,
    state: &tauri::State<'_, SharedState>,
    instance_id: &str,
    bytes: Vec<u8>,
    project_id: String,
    version_id: String,
    name: String,
    author: String,
    summary: String,
    icon_url: Option<String>,
    version_number: String,
    downloads: u64,
    date_modified: Option<String>,
    categories: Vec<String>,
) -> Result<ModpackMeta, String> {
    use tauri::Emitter;

    let dir = instance_dir(instance_id);
    tokio::fs::create_dir_all(&dir).await.map_err(|e| e.to_string())?;
    let mods_dir = dir.join("mods");
    tokio::fs::create_dir_all(&mods_dir).await.map_err(|e| e.to_string())?;

    let manifest = {
        let bytes = bytes.clone();
        tokio::task::spawn_blocking(move || read_cf_manifest(&bytes))
            .await
            .map_err(|e| e.to_string())??
    };

    let file_ids: Vec<u64> = manifest.files.iter().map(|f| f.file_id).collect();
    let resolved = resolve_cf_files(state, &file_ids).await?;
    let by_file_id: std::collections::HashMap<u64, CfFileMeta> =
        resolved.into_iter().map(|f| (f.id, f)).collect();

    let client = reqwest::Client::builder()
        .user_agent("YuyuFrame/1.0")
        .build()
        .map_err(|e| e.to_string())?;

    let mut mod_files: Vec<String> = Vec::new();
    let mut failed_files: Vec<String> = Vec::new();
    let total = manifest.files.len();

    // Livrés dans l'archive elle-même : ils font partie du pack eux aussi.
    let bundled = bundled_mod_names(&bytes, &[manifest.overrides.as_str()]);
    emit_plan(
        app,
        instance_id,
        manifest
            .files
            .iter()
            .filter_map(|f| by_file_id.get(&f.file_id))
            .filter_map(|m| std::path::Path::new(&m.file_name).file_name().map(|n| n.to_string_lossy().to_string()))
            .chain(bundled.iter().cloned())
            .collect(),
    );

    for (i, f) in manifest.files.iter().enumerate() {
        let label = by_file_id.get(&f.file_id).map(|m| m.file_name.clone()).unwrap_or_default();
        let _ = app.emit("modpack_install_progress", serde_json::json!({
            "current": i, "total": total, "label": label,
        }));

        let Some(meta) = by_file_id.get(&f.file_id) else {
            tracing::warn!("[Modpack CF] fichier introuvable côté CurseForge : project {} / file {}", f.project_id, f.file_id);
            failed_files.push(format!("{}:{}", f.project_id, f.file_id));
            continue;
        };
        let Some(url) = &meta.download_url else {
            // Auteur ayant désactivé la distribution via l'API tierce (voir
            // curseforge_mod_files côté server/curseforge.rs) — non installable.
            tracing::warn!("[Modpack CF] pas d'URL de téléchargement pour {}", meta.file_name);
            failed_files.push(meta.file_name.clone());
            continue;
        };
        if check_cf_cdn_url(url).is_err() {
            tracing::warn!("[Modpack CF] URL hors CDN CurseForge ignorée pour {} : {}", meta.file_name, url);
            failed_files.push(meta.file_name.clone());
            continue;
        }

        let safe_name = std::path::Path::new(&meta.file_name)
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| format!("{}.jar", meta.mod_id));
        if !is_jar_file(&safe_name) {
            failed_files.push(safe_name);
            continue;
        }

        let resp = match client.get(url).send().await {
            Ok(r) => r,
            Err(e) => {
                tracing::warn!("[Modpack CF] téléchargement de {} échoué : {}", safe_name, e);
                failed_files.push(safe_name);
                continue;
            }
        };
        if !resp.status().is_success() {
            tracing::warn!("[Modpack CF] téléchargement de {} échoué : HTTP {}", safe_name, resp.status());
            failed_files.push(safe_name);
            continue;
        }
        let data = match resp.bytes().await {
            Ok(d) => d,
            Err(e) => {
                tracing::warn!("[Modpack CF] lecture du corps de réponse pour {} échouée : {}", safe_name, e);
                failed_files.push(safe_name);
                continue;
            }
        };
        if let Err(e) = tokio::fs::write(mods_dir.join(&safe_name), &data).await {
            tracing::warn!("[Modpack CF] écriture de {} échouée : {}", safe_name, e);
            failed_files.push(safe_name);
            continue;
        }
        mod_files.push(safe_name);
    }

    let _ = app.emit("modpack_install_progress", serde_json::json!({
        "current": total, "total": total, "label": "Finalisation...",
    }));

    let overrides_name = manifest.overrides.clone();
    let dir_clone = dir.clone();
    let bytes_clone = bytes.clone();
    tokio::task::spawn_blocking(move || extract_prefixed_dir(&bytes_clone, &overrides_name, &dir_clone))
        .await
        .map_err(|e| e.to_string())??;

    for name in bundled {
        if !mod_files.contains(&name) {
            mod_files.push(name);
        }
    }

    let meta = ModpackMeta {
        project_id,
        version_id,
        name: if name.trim().is_empty() { manifest.name.clone() } else { name },
        author: if author.trim().is_empty() { manifest.author.clone() } else { author },
        summary,
        icon_url,
        version_number: if version_number.trim().is_empty() { manifest.version.clone() } else { version_number },
        downloads,
        date_modified,
        categories,
        mod_files,
        failed_files,
    };

    let json = serde_json::to_string_pretty(&meta).map_err(|e| e.to_string())?;
    tokio::fs::write(dir.join("modpack.json"), json)
        .await
        .map_err(|e| e.to_string())?;

    Ok(meta)
}

/// Aperçu d'un pack CurseForge avant install (ModpackDetailModal côté
/// frontend) — version MC, loader, et noms des mods référencés. Résout les
/// noms de fichiers via le même batch que l'installation réelle plutôt que de
/// n'afficher que des ids CurseForge illisibles.
#[tauri::command]
pub async fn modpack_fetch_curseforge_index(
    state: tauri::State<'_, SharedState>,
    file_url: String,
) -> Result<ModpackIndexInfo, String> {
    check_cf_cdn_url(&file_url)?;
    let client = reqwest::Client::builder()
        .user_agent("YuyuFrame/1.0")
        .build()
        .map_err(|e| e.to_string())?;
    let resp = client.get(&file_url).send().await.map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("Téléchargement échoué: {}", resp.status()));
    }
    let bytes = resp.bytes().await.map_err(|e| e.to_string())?.to_vec();

    let manifest = tokio::task::spawn_blocking(move || read_cf_manifest(&bytes))
        .await
        .map_err(|e| e.to_string())??;

    let file_ids: Vec<u64> = manifest.files.iter().map(|f| f.file_id).collect();
    let resolved = resolve_cf_files(&state, &file_ids).await?;
    let mods = resolved.into_iter().map(|f| f.file_name).collect();

    Ok(ModpackIndexInfo {
        mc_version: if manifest.minecraft.version.is_empty() { None } else { Some(manifest.minecraft.version.clone()) },
        loader: loader_from_cf_manifest(&manifest.minecraft),
        mods,
    })
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModpackInstallCurseforgeInput {
    pub instance_id: String,
    pub file_url: String,
    pub mod_id: u64,
    pub file_id: u64,
    pub name: String,
    pub author: String,
    pub summary: String,
    pub icon_url: Option<String>,
    pub version_number: String,
    pub downloads: u64,
    pub date_modified: Option<String>,
    pub categories: Vec<String>,
}

/// Installe un modpack CurseForge trouvé via la recherche in-app (mêmes
/// principes que `modpack_install` côté Modrinth : télécharge le zip du pack
/// lui-même, puis `install_curseforge_pack` s'occupe de résoudre et
/// télécharger chaque mod référencé par son `manifest.json`.
#[tauri::command]
pub async fn modpack_install_curseforge(
    app: tauri::AppHandle,
    state: tauri::State<'_, SharedState>,
    input: ModpackInstallCurseforgeInput,
) -> Result<ModpackMeta, String> {
    check_cf_cdn_url(&input.file_url)?;
    let client = reqwest::Client::builder()
        .user_agent("YuyuFrame/1.0")
        .build()
        .map_err(|e| e.to_string())?;
    let resp = client.get(&input.file_url).send().await.map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("Téléchargement échoué: {}", resp.status()));
    }
    let bytes = resp.bytes().await.map_err(|e| e.to_string())?.to_vec();

    install_curseforge_pack(
        &app, &state, &input.instance_id, bytes,
        input.mod_id.to_string(), input.file_id.to_string(), input.name, input.author, input.summary,
        input.icon_url, input.version_number, input.downloads, input.date_modified, input.categories,
    ).await
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModpackInstallInput {
    pub instance_id: String,
    pub file_url: String,
    pub project_id: String,
    pub version_id: String,
    pub name: String,
    pub author: String,
    pub summary: String,
    pub icon_url: Option<String>,
    pub version_number: String,
    pub downloads: u64,
    pub date_modified: Option<String>,
    pub categories: Vec<String>,
}

/// Extrait `overrides/`/`client-overrides/` puis retourne les noms de fichiers
/// `mods/` du pack. Chaque entrée est best-effort (log + on passe à la
/// suivante) plutôt que `?` — avant, un seul fichier "overrides" problématique
/// (nom invalide sous Windows, chemin bizarre généré par un outil tiers...)
/// faisait échouer TOUT l'install avec `Err`, alors que les mods eux-mêmes
/// étaient déjà téléchargés avec succès juste avant (voir `install_pack`) et
/// que `modpack.json` n'était donc jamais écrit : le pack "s'installait"
/// (mods présents) mais n'était plus jamais reconnu comme modpack ensuite.
/// Même philosophie que le téléchargement des mods juste au-dessus, qui gère
/// déjà ses échecs individuels via `failed_files` sans jamais tout annuler.
fn extract_into_instance(bytes: &[u8], dir: &std::path::Path) -> Result<Vec<String>, String> {
    let cursor = std::io::Cursor::new(bytes);
    let mut archive = zip::ZipArchive::new(cursor).map_err(|e| e.to_string())?;

    // overrides/ d'abord, client-overrides/ ensuite pour qu'il ait la priorité.
    for prefix in ["overrides/", "client-overrides/"] {
        for i in 0..archive.len() {
            let mut entry = match archive.by_index(i) {
                Ok(e) => e,
                Err(e) => {
                    tracing::warn!("[Modpack] entrée d'archive #{} illisible : {}", i, e);
                    continue;
                }
            };
            let name = entry.name().to_string();
            let Some(rel) = name.strip_prefix(prefix) else { continue };
            if rel.is_empty() || name.ends_with('/') {
                continue;
            }
            let Some(rel) = safe_relative(rel) else {
                tracing::warn!("[Modpack] chemin refusé : {}", name);
                continue;
            };
            let dest = join_relative(dir, &rel);
            if let Some(parent) = dest.parent() {
                if let Err(e) = std::fs::create_dir_all(parent) {
                    tracing::warn!("[Modpack] création du dossier pour {} échouée : {}", name, e);
                    continue;
                }
            }
            let mut out = match std::fs::File::create(&dest) {
                Ok(f) => f,
                Err(e) => {
                    tracing::warn!("[Modpack] écriture de {} échouée : {}", name, e);
                    continue;
                }
            };
            if let Err(e) = std::io::copy(&mut entry, &mut out) {
                tracing::warn!("[Modpack] copie de {} échouée : {}", name, e);
            }
        }
    }

    let index = read_index(bytes)?;
    Ok(mrpack_mod_files(bytes, &index))
}

#[tauri::command]
pub async fn modpack_install(app: tauri::AppHandle, input: ModpackInstallInput) -> Result<ModpackMeta, String> {
    let bytes = download_mrpack(&input.file_url).await?;
    install_pack(
        &app, &input.instance_id, bytes,
        input.project_id, input.version_id, input.name, input.author, input.summary,
        input.icon_url, input.version_number, input.downloads, input.date_modified, input.categories,
    ).await
}

/// Résultat d'un import local : `Structured` pour un pack reconnu (Modrinth/
/// CurseForge, avec bannière + métadonnées comme un install depuis la
/// recherche), `Generic` pour toute autre structure (pack "classique" avec
/// `mods/`/`config/` directement à la racine, instance complète exportée,
/// etc.) — dans ce cas il n'y a ni id de projet ni version à afficher, juste
/// un nombre de fichiers extraits, comme un import de dossier classique.
#[derive(Serialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum ModpackImportResult {
    Structured { meta: ModpackMeta },
    Generic { imported: usize, failed: usize },
}

fn zip_has_entry(bytes: &[u8], name: &str) -> bool {
    let cursor = std::io::Cursor::new(bytes);
    match zip::ZipArchive::new(cursor) {
        Ok(mut archive) => archive.by_name(name).is_ok(),
        Err(_) => false,
    }
}

/// Structure la plus répandue en dehors des formats Modrinth/CurseForge : un
/// zip avec `mods/`/`config/`/etc. directement à sa racine ("pack classique"),
/// éventuellement enveloppé dans un unique dossier parent (export "instance
/// complète" fait à la main), ou séparé en `client/`+`server/` (on ne garde
/// que `client/`, ce launcher ne lance jamais de serveur). Pas de téléchargement
/// réseau : tout le contenu est déjà dans le zip, on l'extrait tel quel dans
/// l'instance, best-effort fichier par fichier comme les autres extractions.
fn extract_generic_pack(bytes: &[u8], dir: &std::path::Path) -> Result<(usize, usize), String> {
    let cursor = std::io::Cursor::new(bytes);
    let mut archive = zip::ZipArchive::new(cursor).map_err(|e| e.to_string())?;

    // Dossier racine effectif : soit un couple client/+server/ (on ne garde que
    // client/), soit un unique dossier enveloppant tout le reste, soit rien.
    let names: Vec<String> = (0..archive.len())
        .filter_map(|i| archive.by_index(i).ok().map(|e| e.name().to_string()))
        .collect();
    let has_client_server = names.iter().any(|n| n.starts_with("client/"))
        && names.iter().any(|n| n.starts_with("server/"));
    let effective_root: String = if has_client_server {
        "client/".to_string()
    } else {
        let top_level: std::collections::HashSet<&str> = names
            .iter()
            .filter_map(|n| n.split('/').next())
            .filter(|s| !s.is_empty())
            .collect();
        if top_level.len() == 1 && names.iter().all(|n| n.starts_with(&format!("{}/", top_level.iter().next().unwrap()))) {
            format!("{}/", top_level.into_iter().next().unwrap())
        } else {
            String::new()
        }
    };

    let mut imported = 0usize;
    let mut failed = 0usize;
    for i in 0..archive.len() {
        let mut entry = match archive.by_index(i) {
            Ok(e) => e,
            Err(e) => {
                tracing::warn!("[Modpack générique] entrée d'archive #{} illisible : {}", i, e);
                failed += 1;
                continue;
            }
        };
        let name = entry.name().to_string();
        let rel = if effective_root.is_empty() {
            name.as_str()
        } else {
            match name.strip_prefix(effective_root.as_str()) {
                Some(r) => r,
                None => continue, // hors racine effective (ex: server/ quand on garde client/)
            }
        };
        if rel.is_empty() || name.ends_with('/') {
            continue;
        }
        let Some(rel) = safe_relative(rel) else {
            tracing::warn!("[Modpack générique] chemin refusé : {}", name);
            failed += 1;
            continue;
        };
        let dest = join_relative(dir, &rel);
        if let Some(parent) = dest.parent() {
            if let Err(e) = std::fs::create_dir_all(parent) {
                tracing::warn!("[Modpack générique] création du dossier pour {} échouée : {}", name, e);
                failed += 1;
                continue;
            }
        }
        let mut out = match std::fs::File::create(&dest) {
            Ok(f) => f,
            Err(e) => {
                tracing::warn!("[Modpack générique] écriture de {} échouée : {}", name, e);
                failed += 1;
                continue;
            }
        };
        if let Err(e) = std::io::copy(&mut entry, &mut out) {
            tracing::warn!("[Modpack générique] copie de {} échouée : {}", name, e);
            failed += 1;
            continue;
        }
        imported += 1;
    }
    Ok((imported, failed))
}

/// Importe un pack déjà présent sur disque, quelle que soit sa structure —
/// détectée automatiquement plutôt que de supposer un `.mrpack` Modrinth :
/// 1. `modrinth.index.json` à la racine → pack Modrinth (comme avant).
/// 2. sinon `manifest.json` à la racine → pack CurseForge (`files` +
///    `overrides/`, résolu via l'API CurseForge comme un install depuis la
///    recherche).
/// 3. sinon structure générique (pack "classique" mods/config/ à la racine,
///    instance complète exportée, client/+server/ séparés...) — tout est déjà
///    dans le zip, extrait tel quel sans aucun appel réseau.
/// Pour 1 et 2 : pas de métadonnées de recherche disponibles (project_id/
/// author/downloads...), donc `name`/`summary`/`version` sont repris du
/// fichier d'index lui-même (best-effort, souvent minimal) plutôt que de
/// l'API — même logique déjà en place pour Modrinth avant cette généralisation.
#[tauri::command]
pub async fn modpack_install_from_path(
    app: tauri::AppHandle,
    state: tauri::State<'_, SharedState>,
    instance_id: String,
    file_path: String,
) -> Result<ModpackImportResult, String> {
    let bytes = tokio::fs::read(&file_path).await.map_err(|e| format!("Lecture du fichier : {}", e))?;
    let file_stem = std::path::Path::new(&file_path)
        .file_stem()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "Modpack importé".to_string());

    let is_modrinth = {
        let bytes = bytes.clone();
        tokio::task::spawn_blocking(move || zip_has_entry(&bytes, "modrinth.index.json"))
            .await
            .map_err(|e| e.to_string())?
    };
    if is_modrinth {
        let index = {
            let bytes = bytes.clone();
            tokio::task::spawn_blocking(move || read_index(&bytes))
                .await
                .map_err(|e| e.to_string())??
        };
        let name = if index.name.trim().is_empty() { file_stem } else { index.name.clone() };
        let meta = install_pack(
            &app, &instance_id, bytes,
            String::new(), index.version_id.clone(), name,
            "Import local".to_string(), index.summary.clone(), None,
            index.version_id, 0, None, Vec::new(),
        ).await?;
        return Ok(ModpackImportResult::Structured { meta });
    }

    let is_curseforge = {
        let bytes = bytes.clone();
        tokio::task::spawn_blocking(move || zip_has_entry(&bytes, "manifest.json"))
            .await
            .map_err(|e| e.to_string())?
    };
    if is_curseforge {
        let manifest = {
            let bytes = bytes.clone();
            tokio::task::spawn_blocking(move || read_cf_manifest(&bytes))
                .await
                .map_err(|e| e.to_string())?
        };
        // manifest.json existe mais ne correspond pas au schéma CurseForge attendu
        // (ex: un manifest maison d'un autre outil) → repli sur la structure générique.
        if let Ok(manifest) = manifest {
            let name = if manifest.name.trim().is_empty() { file_stem } else { manifest.name.clone() };
            let author = manifest.author.clone();
            let version = manifest.version.clone();
            let meta = install_curseforge_pack(
                &app, &state, &instance_id, bytes,
                String::new(), String::new(), name, author, String::new(), None, version,
                0, None, Vec::new(),
            ).await?;
            return Ok(ModpackImportResult::Structured { meta });
        }
    }

    let dir = instance_dir(&instance_id);
    tokio::fs::create_dir_all(&dir).await.map_err(|e| e.to_string())?;
    let dir_clone = dir.clone();
    let (imported, failed) = tokio::task::spawn_blocking(move || extract_generic_pack(&bytes, &dir_clone))
        .await
        .map_err(|e| e.to_string())??;
    Ok(ModpackImportResult::Generic { imported, failed })
}

#[allow(clippy::too_many_arguments)]
async fn install_pack(
    app: &tauri::AppHandle,
    instance_id: &str,
    bytes: Vec<u8>,
    project_id: String,
    version_id: String,
    name: String,
    author: String,
    summary: String,
    icon_url: Option<String>,
    version_number: String,
    downloads: u64,
    date_modified: Option<String>,
    categories: Vec<String>,
) -> Result<ModpackMeta, String> {
    use tauri::Emitter;

    let dir = instance_dir(instance_id);
    tokio::fs::create_dir_all(&dir).await.map_err(|e| e.to_string())?;

    // Remplacement d'un pack précédent : on restaure d'abord les extras qu'il
    // avait éventuellement mis de côté, avant de recalculer les conflits avec
    // le nouveau pack (sinon ils restent piégés dans le dossier de backup).
    {
        let dir_clone = dir.clone();
        tokio::task::spawn_blocking(move || restore_backup(&dir_clone))
            .await
            .map_err(|e| e.to_string())?;
    }

    let index = {
        let bytes = bytes.clone();
        tokio::task::spawn_blocking(move || read_index(&bytes))
            .await
            .map_err(|e| e.to_string())??
    };

    // Snapshot des mods déjà présents (extras + ancien pack le cas échéant) pour
    // détecter les doublons avec ce que le pack va installer (ex: 2x Fabric API).
    let mods_dir = dir.join("mods");
    let existing_ids = collect_mod_ids(&mods_dir);

    // Téléchargement des fichiers référencés (mods, resourcepacks, shaderpacks...).
    let client = reqwest::Client::builder()
        .user_agent("YuyuFrame/1.0")
        .build()
        .map_err(|e| e.to_string())?;
    let mut backed_up: Vec<String> = Vec::new();
    let mut failed_files: Vec<String> = Vec::new();
    let total_files = index.files.len();
    // La même liste que celle écrite dans `modpack.json` à la fin (voir
    // `extract_into_instance`) : ce que l'écran regroupe pendant
    // l'installation est ce qui restera regroupé après.
    emit_plan(app, instance_id, mrpack_mod_files(&bytes, &index));
    for (i, file) in index.files.iter().enumerate() {
        let _ = app.emit("modpack_install_progress", serde_json::json!({
            "current": i,
            "total": total_files,
            "label": file.path.rsplit('/').next().unwrap_or(&file.path),
        }));

        let Some(url) = file.downloads.first() else {
            tracing::warn!("[Modpack] aucune URL de téléchargement pour {}", file.path);
            failed_files.push(file.path.clone());
            continue;
        };
        if check_modrinth_url(url).is_err() {
            tracing::warn!("[Modpack] URL non-Modrinth ignorée pour {} : {}", file.path, url);
            failed_files.push(file.path.clone());
            continue;
        }
        let Some(rel) = safe_relative(&file.path) else {
            tracing::warn!("[Modpack] chemin refusé : {}", file.path);
            failed_files.push(file.path.clone());
            continue;
        };
        let dest = join_relative(&dir, &rel);
        if let Some(parent) = dest.parent() {
            tokio::fs::create_dir_all(parent).await.map_err(|e| e.to_string())?;
        }
        let resp = match client.get(url).send().await {
            Ok(r) => r,
            Err(e) => {
                tracing::warn!("[Modpack] téléchargement de {} échoué : {}", file.path, e);
                failed_files.push(file.path.clone());
                continue;
            }
        };
        if !resp.status().is_success() {
            tracing::warn!("[Modpack] téléchargement de {} échoué : HTTP {}", file.path, resp.status());
            failed_files.push(file.path.clone());
            continue;
        }
        let data = match resp.bytes().await {
            Ok(d) => d,
            Err(e) => {
                tracing::warn!("[Modpack] lecture du corps de réponse pour {} échouée : {}", file.path, e);
                failed_files.push(file.path.clone());
                continue;
            }
        };
        if let Err(e) = tokio::fs::write(&dest, &data).await {
            tracing::warn!("[Modpack] écriture de {} échouée : {}", file.path, e);
            failed_files.push(file.path.clone());
            continue;
        }

        if file.path.starts_with("mods/") {
            if let Some(new_meta) = read_fabric_mod_json(&dest) {
                if let Some(existing_path) = existing_ids.get(&new_meta.id) {
                    if existing_path != &dest {
                        // Conflit (ex: 2x Fabric API) : on déplace l'extra en
                        // backup plutôt que de le supprimer, pour pouvoir le
                        // réinstaller au retrait/remplacement du pack.
                        if let Some(filename) = existing_path.file_name().map(|f| f.to_string_lossy().to_string()) {
                            let bdir = backup_dir(&mods_dir);
                            if std::fs::create_dir_all(&bdir).is_ok() {
                                let bdest = bdir.join(&filename);
                                let _ = std::fs::remove_file(&bdest);
                                if std::fs::rename(existing_path, &bdest).is_ok() {
                                    backed_up.push(filename);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    let _ = app.emit("modpack_install_progress", serde_json::json!({
        "current": total_files,
        "total": total_files,
        "label": "Finalisation...",
    }));

    if !backed_up.is_empty() {
        let json = serde_json::to_string_pretty(&ModpackBackup { files: backed_up }).map_err(|e| e.to_string())?;
        tokio::fs::write(backup_json_path(&dir), json).await.map_err(|e| e.to_string())?;
    }

    let dir_clone = dir.clone();
    let bytes_clone = bytes.clone();
    let mod_files = tokio::task::spawn_blocking(move || extract_into_instance(&bytes_clone, &dir_clone))
        .await
        .map_err(|e| e.to_string())??;

    let meta = ModpackMeta {
        project_id,
        version_id,
        name,
        author,
        summary,
        icon_url,
        version_number,
        downloads,
        date_modified,
        categories,
        mod_files,
        failed_files,
    };

    let json = serde_json::to_string_pretty(&meta).map_err(|e| e.to_string())?;
    tokio::fs::write(dir.join("modpack.json"), json)
        .await
        .map_err(|e| e.to_string())?;

    Ok(meta)
}

/// Retire le modpack de l'instance : désinstalle les mods listés dans
/// `modpack.json` (et leur variante `.disabled`), restaure les extras mis de
/// côté lors d'un conflit, puis supprime les fichiers de métadonnées.
/// Le contenu supplémentaire jamais entré en conflit n'est pas touché.
#[tauri::command]
pub async fn modpack_remove(instance_id: String) -> Result<(), String> {
    let dir = instance_dir(&instance_id);
    let path = dir.join("modpack.json");
    if path.exists() {
        if let Ok(json) = tokio::fs::read_to_string(&path).await {
            if let Ok(meta) = serde_json::from_str::<ModpackMeta>(&json) {
                let mods_dir = dir.join("mods");
                for file in &meta.mod_files {
                    let _ = tokio::fs::remove_file(mods_dir.join(file)).await;
                    let _ = tokio::fs::remove_file(mods_dir.join(format!("{}.disabled", file))).await;
                }
            }
        }
        tokio::fs::remove_file(&path).await.map_err(|e| e.to_string())?;
    }

    let dir_clone = dir.clone();
    tokio::task::spawn_blocking(move || restore_backup(&dir_clone))
        .await
        .map_err(|e| e.to_string())?;

    Ok(())
}

/// Met à jour la référence d'un fichier dans `modpack.json` quand un mod du
/// pack est mis à jour (le nouveau fichier peut avoir un nom différent).
/// No-op si `old_name` n'appartenait pas au pack.
#[tauri::command]
pub async fn modpack_rename_file(
    instance_id: String,
    old_name: String,
    new_name: String,
) -> Result<Option<ModpackMeta>, String> {
    let path = instance_dir(&instance_id).join("modpack.json");
    if !path.exists() {
        return Ok(None);
    }
    let json = tokio::fs::read_to_string(&path).await.map_err(|e| e.to_string())?;
    let mut meta: ModpackMeta = serde_json::from_str(&json).map_err(|e| e.to_string())?;

    let Some(pos) = meta.mod_files.iter().position(|f| f == &old_name) else {
        return Ok(Some(meta));
    };
    if old_name != new_name {
        meta.mod_files[pos] = new_name;
        let json = serde_json::to_string_pretty(&meta).map_err(|e| e.to_string())?;
        tokio::fs::write(&path, json).await.map_err(|e| e.to_string())?;
    }
    Ok(Some(meta))
}

#[tauri::command]
pub async fn modpack_get_meta(instance_id: String) -> Result<Option<ModpackMeta>, String> {
    let path = instance_dir(&instance_id).join("modpack.json");
    if !path.exists() {
        return Ok(None);
    }
    let json = tokio::fs::read_to_string(&path).await.map_err(|e| e.to_string())?;
    serde_json::from_str(&json).map(Some).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    /// Une archive de pack en mémoire : des entrées vides suffisent, seuls
    /// les noms comptent ici.
    fn archive(entries: &[(&str, &str)]) -> Vec<u8> {
        let mut zip = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        for (name, content) in entries {
            zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(content.as_bytes()).unwrap();
        }
        zip.finish().unwrap().into_inner()
    }

    const INDEX: &str = r#"{
        "name": "Pack", "versionId": "1.0",
        "files": [
            { "path": "mods/sodium.jar", "downloads": ["https://cdn.modrinth.com/a"] },
            { "path": "mods/lithium.jar", "downloads": ["https://cdn.modrinth.com/b"] },
            { "path": "resourcepacks/faithful.zip", "downloads": ["https://cdn.modrinth.com/c"] }
        ]
    }"#;

    /// Le cas qui faisait apparaître du « contenu supplémentaire » sur un
    /// pack tout juste installé : des jars livrés dans l'archive elle-même.
    #[test]
    fn les_mods_livres_dans_les_overrides_font_partie_du_pack() {
        let bytes = archive(&[
            ("modrinth.index.json", INDEX),
            ("overrides/mods/ardabrowser-1.0.0.jar", ""),
            ("overrides/mods/create-fabric-custom.jar.disabled", ""),
            ("client-overrides/mods/fabricskyboxes-custom.jar", ""),
            ("overrides/config/sodium-options.json", ""),
        ]);
        let index = read_index(&bytes).unwrap();
        assert_eq!(
            mrpack_mod_files(&bytes, &index),
            vec![
                "sodium.jar",
                "lithium.jar",
                "ardabrowser-1.0.0.jar",
                "create-fabric-custom.jar.disabled",
                "fabricskyboxes-custom.jar",
            ]
        );
    }

    #[test]
    fn seuls_les_jars_poses_directement_dans_mods_comptent() {
        let bytes = archive(&[
            ("overrides/mods/vrai.jar", ""),
            // Un sous-dossier n'est pas lu par les loaders.
            ("overrides/mods/sous-dossier/ignore.jar", ""),
            ("overrides/mods/notes.txt", ""),
            ("overrides/mods/", ""),
            // Ni un `mods/` ailleurs que dans les overrides demandés.
            ("autre/mods/ailleurs.jar", ""),
            ("overrides/resourcepacks/pack.jar", ""),
        ]);
        assert_eq!(bundled_mod_names(&bytes, &["overrides", "client-overrides"]), vec!["vrai.jar"]);
        // CurseForge nomme son dossier comme il veut, parfois avec un `/`.
        assert_eq!(bundled_mod_names(&bytes, &["autre/"]), vec!["ailleurs.jar"]);
    }

    /// Un mod à la fois dans l'index et dans les overrides n'est listé qu'une
    /// fois, et une archive illisible ne fait rien échouer.
    #[test]
    fn pas_de_doublon_ni_d_echec_sur_une_archive_illisible() {
        let bytes = archive(&[("modrinth.index.json", INDEX), ("overrides/mods/sodium.jar", "")]);
        let index = read_index(&bytes).unwrap();
        assert_eq!(mrpack_mod_files(&bytes, &index), vec!["sodium.jar", "lithium.jar"]);
        assert!(bundled_mod_names(b"pas un zip", &["overrides"]).is_empty());
    }
}
