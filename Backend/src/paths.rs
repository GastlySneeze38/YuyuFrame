// Racine de toutes les données YuyuFrame (agent LauncherAgent, P2P, .minecraft
// partagé, skins offline) — PAS la base SQLite, qui reste résolue
// indépendamment à côté de l'exécutable/du repo (voir lib.rs). Configurable
// par l'utilisateur (Settings.tsx, section Stockage) : un petit fichier ancre
// retient le chemin réel choisi. Cet ancre vit TOUJOURS à côté du dossier
// `YuyuFrame` par défaut (jamais dedans) — sinon le déplacer supprimerait le
// pointeur qui indique où il a été déplacé.

use std::path::{Path, PathBuf};

fn app_data_base() -> PathBuf {
    dirs::data_dir().unwrap_or_else(|| PathBuf::from("."))
}

fn default_root() -> PathBuf {
    app_data_base().join("YuyuFrame")
}

fn anchor_file() -> PathBuf {
    app_data_base().join("yuyuframe_location.txt")
}

/// Racine actuelle des données — lue depuis le fichier ancre si présent,
/// sinon l'emplacement par défaut (%AppData%\YuyuFrame). Jamais mise en
/// cache : un déplacement via `set_root` prend effet immédiatement pour tout
/// appel suivant, sans redémarrage du launcher.
pub fn root() -> PathBuf {
    if let Ok(content) = std::fs::read_to_string(anchor_file()) {
        let trimmed = content.trim();
        if !trimmed.is_empty() {
            return PathBuf::from(trimmed);
        }
    }
    default_root()
}

/// Crée (si absent) le dossier racine et ses sous-dossiers visibles même
/// vides (`.minecraft`, `agent`, `p2p`) — avant ça, `p2p` en particulier
/// n'apparaissait qu'à la toute première session P2P, rendant la structure
/// incohérente pour qui va inspecter ou déplacer ce dossier.
pub fn ensure_structure() {
    let root = root();
    for sub in [".minecraft", "agent", "p2p"] {
        let _ = std::fs::create_dir_all(root.join(sub));
    }
}

fn copy_dir_recursive(src: &Path, dst: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(dst)?;
    for entry in std::fs::read_dir(src)? {
        let entry = entry?;
        let ty = entry.file_type()?;
        let dest_path = dst.join(entry.file_name());
        if ty.is_dir() {
            copy_dir_recursive(&entry.path(), &dest_path)?;
        } else {
            std::fs::copy(entry.path(), &dest_path)?;
        }
    }
    Ok(())
}

/// Déplace toutes les données YuyuFrame vers `new_parent/YuyuFrame` : copie
/// intégrale d'abord, l'ancien dossier n'est supprimé qu'APRÈS un succès
/// complet de la copie (jamais de perte si la copie échoue à mi-chemin —
/// disque plein, permissions...), puis le fichier ancre est mis à jour.
/// Retourne le nouveau chemin racine.
pub fn set_root(new_parent: &Path) -> Result<PathBuf, String> {
    let old_root = root();
    let new_root = new_parent.join("YuyuFrame");

    if new_root == old_root {
        return Ok(new_root);
    }
    if new_root.starts_with(&old_root) {
        return Err("Le nouveau dossier ne peut pas être à l'intérieur de l'ancien".into());
    }

    if old_root.exists() {
        copy_dir_recursive(&old_root, &new_root)
            .map_err(|e| format!("Échec de la copie vers {} : {}", new_root.display(), e))?;
    } else {
        std::fs::create_dir_all(&new_root).map_err(|e| e.to_string())?;
    }

    let anchor = anchor_file();
    if let Some(parent) = anchor.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    std::fs::write(&anchor, new_root.to_string_lossy().as_bytes())
        .map_err(|e| format!("Copie réussie mais impossible d'enregistrer le nouvel emplacement : {}", e))?;

    if old_root.exists() {
        if let Err(e) = std::fs::remove_dir_all(&old_root) {
            tracing::warn!(
                "[Paths] déplacement réussi mais nettoyage de l'ancien dossier {} échoué : {}",
                old_root.display(), e,
            );
        }
    }

    ensure_structure();
    Ok(new_root)
}

/// Ouvre un dossier dans l'explorateur Windows — le crée d'abord s'il
/// n'existe pas encore (ex: dossier d'une instance jamais lancée). Utilise
/// `explorer.exe` directement plutôt que le plugin `shell` de Tauri : ce
/// dernier restreint `open()` aux liens http(s)/mailto/tel par défaut, pas
/// aux chemins de dossiers locaux.
pub fn open_in_explorer(path: &Path) -> Result<(), String> {
    std::fs::create_dir_all(path).map_err(|e| e.to_string())?;
    std::process::Command::new("explorer")
        .arg(path)
        .spawn()
        .map_err(|e| e.to_string())?;
    Ok(())
}
