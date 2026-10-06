use std::io::Read;

/// Télécharge un fichier vers destPath.
///
/// Whitelist stricte sur cdn.modrinth.com — même garde que
/// Backend/src/instances/mods.rs côté Tauri, appliquée ici côté Rust JNI
/// puisque le téléchargement ne passe plus par le launcher mais par cet agent.
/// Ce garde-fou-ci reste voulu : CECI écrit un vrai fichier sur le disque du
/// joueur (resource pack/shader pack potentiellement exécuté par le jeu),
/// contrairement à `image::fetch_image_rgba` (juste un visuel en mémoire).
pub fn download_file(url: &str, dest_path: &str) -> Result<(), String> {
    if !url.starts_with("https://cdn.modrinth.com/") {
        return Err("URL non autorisée (cdn.modrinth.com uniquement)".into());
    }

    let response = ureq::get(url)
        .set("User-Agent", "YuyuFrame-LauncherAgent/0.1")
        .call()
        .map_err(|e| format!("téléchargement échoué : {e}"))?;

    let mut bytes = Vec::new();
    response
        .into_reader()
        .read_to_end(&mut bytes)
        .map_err(|e| format!("lecture flux échouée : {e}"))?;

    if let Some(parent) = std::path::Path::new(dest_path).parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("création dossier échouée : {e}"))?;
    }
    std::fs::write(dest_path, bytes).map_err(|e| format!("écriture fichier échouée : {e}"))?;

    Ok(())
}
