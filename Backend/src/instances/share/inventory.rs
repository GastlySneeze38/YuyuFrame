//! Inventaire : ce que le dossier de l'instance contient réellement, et ce qui
//! en est proposé au partage.

use super::*;

#[derive(Serialize, Clone, Copy, PartialEq, Eq, Debug)]
#[serde(rename_all = "lowercase")]
pub enum Group {
    Mods,
    Resourcepacks,
    Shaderpacks,
    Settings,
    Servers,
    Saves,
    Other,
}

impl Group {
    pub(super) fn selected_by_default(self) -> bool {
        matches!(self, Group::Mods | Group::Resourcepacks | Group::Shaderpacks | Group::Settings)
    }
}

/// D'où le destinataire récupérera un élément.
#[derive(Serialize, Clone, Copy, PartialEq, Eq, Debug)]
#[serde(rename_all = "lowercase")]
pub enum Source {
    Modrinth,
    Curseforge,
    /// Copié dans le pack.
    Embedded,
}

/// Une unité de partage : un fichier de contenu, ou un dossier entier
/// (`config/`, un monde…) qui se coche d'un seul geste.
#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ShareItem {
    /// Chemin relatif à l'instance, séparé par `/` — c'est aussi l'identifiant
    /// que l'interface renvoie pour désigner sa sélection.
    pub path: String,
    pub group: Group,
    pub size: u64,
    pub files: u32,
    pub source: Source,
    pub selected: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ShareScan {
    pub name: String,
    pub mc_version: String,
    pub loader: String,
    pub items: Vec<ShareItem>,
    /// Modrinth n'a pas répondu : les fichiers qu'il aurait reconnus sont
    /// comptés comme embarqués, et l'interface le dit — sinon le pack
    /// gonflerait sans explication.
    pub lookup_failed: bool,
    /// Configuration Java qui partira, déjà filtrée.
    pub jvm: JvmShare,
    /// Arguments JVM qui ne partiront pas (filtre de sécurité).
    pub jvm_rejected: Vec<String>,
    pub jvm_profile: Option<String>,
    /// Nombre d'options du client intégré qui partiraient (0 : jamais lancé).
    pub client_options: u32,
}

/// Élément trouvé sur le disque, avant d'avoir cherché où il se télécharge.
pub(super) struct Entry {
    pub(super) path: String,
    pub(super) abs: PathBuf,
    pub(super) group: Group,
    pub(super) size: u64,
    pub(super) files: u32,
    /// Fichier de contenu qu'une plateforme peut connaître (jar, zip).
    pub(super) lookup: bool,
}

/// Classe un nom de premier niveau. `None` = jamais proposé.
pub(super) fn top_level_group(name: &str, is_dir: bool) -> Option<Group> {
    let lower = name.to_lowercase();
    if lower.starts_with('.') || NEVER_SHARED.contains(&lower.as_str()) {
        return None;
    }
    Some(match (lower.as_str(), is_dir) {
        ("mods", true) => Group::Mods,
        ("resourcepacks", true) => Group::Resourcepacks,
        ("shaderpacks", true) => Group::Shaderpacks,
        ("saves", true) => Group::Saves,
        ("config", true) => Group::Settings,
        ("servers.dat", false) => Group::Servers,
        (n, false) if SETTINGS_FILES.contains(&n) => Group::Settings,
        _ => Group::Other,
    })
}

/// Taille et nombre de fichiers d'un dossier, liens symboliques ignorés.
pub(super) fn dir_stats(dir: &Path) -> (u64, u32) {
    let mut size = 0u64;
    let mut files = 0u32;
    let Ok(entries) = std::fs::read_dir(dir) else { return (0, 0) };
    for entry in entries.flatten() {
        let Ok(ty) = entry.file_type() else { continue };
        if ty.is_symlink() {
            continue;
        }
        if ty.is_dir() {
            let (s, f) = dir_stats(&entry.path());
            size += s;
            files += f;
        } else if let Ok(meta) = entry.metadata() {
            size += meta.len();
            files += 1;
        }
    }
    (size, files)
}

pub(super) fn entry_for(abs: PathBuf, path: String, group: Group, lookup: bool) -> Option<Entry> {
    let meta = std::fs::symlink_metadata(&abs).ok()?;
    if meta.file_type().is_symlink() {
        return None;
    }
    let (size, files) = if meta.is_dir() { dir_stats(&abs) } else { (meta.len(), 1) };
    if files == 0 {
        return None;
    }
    Some(Entry { path, abs, group, size, files, lookup })
}

/// Tout ce qui peut se partager dans une instance.
pub(super) fn inventory(dir: &Path) -> Vec<Entry> {
    let mut out = Vec::new();
    let Ok(entries) = std::fs::read_dir(dir) else { return out };
    let mut entries: Vec<_> = entries.flatten().collect();
    entries.sort_by_key(|e| e.file_name().to_string_lossy().to_lowercase());

    for entry in entries {
        let name = entry.file_name().to_string_lossy().to_string();
        let Ok(ty) = entry.file_type() else { continue };
        if ty.is_symlink() {
            continue;
        }
        let Some(group) = top_level_group(&name, ty.is_dir()) else { continue };

        match group {
            // Dossiers de contenu : un élément par fichier, pour qu'on puisse
            // retirer un mod sans retirer les autres.
            Group::Mods | Group::Resourcepacks | Group::Shaderpacks | Group::Saves => {
                let Ok(children) = std::fs::read_dir(entry.path()) else { continue };
                let mut children: Vec<_> = children.flatten().collect();
                children.sort_by_key(|e| e.file_name().to_string_lossy().to_lowercase());
                for child in children {
                    let child_name = child.file_name().to_string_lossy().to_string();
                    if child_name.starts_with('.') {
                        continue;
                    }
                    let Ok(child_ty) = child.file_type() else { continue };
                    let lower = child_name.to_lowercase();
                    let lookup = match group {
                        // Hors des jars, `mods/` ne contient que des restes
                        // d'outils (sauvegardes, index) : rien à partager.
                        Group::Mods => {
                            if !child_ty.is_file() || !is_jar_file(&child_name) {
                                continue;
                            }
                            true
                        }
                        // Les mondes sont des dossiers.
                        Group::Saves => {
                            if !child_ty.is_dir() {
                                continue;
                            }
                            false
                        }
                        // Un pack zippé peut être connu d'une plateforme ; un
                        // pack décompressé ou le réglage d'un shader (`.txt`)
                        // ne l'est jamais.
                        _ => child_ty.is_file() && lower.ends_with(".zip"),
                    };
                    let path = format!("{name}/{child_name}");
                    if let Some(e) = entry_for(child.path(), path, group, lookup) {
                        out.push(e);
                    }
                }
            }
            _ => {
                if let Some(e) = entry_for(entry.path(), name, group, false) {
                    out.push(e);
                }
            }
        }
    }
    out
}
