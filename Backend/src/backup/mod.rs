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

pub mod commands;
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

/// Ce qu'on retient d'un fichier d'une sauvegarde à l'autre, pour ne pas
/// avoir à le relire quand il n'a pas bougé.
#[derive(Serialize, Deserialize, Clone)]
struct Known {
    /// Date de modification, en secondes Unix.
    mtime: i64,
    size: i64,
    chunks: Vec<String>,
}

type Index = std::collections::HashMap<String, Known>;

fn index_path(instance_id: &str) -> PathBuf {
    manifests_dir(instance_id).join("index.json")
}

/// Index de la sauvegarde précédente. Vide s'il est absent ou illisible : on
/// relira tout, ce qui est lent mais jamais faux.
fn load_index(instance_id: &str) -> Index {
    std::fs::read(index_path(instance_id)).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
}

fn save_index(instance_id: &str, index: &Index) -> std::io::Result<()> {
    let path = index_path(instance_id);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(path, serde_json::to_vec(index)?)
}

/// Peut-on réutiliser les empreintes connues de ce fichier, sans le relire ?
///
/// Deux conditions, et la seconde compte autant que la première : une
/// sauvegarde élaguée entre-temps a pu emporter ses morceaux du dépôt, et
/// réutiliser ses empreintes donnerait un manifeste qui ne restaure pas —
/// une sauvegarde qui ment, découverte le jour où elle sert.
fn reusable<'a>(known: Option<&'a Known>, mtime: i64, size: i64, present: impl Fn(&str) -> bool) -> Option<&'a Known> {
    known.filter(|k| k.mtime == mtime && k.size == size).filter(|k| k.chunks.iter().all(|c| present(c)))
}

fn mtime_of(meta: &std::fs::Metadata) -> i64 {
    meta.modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// Prend un instantané de l'instance.
///
/// Rend `None` quand il n'y a rien à sauvegarder — une instance sans monde, ou
/// dont aucun dossier n'a été retenu. Créer une sauvegarde vide ne rendrait
/// service à personne et remplirait la liste de lignes trompeuses.
///
/// Deux économies, qui changent tout sur un monde de plusieurs centaines de
/// mégaoctets :
///
/// - **un fichier inchangé n'est pas relu.** Sa date et sa taille suffisent à
///   réutiliser les empreintes de la sauvegarde précédente. Sur un monde où
///   trois régions ont bougé, on lit trois régions au lieu du monde entier ;
/// - **un fichier changé n'est lu qu'une fois.** La version d'avant le lisait
///   pour calculer les empreintes, puis relisait chaque morceau depuis le
///   disque pour l'écrire dans le dépôt — deux fois le monde à chaque
///   sauvegarde.
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

    let known = load_index(instance_id);
    let mut fresh: Index = Index::new();
    let mut files: Vec<FileEntry> = Vec::new();
    // Morceaux déjà traités pendant CETTE sauvegarde : deux copies du même
    // fichier ne s'écrivent pas deux fois.
    let mut seen: HashSet<String> = HashSet::new();

    for (path, source, meta) in walk_selected(game_dir, &includes)? {
        let (mtime, size) = (mtime_of(&meta), meta.len() as i64);

        // Chemin rapide : rien n'a bougé, et les morceaux sont encore dans le
        // dépôt.
        let reused = reusable(known.get(&path), mtime, size, |sha| object_path(sha).exists());
        if let Some(k) = reused {
            seen.extend(k.chunks.iter().cloned());
            files.push(FileEntry { path: path.clone(), size, chunks: k.chunks.clone() });
            fresh.insert(path, k.clone());
            continue;
        }

        // Une seule lecture, et les morceaux partent de ce qu'on a déjà en
        // mémoire.
        let data = std::fs::read(&source)?;
        let hashes = chunks::hash_chunks(&data);
        for (index, sha) in hashes.iter().enumerate() {
            if !seen.insert(sha.clone()) {
                continue;
            }
            let start = index * chunks::CHUNK_SIZE;
            let end = (start + chunks::CHUNK_SIZE).min(data.len());
            store_chunk(sha, &data[start..end])?;
        }
        files.push(FileEntry { path: path.clone(), size: data.len() as i64, chunks: hashes.clone() });
        fresh.insert(path, Known { mtime, size: data.len() as i64, chunks: hashes });
    }

    if files.is_empty() {
        return Ok(None);
    }
    files.sort_by(|a, b| a.path.cmp(&b.path));

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
    // Le manifeste après les morceaux : un manifeste qui en référence un
    // absent est une sauvegarde qui ne restaure pas, et on ne le découvrirait
    // que le jour où on en a besoin.
    write_manifest(&backup)?;
    // L'index après le manifeste, et sans bloquer : le perdre ne coûte qu'une
    // sauvegarde lente, pas une sauvegarde fausse.
    if let Err(e) = save_index(instance_id, &fresh) {
        tracing::warn!("[Backup] index non écrit, la prochaine sauvegarde relira tout : {e}");
    }
    prune(instance_id, settings.keep)?;
    Ok(Some(backup))
}

/// Parcourt les dossiers retenus et rend, pour chaque fichier, son chemin de
/// manifeste, son chemin réel et ses métadonnées — sans jamais lire son
/// contenu. C'est ce qui permet de décider quoi relire.
#[allow(clippy::type_complexity)]
fn walk_selected(root: &Path, includes: &[String]) -> std::io::Result<Vec<(String, PathBuf, std::fs::Metadata)>> {
    fn walk(root: &Path, dir: &Path, out: &mut Vec<(String, PathBuf, std::fs::Metadata)>) -> std::io::Result<()> {
        let Ok(entries) = std::fs::read_dir(dir) else { return Ok(()) };
        for entry in entries.flatten() {
            let path = entry.path();
            // Jamais de lien symbolique : à la restauration, il écrirait hors
            // du dossier de l'instance.
            let meta = std::fs::symlink_metadata(&path)?;
            if meta.file_type().is_symlink() {
                continue;
            }
            let name = entry.file_name();
            let name = name.to_string_lossy();
            if NEVER.iter().any(|s| s.eq_ignore_ascii_case(&name)) {
                continue;
            }
            if meta.is_dir() {
                walk(root, &path, out)?;
            } else if meta.is_file() {
                if let Some(rel) = relative_path(root, &path) {
                    out.push((rel, path, meta));
                }
            }
        }
        Ok(())
    }

    let mut out = Vec::new();
    for dir in includes {
        let start = root.join(dir);
        if start.is_dir() {
            walk(root, &start, &mut out)?;
        }
    }
    Ok(out)
}

/// Chemin de manifeste (« saves/Monde/level.dat »), ou `None` si le chemin
/// sort de la racine ou n'est pas représentable.
fn relative_path(root: &Path, file: &Path) -> Option<String> {
    let mut parts = Vec::new();
    for part in file.strip_prefix(root).ok()?.components() {
        match part {
            std::path::Component::Normal(p) => parts.push(p.to_str()?.to_string()),
            _ => return None,
        }
    }
    (!parts.is_empty()).then(|| parts.join("/"))
}

/// Écrit un morceau dans le dépôt, s'il n'y est pas déjà. L'écriture passe par
/// un fichier temporaire : un morceau à moitié écrit porterait le nom d'une
/// empreinte qu'il ne respecte pas, et serait pris pour bon à jamais.
fn store_chunk(sha: &str, data: &[u8]) -> std::io::Result<()> {
    let target = object_path(sha);
    if target.exists() {
        return Ok(());
    }
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent)?;
    }
    // Nom temporaire unique : deux sauvegardes d'instances différentes peuvent
    // écrire le même morceau en même temps, et se marcher dessus.
    let temp = target.with_extension(format!("part{}", std::process::id()));
    std::fs::write(&temp, data)?;
    match std::fs::rename(&temp, &target) {
        // L'autre l'a posé entre-temps : le contenu est identique par
        // construction, il n'y a rien à réparer.
        Err(_) if target.exists() => {
            let _ = std::fs::remove_file(&temp);
            Ok(())
        }
        other => other,
    }
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
///
/// Ne fait **pas** le ménage du dépôt. C'est délibéré : `prune` est appelé à
/// chaque sauvegarde, y compris juste avant un lancement, et un ramassage
/// relit tous les manifestes de toutes les instances. On paierait ce prix à
/// chaque partie pour libérer quelques morceaux qui ne gênent personne. Le
/// ménage se fait à la suppression explicite et par le bouton dédié.
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
    // Les index comptent autant que les manifestes : ils réutilisent les
    // morceaux d'une sauvegarde à l'autre, et les effacer sous leurs pieds
    // ferait relire tout le monde à la sauvegarde suivante.
    let mut referenced: HashSet<String> = list_all().iter().flat_map(|b| b.files.iter().flat_map(|f| f.chunks.clone())).collect();
    if let Ok(dirs) = std::fs::read_dir(root().join("instances")) {
        for dir in dirs.flatten().filter(|e| e.path().is_dir()) {
            let index = load_index(&dir.file_name().to_string_lossy());
            referenced.extend(index.into_values().flat_map(|k| k.chunks));
        }
    }
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

    fn known(mtime: i64, size: i64) -> Known {
        Known { mtime, size, chunks: vec!["a".repeat(64), "b".repeat(64)] }
    }

    #[test]
    fn an_untouched_file_is_never_read_again() {
        let k = known(1000, 42);
        assert!(reusable(Some(&k), 1000, 42, |_| true).is_some(), "même date, même taille, morceaux présents");
    }

    #[test]
    fn a_touched_file_is_read_again() {
        let k = known(1000, 42);
        assert!(reusable(Some(&k), 1001, 42, |_| true).is_none(), "la date a bougé");
        assert!(reusable(Some(&k), 1000, 43, |_| true).is_none(), "la taille a bougé");
        assert!(reusable(None, 1000, 42, |_| true).is_none(), "jamais vu");
    }

    #[test]
    fn a_pruned_chunk_forces_a_reread() {
        // Le cas dangereux : le fichier n'a pas bougé, mais l'élagage a
        // emporté ses morceaux. Réutiliser ses empreintes écrirait un
        // manifeste irrestaurable.
        let k = known(1000, 42);
        let missing_second = |sha: &str| sha.starts_with('a');
        assert!(reusable(Some(&k), 1000, 42, missing_second).is_none());
    }

    #[test]
    fn the_index_survives_a_round_trip() {
        let mut index = Index::new();
        index.insert("saves/Monde/level.dat".into(), known(1700, 512));
        let json = serde_json::to_vec(&index).unwrap();
        let back: Index = serde_json::from_slice(&json).unwrap();
        assert_eq!(back["saves/Monde/level.dat"].chunks.len(), 2);
    }

    #[test]
    fn manifest_paths_stay_inside_the_instance() {
        let root = Path::new("C:/instances/coco");
        assert_eq!(relative_path(root, Path::new("C:/instances/coco/saves/M/level.dat")).as_deref(), Some("saves/M/level.dat"));
        assert!(relative_path(root, Path::new("C:/instances/autre/level.dat")).is_none());
    }

    #[test]
    fn keeping_zero_means_keeping_everything() {
        // `keep = 0` dans les réglages veut dire « pas de limite ». L'inverse
        // effacerait la sauvegarde qu'on vient tout juste de prendre.
        assert_eq!(prune("instance-qui-nexiste-pas", 0).unwrap(), 0);
    }
}
