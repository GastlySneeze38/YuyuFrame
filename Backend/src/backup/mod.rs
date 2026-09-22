//! Sauvegardes d'instance.
//!
//! La fonctionnalité existe pour **les mondes**. C'est ce qui est
//! irremplaçable : un mod se retélécharge, une configuration se réécrit, un
//! monde non. Le reste (configurations, mods) est optionnel, pour pouvoir
//! revenir à un état d'instance qui marchait et pas seulement à un monde.
//!
//! Volontairement séparée de la synchronisation. Synchroniser un monde entre
//! deux PC pose une question sans réponse — lequel gagne si on a joué des deux
//! côtés ? — alors qu'une sauvegarde est datée, empilée, et ne prétend jamais
//! fusionner quoi que ce soit.
//!
//! **Stockage adressé par contenu.** Une sauvegarde n'est pas une copie du
//! dossier : c'est un manifeste qui liste des morceaux, et les morceaux vivent
//! dans un dépôt commun nommé par leur empreinte. Dix sauvegardes d'un monde
//! de 500 Mo où seules quelques régions changent coûtent donc à peine plus que
//! la première. C'est exactement le format du protocole par morceaux de la
//! sync (`crate::sync::chunks`), ce qui permet d'envoyer une sauvegarde au
//! serveur sans la reconstruire.

pub mod settings;

use std::collections::HashSet;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::sync::chunks::{self, FileEntry};

pub use settings::BackupSettings;

/// Jamais sauvegardé, où qu'il se trouve : régénéré à chaque lancement, et
/// c'est ce qui grossit le plus vite.
const NEVER: [&str; 4] = ["logs", "crash-reports", "cache", "natives"];

/// Une sauvegarde, telle qu'elle est écrite sur le disque.
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Backup {
    pub id: String,
    pub instance_id: String,
    pub instance_name: String,
    /// Secondes Unix.
    pub created_at: i64,
    /// manual | launch | daily
    pub trigger: String,
    /// Dossiers inclus (« saves », « config », « mods »).
    pub includes: Vec<String>,
    pub total_bytes: i64,
    pub file_count: usize,
    pub files: Vec<FileEntry>,
    /// Renseigné une fois la sauvegarde poussée sur nos serveurs.
    #[serde(default)]
    pub cloud_id: Option<i64>,
}

/// Résumé pour les listes : le manifeste complet d'un monde fait des milliers
/// d'entrées, inutile de le charger pour dessiner une ligne.
#[derive(Serialize, Clone, Debug)]
pub struct BackupSummary {
    pub id: String,
    pub instance_id: String,
    pub instance_name: String,
    pub created_at: i64,
    pub trigger: String,
    pub includes: Vec<String>,
    pub total_bytes: i64,
    pub file_count: usize,
    pub cloud_id: Option<i64>,
}

impl From<&Backup> for BackupSummary {
    fn from(b: &Backup) -> Self {
        Self {
            id: b.id.clone(),
            instance_id: b.instance_id.clone(),
            instance_name: b.instance_name.clone(),
            created_at: b.created_at,
            trigger: b.trigger.clone(),
            includes: b.includes.clone(),
            total_bytes: b.total_bytes,
            file_count: b.file_count,
            cloud_id: b.cloud_id,
        }
    }
}

// ── Emplacements ─────────────────────────────────────────────────────────────

pub fn root() -> PathBuf {
    crate::paths::root().join("backups")
}

/// Dépôt des morceaux, partagé par TOUTES les instances : deux instances qui
/// contiennent le même mod ne le stockent qu'une fois.
fn objects_dir() -> PathBuf {
    root().join("objects")
}

/// Les morceaux sont rangés dans un sous-dossier par préfixe : un dossier de
/// cent mille fichiers plats met un système de fichiers à genoux.
fn object_path(sha: &str) -> PathBuf {
    objects_dir().join(&sha[..2]).join(sha)
}

fn manifests_dir(instance_id: &str) -> PathBuf {
    root().join("instances").join(instance_id)
}

// ── Création ─────────────────────────────────────────────────────────────────

/// Prend un instantané de l'instance.
///
/// Rend `None` quand il n'y a rien à sauvegarder — une instance sans monde, ou
/// dont aucun dossier n'a été retenu. Créer une sauvegarde vide ne rendrait
/// service à personne et remplirait la liste de lignes trompeuses.
pub fn create(
    instance_id: &str,
    instance_name: &str,
    game_dir: &Path,
    settings: &BackupSettings,
    trigger: &str,
) -> std::io::Result<Option<Backup>> {
    let includes = settings.included_dirs();
    if includes.is_empty() {
        return Ok(None);
    }
    let files = chunks::scan(game_dir, &includes, &NEVER)?;
    if files.is_empty() {
        return Ok(None);
    }

    // Écrire les morceaux AVANT le manifeste : un manifeste qui référence un
    // morceau absent est une sauvegarde qui ne restaure pas, et on ne le
    // découvrirait que le jour où on en a besoin.
    let mut written = HashSet::new();
    for file in &files {
        let source = game_dir.join(file.path.replace('/', std::path::MAIN_SEPARATOR_STR));
        for (index, sha) in file.chunks.iter().enumerate() {
            if !written.insert(sha.clone()) {
                continue;
            }
            store_chunk(&source, index, sha)?;
        }
    }

    let backup = Backup {
        id: uuid::Uuid::new_v4().to_string(),
        instance_id: instance_id.to_string(),
        instance_name: instance_name.to_string(),
        created_at: chrono::Utc::now().timestamp(),
        trigger: trigger.to_string(),
        includes,
        total_bytes: files.iter().map(|f| f.size).sum(),
        file_count: files.len(),
        files,
        cloud_id: None,
    };
    write_manifest(&backup)?;
    prune(instance_id, settings.keep)?;
    Ok(Some(backup))
}

/// Copie un morceau dans le dépôt, s'il n'y est pas déjà. L'écriture passe par
/// un fichier temporaire : un morceau à moitié écrit porterait le nom d'une
/// empreinte qu'il ne respecte pas, et serait pris pour bon à jamais.
fn store_chunk(source: &Path, index: usize, sha: &str) -> std::io::Result<()> {
    let target = object_path(sha);
    if target.exists() {
        return Ok(());
    }
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let data = read_chunk(source, index)?;
    let temp = target.with_extension("part");
    std::fs::write(&temp, &data)?;
    std::fs::rename(&temp, &target)
}

fn read_chunk(file: &Path, index: usize) -> std::io::Result<Vec<u8>> {
    use std::io::{Read, Seek, SeekFrom};
    let mut handle = std::fs::File::open(file)?;
    handle.seek(SeekFrom::Start((index * chunks::CHUNK_SIZE) as u64))?;
    let mut buffer = vec![0u8; chunks::CHUNK_SIZE];
    let mut filled = 0;
    while filled < chunks::CHUNK_SIZE {
        match handle.read(&mut buffer[filled..])? {
            0 => break,
            n => filled += n,
        }
    }
    buffer.truncate(filled);
    Ok(buffer)
}

fn write_manifest(backup: &Backup) -> std::io::Result<()> {
    let dir = manifests_dir(&backup.instance_id);
    std::fs::create_dir_all(&dir)?;
    std::fs::write(dir.join(format!("{}.json", backup.id)), serde_json::to_vec(backup)?)
}

// ── Lecture ──────────────────────────────────────────────────────────────────

/// Les sauvegardes d'une instance, de la plus récente à la plus ancienne.
pub fn list(instance_id: &str) -> Vec<Backup> {
    let Ok(entries) = std::fs::read_dir(manifests_dir(instance_id)) else { return Vec::new() };
    let mut out: Vec<Backup> = entries
        .flatten()
        .filter(|e| e.path().extension().and_then(|x| x.to_str()) == Some("json"))
        .filter_map(|e| serde_json::from_slice(&std::fs::read(e.path()).ok()?).ok())
        .collect();
    out.sort_by(|a: &Backup, b| b.created_at.cmp(&a.created_at));
    out
}

/// Toutes les sauvegardes de toutes les instances.
pub fn list_all() -> Vec<Backup> {
    let Ok(entries) = std::fs::read_dir(root().join("instances")) else { return Vec::new() };
    let mut out: Vec<Backup> = entries
        .flatten()
        .filter(|e| e.path().is_dir())
        .flat_map(|e| list(&e.file_name().to_string_lossy()))
        .collect();
    out.sort_by(|a, b| b.created_at.cmp(&a.created_at));
    out
}

pub fn get(instance_id: &str, backup_id: &str) -> Option<Backup> {
    let path = manifests_dir(instance_id).join(format!("{backup_id}.json"));
    serde_json::from_slice(&std::fs::read(path).ok()?).ok()
}

/// Place occupée par le dépôt, morceaux partagés compris. C'est le seul
/// chiffre honnête : additionner la taille des sauvegardes compterait
/// plusieurs fois les morceaux communs et annoncerait dix fois trop.
pub fn disk_usage() -> u64 {
    fn walk(dir: &Path) -> u64 {
        let Ok(entries) = std::fs::read_dir(dir) else { return 0 };
        entries
            .flatten()
            .map(|e| match e.metadata() {
                Ok(m) if m.is_dir() => walk(&e.path()),
                Ok(m) => m.len(),
                Err(_) => 0,
            })
            .sum()
    }
    walk(&root())
}

// ── Restauration ─────────────────────────────────────────────────────────────

/// Réécrit les fichiers de la sauvegarde dans l'instance.
///
/// `replace` supprime d'abord ce que la sauvegarde couvre : c'est le seul
/// moyen de vraiment revenir en arrière, sinon un monde corrompu garderait
/// ses fichiers en trop. Sans lui, la restauration se contente d'ajouter et
/// d'écraser, ce qui est plus sûr quand on ne veut récupérer qu'un morceau.
pub fn restore(backup: &Backup, game_dir: &Path, replace: bool) -> std::io::Result<usize> {
    if replace {
        for dir in &backup.includes {
            let path = game_dir.join(dir);
            if path.is_dir() {
                std::fs::remove_dir_all(&path)?;
            }
        }
    }

    let mut restored = 0;
    for file in &backup.files {
        let target = game_dir.join(file.path.replace('/', std::path::MAIN_SEPARATOR_STR));
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut content = Vec::with_capacity(file.size.max(0) as usize);
        for sha in &file.chunks {
            content.extend_from_slice(&std::fs::read(object_path(sha))?);
        }
        std::fs::write(&target, content)?;
        restored += 1;
    }
    Ok(restored)
}

// ── Ménage ───────────────────────────────────────────────────────────────────

/// Ne garde que les `keep` sauvegardes les plus récentes de l'instance.
/// `keep = 0` ne supprime rien : c'est « garder tout », pas « tout jeter ».
pub fn prune(instance_id: &str, keep: u32) -> std::io::Result<usize> {
    if keep == 0 {
        return Ok(0);
    }
    let all = list(instance_id);
    if all.len() <= keep as usize {
        return Ok(0);
    }
    let mut removed = 0;
    for old in all.into_iter().skip(keep as usize) {
        std::fs::remove_file(manifests_dir(instance_id).join(format!("{}.json", old.id)))?;
        removed += 1;
    }
    collect_garbage();
    Ok(removed)
}

pub fn delete(instance_id: &str, backup_id: &str) -> std::io::Result<()> {
    let path = manifests_dir(instance_id).join(format!("{backup_id}.json"));
    match std::fs::remove_file(path) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        other => other?,
    }
    collect_garbage();
    Ok(())
}

/// Supprime les morceaux qu'aucun manifeste ne référence plus.
///
/// Lancé après chaque suppression : sans lui, le dépôt ne ferait que grossir,
/// et l'intérêt du partage de morceaux se retournerait contre la personne.
pub fn collect_garbage() -> u64 {
    let referenced: HashSet<String> = list_all().iter().flat_map(|b| b.files.iter().flat_map(|f| f.chunks.clone())).collect();
    let mut freed = 0;
    let Ok(prefixes) = std::fs::read_dir(objects_dir()) else { return 0 };
    for prefix in prefixes.flatten() {
        let Ok(entries) = std::fs::read_dir(prefix.path()) else { continue };
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if referenced.contains(&name) {
                continue;
            }
            let size = entry.metadata().map(|m| m.len()).unwrap_or(0);
            if std::fs::remove_file(entry.path()).is_ok() {
                freed += size;
            }
        }
    }
    freed
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Chaque test travaille dans sa propre racine : `paths::root()` est
    /// global, donc on ne peut pas le détourner — les tests ci-dessous
    /// n'exercent que les fonctions pures et le format du manifeste.
    fn sample(files: Vec<FileEntry>, created_at: i64) -> Backup {
        Backup {
            id: uuid::Uuid::new_v4().to_string(),
            instance_id: "coco".into(),
            instance_name: "CocoWorld".into(),
            created_at,
            trigger: "manual".into(),
            includes: vec!["saves".into()],
            total_bytes: files.iter().map(|f| f.size).sum(),
            file_count: files.len(),
            files,
            cloud_id: None,
        }
    }

    #[test]
    fn objects_are_spread_over_prefix_folders() {
        let sha = "ab".repeat(32);
        let path = object_path(&sha);
        assert!(path.ends_with(&sha));
        assert_eq!(path.parent().unwrap().file_name().unwrap(), "ab", "un dossier plat de 100 000 fichiers est ingérable");
    }

    #[test]
    fn a_summary_keeps_what_a_list_needs_and_drops_the_manifest() {
        let files = vec![FileEntry { path: "saves/Monde/level.dat".into(), size: 12, chunks: vec!["a".repeat(64)] }];
        let backup = sample(files, 1_700_000_000);
        let summary = BackupSummary::from(&backup);
        assert_eq!(summary.file_count, 1);
        assert_eq!(summary.total_bytes, 12);
        assert_eq!(summary.instance_name, "CocoWorld");
    }

    #[test]
    fn the_manifest_survives_a_round_trip() {
        let files = vec![FileEntry { path: "saves/Monde/level.dat".into(), size: 5, chunks: chunks::hash_chunks(b"hello") }];
        let backup = sample(files, 42);
        let json = serde_json::to_vec(&backup).unwrap();
        let back: Backup = serde_json::from_slice(&json).unwrap();
        assert_eq!(back.files, backup.files);
        assert_eq!(back.trigger, "manual");
        assert!(back.cloud_id.is_none());
    }

    #[test]
    fn an_old_manifest_without_cloud_id_still_reads() {
        // Les sauvegardes prises avant l'envoi vers le serveur n'ont pas le
        // champ : elles doivent rester lisibles, pas disparaître de la liste.
        let json = r#"{"id":"x","instance_id":"coco","instance_name":"C","created_at":1,
            "trigger":"manual","includes":["saves"],"total_bytes":0,"file_count":0,"files":[]}"#;
        let back: Backup = serde_json::from_str(json).unwrap();
        assert!(back.cloud_id.is_none());
    }

    #[test]
    fn regenerated_folders_are_never_backed_up() {
        for noise in ["logs", "crash-reports", "cache"] {
            assert!(NEVER.contains(&noise));
        }
    }

    #[test]
    fn keeping_zero_means_keeping_everything() {
        // `keep = 0` dans les réglages veut dire « pas de limite ». L'inverse
        // effacerait la sauvegarde qu'on vient tout juste de prendre.
        assert_eq!(prune("instance-qui-nexiste-pas", 0).unwrap(), 0);
    }
}
