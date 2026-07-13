use std::io::Read;

/// Recherche de resource packs/shaders sur Modrinth, avec filtres optionnels
/// (écran de filtres côté Java, voir ModrinthContentScreen) — chaque facet
/// Modrinth est un groupe `[...]` : les valeurs À L'INTÉRIEUR d'un groupe sont
/// en OU, les groupes entre eux sont en ET. `categories` est donc mis un
/// groupe PAR catégorie sélectionnée (ET entre catégories — cohérent avec un
/// filtre qui "affine"/réduit les résultats, pas qui les élargit).
///
/// `categories`/`version`/`sort_index` vides = aucun filtre / tri par défaut
/// (comportement inchangé par rapport à l'ancienne signature 2 paramètres).
pub fn search_modrinth(
    query: &str,
    project_type: &str,
    categories: &[String],
    version: &str,
    sort_index: &str,
) -> Result<String, String> {
    let mut facet_groups: Vec<String> = vec![format!("[\"project_type:{}\"]", project_type)];
    if !version.trim().is_empty() {
        facet_groups.push(format!("[\"versions:{}\"]", version.trim()));
    }
    for cat in categories {
        let cat = cat.trim();
        if !cat.is_empty() {
            facet_groups.push(format!("[\"categories:{}\"]", cat));
        }
    }
    let facets = format!("[{}]", facet_groups.join(","));

    // Requête vide = recommandations à l'ouverture de l'écran : trier par
    // popularité plutôt que "relevance" (mal défini sans terme de recherche).
    // Un index explicite (choisi dans l'écran de filtres) prime toujours sur
    // ce repli automatique.
    let index = if !sort_index.trim().is_empty() {
        sort_index.trim()
    } else if query.trim().is_empty() {
        "downloads"
    } else {
        "relevance"
    };
    let url = format!(
        "https://api.modrinth.com/v2/search?query={}&facets={}&index={}&limit=24",
        urlencoding(query),
        urlencoding(&facets),
        index
    );

    let response = ureq::get(&url)
        .set("User-Agent", "YuyuFrame-LauncherAgent/0.1")
        .call()
        .map_err(|e| format!("requête Modrinth échouée : {e}"))?;

    response
        .into_string()
        .map_err(|e| format!("lecture réponse Modrinth échouée : {e}"))
}

/// Résout le fichier de la dernière version publiée d'un projet Modrinth.
/// Retourne un JSON simplifié `{"url":"...","filename":"..."}` — évite à Java
/// de parser la structure complète des versions (fichiers multiples,
/// primary/non-primary...), qui n'est utile qu'ici côté Rust.
pub fn get_latest_file(project_id: &str) -> Result<String, String> {
    let url = format!(
        "https://api.modrinth.com/v2/project/{}/version",
        urlencoding(project_id)
    );

    let response = ureq::get(&url)
        .set("User-Agent", "YuyuFrame-LauncherAgent/0.1")
        .call()
        .map_err(|e| format!("requête version Modrinth échouée : {e}"))?;

    let versions: serde_json::Value = response
        .into_json()
        .map_err(|e| format!("réponse version Modrinth invalide : {e}"))?;

    let files = versions
        .get(0)
        .and_then(|v| v.get("files"))
        .and_then(|f| f.as_array())
        .ok_or_else(|| "aucune version disponible pour ce projet".to_string())?;

    let file = files
        .iter()
        .find(|f| f.get("primary").and_then(|p| p.as_bool()).unwrap_or(false))
        .or_else(|| files.first())
        .ok_or_else(|| "aucun fichier dans la dernière version".to_string())?;

    let file_url = file
        .get("url")
        .and_then(|u| u.as_str())
        .ok_or_else(|| "url de fichier manquante".to_string())?;
    let filename = file
        .get("filename")
        .and_then(|n| n.as_str())
        .ok_or_else(|| "nom de fichier manquant".to_string())?;

    Ok(format!(
        "{{\"url\":\"{}\",\"filename\":\"{}\"}}",
        file_url.replace('"', "\\\""),
        filename.replace('"', "\\\""),
    ))
}

/// Télécharge un fichier vers destPath.
///
/// Whitelist stricte sur cdn.modrinth.com — même garde que
/// Backend/src/commands/mods.rs:141 côté Tauri, appliquée ici côté Rust JNI
/// puisque le téléchargement ne passe plus par le launcher mais par cet agent.
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

/// Encodage de requête minimal — évite une dépendance supplémentaire pour
/// quelques caractères réservés dans query/facets.
fn urlencoding(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char);
            }
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}
