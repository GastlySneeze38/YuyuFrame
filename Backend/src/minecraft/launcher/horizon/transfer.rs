//! Les fichiers du jeu d'Horizon, du PC vers l'iPhone.
//!
//! Le jeu a besoin des ressources du vrai Minecraft, préparées par
//! `horizon-prepare` (`prepare.rs`) : c'est ce programme qui fait tourner le
//! code de Mojang dans une JVM, et iOS ne laisse lancer ni Java ni aucun autre
//! programme. On les prépare donc sur le PC, où le launcher le fait déjà pour
//! jouer, puis on les emporte :
//!
//! - sur PC, `horizon_ios_resources_export` zippe un dossier préparé (celui
//!   que marque `horizon-resources.json`) dans le fichier choisi ;
//! - sur iPhone, `horizon_ios_resources_import` l'installe avec l'importation
//!   du moteur lui-même (`horizon_client::import`, format « Resources ») :
//!   décompressé à côté, version de Minecraft vérifiée, puis mis à la place
//!   de l'ancien, qui reste si quoi que ce soit échoue.
//!
//! Ailleurs, les commandes existent pour que l'interface reste la même, et
//! refusent.

/// Les fichiers d'un dossier préparé, en chemins relatifs à `/`, plus profonds
/// d'abord parcourus : ce que le zip emporte.
#[cfg(not(any(target_os = "android", target_os = "ios")))]
fn files_under(root: &std::path::Path) -> std::io::Result<Vec<std::path::PathBuf>> {
    let mut files = Vec::new();
    let mut folders = vec![root.to_path_buf()];
    while let Some(folder) = folders.pop() {
        for entry in std::fs::read_dir(&folder)? {
            let entry = entry?;
            let kind = entry.file_type()?;
            if kind.is_dir() {
                folders.push(entry.path());
            } else if kind.is_file() {
                files.push(entry.path());
            }
        }
    }
    files.sort();
    Ok(files)
}

/// Le dossier préparé le plus récent parmi les versions d'Horizon installées.
#[cfg(not(any(target_os = "android", target_os = "ios")))]
fn newest_prepared() -> Option<std::path::PathBuf> {
    let versions = crate::paths::root().join("horizon").join("versions");
    std::fs::read_dir(versions)
        .ok()?
        .flatten()
        .map(|version| version.path().join(".local").join("horizon-client").join(super::GAME_VERSION))
        .filter_map(|resources| {
            let marker = std::fs::metadata(resources.join("horizon-resources.json")).ok()?;
            Some((marker.modified().ok()?, resources))
        })
        .max_by_key(|(modified, _)| *modified)
        .map(|(_, resources)| resources)
}

/// Zippe les fichiers du jeu préparés sur ce PC dans `path`, pour les
/// emporter sur un iPhone. Rend la taille du zip, en octets.
#[cfg(not(any(target_os = "android", target_os = "ios")))]
#[tauri::command]
pub async fn horizon_ios_resources_export(path: String) -> Result<u64, String> {
    use std::io::Write;

    let Some(resources) = newest_prepared() else {
        return Err("Aucun fichier du jeu préparé sur ce PC : lance une fois une instance Horizon, puis réessaie".into());
    };
    let target = std::path::PathBuf::from(path);
    tokio::task::spawn_blocking(move || -> Result<u64, String> {
        let files = files_under(&resources).map_err(|e| format!("lecture des fichiers du jeu : {e}"))?;
        // Écrit à côté puis renommé : un export interrompu ne laisse pas un
        // zip tronqué sous le nom choisi, que l'iPhone refuserait plus tard.
        let partial = target.with_extension("zip.part");
        let write = || -> Result<(), String> {
            let out = std::fs::File::create(&partial).map_err(|e| e.to_string())?;
            let mut zip = zip::ZipWriter::new(std::io::BufWriter::new(out));
            let options = zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Deflated)
                .large_file(true);
            for file in &files {
                let relative = file.strip_prefix(&resources).map_err(|e| e.to_string())?;
                let name = relative.to_string_lossy().replace('\\', "/");
                zip.start_file(name.as_str(), options).map_err(|e| e.to_string())?;
                let mut input = std::fs::File::open(file).map_err(|e| format!("{name} : {e}"))?;
                std::io::copy(&mut input, &mut zip).map_err(|e| format!("{name} : {e}"))?;
            }
            zip.finish().map_err(|e| e.to_string())?.flush().map_err(|e| e.to_string())?;
            Ok(())
        };
        if let Err(e) = write() {
            let _ = std::fs::remove_file(&partial);
            return Err(e);
        }
        std::fs::rename(&partial, &target).map_err(|e| e.to_string())?;
        Ok(std::fs::metadata(&target).map(|m| m.len()).unwrap_or(0))
    })
    .await
    .map_err(|e| e.to_string())?
}

#[cfg(any(target_os = "android", target_os = "ios"))]
#[tauri::command]
pub async fn horizon_ios_resources_export(path: String) -> Result<u64, String> {
    let _ = path;
    Err("Disponible sur ordinateur seulement".into())
}

/// Si les fichiers du jeu sont installés sur cet iPhone.
#[cfg(target_os = "ios")]
#[tauri::command]
pub async fn horizon_ios_resources_status() -> Result<bool, String> {
    Ok(super::ios::resources_ready())
}

#[cfg(not(target_os = "ios"))]
#[tauri::command]
pub async fn horizon_ios_resources_status() -> Result<bool, String> {
    Err("Disponible sur iPhone seulement".into())
}

/// Installe les fichiers du jeu zippés sur un PC (`path` : le fichier rendu
/// par le sélecteur, une copie dans l'appli). Refusé pendant une partie : le
/// jeu les lit tant qu'il tourne.
#[cfg(target_os = "ios")]
#[tauri::command]
pub async fn horizon_ios_resources_import(state: tauri::State<'_, crate::state::SharedState>, path: String) -> Result<(), String> {
    if !state.read().await.running_instances.is_empty() {
        return Err("Le jeu tourne : ferme et rouvre l'appli avant de remplacer ses fichiers".into());
    }
    // Le sélecteur d'iOS peut rendre une adresse (`file:///…%20…`) plutôt qu'un chemin.
    let path = match path.strip_prefix("file://") {
        Some(url) => crate::share_link::percent_decode(url).ok_or("Chemin du fichier illisible")?,
        None => path,
    };
    install_zip(std::path::PathBuf::from(path)).await
}

/// Installe un zip des fichiers du jeu avec l'importation du moteur, qui
/// décompresse à côté, vérifie la version de données, puis remplace.
#[cfg(target_os = "ios")]
async fn install_zip(path: std::path::PathBuf) -> Result<(), String> {
    tokio::task::spawn_blocking(move || -> Result<(), String> {
        let (kind, _) = horizon_client::import::classify(&path);
        if kind != horizon_client::import::Kind::Resources {
            return Err("Ce fichier n'est pas un export des fichiers du jeu : sur ton PC, Paramètres → Stockage → Exporter pour iPhone".into());
        }
        let places = horizon_client::import::Places {
            game: super::ios::private_dir(),
            resources: super::ios::resources_dir(),
        };
        let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        horizon_client::import::install(&path, &name, &places).map(|_| ())
    })
    .await
    .map_err(|e| e.to_string())?
}

#[cfg(not(target_os = "ios"))]
#[tauri::command]
pub async fn horizon_ios_resources_import(path: String) -> Result<(), String> {
    let _ = path;
    Err("Disponible sur iPhone seulement".into())
}

// ── Téléchargement depuis le serveur (2026-11-28) ────────────────────────────
//
// Plus besoin de PC pour le joueur : l'équipe envoie l'export depuis le
// back-office (Plateforme → Fichiers Horizon), un zip par version de
// Minecraft, et l'appli le télécharge ici (LauncherAPI
// `GET /v1/horizon/resources/{version}/download`, compte YuyuFrame connecté).
// ~400 Mo sur un téléphone : le zip s'écrit sous un nom provisoire qui porte
// son empreinte, et une coupure reprend où elle en était (`Range`). Un zip
// remplacé entre-temps a une autre empreinte, donc un autre fichier : on ne
// recolle jamais deux zips. L'empreinte est vérifiée avant d'installer.

/// Progression, événement `horizon_resources_progress`.
#[cfg(target_os = "ios")]
#[derive(Clone, serde::Serialize)]
struct DownloadProgress {
    done: u64,
    total: u64,
}

/// Télécharge et installe les fichiers du jeu préparés pour cette version
/// d'Horizon. Refusé pendant une partie, comme l'importation.
#[cfg(target_os = "ios")]
#[tauri::command]
pub async fn horizon_ios_resources_download(app: tauri::AppHandle, state: tauri::State<'_, crate::state::SharedState>) -> Result<(), String> {
    use crate::server as api;
    use futures::StreamExt;
    use sha2::{Digest, Sha256};
    use tauri::Emitter;
    use tokio::io::AsyncWriteExt;

    if !state.read().await.running_instances.is_empty() {
        return Err("Le jeu tourne : ferme et rouvre l'appli avant de remplacer ses fichiers".into());
    }
    let shared: crate::state::SharedState = state.inner().clone();

    let list = api::get(&shared, "/horizon/resources", &[]).await.map_err(|e| e.to_string())?;
    let entry = list
        .as_array()
        .and_then(|items| items.iter().find(|i| i["minecraft_version"] == super::GAME_VERSION))
        .ok_or_else(|| format!("Les fichiers du jeu pour Minecraft {} ne sont pas encore disponibles", super::GAME_VERSION))?;
    let sha256 = entry["sha256"].as_str().unwrap_or_default().to_ascii_lowercase();
    let total = entry["size_bytes"].as_u64().unwrap_or(0);
    if sha256.len() != 64 || !sha256.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("Réponse du serveur illisible".into());
    }

    let dir = super::ios::private_dir();
    tokio::fs::create_dir_all(&dir).await.map_err(|e| e.to_string())?;
    // Les restes d'un autre zip (remplacé depuis sur le serveur) ne servent plus.
    let partial = dir.join(format!("resources-{sha256}.zip.part"));
    if let Ok(mut entries) = tokio::fs::read_dir(&dir).await {
        while let Ok(Some(e)) = entries.next_entry().await {
            let name = e.file_name().to_string_lossy().into_owned();
            if name.starts_with("resources-") && name.ends_with(".zip.part") && e.path() != partial {
                let _ = tokio::fs::remove_file(e.path()).await;
            }
        }
    }

    let path = format!("{}/horizon/resources/{}/download", api::base(), super::GAME_VERSION);
    let mut token = api::valid_access_token(&shared).await.map_err(|e| e.to_string())?;
    let mut refreshed = false;
    let mut retried_from_scratch = false;
    loop {
        let have = tokio::fs::metadata(&partial).await.map(|m| m.len()).unwrap_or(0);
        if total == 0 || have < total {
            let mut req = crate::net::files().get(&path).bearer_auth(&token);
            if have > 0 {
                req = req.header(reqwest::header::RANGE, format!("bytes={have}-"));
            }
            let resp = req.send().await.map_err(|e| format!("Téléchargement impossible : {e}"))?;
            let status = resp.status();
            if status == reqwest::StatusCode::UNAUTHORIZED && !refreshed {
                refreshed = true;
                token = api::refresh(&shared).await.map_err(|e| e.to_string())?;
                continue;
            }
            if !status.is_success() {
                return Err(api::error::ApiError::from_response(resp).await.to_string());
            }
            // 206 : la suite ; 200 : le serveur renvoie tout, on repart de zéro.
            let append = status == reqwest::StatusCode::PARTIAL_CONTENT && have > 0;
            let mut file = tokio::fs::OpenOptions::new()
                .create(true)
                .write(true)
                .append(append)
                .truncate(!append)
                .open(&partial)
                .await
                .map_err(|e| e.to_string())?;
            let mut done = if append { have } else { 0 };
            let mut last_emit = 0u64;
            let mut stream = resp.bytes_stream();
            while let Some(chunk) = stream.next().await {
                let chunk = chunk.map_err(|e| format!("Téléchargement interrompu (il reprendra où il en était) : {e}"))?;
                file.write_all(&chunk).await.map_err(|e| e.to_string())?;
                done += chunk.len() as u64;
                if done - last_emit >= 1 << 20 {
                    last_emit = done;
                    let _ = app.emit("horizon_resources_progress", DownloadProgress { done, total });
                }
            }
            file.flush().await.map_err(|e| e.to_string())?;
            let _ = app.emit("horizon_resources_progress", DownloadProgress { done, total });
        }

        // L'empreinte, avant d'installer quoi que ce soit.
        let check = partial.clone();
        let actual = tokio::task::spawn_blocking(move || -> Result<String, String> {
            let mut file = std::fs::File::open(&check).map_err(|e| e.to_string())?;
            let mut hasher = Sha256::new();
            std::io::copy(&mut file, &mut hasher).map_err(|e| e.to_string())?;
            Ok(hasher.finalize().iter().map(|b| format!("{b:02x}")).collect())
        })
        .await
        .map_err(|e| e.to_string())??;
        if actual == sha256 {
            break;
        }
        let _ = tokio::fs::remove_file(&partial).await;
        if retried_from_scratch {
            return Err("Le fichier téléchargé est abîmé : réessaie plus tard".into());
        }
        retried_from_scratch = true;
    }

    let result = install_zip(partial.clone()).await;
    let _ = tokio::fs::remove_file(&partial).await;
    result
}

#[cfg(not(target_os = "ios"))]
#[tauri::command]
pub async fn horizon_ios_resources_download() -> Result<(), String> {
    Err("Disponible sur iPhone seulement".into())
}
