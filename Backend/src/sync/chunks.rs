//! Moteur par morceaux : découpe, empreintes, envoi et récupération.
//!
//! Le protocole est celui de `/v1/sync` (voir `Server/LauncherAPI/src/routes/
//! sync.rs`). Il remplace l'envoi d'une archive entière, qui repartait de zéro
//! au moindre incident et saturait le serveur : ici chaque fichier est coupé
//! en morceaux de 16 Mio adressés par leur SHA-256, et **seuls les morceaux
//! que le serveur n'a pas déjà** sont envoyés. Changer un mod dans une
//! instance de 2 Gio n'envoie que ce mod ; un envoi coupé reprend où il en
//! était ; et deux instances qui partagent un même fichier ne le stockent
//! qu'une fois.
//!
//! Ce module ne connaît ni la sync ni les sauvegardes : il prend un dossier et
//! une liste de ce qu'il faut y inclure. La sync cloud et le backup s'en
//! servent tous les deux, pour deux contenus différents.

use std::collections::HashSet;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::server as api;
use crate::server::error::{ApiError, ApiResult};
use crate::state::SharedState;

/// Même valeur que `CHUNK_MAX` côté serveur. Un morceau plus gros serait
/// refusé net (413).
pub const CHUNK_SIZE: usize = 16 * 1024 * 1024;

/// Délai d'un transfert de morceau. Large : 16 Mio sur une connexion modeste
/// prennent plusieurs minutes, et abandonner à mi-chemin obligerait à tout
/// recommencer.
const CHUNK_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(300);

/// Un fichier tel que le manifeste le décrit.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct FileEntry {
    /// Chemin relatif à la racine, séparateurs « / ».
    pub path: String,
    pub size: i64,
    /// SHA-256 de chaque morceau, dans l'ordre.
    pub chunks: Vec<String>,
}

#[derive(Deserialize, Debug)]
pub struct Manifest {
    pub revision: i64,
    pub files: Vec<FileEntry>,
}

/// Avancement, remonté au frontend pendant un transfert.
#[derive(Clone, Copy, Debug)]
pub struct Progress {
    pub done_bytes: i64,
    pub total_bytes: i64,
    pub done_files: usize,
    pub total_files: usize,
}

/// Ce qu'on fait remonter : un appel par morceau traité.
pub type Report<'a> = &'a (dyn Fn(Progress) + Send + Sync);

// ── Lecture du disque ────────────────────────────────────────────────────────

/// Normalise un chemin relatif en chemin de manifeste (« mods/sodium.jar »).
/// Rend `None` pour tout ce que le serveur refuserait de toute façon.
fn manifest_path(root: &Path, file: &Path) -> Option<String> {
    let relative = file.strip_prefix(root).ok()?;
    let mut parts = Vec::new();
    for part in relative.components() {
        match part {
            std::path::Component::Normal(p) => parts.push(p.to_str()?.to_string()),
            // Un lien ou un « .. » n'a rien à faire dans une sauvegarde :
            // à la restauration il écrirait hors du dossier de l'instance.
            _ => return None,
        }
    }
    (!parts.is_empty()).then(|| parts.join("/"))
}

/// Parcourt `root` et construit le manifeste des dossiers demandés.
///
/// `include` liste les premiers niveaux à prendre (« mods », « config »…).
/// Vide = tout le dossier. `skip` écarte des noms de dossier n'importe où
/// dans l'arborescence.
pub fn scan(root: &Path, include: &[String], skip: &[&str]) -> std::io::Result<Vec<FileEntry>> {
    let mut out = Vec::new();
    let roots: Vec<PathBuf> = if include.is_empty() {
        vec![root.to_path_buf()]
    } else {
        include.iter().map(|d| root.join(d)).collect()
    };
    for start in roots {
        if start.is_file() {
            if let Some(entry) = entry_of(root, &start)? {
                out.push(entry);
            }
        } else if start.is_dir() {
            walk(root, &start, skip, &mut out)?;
        }
    }
    out.sort_by(|a, b| a.path.cmp(&b.path));
    Ok(out)
}

fn walk(root: &Path, dir: &Path, skip: &[&str], out: &mut Vec<FileEntry>) -> std::io::Result<()> {
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        // `symlink_metadata` : on ne suit jamais un lien, qui pourrait sortir
        // de l'instance et embarquer n'importe quoi du disque.
        let meta = std::fs::symlink_metadata(&path)?;
        if meta.file_type().is_symlink() {
            continue;
        }
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if skip.iter().any(|s| s.eq_ignore_ascii_case(&name)) {
            continue;
        }
        if meta.is_dir() {
            walk(root, &path, skip, out)?;
        } else if meta.is_file() {
            if let Some(file) = entry_of(root, &path)? {
                out.push(file);
            }
        }
    }
    Ok(())
}

fn entry_of(root: &Path, file: &Path) -> std::io::Result<Option<FileEntry>> {
    let Some(path) = manifest_path(root, file) else { return Ok(None) };
    let data = std::fs::read(file)?;
    Ok(Some(FileEntry { path, size: data.len() as i64, chunks: hash_chunks(&data) }))
}

/// Empreintes des morceaux d'un contenu. Un fichier vide n'a aucun morceau —
/// c'est ce que le serveur attend (`size = 0`, `chunks = []`).
pub fn hash_chunks(data: &[u8]) -> Vec<String> {
    data.chunks(CHUNK_SIZE).map(hex_sha256).collect()
}

pub fn hex_sha256(data: &[u8]) -> String {
    Sha256::digest(data).iter().map(|b| format!("{b:02x}")).collect()
}

/// Relit un morceau précis d'un fichier, sans charger le reste en mémoire.
fn read_chunk(file: &Path, index: usize) -> std::io::Result<Vec<u8>> {
    use std::io::{Read, Seek, SeekFrom};
    let mut handle = std::fs::File::open(file)?;
    handle.seek(SeekFrom::Start((index * CHUNK_SIZE) as u64))?;
    let mut buffer = vec![0u8; CHUNK_SIZE];
    let mut filled = 0;
    while filled < CHUNK_SIZE {
        match handle.read(&mut buffer[filled..])? {
            0 => break,
            n => filled += n,
        }
    }
    buffer.truncate(filled);
    Ok(buffer)
}

// ── Envoi ────────────────────────────────────────────────────────────────────

/// Où se trouve chaque morceau du manifeste sur le disque.
fn locate(files: &[FileEntry]) -> Vec<(String, String, usize)> {
    let mut out = Vec::new();
    for f in files {
        for (index, sha) in f.chunks.iter().enumerate() {
            out.push((sha.clone(), f.path.clone(), index));
        }
    }
    out
}

/// Envoie les morceaux que le serveur déclare ne pas avoir.
///
/// L'ordre compte : on valide le manifeste seulement après, pour qu'un envoi
/// interrompu laisse le serveur sur son ancienne version cohérente plutôt que
/// sur un manifeste dont la moitié des morceaux manquent.
pub async fn upload_missing(
    state: &SharedState,
    root: &Path,
    files: &[FileEntry],
    missing: &[String],
    report: Report<'_>,
) -> ApiResult<()> {
    let wanted: HashSet<&str> = missing.iter().map(String::as_str).collect();
    let located = locate(files);
    // Un même morceau peut apparaître dans plusieurs fichiers (deux copies du
    // même mod) : on ne l'envoie qu'une fois.
    let mut sent: HashSet<String> = HashSet::new();
    let total_bytes: i64 = files.iter().map(|f| f.size).sum();
    let mut done_bytes = 0i64;

    for (sha, path, index) in located {
        if !wanted.contains(sha.as_str()) || !sent.insert(sha.clone()) {
            continue;
        }
        let file = root.join(path.replace('/', std::path::MAIN_SEPARATOR_STR));
        let data = tokio::task::spawn_blocking(move || read_chunk(&file, index))
            .await
            .map_err(|e| ApiError::new("internal", e.to_string()))?
            .map_err(|e| ApiError::new("internal", format!("Lecture impossible : {e}")))?;

        // Le fichier a changé depuis le scan (le jeu tourne, un mod s'écrit) :
        // le serveur refuserait l'empreinte. Mieux vaut le dire ici.
        if hex_sha256(&data) != sha {
            return Err(ApiError::new("changed", "Un fichier a changé pendant l'envoi — relance la synchronisation"));
        }

        done_bytes += data.len() as i64;
        api::put_bytes(state, &format!("/sync/chunks/{sha}"), data, CHUNK_TIMEOUT).await?;
        report(Progress { done_bytes, total_bytes, done_files: sent.len(), total_files: files.len() });
    }
    Ok(())
}

// ── Récupération ─────────────────────────────────────────────────────────────

/// Écrit les fichiers du manifeste dans `root`, en ne téléchargeant que ce qui
/// n'est pas déjà sur le disque.
///
/// Rien n'est supprimé ici : retirer ce qui n'est plus dans le manifeste est
/// une décision qui appartient à l'appelant (une sync le fait, une
/// restauration de sauvegarde pas forcément).
pub async fn download(state: &SharedState, root: &Path, files: &[FileEntry], report: Report<'_>) -> ApiResult<()> {
    let total_bytes: i64 = files.iter().map(|f| f.size).sum();
    let mut done_bytes = 0i64;
    // Morceaux déjà rapatriés pendant CE transfert : deux fichiers identiques
    // ne se téléchargent pas deux fois.
    let mut cache: std::collections::HashMap<String, Vec<u8>> = std::collections::HashMap::new();

    for (done_files, file) in files.iter().enumerate() {
        let target = root.join(file.path.replace('/', std::path::MAIN_SEPARATOR_STR));
        if already_there(&target, file) {
            done_bytes += file.size;
            report(Progress { done_bytes, total_bytes, done_files, total_files: files.len() });
            continue;
        }

        let mut content = Vec::with_capacity(file.size.max(0) as usize);
        for sha in &file.chunks {
            let data = match cache.get(sha) {
                Some(data) => data.clone(),
                None => {
                    let data = api::get_bytes(state, &format!("/sync/chunks/{sha}"), CHUNK_TIMEOUT).await?;
                    if hex_sha256(&data) != *sha {
                        return Err(ApiError::new("corrupt", format!("Morceau abîmé pendant le transfert : {}", file.path)));
                    }
                    // Le cache ne garde que les petits morceaux : garder
                    // plusieurs blocs de 16 Mio pour économiser un rare doublon
                    // coûterait plus de mémoire que de réseau.
                    if data.len() <= 1024 * 1024 {
                        cache.insert(sha.clone(), data.clone());
                    }
                    data
                }
            };
            done_bytes += data.len() as i64;
            content.extend_from_slice(&data);
            report(Progress { done_bytes, total_bytes, done_files, total_files: files.len() });
        }

        let path = target.clone();
        tokio::task::spawn_blocking(move || -> std::io::Result<()> {
            if let Some(parent) = path.parent() {
                std::fs::create_dir_all(parent)?;
            }
            std::fs::write(&path, content)
        })
        .await
        .map_err(|e| ApiError::new("internal", e.to_string()))?
        .map_err(|e| ApiError::new("internal", format!("Écriture impossible : {e}")))?;
    }
    Ok(())
}

/// Le fichier local est-il déjà exactement celui du manifeste ? La taille
/// d'abord (immédiat), l'empreinte ensuite : sans ce test, une restauration
/// retéléchargerait toute l'instance à chaque fois.
fn already_there(target: &Path, file: &FileEntry) -> bool {
    let Ok(meta) = std::fs::metadata(target) else { return false };
    if meta.len() as i64 != file.size {
        return false;
    }
    let Ok(data) = std::fs::read(target) else { return false };
    hash_chunks(&data) == file.chunks
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("yuyu-chunks-{name}-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn an_empty_file_has_no_chunk() {
        assert!(hash_chunks(b"").is_empty(), "le serveur attend size = 0 et chunks = []");
        assert_eq!(hash_chunks(b"a").len(), 1);
    }

    #[test]
    fn identical_content_gives_identical_chunks() {
        // C'est toute l'économie du protocole : le même mod dans deux
        // instances ne part qu'une fois.
        assert_eq!(hash_chunks(b"sodium"), hash_chunks(b"sodium"));
        assert_ne!(hash_chunks(b"sodium"), hash_chunks(b"iris"));
    }

    #[test]
    fn scan_keeps_only_the_requested_folders() {
        let root = temp_dir("scan");
        for (dir, file) in [("mods", "sodium.jar"), ("config", "sodium.json"), ("saves", "level.dat")] {
            std::fs::create_dir_all(root.join(dir)).unwrap();
            std::fs::write(root.join(dir).join(file), b"x").unwrap();
        }
        let files = scan(&root, &["mods".into(), "config".into()], &[]).unwrap();
        let paths: Vec<&str> = files.iter().map(|f| f.path.as_str()).collect();
        assert_eq!(paths, vec!["config/sodium.json", "mods/sodium.jar"]);
        assert!(!paths.iter().any(|p| p.starts_with("saves")), "les mondes ne partent pas dans la sync");
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn scan_skips_what_it_is_told_to() {
        let root = temp_dir("skip");
        std::fs::create_dir_all(root.join("mods")).unwrap();
        std::fs::create_dir_all(root.join("logs")).unwrap();
        std::fs::write(root.join("mods/a.jar"), b"x").unwrap();
        std::fs::write(root.join("logs/latest.log"), b"x").unwrap();
        let files = scan(&root, &[], &["logs"]).unwrap();
        assert_eq!(files.len(), 1);
        assert_eq!(files[0].path, "mods/a.jar");
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn manifest_paths_use_forward_slashes_and_stay_inside() {
        let root = Path::new("C:/instances/coco");
        assert_eq!(manifest_path(root, Path::new("C:/instances/coco/mods/a.jar")).as_deref(), Some("mods/a.jar"));
        assert!(manifest_path(root, Path::new("C:/instances/autre/a.jar")).is_none());
    }

    #[test]
    fn a_file_already_on_disk_is_not_downloaded_again() {
        let root = temp_dir("have");
        std::fs::write(root.join("a.jar"), b"hello").unwrap();
        let entry = FileEntry { path: "a.jar".into(), size: 5, chunks: hash_chunks(b"hello") };
        assert!(already_there(&root.join("a.jar"), &entry));

        let other = FileEntry { path: "a.jar".into(), size: 5, chunks: hash_chunks(b"world") };
        assert!(!already_there(&root.join("a.jar"), &other), "même taille mais contenu différent");
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn chunks_can_be_reread_one_by_one() {
        let root = temp_dir("read");
        let data: Vec<u8> = (0..(CHUNK_SIZE + 1234)).map(|i| (i % 251) as u8).collect();
        let file = root.join("big.bin");
        std::fs::write(&file, &data).unwrap();

        let expected = hash_chunks(&data);
        assert_eq!(expected.len(), 2);
        assert_eq!(hex_sha256(&read_chunk(&file, 0).unwrap()), expected[0]);
        let tail = read_chunk(&file, 1).unwrap();
        assert_eq!(tail.len(), 1234, "le dernier morceau n'est pas complété de zéros");
        assert_eq!(hex_sha256(&tail), expected[1]);
        std::fs::remove_dir_all(&root).ok();
    }
}
