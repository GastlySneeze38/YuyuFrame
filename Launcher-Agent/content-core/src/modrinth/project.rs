//! Fiche d'un projet Modrinth : détail, dernier fichier, liste des versions.

use super::urlencoding;

/// Détail complet d'un projet Modrinth (page "comme sur Modrinth" côté Java —
/// voir ModrinthProjectDetailScreen) — contient notamment `body` (description
/// longue, markdown) et `gallery` (captures d'écran) qu'AUCUN des deux
/// endpoints déjà utilisés (recherche, dernière version) ne renvoie. JSON
/// brut renvoyé tel quel, parsé côté Java (voir ModrinthJson) — même
/// convention que search_modrinth, pas de modèle de données dupliqué ici.
pub fn get_project(project_id: &str) -> Result<String, String> {
    let url = format!(
        "https://api.modrinth.com/v2/project/{}",
        urlencoding(project_id)
    );

    let response = ureq::get(&url)
        .set("User-Agent", "YuyuFrame-LauncherAgent/0.1")
        .call()
        .map_err(|e| format!("requête détail projet échouée : {e}"))?;

    response
        .into_string()
        .map_err(|e| format!("lecture réponse détail projet échouée : {e}"))
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

/// Liste des versions publiées d'un projet (onglet « Versions » de la page de
/// détail côté Java), de la plus récente à la plus ancienne — l'ordre de
/// Modrinth. Rend `{"versions":[…]}` APLATI pour le petit lecteur JSON de
/// Java, qui ne sait lire que des chaînes et des nombres : `game_versions` et
/// `loaders` sont joints par des virgules, et le fichier principal de chaque
/// version est remonté en `url` / `filename`. Une version sans fichier est
/// écartée (rien à installer). Plafonné à 100 entrées.
pub fn get_versions(project_id: &str) -> Result<String, String> {
    let url = format!(
        "https://api.modrinth.com/v2/project/{}/version",
        urlencoding(project_id)
    );

    let response = ureq::get(&url)
        .set("User-Agent", "YuyuFrame-LauncherAgent/0.1")
        .call()
        .map_err(|e| format!("requête versions Modrinth échouée : {e}"))?;

    let versions: serde_json::Value = response
        .into_json()
        .map_err(|e| format!("réponse versions Modrinth invalide : {e}"))?;

    let join = |v: &serde_json::Value, key: &str| -> String {
        v.get(key)
            .and_then(|a| a.as_array())
            .map(|a| {
                a.iter()
                    .filter_map(|s| s.as_str())
                    .collect::<Vec<_>>()
                    .join(",")
            })
            .unwrap_or_default()
    };
    let text = |v: &serde_json::Value, key: &str| -> String {
        v.get(key).and_then(|s| s.as_str()).unwrap_or("").to_string()
    };

    let mut out: Vec<serde_json::Value> = Vec::new();
    for v in versions.as_array().map(|a| a.as_slice()).unwrap_or(&[]) {
        if out.len() >= 100 {
            break;
        }
        let files = match v.get("files").and_then(|f| f.as_array()) {
            Some(f) => f,
            None => continue,
        };
        let file = match files
            .iter()
            .find(|f| f.get("primary").and_then(|p| p.as_bool()).unwrap_or(false))
            .or_else(|| files.first())
        {
            Some(f) => f,
            None => continue,
        };
        out.push(serde_json::json!({
            "version_number": text(v, "version_number"),
            "version_type": text(v, "version_type"),
            "game_versions": join(v, "game_versions"),
            "loaders": join(v, "loaders"),
            "date_published": text(v, "date_published"),
            "downloads": v.get("downloads").and_then(|d| d.as_u64()).unwrap_or(0),
            "url": text(file, "url"),
            "filename": text(file, "filename"),
        }));
    }

    Ok(serde_json::json!({ "versions": out }).to_string())
}
