//! Formes partagées par les écrans de synchronisation.
//!
//! Ce fichier portait tout l'ancien protocole : construction d'un zip de
//! l'instance, résolution des mods chez Modrinth pour n'envoyer que des
//! références, extraction à l'arrivée. Le passage au protocole par morceaux
//! (2026-09-21) l'a rendu inutile — les morceaux adressés par empreinte font
//! mieux et plus simplement ce que la déduplication par référence Modrinth
//! tentait d'approcher : un mod déjà connu du serveur ne repart pas, qu'il
//! vienne de Modrinth, de CurseForge ou d'un fichier posé à la main.
//!
//! Ne restent que les types que le frontend lit, et la taille d'un dossier.

use serde::{Deserialize, Serialize};
use std::path::Path;

/// Une instance synchronisée, telle que le serveur la décrit. Mêmes champs
/// que `SyncInstance` de `LauncherAPI/src/launcher/sync.rs` : ce type ne fait
/// que traverser, et en inventer une forme locale garantirait qu'elles
/// divergent un jour.
#[derive(Serialize, Deserialize, Clone)]
pub struct SyncInstance {
    pub id: i64,
    pub instance_name: String,
    pub mc_version: String,
    pub loader: String,
    pub ram_mb: i32,
    /// Toujours 0 depuis que les mondes relèvent du backup ; gardé parce que
    /// le serveur l'envoie encore et qu'un ancien envoi peut en avoir.
    #[serde(default)]
    pub save_count: i32,
    #[serde(default)]
    pub save_names: Vec<String>,
    /// Augmente à chaque envoi validé — c'est elle qui détecte un conflit
    /// entre deux PC.
    pub revision: i64,
    pub total_bytes: i64,
    pub file_count: i32,
    /// RFC 3339.
    pub updated_at: String,
}

/// Un monde de l'instance. Sert au backup, qui est désormais seul à s'en
/// occuper — la sync ne touche plus aux mondes.
#[derive(Serialize, Clone)]
pub struct SaveInfo {
    pub name: String,
    pub updated_at: i64,
    pub size_bytes: u64,
}

/// Avancement d'un transfert. `phase` : scanning | comparing | uploading |
/// downloading | done.
#[derive(Serialize, Clone)]
pub struct SyncProgressEvent {
    pub phase: String,
    pub percent: u8,
    pub label: String,
}

/// Taille d'un dossier, récursivement. Les liens ne sont pas suivis : un
/// monde qui pointe ailleurs ne doit pas faire compter tout le disque.
pub(super) fn dir_size(path: &Path) -> u64 {
    let mut size = 0u64;
    let Ok(entries) = std::fs::read_dir(path) else { return 0 };
    for entry in entries.flatten() {
        let Ok(meta) = std::fs::symlink_metadata(entry.path()) else { continue };
        if meta.file_type().is_symlink() {
            continue;
        }
        if meta.is_file() {
            size += meta.len();
        } else if meta.is_dir() {
            size += dir_size(&entry.path());
        }
    }
    size
}
