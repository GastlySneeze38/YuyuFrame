//! Chemins venus de l'extérieur — entrée d'une archive, fichier nommé par un
//! pack ou un lien de partage. Leur nom est choisi par qui a fabriqué
//! l'archive : `../../AppData/…` est une entrée de zip parfaitement valide.
//! Rien de ce qui est lu là ne s'écrit sur le disque sans passer par ici.

use std::path::{Path, PathBuf};

/// Fichiers qu'un pack reçu n'a jamais le droit d'écrire.
const PROTECTED_FILES: &[&str] = &["meta.json", "modpack.json", "modpack_backup.json"];

/// Chemin relatif sûr : ni `..`, ni lecteur, ni chemin absolu, ni fichier
/// interne du launcher. Rend le chemin normalisé avec des `/`. Sert aussi à
/// l'import de modpacks (`modpack.rs`), qui lit des archives tout aussi
/// étrangères.
pub fn safe_relative(path: &str) -> Option<String> {
    if path.starts_with('/') || path.starts_with('\\') {
        return None;
    }
    let parts: Vec<&str> = path.split(['/', '\\']).filter(|p| !p.is_empty() && *p != ".").collect();
    if parts.is_empty() || parts.iter().any(|p| *p == ".." || p.contains(':')) {
        return None;
    }
    if parts.len() == 1 && PROTECTED_FILES.contains(&parts[0].to_lowercase().as_str()) {
        return None;
    }
    Some(parts.join("/"))
}

pub fn join_relative(base: &Path, rel: &str) -> PathBuf {
    rel.split('/').fold(base.to_path_buf(), |acc, part| acc.join(part))
}

/// Un chemin lu dans une archive est-il sûr à écrire sous `base` ?
///
/// Une entrée de zip est une chaîne quelconque, choisie par qui a fabriqué
/// l'archive : `../../../AppData/…` est un nom d'entrée parfaitement valide.
/// On n'accepte donc que des composants ordinaires, et on refuse tout le
/// reste plutôt que d'essayer de le réparer.
pub fn safe_join(base: &Path, entry: &str) -> Option<PathBuf> {
    let mut out = base.to_path_buf();
    let mut depth = 0usize;
    for part in entry.split(['/', '\\']) {
        if part.is_empty() || part == "." {
            continue;
        }
        if part == ".." || part.contains(':') {
            return None;
        }
        out.push(part);
        depth += 1;
    }
    (depth > 0).then_some(out)
}
