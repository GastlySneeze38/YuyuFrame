use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::Write;
use std::path::PathBuf;

use super::super::instance::mods::sha1_cached;

pub(super) use crate::commands::api_base;

// ── Types exposés au frontend ──────────────────────────────────────────────────

#[derive(Serialize, Deserialize, Clone)]
pub struct SyncInstance {
    pub id: i64,
    pub instance_name: String,
    pub mc_version: String,
    pub loader: String,
    pub ram_mb: u32,
    pub save_count: u32,
    pub save_names: Vec<String>,
    pub has_data: bool,
    pub updated_at: i64,
}

#[derive(Serialize, Clone)]
pub struct SaveInfo {
    pub name: String,
    pub updated_at: i64,
    pub size_bytes: u64,
}

#[derive(Serialize, Clone)]
pub struct SyncProgressEvent {
    pub phase: String, // "resolving_mods"|"compressing"|"uploading"|"downloading"|"installing_mods"|"done"
    pub percent: u8,
    pub label: String,
}

// ── Manifest de mods ───────────────────────────────────────────────────────────

#[derive(Serialize, Deserialize, Clone)]
pub struct ModrinthRef {
    pub project_id: String,
    pub version_id: String,
    pub download_url: String,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct ModManifestEntry {
    /// Nom propre du fichier (sans le suffixe .disabled)
    pub filename: String,
    pub sha1: String,
    pub enabled: bool,
    /// None = mod non trouvé sur Modrinth (inclus dans le ZIP)
    pub modrinth: Option<ModrinthRef>,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct ModpackRef {
    pub project_id: String,
    pub version_id: String,
    pub name: String,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct ModManifest {
    pub format_version: u32,
    pub mc_version: String,
    pub loader: String,
    pub modpack: Option<ModpackRef>,
    pub mods: Vec<ModManifestEntry>,
}

// ── Auth helpers ───────────────────────────────────────────────────────────────

pub(super) fn get_token(state: &crate::state::AppState) -> Result<String, String> {
    state
        .yuyu_session
        .as_ref()
        .map(|s| s.token.clone())
        .ok_or_else(|| "Non connecté à YuyuFrame".into())
}

pub(super) fn require_premium(state: &crate::state::AppState) -> Result<(), String> {
    let session = state
        .yuyu_session
        .as_ref()
        .ok_or_else(|| String::from("Non connecté à YuyuFrame"))?;
    if !session.is_premium() {
        return Err(String::from("Abonnement Premium requis pour la synchronisation"));
    }
    Ok(())
}

#[allow(dead_code)]
pub(super) fn require_ultimate(state: &crate::state::AppState) -> Result<(), String> {
    let session = state
        .yuyu_session
        .as_ref()
        .ok_or_else(|| String::from("Non connecté à YuyuFrame"))?;
    if !session.is_ultimate() {
        return Err(String::from("Abonnement Ultimate requis pour cette fonctionnalité"));
    }
    Ok(())
}

// ── Save listing ───────────────────────────────────────────────────────────────

pub(super) fn dir_size(path: &PathBuf) -> u64 {
    let mut size = 0u64;
    if let Ok(entries) = std::fs::read_dir(path) {
        for entry in entries.flatten() {
            let p = entry.path();
            if p.is_file() {
                size += std::fs::metadata(&p).map(|m| m.len()).unwrap_or(0);
            } else if p.is_dir() {
                size += dir_size(&p);
            }
        }
    }
    size
}

// ── Mod manifest helpers ───────────────────────────────────────────────────────

/// Lit le modpack.json de l'instance pour récupérer les infos Modrinth du pack.
pub(super) fn read_modpack_ref(inst_dir: &PathBuf) -> Option<ModpackRef> {
    let json = std::fs::read_to_string(inst_dir.join("modpack.json")).ok()?;
    #[derive(Deserialize)]
    struct PackMeta { project_id: String, version_id: String, name: String }
    let m: PackMeta = serde_json::from_str(&json).ok()?;
    Some(ModpackRef { project_id: m.project_id, version_id: m.version_id, name: m.name })
}

/// Liste tous les mods d'une instance.
/// Retourne (nom_propre_sans_disabled, sha1, enabled).
pub(super) fn list_mods_raw(mods_dir: &PathBuf) -> Vec<(String, String, bool)> {
    let Ok(entries) = std::fs::read_dir(mods_dir) else { return vec![] };
    entries
        .flatten()
        .filter_map(|e| {
            let path = e.path();
            let raw = path.file_name()?.to_str()?.to_string();
            let enabled = raw.ends_with(".jar");
            let disabled = raw.ends_with(".jar.disabled");
            if !enabled && !disabled { return None; }
            let clean = if disabled {
                raw.trim_end_matches(".disabled").to_string()
            } else {
                raw.clone()
            };
            let sha1 = sha1_cached(&path);
            Some((clean, sha1, enabled))
        })
        .collect()
}

/// Batch lookup Modrinth par SHA1.
/// Retourne un map sha1 → (project_id, version_id, download_url).
pub(super) async fn modrinth_lookup_batch(
    client: &reqwest::Client,
    sha1s: &[String],
) -> HashMap<String, (String, String, String)> {
    if sha1s.is_empty() {
        return HashMap::new();
    }
    let resp = client
        .post("https://api.modrinth.com/v2/version_files")
        .header("User-Agent", "YuyuFrame/1.0")
        .json(&serde_json::json!({ "hashes": sha1s, "algorithm": "sha1" }))
        .send()
        .await;

    let Ok(resp) = resp else { return HashMap::new() };
    if !resp.status().is_success() { return HashMap::new(); }
    let Ok(json) = resp.json::<serde_json::Value>().await else { return HashMap::new() };
    let Some(obj) = json.as_object() else { return HashMap::new() };

    let mut result = HashMap::new();
    for (hash, version) in obj {
        let Some(project_id) = version["project_id"].as_str() else { continue };
        let Some(version_id) = version["id"].as_str() else { continue };
        let files = version["files"].as_array();
        let download_url = files
            .and_then(|f| f.iter().find(|file| file["primary"].as_bool().unwrap_or(false)))
            .or_else(|| files.and_then(|f| f.first()))
            .and_then(|f| f["url"].as_str());
        let Some(url) = download_url else { continue };
        // Sécurité : on n'accepte que les URLs Modrinth CDN
        if !url.starts_with("https://cdn.modrinth.com/") { continue; }
        result.insert(
            hash.clone(),
            (project_id.to_string(), version_id.to_string(), url.to_string()),
        );
    }
    result
}

// ── ZIP helpers ────────────────────────────────────────────────────────────────

pub(super) fn collect_files(
    dir: &PathBuf,
    base: &PathBuf,
    prefix: &str,
    out: &mut Vec<(PathBuf, String)>,
) -> Result<(), String> {
    for entry in std::fs::read_dir(dir).map_err(|e| e.to_string())? {
        let entry = entry.map_err(|e| e.to_string())?;
        let path = entry.path();
        let relative = path
            .strip_prefix(base)
            .map_err(|e| e.to_string())?
            .to_string_lossy()
            .replace('\\', "/");
        let zip_path = format!("{}/{}", prefix, relative);

        if path.is_dir() {
            out.push((path.clone(), format!("{}/", zip_path)));
            collect_files(&path, base, prefix, out)?;
        } else {
            out.push((path, zip_path));
        }
    }
    Ok(())
}

pub(super) fn build_instance_zip_with_progress(
    inst_dir: PathBuf,
    save_names: Vec<String>,
    manifest: ModManifest,
    tx: tokio::sync::mpsc::Sender<SyncProgressEvent>,
) -> Result<Vec<u8>, String> {
    use std::io::Cursor;
    use zip::write::SimpleFileOptions;

    // Mods non-Modrinth (à inclure dans le ZIP tel quel)
    let manual_filenames: std::collections::HashSet<String> = manifest.mods.iter()
        .filter(|m| m.modrinth.is_none())
        .map(|m| m.filename.clone())
        .collect();

    let mut files: Vec<(PathBuf, String)> = Vec::new();

    // Inclure les mods manuels (avec leur suffixe .disabled si nécessaire)
    let mods_dir = inst_dir.join("mods");
    if mods_dir.is_dir() {
        if let Ok(entries) = std::fs::read_dir(&mods_dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                let raw = path.file_name().unwrap_or_default().to_string_lossy().to_string();
                let clean = raw.trim_end_matches(".disabled").to_string();
                if manual_filenames.contains(&clean) {
                    files.push((path, format!("mods/{}", raw)));
                }
            }
        }
    }

    // config/
    let config_dir = inst_dir.join("config");
    if config_dir.is_dir() {
        collect_files(&config_dir, &config_dir, "config", &mut files)?;
    }

    // saves sélectionnées
    for save_name in save_names.iter().take(3) {
        let save_dir = inst_dir.join("saves").join(save_name);
        if save_dir.is_dir() {
            let prefix = format!("saves/{}", save_name);
            collect_files(&save_dir, &save_dir, &prefix, &mut files)?;
        }
    }

    let total = files.len() + 1; // +1 pour mods.json
    let _ = tx.blocking_send(SyncProgressEvent {
        phase: "compressing".into(),
        percent: 0,
        label: "Compression...".into(),
    });

    let cursor = Cursor::new(Vec::new());
    let mut zip = zip::ZipWriter::new(cursor);
    let options = SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);

    // mods.json en premier
    let manifest_json = serde_json::to_string_pretty(&manifest).map_err(|e| e.to_string())?;
    zip.start_file("mods.json", options).map_err(|e| e.to_string())?;
    zip.write_all(manifest_json.as_bytes()).map_err(|e| e.to_string())?;

    for (i, (file_path, zip_path)) in files.iter().enumerate() {
        if file_path.is_dir() {
            zip.add_directory(zip_path, options).map_err(|e| e.to_string())?;
        } else {
            zip.start_file(zip_path, options).map_err(|e| e.to_string())?;
            let data = std::fs::read(file_path).map_err(|e| e.to_string())?;
            zip.write_all(&data).map_err(|e| e.to_string())?;
        }
        let percent = ((i + 2) * 50 / total) as u8;
        let _ = tx.blocking_send(SyncProgressEvent {
            phase: "compressing".into(),
            percent: percent.min(50),
            label: format!("Compression... {}/{} fichiers", i + 2, total),
        });
    }

    let cursor = zip.finish().map_err(|e| e.to_string())?;
    Ok(cursor.into_inner())
}

pub(super) fn extract_zip_to_instance(zip_bytes: Vec<u8>, inst_dir: PathBuf) -> Result<(), String> {
    use std::io::{Cursor, Read};
    let cursor = Cursor::new(zip_bytes);
    let mut archive = zip::ZipArchive::new(cursor).map_err(|e| e.to_string())?;

    for i in 0..archive.len() {
        let mut file = archive.by_index(i).map_err(|e| e.to_string())?;
        let file_name = file.name().to_string();

        if file_name.contains("..") {
            continue;
        }

        let out_path = inst_dir.join(&file_name);

        if file_name.ends_with('/') {
            std::fs::create_dir_all(&out_path).map_err(|e| e.to_string())?;
        } else {
            if let Some(parent) = out_path.parent() {
                std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
            }
            let mut buf = Vec::new();
            file.read_to_end(&mut buf).map_err(|e| e.to_string())?;
            std::fs::write(&out_path, &buf).map_err(|e| e.to_string())?;
        }
    }
    Ok(())
}
