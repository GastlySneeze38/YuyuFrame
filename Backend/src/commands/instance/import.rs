use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::path::{Path, PathBuf};

use super::crud::{instance_create, instance_mods_dir};
use super::mods::{sha1_cached, ModInfo};
use crate::state::SharedState;

/// Cherche le dossier `mods/` d'une instance externe, quel que soit le launcher :
/// à la racine (MultiMC/Prism, CurseForge, ATLauncher) ou sous `.minecraft`/`minecraft`
/// (Modrinth App, Feather, Lunar Client, TLauncher, installation manuelle...).
fn find_mods_dir(root: &Path) -> Option<PathBuf> {
    [
        root.join("mods"),
        root.join(".minecraft").join("mods"),
        root.join("minecraft").join("mods"),
    ]
    .into_iter()
    .find(|candidate| candidate.is_dir())
}

/// Best-effort : cherche "fabric"/"forge"/"quilt"/"neoforge" dans un blob de texte
/// (fichier de métadonnées de launcher) pour deviner le loader.
fn guess_loader_from_text(text: &str) -> Option<String> {
    let lower = text.to_lowercase();
    if lower.contains("neoforge") {
        Some("neoforge".to_string())
    } else if lower.contains("quilt") {
        Some("quilt".to_string())
    } else if lower.contains("fabric") {
        Some("fabric".to_string())
    } else if lower.contains("forge") {
        Some("forge".to_string())
    } else {
        None
    }
}

/// Version Minecraft déclarée dans un `mmc-pack.json` (MultiMC/Prism) —
/// composant dont l'uid est `net.minecraft`.
fn mmc_pack_mc_version(pack_text: &str) -> Option<String> {
    let value: serde_json::Value = serde_json::from_str(pack_text).ok()?;
    let components = value.get("components")?.as_array()?;
    components.iter().find_map(|c| {
        if c.get("uid").and_then(|v| v.as_str()) == Some("net.minecraft") {
            c.get("version").and_then(|v| v.as_str()).map(String::from)
        } else {
            None
        }
    })
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct DetectedSource {
    pub kind: String,
    pub name: Option<String>,
    pub mc_version: Option<String>,
    pub loader: Option<String>,
}

/// Reconnaît best-effort les formats propres (MultiMC/Prism, CurseForge, ATLauncher)
/// pour préremplir nom/version/loader. Tout le reste (Modrinth App, Feather, Lunar
/// Client, TLauncher, dossier `mods/` brut) retombe sur "unknown" — l'utilisateur
/// complète manuellement si besoin.
fn detect_source(root: &Path) -> DetectedSource {
    let cfg_path = root.join("instance.cfg");
    if cfg_path.is_file() {
        let cfg_text = std::fs::read_to_string(&cfg_path).unwrap_or_default();
        let name = cfg_text
            .lines()
            .find_map(|l| l.strip_prefix("name=").map(|v| v.trim().to_string()));
        let pack_text = std::fs::read_to_string(root.join("mmc-pack.json")).unwrap_or_default();
        return DetectedSource {
            kind: "multimc_prism".to_string(),
            name,
            mc_version: mmc_pack_mc_version(&pack_text),
            loader: guess_loader_from_text(&pack_text),
        };
    }

    let cf_path = root.join("minecraftinstance.json");
    if cf_path.is_file() {
        let text = std::fs::read_to_string(&cf_path).unwrap_or_default();
        let value: serde_json::Value = serde_json::from_str(&text).unwrap_or_default();
        let name = value.get("name").and_then(|v| v.as_str()).map(String::from);
        let mc_version = value
            .get("gameVersion")
            .and_then(|v| v.as_str())
            .map(String::from)
            .or_else(|| {
                value
                    .pointer("/baseModLoader/minecraftVersion")
                    .and_then(|v| v.as_str())
                    .map(String::from)
            });
        let loader = value
            .pointer("/baseModLoader/name")
            .and_then(|v| v.as_str())
            .and_then(guess_loader_from_text)
            .or_else(|| guess_loader_from_text(&text));
        return DetectedSource { kind: "curseforge".to_string(), name, mc_version, loader };
    }

    let at_path = root.join("instance.json");
    if at_path.is_file() {
        let text = std::fs::read_to_string(&at_path).unwrap_or_default();
        let value: serde_json::Value = serde_json::from_str(&text).unwrap_or_default();
        let name = value
            .get("name")
            .and_then(|v| v.as_str())
            .map(String::from)
            .or_else(|| value.pointer("/launcher/name").and_then(|v| v.as_str()).map(String::from));
        let mc_version = value.get("minecraftVersion").and_then(|v| v.as_str()).map(String::from);
        return DetectedSource {
            kind: "atlauncher".to_string(),
            name,
            mc_version,
            loader: guess_loader_from_text(&text),
        };
    }

    DetectedSource {
        kind: "unknown".to_string(),
        name: root.file_name().map(|n| n.to_string_lossy().to_string()),
        mc_version: None,
        loader: None,
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanResult {
    pub mods_dir: String,
    pub source: DetectedSource,
    pub mods: Vec<ModInfo>,
}

#[tauri::command]
pub async fn import_scan_folder(path: String) -> Result<ScanResult, String> {
    let root = PathBuf::from(&path);
    if !root.is_dir() {
        return Err("Dossier introuvable".into());
    }

    tokio::task::spawn_blocking(move || {
        let mods_dir = find_mods_dir(&root).ok_or_else(|| {
            "Aucun dossier de mods trouvé (mods/, .minecraft/mods/ ou minecraft/mods/)".to_string()
        })?;
        let source = detect_source(&root);

        let mut mods = Vec::new();
        if let Ok(entries) = std::fs::read_dir(&mods_dir) {
            for entry in entries.flatten() {
                let p = entry.path();
                let name = p.file_name().unwrap_or_default().to_string_lossy().to_string();
                let enabled = name.ends_with(".jar") && !name.ends_with(".jar.disabled");
                let disabled = name.ends_with(".jar.disabled");
                if !enabled && !disabled {
                    continue;
                }
                let size = entry.metadata().map(|m| m.len()).unwrap_or(0);
                let sha1 = sha1_cached(&p);
                mods.push(ModInfo { name, size, enabled, sha1 });
            }
        }
        mods.sort_by_key(|a| a.name.to_lowercase());

        Ok(ScanResult { mods_dir: mods_dir.to_string_lossy().to_string(), source, mods })
    })
    .await
    .map_err(|e| e.to_string())?
}

/// Copie `sources` dans `dest_dir` en ignorant celles dont le SHA1 est déjà présent
/// (dédoublonnage), et en renommant en cas de collision de *nom* entre deux fichiers
/// de contenu différent.
fn copy_mods_into_instance(dest_dir: &Path, sources: &[PathBuf]) -> (Vec<ModInfo>, Vec<String>) {
    let _ = std::fs::create_dir_all(dest_dir);

    let mut existing_hashes: HashSet<String> = HashSet::new();
    if let Ok(entries) = std::fs::read_dir(dest_dir) {
        for entry in entries.flatten() {
            let p = entry.path();
            let name = p.file_name().unwrap_or_default().to_string_lossy().to_string();
            if name.ends_with(".jar") || name.ends_with(".jar.disabled") {
                let h = sha1_cached(&p);
                if !h.is_empty() {
                    existing_hashes.insert(h);
                }
            }
        }
    }

    let mut imported = Vec::new();
    let mut skipped = Vec::new();

    for src in sources {
        let Some(file_name) = src.file_name().map(|n| n.to_string_lossy().to_string()) else { continue };
        let sha1 = sha1_cached(src);
        if !sha1.is_empty() && existing_hashes.contains(&sha1) {
            skipped.push(file_name);
            continue;
        }

        let mut dest_name = file_name.clone();
        let mut dest_path = dest_dir.join(&dest_name);
        let mut counter = 1;
        while dest_path.exists() {
            let stem = Path::new(&file_name).file_stem().unwrap_or_default().to_string_lossy().to_string();
            let ext = Path::new(&file_name).extension().map(|e| e.to_string_lossy().to_string());
            dest_name = match &ext {
                Some(ext) => format!("{}_{}.{}", stem, counter, ext),
                None => format!("{}_{}", stem, counter),
            };
            dest_path = dest_dir.join(&dest_name);
            counter += 1;
        }

        if std::fs::copy(src, &dest_path).is_err() {
            continue;
        }
        existing_hashes.insert(sha1.clone());
        let size = std::fs::metadata(&dest_path).map(|m| m.len()).unwrap_or(0);
        let enabled = dest_name.ends_with(".jar") && !dest_name.ends_with(".jar.disabled");
        imported.push(ModInfo { name: dest_name, size, enabled, sha1 });
    }

    (imported, skipped)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportResult {
    pub instance_id: String,
    pub imported: Vec<ModInfo>,
    pub skipped: Vec<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewInstanceParams {
    pub name: String,
    pub mc_version: String,
    pub loader: String,
    pub ram_mb: u32,
}

#[tauri::command]
pub async fn import_apply(
    state: tauri::State<'_, SharedState>,
    source_mods_dir: String,
    selected_files: Vec<String>,
    mode: String,
    target_instance_id: Option<String>,
    new_instance: Option<NewInstanceParams>,
) -> Result<ImportResult, String> {
    let source_dir = PathBuf::from(&source_mods_dir);
    let canonical_source = source_dir.canonicalize().map_err(|e| e.to_string())?;

    let mut sources = Vec::new();
    for name in &selected_files {
        let canonical = source_dir
            .join(name)
            .canonicalize()
            .map_err(|_| format!("Fichier introuvable: {}", name))?;
        if !canonical.starts_with(&canonical_source) {
            return Err("Accès refusé".into());
        }
        sources.push(canonical);
    }

    let instance_id = match mode.as_str() {
        "new" => {
            let params = new_instance.ok_or("Paramètres de nouvelle instance manquants")?;
            let instance = instance_create(
                state,
                params.name,
                params.mc_version,
                params.loader,
                params.ram_mb,
                None,
            )
            .await?;
            instance.id
        }
        "existing" => target_instance_id.ok_or("Instance cible manquante")?,
        _ => return Err("Mode d'import invalide".into()),
    };

    let dest_dir = instance_mods_dir(&instance_id);
    tokio::task::spawn_blocking(move || {
        let (imported, skipped) = copy_mods_into_instance(&dest_dir, &sources);
        ImportResult { instance_id, imported, skipped }
    })
    .await
    .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn mods_import_paths(instance_id: String, paths: Vec<String>) -> Result<ImportResult, String> {
    let dest_dir = instance_mods_dir(&instance_id);
    let sources: Vec<PathBuf> = paths
        .into_iter()
        .map(PathBuf::from)
        .filter(|p| p.extension().map(|e| e.eq_ignore_ascii_case("jar")).unwrap_or(false))
        .collect();

    if sources.is_empty() {
        return Err("Aucun fichier .jar valide sélectionné".into());
    }

    tokio::task::spawn_blocking(move || {
        let (imported, skipped) = copy_mods_into_instance(&dest_dir, &sources);
        ImportResult { instance_id, imported, skipped }
    })
    .await
    .map_err(|e| e.to_string())
}
