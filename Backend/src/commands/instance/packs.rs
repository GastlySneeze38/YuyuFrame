//! Packs de ressources et shaders d'une instance.
//!
//! Volontairement séparé de `mods.rs` malgré des gestes très proches
//! (lister, installer, supprimer) : un mod est un `.jar` dans `mods/` qu'on
//! active ou désactive en renommant son extension, un pack est un `.zip` dans
//! `resourcepacks/` ou `shaderpacks/` que **le jeu** active — via
//! `options.txt` pour les packs de ressources, via la configuration du mod de
//! shaders pour les shaders. Il n'y a donc rien ici qui ressemble à
//! `mods_toggle`, et vouloir partager le code des deux reviendrait à
//! introduire partout un « selon le type ».
//!
//! Les deux dossiers sont déjà connus ailleurs dans le launcher :
//! `commands/instance/import.rs` les recopie à l'import d'une instance, et
//! `sync/push_pull.rs` les synchronise. Cette liste doit rester la même.

use serde::{Deserialize, Serialize};
use std::path::PathBuf;

use super::crud::instance_dir;

/// Les deux familles de packs qu'une instance peut contenir.
///
/// Sérialisé en minuscules pour coller aux `project_type` de Modrinth
/// (`resourcepack`, `shader`) : la même chaîne sert à chercher côté Modrinth
/// et à ranger le fichier ici, donc le frontend n'a qu'un vocabulaire à
/// connaître.
#[derive(Serialize, Deserialize, Clone, Copy, PartialEq, Eq, Debug)]
#[serde(rename_all = "lowercase")]
pub enum PackKind {
    Resourcepack,
    Shader,
}

impl PackKind {
    /// Dossier de l'instance où le jeu va chercher ce type de pack.
    fn dir_name(self) -> &'static str {
        match self {
            PackKind::Resourcepack => "resourcepacks",
            PackKind::Shader => "shaderpacks",
        }
    }
}

fn packs_dir(instance_id: &str, kind: PackKind) -> PathBuf {
    instance_dir(instance_id).join(kind.dir_name())
}

#[derive(Serialize, Clone, Debug, PartialEq)]
pub struct PackInfo {
    pub name: String,
    pub size: u64,
    pub kind: PackKind,
    /// Empreinte du fichier — c'est par elle que l'interface retrouve le
    /// projet Modrinth d'un pack (`/v2/version_files`), comme pour les mods :
    /// le nom du fichier téléchargé ne ressemble pas au titre du projet, un
    /// pack installé n'était donc jamais reconnu dans la recherche. Même cache
    /// (taille, date) que les mods : un pack de plusieurs centaines de Mo
    /// n'est haché qu'une fois.
    pub sha1: String,
}

/// SHA1 d'un fichier de pack, hors du fil asynchrone (lecture complète du fichier).
async fn pack_sha1(path: PathBuf) -> String {
    tokio::task::spawn_blocking(move || super::mods::sha1_cached(&path))
        .await
        .unwrap_or_default()
}

/// Un pack accepté est une archive `.zip`.
///
/// Les deux formats tolèrent aussi un dossier décompressé, mais on ne
/// l'installe jamais nous-mêmes : un dossier n'a ni taille ni identité
/// stables, et rien dans l'interface ne saurait quoi en montrer. Un dossier
/// déjà présent est simplement ignoré par `packs_list` plutôt que listé à
/// moitié.
fn is_pack_archive(name: &str) -> bool {
    name.to_ascii_lowercase().ends_with(".zip")
}

/// Ne garde que le nom de fichier d'un chemin, quelle que soit sa forme.
///
/// Le nom vient de Modrinth ou d'un fichier choisi par l'utilisateur : sans
/// ça, un nom contenant `../` écrirait hors du dossier de l'instance.
fn safe_file_name(raw: &str, fallback: &str) -> String {
    std::path::Path::new(raw)
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .filter(|n| !n.is_empty() && n != "." && n != "..")
        .unwrap_or_else(|| fallback.to_string())
}

/// Vérifie qu'un chemin reste bien à l'intérieur du dossier attendu, une fois
/// les liens symboliques et les `..` résolus. Même garde que `mods_delete`.
fn inside(path: &std::path::Path, dir: &std::path::Path) -> bool {
    let Ok(canonical) = path.canonicalize() else { return false };
    let dir = dir.canonicalize().unwrap_or_else(|_| dir.to_path_buf());
    canonical.starts_with(&dir)
}

#[tauri::command]
pub async fn packs_list(instance_id: String, kind: PackKind) -> Result<Vec<PackInfo>, String> {
    let dir = packs_dir(&instance_id, kind);
    // Dossier absent = instance qui n'a jamais reçu de pack. Ce n'est pas une
    // erreur, c'est une liste vide.
    let Ok(mut entries) = tokio::fs::read_dir(&dir).await else {
        return Ok(Vec::new());
    };

    let mut packs = Vec::new();
    while let Ok(Some(entry)) = entries.next_entry().await {
        let name = entry.file_name().to_string_lossy().to_string();
        if !is_pack_archive(&name) {
            continue;
        }
        let size = entry.metadata().await.map(|m| m.len()).unwrap_or(0);
        let sha1 = pack_sha1(entry.path()).await;
        packs.push(PackInfo { name, size, kind, sha1 });
    }
    // Ordre alphabétique insensible à la casse : `read_dir` ne garantit aucun
    // ordre, et une liste qui se réordonne à chaque rafraîchissement est
    // illisible.
    packs.sort_by_key(|p| p.name.to_lowercase());
    Ok(packs)
}

#[tauri::command]
pub async fn packs_delete(instance_id: String, kind: PackKind, name: String) -> Result<(), String> {
    let dir = packs_dir(&instance_id, kind);
    let path = dir.join(safe_file_name(&name, ""));
    if !path.exists() {
        return Err(format!("Pack « {} » introuvable", name));
    }
    if !inside(&path, &dir) {
        return Err("Accès refusé".into());
    }
    tokio::fs::remove_file(&path).await.map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub async fn packs_install(
    app: tauri::AppHandle,
    instance_id: String,
    kind: PackKind,
    url: String,
    filename: String,
) -> Result<PackInfo, String> {
    use futures::StreamExt;
    use tauri::Emitter;

    // Même restriction que `mods_install` : seul le CDN de Modrinth est
    // autorisé. Les URL arrivent d'une recherche que nous avons faite
    // nous-mêmes, jamais d'une saisie libre — accepter n'importe quel hôte
    // ferait de cette commande un téléchargeur générique.
    if !url.starts_with("https://cdn.modrinth.com/") {
        return Err("URL non autorisée".into());
    }

    let safe_name = safe_file_name(&filename, "pack.zip");
    if !is_pack_archive(&safe_name) {
        return Err("Seules les archives .zip sont acceptées".into());
    }

    let dir = packs_dir(&instance_id, kind);
    tokio::fs::create_dir_all(&dir).await.map_err(|e| e.to_string())?;

    let client = reqwest::Client::builder()
        .user_agent("YuyuFrame/1.0")
        .build()
        .map_err(|e| e.to_string())?;

    let resp = client.get(&url).send().await.map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("Téléchargement échoué: {}", resp.status()));
    }

    // Téléchargement en flux : un pack de ressources en haute résolution pèse
    // couramment plusieurs centaines de mégaoctets, bien plus qu'un mod. Sans
    // progression, l'interface resterait figée assez longtemps pour qu'on la
    // croie plantée.
    let total = resp.content_length().unwrap_or(0);
    let mut downloaded: u64 = 0;
    let mut bytes: Vec<u8> = Vec::new();
    let mut stream = resp.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| e.to_string())?;
        downloaded += chunk.len() as u64;
        bytes.extend_from_slice(&chunk);
        let _ = app.emit("pack_install_progress", serde_json::json!({
            "filename": &safe_name,
            "downloaded": downloaded,
            "total": total,
        }));
    }

    let target = dir.join(&safe_name);
    tokio::fs::write(&target, &bytes)
        .await
        .map_err(|e| e.to_string())?;
    let sha1 = pack_sha1(target).await;

    crate::integrations::analytics::capture("pack_install_succeeded", serde_json::json!({
        "instance_id": &instance_id,
        "kind": kind,
    }));
    Ok(PackInfo { name: safe_name, size: bytes.len() as u64, kind, sha1 })
}

/// Copie des archives choisies sur le disque dans le dossier de l'instance.
///
/// Rend la liste des packs effectivement ajoutés plutôt qu'une erreur au
/// premier fichier refusé : l'utilisateur peut sélectionner dix fichiers d'un
/// coup, et un intrus parmi eux ne doit pas annuler les neuf autres.
#[tauri::command]
pub async fn packs_import_paths(
    instance_id: String,
    kind: PackKind,
    paths: Vec<String>,
) -> Result<Vec<PackInfo>, String> {
    let dir = packs_dir(&instance_id, kind);
    tokio::fs::create_dir_all(&dir).await.map_err(|e| e.to_string())?;

    let mut added = Vec::new();
    for path in paths {
        let source = PathBuf::from(&path);
        let name = safe_file_name(&path, "");
        if name.is_empty() || !is_pack_archive(&name) {
            tracing::warn!("Import de pack ignoré (pas une archive .zip) : {}", path);
            continue;
        }
        let target = dir.join(&name);
        match tokio::fs::copy(&source, &target).await {
            Ok(size) => {
                let sha1 = pack_sha1(target).await;
                added.push(PackInfo { name, size, kind, sha1 });
            }
            Err(e) => tracing::warn!("Copie du pack {} échouée : {}", path, e),
        }
    }
    Ok(added)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chaque_type_a_son_dossier() {
        assert_eq!(PackKind::Resourcepack.dir_name(), "resourcepacks");
        assert_eq!(PackKind::Shader.dir_name(), "shaderpacks");
    }

    #[test]
    fn seules_les_archives_zip_sont_acceptees() {
        assert!(is_pack_archive("faithful.zip"));
        // L'extension peut remonter telle quelle de Modrinth, casse comprise.
        assert!(is_pack_archive("BSL_v8.2.zip"));
        assert!(is_pack_archive("Sildurs.ZIP"));
        assert!(!is_pack_archive("sodium.jar"));
        assert!(!is_pack_archive("pack"));
        assert!(!is_pack_archive("notes.zip.txt"));
    }

    #[test]
    fn un_nom_ne_peut_pas_sortir_du_dossier() {
        assert_eq!(safe_file_name("../../evil.zip", "pack.zip"), "evil.zip");
        assert_eq!(safe_file_name("a/b/c/pack.zip", "pack.zip"), "pack.zip");
        assert_eq!(safe_file_name("..", "pack.zip"), "pack.zip");
        assert_eq!(safe_file_name("", "pack.zip"), "pack.zip");
        assert_eq!(safe_file_name("normal.zip", "pack.zip"), "normal.zip");
    }

    #[test]
    fn le_type_se_serialise_comme_modrinth() {
        // La valeur part telle quelle en `project_type` dans la recherche
        // Modrinth : si elle change ici, la recherche ne rend plus rien.
        assert_eq!(serde_json::to_string(&PackKind::Resourcepack).unwrap(), "\"resourcepack\"");
        assert_eq!(serde_json::to_string(&PackKind::Shader).unwrap(), "\"shader\"");
    }
}
