// Racine de toutes les données YuyuFrame (agent LauncherAgent, P2P, .minecraft
// partagé, skins offline) — **et la base SQLite depuis le 2026-09-28** : elle
// vivait à côté de l'exécutable, donc une réinstallation l'emportait et le
// launcher repartait sur une base vide (voir `migrate_db_from_exe_dir` dans
// lib.rs). En dev seulement, elle reste dans Backend/. Configurable
// par l'utilisateur (Settings.tsx, section Stockage) : un petit fichier ancre
// retient le chemin réel choisi. Cet ancre vit TOUJOURS à côté du dossier
// `YuyuFrame` par défaut (jamais dedans) — sinon le déplacer supprimerait le
// pointeur qui indique où il a été déplacé.

use std::path::{Path, PathBuf};

#[cfg(not(target_os = "android"))]
fn app_data_base() -> PathBuf {
    dirs::data_dir().unwrap_or_else(|| PathBuf::from("."))
}

/// Android : les fichiers privés de l'appli (`Context.getFilesDir()`), que
/// `dirs` ne connaît pas. C'est aussi le dossier que le jeu Horizon lit
/// (`horizon-args.txt`, ses ressources préparées) : le launcher et le jeu sont
/// deux processus de la même appli, ils voient les mêmes fichiers.
#[cfg(target_os = "android")]
fn app_data_base() -> PathBuf {
    static FILES: std::sync::OnceLock<PathBuf> = std::sync::OnceLock::new();
    FILES
        .get_or_init(|| {
            android_files_dir().unwrap_or_else(|| {
                tracing::error!("dossier des fichiers de l'appli introuvable");
                PathBuf::from("/data/local/tmp/yuyuframe")
            })
        })
        .clone()
}

/// Les fichiers privés de l'appli, là où le jeu Horizon lit `horizon-args.txt`.
#[cfg(target_os = "android")]
pub fn app_files_dir() -> PathBuf {
    app_data_base()
}

/// Ce que rend `getFilesDir()`, calculé sans le demander à Android :
/// `/data/user/<utilisateur>/<paquet>/files`.
///
/// Pas par JNI : `run` (donc `root()`, pour le journal) est appelé avant que
/// l'activité de Tauri ait donné son contexte au Rust — `ndk_context` y
/// panique (« android context was not initialized »), vu sur un vrai
/// téléphone le 2026-11-08. Pas non plus `/data/data/<paquet>` écrit en dur :
/// il ne vaut que pour le premier utilisateur, pas pour un second ni pour un
/// profil professionnel.
///
/// L'utilisateur Android est l'UID du processus divisé par 100 000 ; le paquet
/// est le nom du processus (`/proc/self/cmdline`), sans le `:suffixe` d'un
/// processus secondaire.
#[cfg(target_os = "android")]
fn android_files_dir() -> Option<PathBuf> {
    use std::os::unix::fs::MetadataExt;

    let uid = std::fs::metadata("/proc/self").ok()?.uid();
    let cmdline = std::fs::read("/proc/self/cmdline").ok()?;
    let name = cmdline.split(|b| *b == 0).next()?;
    let package = std::str::from_utf8(name).ok()?.split(':').next()?.trim();
    if package.is_empty() || package.contains('/') {
        return None;
    }
    Some(PathBuf::from(format!("/data/user/{}/{}/files", uid / 100_000, package)))
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
