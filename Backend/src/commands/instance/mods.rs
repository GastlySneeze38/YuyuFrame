use base64::Engine as _;
use serde::{Deserialize, Serialize};
use sha1::{Digest, Sha1};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Mutex, LazyLock};

use super::crud::instance_mods_dir;
use crate::minecraft::mod_files::{is_disabled_jar, is_enabled_jar};
use crate::minecraft::versions::predicate::{normalize_version, read_mod_meta, version_allowed};

#[derive(Serialize, Clone)]
pub struct ModInfo {
    pub name: String,
    pub size: u64,
    pub enabled: bool,
    pub sha1: String,
}

/// (taille, mtime_secs, sha1) — entrée du cache SHA1, invalidée automatiquement
/// si le fichier est modifié ou remplacé.
type Sha1CacheEntry = (u64, u64, String);

// Cache SHA1 : chemin → Sha1CacheEntry
static SHA1_CACHE: LazyLock<Mutex<HashMap<PathBuf, Sha1CacheEntry>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

fn compute_sha1(path: &std::path::Path) -> String {
    use std::io::Read;
    let Ok(mut file) = std::fs::File::open(path) else { return String::new() };
    let mut hasher = Sha1::new();
    let mut buf = [0u8; 65536];
    loop {
        match file.read(&mut buf) {
            Ok(0) | Err(_) => break,
            Ok(n) => hasher.update(&buf[..n]),
        }
    }
    format!("{:x}", hasher.finalize())
}

pub fn sha1_cached(path: &std::path::Path) -> String {
    let Ok(meta) = std::fs::metadata(path) else { return String::new() };
    let size = meta.len();
    let mtime = meta.modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs())
        .unwrap_or(0);

    let key = path.to_path_buf();
    if let Ok(cache) = SHA1_CACHE.lock() {
        if let Some((cs, cm, sha1)) = cache.get(&key) {
            if *cs == size && *cm == mtime {
                return sha1.clone();
            }
        }
    }

    let sha1 = compute_sha1(path);
    if let Ok(mut cache) = SHA1_CACHE.lock() {
        cache.insert(key, (size, mtime, sha1.clone()));
    }
    sha1
}

#[tauri::command]
pub async fn mods_list(instance_id: String) -> Result<Vec<ModInfo>, String> {
    let dir = instance_mods_dir(&instance_id);
    let mut mods = Vec::new();

    if let Ok(mut entries) = tokio::fs::read_dir(&dir).await {
        while let Ok(Some(entry)) = entries.next_entry().await {
            let path = entry.path();
            let name = path.file_name().unwrap_or_default().to_string_lossy().to_string();
            let enabled = is_enabled_jar(&name);
            let disabled = is_disabled_jar(&name);
            if !enabled && !disabled {
                continue;
            }
            let size = entry.metadata().await.map(|m| m.len()).unwrap_or(0);
            let path_clone = path.clone();
            let sha1 = tokio::task::spawn_blocking(move || sha1_cached(&path_clone))
                .await
                .unwrap_or_default();
            mods.push(ModInfo { name, size, enabled, sha1 });
        }
    }

    mods.sort_by_key(|a| a.name.to_lowercase());
    Ok(mods)
}

#[tauri::command]
pub async fn mods_toggle(instance_id: String, name: String) -> Result<ModInfo, String> {
    let dir = instance_mods_dir(&instance_id);
    let from = dir.join(&name);

    if !from.exists() {
        return Err(format!("Mod '{}' introuvable", name));
    }

    let (to_name, enabled) = if is_disabled_jar(&name) {
        // Retire le suffixe ".disabled" en préservant la casse du reste du nom.
        // La détection ci-dessus est insensible à la casse (voir is_disabled_jar) :
        // un `trim_end_matches(".disabled")` strictement minuscule laisserait un
        // ".DISABLED" traînant sur un fichier renommé à la main avec cette casse.
        let cut = name.len() - ".disabled".len();
        (name[..cut].to_string(), true)
    } else if is_enabled_jar(&name) {
        (format!("{}.disabled", name), false)
    } else {
        return Err("Nom de mod invalide".into());
    };

    let to = dir.join(&to_name);
    tokio::fs::rename(&from, &to).await.map_err(|e| e.to_string())?;

    let size = tokio::fs::metadata(&to).await.map(|m| m.len()).unwrap_or(0);
    let sha1 = tokio::task::spawn_blocking(move || sha1_cached(&to))
        .await
        .unwrap_or_default();
    Ok(ModInfo { name: to_name, size, enabled, sha1 })
}

#[tauri::command]
pub async fn mods_delete(instance_id: String, name: String) -> Result<(), String> {
    let dir = instance_mods_dir(&instance_id);
    let path = dir.join(&name);

    if !path.exists() {
        return Err(format!("Mod '{}' introuvable", name));
    }

    let canonical = path.canonicalize().map_err(|e| e.to_string())?;
    let canonical_dir = dir.canonicalize().unwrap_or(dir);
    if !canonical.starts_with(&canonical_dir) {
        return Err("Accès refusé".into());
    }

    tokio::fs::remove_file(&canonical).await.map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub async fn mods_install(
    app: tauri::AppHandle,
    instance_id: String,
    url: String,
    filename: String,
) -> Result<ModInfo, String> {
    use futures::StreamExt;
    use tauri::Emitter;

    if !url.starts_with("https://cdn.modrinth.com/") {
        return Err("URL non autorisée".into());
    }

    let safe_name = std::path::Path::new(&filename)
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "mod.jar".to_string());

    if !is_enabled_jar(&safe_name) {
        return Err("Seuls les fichiers .jar sont acceptés".into());
    }

    let dir = instance_mods_dir(&instance_id);
    tokio::fs::create_dir_all(&dir).await.map_err(|e| e.to_string())?;

    let client = reqwest::Client::builder()
        .user_agent("YuyuFrame/1.0")
        .build()
        .map_err(|e| e.to_string())?;

    let resp = client.get(&url).send().await.map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("Téléchargement échoué: {}", resp.status()));
    }

    // Téléchargement en flux plutôt qu'en un bloc (`bytes()`) pour pouvoir
    // publier une vraie progression (utile sur les mods volumineux comme
    // Sodium/embeddium — sur les petits mods, la barre passe juste très vite
    // à 100%).
    let total = resp.content_length().unwrap_or(0);
    let mut downloaded: u64 = 0;
    let mut bytes: Vec<u8> = Vec::new();
    let mut stream = resp.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| e.to_string())?;
        downloaded += chunk.len() as u64;
        bytes.extend_from_slice(&chunk);
        let _ = app.emit("mod_install_progress", serde_json::json!({
            "filename": &safe_name,
            "downloaded": downloaded,
            "total": total,
        }));
    }

    let dest = dir.join(&safe_name);
    tokio::fs::write(&dest, &bytes).await.map_err(|e| e.to_string())?;
    let sha1 = tokio::task::spawn_blocking(move || sha1_cached(&dest))
        .await
        .unwrap_or_default();

    crate::integrations::analytics::capture("mod_install_succeeded", serde_json::json!({
        "instance_id": &instance_id,
    }));
    Ok(ModInfo { name: safe_name, size: bytes.len() as u64, enabled: true, sha1 })
}

/// Extrait l'icône d'un mod directement depuis son JAR et la retourne en data URL base64.
/// Lit le champ `icon` de fabric.mod.json (Fabric/Quilt) ou `logoFile` de
/// META-INF/mods.toml (Forge/NeoForge, même format pour les deux), avec repli
/// sur pack.png. Avant l'ajout de mods.toml, tous les mods Forge/NeoForge
/// tombaient systématiquement sur "Pas d'icône" puisqu'ils n'ont jamais de
/// fabric.mod.json ni, en général, de pack.png à la racine du jar — l'icône
/// n'apparaissait alors qu'en recherche (métadonnées distantes Modrinth/
/// CurseForge), jamais une fois le mod installé localement.
#[tauri::command]
pub async fn mod_icon(instance_id: String, name: String) -> Result<String, String> {
    let dir = instance_mods_dir(&instance_id);
    let path = dir.join(&name);
    if !path.exists() {
        return Err("Mod introuvable".into());
    }

    tokio::task::spawn_blocking(move || mod_icon_blocking(&path))
        .await
        .map_err(|e| e.to_string())?
}

fn mod_icon_blocking(path: &std::path::Path) -> Result<String, String> {
    use std::io::Read;

    let bytes = std::fs::read(path).map_err(|e| e.to_string())?;

    // Lire fabric.mod.json (Fabric/Quilt) ou META-INF/mods.toml (Forge/NeoForge)
    // pour trouver le chemin de l'icône déclaré par le mod.
    let icon_path: Option<String> = {
        let cursor = std::io::Cursor::new(&bytes);
        if let Ok(mut archive) = zip::ZipArchive::new(cursor) {
            let mut found: Option<String> = None;

            if let Ok(mut entry) = archive.by_name("fabric.mod.json") {
                let mut content = String::new();
                let _ = entry.read_to_string(&mut content);
                found = serde_json::from_str::<serde_json::Value>(&content)
                    .ok()
                    .and_then(|v| v.get("icon").and_then(|i| i.as_str()).map(|s| s.to_string()));
            }

            // Séparé du `if` précédent (pas de `else if`) : `archive.by_name`
            // emprunte `archive` mutablement, et un `else if` chaîné garde le
            // premier emprunt vivant jusqu'à la fin de toute la chaîne aux yeux
            // du borrow checker — deux `if` distincts referment chacun leur
            // emprunt à leur propre accolade fermante.
            if found.is_none() {
                if let Ok(mut entry) = archive.by_name("META-INF/mods.toml") {
                    let mut content = String::new();
                    let _ = entry.read_to_string(&mut content);
                    found = toml::from_str::<toml::Value>(&content).ok().and_then(|v| {
                        v.get("mods")
                            .and_then(|m| m.as_array())
                            .and_then(|a| a.first())
                            .and_then(|m| m.get("logoFile"))
                            .and_then(|i| i.as_str())
                            .map(|s| s.to_string())
                    });
                }
            }

            found
        } else {
            None
        }
    };

    let cursor = std::io::Cursor::new(&bytes);
    let mut archive = zip::ZipArchive::new(cursor).map_err(|e| e.to_string())?;

    let candidates: Vec<&str> = if let Some(ref p) = icon_path {
        vec![p.as_str(), "pack.png"]
    } else {
        vec!["pack.png"]
    };

    for candidate in candidates {
        if let Ok(mut entry) = archive.by_name(candidate) {
            let mut icon_bytes = Vec::new();
            if entry.read_to_end(&mut icon_bytes).is_ok() && !icon_bytes.is_empty() {
                let mime = if icon_bytes.starts_with(&[0x89, 0x50, 0x4E, 0x47]) {
                    "image/png"
                } else if icon_bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
                    "image/jpeg"
                } else {
                    "image/png"
                };
                let b64 = base64::engine::general_purpose::STANDARD.encode(&icon_bytes);
                return Ok(format!("data:{};base64,{}", mime, b64));
            }
        }
    }

    Err("Pas d'icône dans ce JAR".into())
}

#[tauri::command]
pub async fn mods_upload(
    instance_id: String,
    filename: String,
    data: Vec<u8>,
) -> Result<ModInfo, String> {
    let safe_name = std::path::Path::new(&filename)
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "mod.jar".to_string());

    if !is_enabled_jar(&safe_name) {
        return Err("Seuls les fichiers .jar sont acceptés".into());
    }

    let dir = instance_mods_dir(&instance_id);
    tokio::fs::create_dir_all(&dir).await.map_err(|e| e.to_string())?;

    let dest = dir.join(&safe_name);
    let size = data.len() as u64;
    tokio::fs::write(&dest, &data).await.map_err(|e| e.to_string())?;
    let sha1 = tokio::task::spawn_blocking(move || sha1_cached(&dest))
        .await
        .unwrap_or_default();

    Ok(ModInfo { name: safe_name, size, enabled: true, sha1 })
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateCandidate {
    /// Nom de fichier actuel du mod dans le dossier de l'instance.
    pub name: String,
    pub new_version: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateSafety {
    pub name: String,
    pub safe: bool,
    /// Mods dont la dépendance déclarée bloquerait cette mise à jour.
    pub blocked_by: Vec<String>,
}

/// Un mod installé et actif, avec ce qu'il exige des autres.
struct Installed {
    /// Nom du fichier .jar — c'est lui qu'on montre à la personne.
    file: String,
    meta: crate::minecraft::versions::predicate::ModMeta,
    /// Version normalisée, comparable à celles annoncées par Modrinth.
    version: String,
}

/// Lit tous les mods actifs d'une instance, Fabric comme Forge, jars
/// imbriqués compris.
fn read_installed(dir: &std::path::Path, mc_version: &str, loader: &str) -> Vec<Installed> {
    let Ok(entries) = std::fs::read_dir(dir) else { return Vec::new() };
    entries
        .flatten()
        .filter_map(|entry| {
            let path = entry.path();
            let file = path.file_name()?.to_string_lossy().to_string();
            if !is_enabled_jar(&file) {
                return None;
            }
            let meta = read_mod_meta(&path)?;
            let version = normalize_version(&meta.version, mc_version, loader);
            Some(Installed { file, meta, version })
        })
        .collect()
}

/// Index identifiant -> fichier, jars imbriqués inclus.
///
/// Sans les imbriqués, une contrainte visant `fabric-resource-loader-v0` ne
/// trouvait personne alors que le module est bien là, empaqueté dans Fabric
/// API ou dans Sodium.
fn index_ids(installed: &[Installed], mc_version: &str, loader: &str) -> HashMap<String, (String, String)> {
    let mut by_id: HashMap<String, (String, String)> = HashMap::new();
    for m in installed {
        by_id.insert(m.meta.id.clone(), (m.file.clone(), m.version.clone()));
        for (id, version) in &m.meta.nested {
            by_id
                .entry(id.clone())
                .or_insert_with(|| (m.file.clone(), normalize_version(version, mc_version, loader)));
        }
    }
    by_id
}

/// Vérifie, pour chaque mise à jour candidate, que la nouvelle version
/// respecte ce que les *autres* mods installés exigent d'elle — pour ne pas
/// proposer une mise à jour qui casserait un mod dépendant (ex : Iris exige
/// Sodium en 0.8.x, Sodium déclare casser Iris ≤ 1.10.8).
#[tauri::command]
pub async fn mods_check_update_safety(
    instance_id: String,
    mc_version: String,
    loader: String,
    candidates: Vec<UpdateCandidate>,
) -> Result<Vec<UpdateSafety>, String> {
    let dir = instance_mods_dir(&instance_id);

    tokio::task::spawn_blocking(move || {
        let installed = read_installed(&dir, &mc_version, &loader);
        let id_by_file: HashMap<String, String> =
            installed.iter().map(|m| (m.file.clone(), m.meta.id.clone())).collect();

        candidates
            .into_iter()
            .map(|c| {
                let target_id = id_by_file.get(&c.name).cloned();
                let new_version = normalize_version(&c.new_version, &mc_version, &loader);
                let blocked_by: Vec<String> = match &target_id {
                    Some(id) => installed
                        .iter()
                        .filter(|m| m.file != c.name)
                        .filter(|m| {
                            m.meta
                                .requirements
                                .iter()
                                .filter(|r| &r.id == id)
                                .any(|r| !version_allowed(&new_version, &r.depends, &r.breaks))
                        })
                        .map(|m| m.file.clone())
                        .collect(),
                    None => Vec::new(),
                };
                UpdateSafety { safe: blocked_by.is_empty(), name: c.name, blocked_by }
            })
            .collect()
    })
    .await
    .map_err(|e| e.to_string())
}

/// Une incompatibilité constatée entre deux mods déjà installés.
#[derive(serde::Serialize)]
pub struct ModConflict {
    /// Fichier du mod qui déclare la contrainte.
    pub declared_by: String,
    /// Fichier du mod visé, s'il est installé.
    pub target: String,
    /// Version installée du mod visé.
    pub target_version: String,
    /// `breaks` = incompatibilité déclarée, `depends` = version hors plage.
    pub kind: String,
    /// La contrainte telle qu'écrite, pour l'afficher.
    pub expected: String,
}

/// Passe en revue TOUT ce qui est installé et signale les incompatibilités
/// déjà présentes.
///
/// `mods_check_update_safety` ne regarde que dans un sens : « les autres
/// acceptent-ils cette nouvelle version ? ». Il ne dit rien d'un conflit qui
/// existe déjà — typiquement un mod installé à la main, ou resté en arrière
/// pendant qu'un autre avançait. Ce sont précisément ceux-là que Fabric
/// découvre au démarrage, une fois qu'il est trop tard.
#[tauri::command]
pub async fn mods_check_conflicts(
    instance_id: String,
    mc_version: String,
    loader: String,
) -> Result<Vec<ModConflict>, String> {
    let dir = instance_mods_dir(&instance_id);

    tokio::task::spawn_blocking(move || {
        let installed = read_installed(&dir, &mc_version, &loader);
        let by_id = index_ids(&installed, &mc_version, &loader);

        let mut conflicts = Vec::new();
        for m in &installed {
            for req in &m.meta.requirements {
                // Un mod absent n'est pas un conflit : Fabric le signalera
                // comme dépendance manquante, et le résolveur d'installation
                // s'en occupe déjà. Ici on ne parle que de ce qui est là.
                let Some((target_file, version)) = by_id.get(&req.id) else { continue };
                if target_file == &m.file {
                    continue;
                }
                // Version inconnue : on ne sait pas juger, et une comparaison
                // contre une chaîne vide échoue toujours — ce serait accuser
                // au hasard. Mieux vaut se taire.
                if version.is_empty() {
                    continue;
                }
                if version_allowed(version, &req.depends, &req.breaks) {
                    continue;
                }
                let breaks_it = !req.breaks.is_empty() && !version_allowed(version, &[], &req.breaks);
                conflicts.push(ModConflict {
                    declared_by: m.file.clone(),
                    target: target_file.clone(),
                    target_version: version.clone(),
                    kind: if breaks_it { "breaks".into() } else { "depends".into() },
                    expected: format_groups(if breaks_it { &req.breaks } else { &req.depends }),
                });
            }
        }
        conflicts
    })
    .await
    .map_err(|e| e.to_string())
}

/// Remet des groupes de jetons sous une forme lisible (« >=1.0 <2.0 »).
fn format_groups(groups: &[Vec<String>]) -> String {
    groups.iter().map(|g| g.join(" ")).collect::<Vec<_>>().join(" ou ")
}
