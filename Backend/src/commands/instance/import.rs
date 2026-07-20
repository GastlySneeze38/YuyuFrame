use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::path::{Path, PathBuf};
use tauri::Emitter;

use super::crud::{instance_create, instance_dir, instance_mods_dir};
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

/// Reconnaît best-effort les formats propres (MultiMC/Prism, CurseForge, ATLauncher,
/// Modrinth App) pour préremplir nom/version/loader. Tout le reste (Feather, Lunar
/// Client, TLauncher, dossier `mods/` brut) retombe sur "unknown" — l'utilisateur
/// complète manuellement si besoin.
///
/// Modrinth App (`profile.json`) : le schéma exact a changé entre versions du
/// launcher (certaines l'ont à plat, d'autres sous "metadata") — on essaie les
/// deux sans certitude absolue sur la version en vigueur chez l'utilisateur, en
/// repli propre sur "unknown" si rien ne matche plutôt que sur des données fausses.
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

    let mr_path = root.join("profile.json");
    if mr_path.is_file() {
        let text = std::fs::read_to_string(&mr_path).unwrap_or_default();
        let value: serde_json::Value = serde_json::from_str(&text).unwrap_or_default();
        // "metadata.*" (schéma récent) avec repli sur les mêmes clés à plat
        // (schéma plus ancien) si absentes.
        let meta = value.get("metadata").unwrap_or(&value);
        let name = meta.get("name").and_then(|v| v.as_str()).map(String::from);
        let mc_version = meta.get("game_version").and_then(|v| v.as_str()).map(String::from);
        let loader = meta
            .get("loader")
            .and_then(|v| v.as_str())
            .and_then(guess_loader_from_text)
            .or_else(|| guess_loader_from_text(&text));
        if name.is_some() || mc_version.is_some() || loader.is_some() {
            return DetectedSource { kind: "modrinth_app".to_string(), name, mc_version, loader };
        }
    }

    DetectedSource {
        kind: "unknown".to_string(),
        name: root.file_name().map(|n| n.to_string_lossy().to_string()),
        mc_version: None,
        loader: None,
    }
}

/// Sous-dossiers optionnels (à côté de `mods/`) qu'on propose de reprendre en
/// plus des mods — beaucoup de mods ne fonctionnent pas correctement (ou
/// plantent) sans leur config par défaut, d'où l'intérêt de les proposer.
const EXTRA_DIR_NAMES: &[&str] = &["config", "resourcepacks", "shaderpacks"];

fn detect_extra_dirs(source_root: &Path) -> Vec<String> {
    EXTRA_DIR_NAMES
        .iter()
        .filter(|name| source_root.join(name).is_dir())
        .map(|s| s.to_string())
        .collect()
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanResult {
    pub mods_dir: String,
    /// Parent de `mods_dir` — c'est là que vivent `config/`, `resourcepacks/`
    /// et `shaderpacks/` s'ils existent (voir `extra_dirs`).
    pub source_root: String,
    pub source: DetectedSource,
    pub mods: Vec<ModInfo>,
    pub extra_dirs: Vec<String>,
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
        let source_root = mods_dir.parent().unwrap_or(&root).to_path_buf();
        let extra_dirs = detect_extra_dirs(&source_root);

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
                // Le SHA1 n'est utile qu'au moment d'IMPORTER (dédoublonnage,
                // voir copy_mods_into_instance) — le calculer ici hacherait
                // intégralement chaque jar de la source dès l'ouverture de la
                // modale, avant même que l'utilisateur ait vu la liste ou
                // choisi quoi importer. Sur un gros modpack (100-200+ mods),
                // ça bloquait "Analyse..." plusieurs secondes pour rien.
                let size = entry.metadata().map(|m| m.len()).unwrap_or(0);
                mods.push(ModInfo { name, size, enabled, sha1: String::new() });
            }
        }
        mods.sort_by_key(|a| a.name.to_lowercase());

        Ok(ScanResult {
            mods_dir: mods_dir.to_string_lossy().to_string(),
            source_root: source_root.to_string_lossy().to_string(),
            source,
            mods,
            extra_dirs,
        })
    })
    .await
    .map_err(|e| e.to_string())?
}

/// Compare par SHA1 les mods d'un dossier source à ceux déjà présents dans
/// une instance cible — appelé par le frontend dès que l'utilisateur choisit
/// "instance existante", pour pré-cocher/griser les doublons AVANT de cliquer
/// Importer plutôt que de les découvrir dans le résumé final.
#[tauri::command]
pub async fn import_check_duplicates(
    source_mods_dir: String,
    target_instance_id: String,
) -> Result<Vec<String>, String> {
    let source_dir = PathBuf::from(&source_mods_dir);
    let dest_dir = instance_mods_dir(&target_instance_id);

    tokio::task::spawn_blocking(move || {
        let mut dest_hashes: HashSet<String> = HashSet::new();
        if let Ok(entries) = std::fs::read_dir(&dest_dir) {
            for entry in entries.flatten() {
                let p = entry.path();
                let name = p.file_name().unwrap_or_default().to_string_lossy().to_string();
                if name.ends_with(".jar") || name.ends_with(".jar.disabled") {
                    let h = sha1_cached(&p);
                    if !h.is_empty() {
                        dest_hashes.insert(h);
                    }
                }
            }
        }

        let mut duplicates = Vec::new();
        if let Ok(entries) = std::fs::read_dir(&source_dir) {
            for entry in entries.flatten() {
                let p = entry.path();
                let name = p.file_name().unwrap_or_default().to_string_lossy().to_string();
                if !name.ends_with(".jar") && !name.ends_with(".jar.disabled") {
                    continue;
                }
                let h = sha1_cached(&p);
                if !h.is_empty() && dest_hashes.contains(&h) {
                    duplicates.push(name);
                }
            }
        }
        duplicates
    })
    .await
    .map_err(|e| e.to_string())
}

/// Copie `sources` dans `dest_dir` en ignorant celles dont le SHA1 est déjà présent
/// (dédoublonnage), et en renommant en cas de collision de *nom* entre deux fichiers
/// de contenu différent. Émet une progression (`import_progress`) — le dédoublonnage
/// hache intégralement chaque fichier, potentiellement long sur un gros modpack.
fn copy_mods_into_instance(
    app: &tauri::AppHandle,
    dest_dir: &Path,
    sources: &[PathBuf],
) -> (Vec<ModInfo>, Vec<String>) {
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
    let total = sources.len();

    for (i, src) in sources.iter().enumerate() {
        let _ = app.emit(
            "import_progress",
            serde_json::json!({ "phase": "mods", "current": i, "total": total }),
        );

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

    let _ = app.emit(
        "import_progress",
        serde_json::json!({ "phase": "mods", "current": total, "total": total }),
    );

    (imported, skipped)
}

/// Copie récursivement `src` dans `dest`, sans écraser un fichier déjà présent
/// à destination — préserve les réglages déjà là si l'instance cible existait
/// avant l'import. Retourne (copiés, ignorés).
fn copy_dir_merge(src: &Path, dest: &Path) -> (usize, usize) {
    let mut copied = 0;
    let mut skipped = 0;
    let _ = std::fs::create_dir_all(dest);
    let Ok(entries) = std::fs::read_dir(src) else { return (0, 0) };
    for entry in entries.flatten() {
        let path = entry.path();
        let dest_path = dest.join(entry.file_name());
        if path.is_dir() {
            let (c, s) = copy_dir_merge(&path, &dest_path);
            copied += c;
            skipped += s;
        } else if path.is_file() {
            if dest_path.exists() {
                skipped += 1;
            } else if std::fs::copy(&path, &dest_path).is_ok() {
                copied += 1;
            } else {
                skipped += 1;
            }
        }
    }
    (copied, skipped)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportResult {
    pub instance_id: String,
    pub imported: Vec<ModInfo>,
    pub skipped: Vec<String>,
    /// Fichiers copiés depuis config/resourcepacks/shaderpacks (voir `extra_dirs`).
    pub extra_copied: usize,
    /// Fichiers de ces mêmes dossiers ignorés (déjà présents à destination).
    pub extra_skipped: usize,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewInstanceParams {
    pub name: String,
    pub mc_version: String,
    pub loader: String,
    pub ram_mb: u32,
}

#[allow(clippy::too_many_arguments)]
#[tauri::command]
pub async fn import_apply(
    state: tauri::State<'_, SharedState>,
    app: tauri::AppHandle,
    source_mods_dir: String,
    source_root: String,
    selected_files: Vec<String>,
    extra_dirs: Vec<String>,
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

    // Même garde-fou que pour les mods (pas d'évasion hors du dossier source
    // choisi par l'utilisateur via le sélecteur de dossier natif).
    let canonical_root = PathBuf::from(&source_root).canonicalize().map_err(|e| e.to_string())?;
    let mut extra_src_dirs = Vec::new();
    for name in &extra_dirs {
        if !EXTRA_DIR_NAMES.contains(&name.as_str()) {
            continue;
        }
        let candidate = canonical_root.join(name);
        if candidate.is_dir() {
            extra_src_dirs.push(candidate);
        }
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
    let dest_root = instance_dir(&instance_id);
    let instance_id_for_result = instance_id.clone();

    tokio::task::spawn_blocking(move || {
        let (imported, skipped) = copy_mods_into_instance(&app, &dest_dir, &sources);

        let mut extra_copied = 0;
        let mut extra_skipped = 0;
        for (i, extra_src) in extra_src_dirs.iter().enumerate() {
            let name = extra_src.file_name().unwrap_or_default().to_string_lossy().to_string();
            let _ = app.emit(
                "import_progress",
                serde_json::json!({ "phase": "extras", "current": i, "total": extra_src_dirs.len(), "label": name }),
            );
            let dest = dest_root.join(extra_src.file_name().unwrap_or_default());
            let (c, s) = copy_dir_merge(extra_src, &dest);
            extra_copied += c;
            extra_skipped += s;
        }

        ImportResult { instance_id: instance_id_for_result, imported, skipped, extra_copied, extra_skipped }
    })
    .await
    .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn mods_import_paths(app: tauri::AppHandle, instance_id: String, paths: Vec<String>) -> Result<ImportResult, String> {
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
        let (imported, skipped) = copy_mods_into_instance(&app, &dest_dir, &sources);
        ImportResult { instance_id, imported, skipped, extra_copied: 0, extra_skipped: 0 }
    })
    .await
    .map_err(|e| e.to_string())
}
