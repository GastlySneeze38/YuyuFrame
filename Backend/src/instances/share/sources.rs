//! Où se télécharge un fichier : Modrinth par SHA-1, CurseForge par empreinte.

use super::*;

#[derive(Clone)]
pub(super) struct Remote {
    pub(super) source: Source,
    pub(super) url: String,
    /// Identifiant de version Modrinth, pour le lien de partage.
    pub(super) modrinth_version: Option<String>,
    /// La version Modrinth publie plusieurs fichiers : le lien doit dire
    /// lequel (début du SHA-1). Sinon, l'identifiant suffit.
    pub(super) ambiguous: bool,
    pub(super) sha512: Option<String>,
}

/// Réponses déjà obtenues, par SHA-1 (`None` = connu de personne). Un même
/// fichier ne change pas de réponse pendant une session ; sans ce cache,
/// exporter refaisait toutes les requêtes que l'aperçu venait de faire.
pub(super) static RESOLVED: LazyLock<Mutex<HashMap<String, Option<Remote>>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

#[derive(Deserialize)]
pub(super) struct MrVersion {
    pub(super) id: String,
    pub(super) files: Vec<MrVersionFile>,
}

#[derive(Deserialize)]
pub(super) struct MrVersionFile {
    pub(super) hashes: MrHashes,
    pub(super) url: String,
    pub(super) filename: String,
    #[serde(default)]
    pub(super) primary: bool,
    #[serde(default)]
    pub(super) size: u64,
}

#[derive(Deserialize)]
pub(super) struct MrHashes {
    pub(super) sha1: String,
    #[serde(default)]
    pub(super) sha512: Option<String>,
}

pub(super) fn http() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .user_agent(USER_AGENT)
        .timeout(std::time::Duration::from_secs(30))
        .build()
        .map_err(|e| e.to_string())
}

/// Cherche chez Modrinth, puis chez CurseForge pour ce qui reste. Rend vrai
/// si Modrinth n'a pas pu répondre.
pub(super) async fn resolve(state: &tauri::State<'_, SharedState>, files: &[(String, PathBuf)]) -> bool {
    let unknown: Vec<(String, PathBuf)> = {
        let cache = RESOLVED.lock().unwrap();
        files.iter().filter(|(sha1, _)| !cache.contains_key(sha1)).cloned().collect()
    };
    if unknown.is_empty() {
        return false;
    }

    // Modrinth : une seule requête pour toute la liste.
    let hashes: Vec<&str> = unknown.iter().map(|(h, _)| h.as_str()).collect();
    let modrinth: Option<HashMap<String, MrVersion>> = async {
        let resp = http()
            .ok()?
            .post("https://api.modrinth.com/v2/version_files")
            .json(&serde_json::json!({ "hashes": hashes, "algorithm": "sha1" }))
            .send()
            .await
            .ok()?;
        if !resp.status().is_success() {
            return None;
        }
        resp.json().await.ok()
    }
    .await;
    let lookup_failed = modrinth.is_none();

    let mut found: HashMap<String, Remote> = HashMap::new();
    for (sha1, version) in modrinth.unwrap_or_default() {
        if let Some(file) = version.files.iter().find(|f| f.hashes.sha1 == sha1) {
            found.insert(sha1, Remote {
                source: Source::Modrinth,
                url: file.url.clone(),
                modrinth_version: Some(version.id.clone()),
                ambiguous: version.files.len() > 1,
                sha512: file.hashes.sha512.clone(),
            });
        }
    }

    // CurseForge, par empreinte, pour ce que Modrinth ne connaît pas. Passe
    // par LauncherAPI (la clé de leur API n'est pas dans le launcher) : une
    // panne n'empêche rien, le fichier sera simplement embarqué.
    let rest: Vec<&(String, PathBuf)> = unknown.iter().filter(|(h, _)| !found.contains_key(h)).collect();
    let mut curseforge_ok = rest.is_empty();
    if !rest.is_empty() {
        let paths: Vec<(String, PathBuf)> = rest.iter().map(|(h, p)| (h.clone(), p.clone())).collect();
        let prints: Vec<(String, u32)> = tokio::task::spawn_blocking(move || {
            paths
                .into_iter()
                .filter_map(|(h, p)| std::fs::read(&p).ok().map(|d| (h, crate::server::curseforge::curseforge_fingerprint(&d))))
                .collect()
        })
        .await
        .unwrap_or_default();
        let body = serde_json::json!({ "fingerprints": prints.iter().map(|(_, f)| *f).collect::<Vec<_>>() });
        if let Ok(value) = crate::server::curseforge::post_json(state, "/curseforge/fingerprints", body).await {
            curseforge_ok = true;
            let by_print: HashMap<u32, &str> = prints.iter().map(|(h, f)| (*f, h.as_str())).collect();
            let matches = value["data"]["exactMatches"].as_array().cloned().unwrap_or_default();
            for m in matches {
                let file = &m["file"];
                let Some(print) = file["fileFingerprint"].as_u64() else { continue };
                let Some(sha1) = by_print.get(&(print as u32)) else { continue };
                // Sans adresse, l'auteur a refusé la distribution par des tiers :
                // le fichier reste embarqué.
                let Some(url) = file["downloadUrl"].as_str() else { continue };
                if !CURSEFORGE_CDN.iter().any(|p| url.starts_with(p)) {
                    continue;
                }
                found.insert(sha1.to_string(), Remote {
                    source: Source::Curseforge,
                    url: url.to_string(),
                    modrinth_version: None,
                    ambiguous: false,
                    sha512: None,
                });
            }
        }
    }

    // On ne retient « inconnu » que si les deux plateformes ont répondu : une
    // panne ne doit pas laisser une mauvaise réponse en cache.
    let mut cache = RESOLVED.lock().unwrap();
    for (sha1, _) in &unknown {
        match found.remove(sha1) {
            Some(remote) => {
                cache.insert(sha1.clone(), Some(remote));
            }
            None if !lookup_failed && curseforge_ok => {
                cache.insert(sha1.clone(), None);
            }
            None => {}
        }
    }
    lookup_failed
}

pub(super) fn cached(sha1: &str) -> Option<Remote> {
    RESOLVED.lock().unwrap().get(sha1).cloned().flatten()
}
