// Skin custom pour les comptes hors ligne (voir offline.rs) — aucune
// authentification Mojang derrière un compte offline, donc pas de skin réel
// à récupérer : l'utilisateur fournit son propre PNG, stocké tel quel sous
// %APPDATA%\YuyuFrame\skins\<uuid>.png et renvoyé en data URI (aucune config
// Tauri asset-protocol à ouvrir, le PNG est minuscule — quelques Ko).
//
// Purement cosmétique côté launcher (avatar + aperçu 3D skinview3d) : le
// rendre visible en jeu aux autres joueurs demanderait un serveur (skin
// restorer ou équivalent), hors scope ici.

use base64::Engine as _;
use std::path::PathBuf;

fn skins_dir() -> PathBuf {
    crate::paths::root().join("skins")
}

const PNG_SIGNATURE: [u8; 8] = [0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1a, b'\n'];

/// Valide la signature PNG + les dimensions (64×64 moderne ou 64×32 legacy,
/// les deux seuls formats de skin Minecraft) en lisant directement le chunk
/// IHDR (largeur/hauteur en big-endian aux octets 16-23) — pas besoin d'une
/// dépendance de décodage d'image complète pour ça.
fn validate_skin_png(bytes: &[u8]) -> Result<(), String> {
    if bytes.len() < 24 || bytes[0..8] != PNG_SIGNATURE {
        return Err("Fichier non valide — un PNG est requis".to_string());
    }
    let width = u32::from_be_bytes([bytes[16], bytes[17], bytes[18], bytes[19]]);
    let height = u32::from_be_bytes([bytes[20], bytes[21], bytes[22], bytes[23]]);
    if !((width == 64 && height == 64) || (width == 64 && height == 32)) {
        return Err(format!(
            "Dimensions invalides ({}×{}) — un skin Minecraft fait 64×64 (ou 64×32)",
            width, height
        ));
    }
    Ok(())
}

fn to_data_uri(bytes: &[u8]) -> String {
    format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(bytes))
}

async fn store_skin(uuid: &str, bytes: &[u8]) -> Result<String, String> {
    validate_skin_png(bytes)?;
    let dir = skins_dir();
    tokio::fs::create_dir_all(&dir).await.map_err(|e| e.to_string())?;
    tokio::fs::write(dir.join(format!("{}.png", uuid)), bytes)
        .await
        .map_err(|e| e.to_string())?;
    Ok(to_data_uri(bytes))
}

#[tauri::command]
pub async fn set_account_skin(uuid: String, source_path: String) -> Result<String, String> {
    let bytes = tokio::fs::read(&source_path).await.map_err(|e| format!("Lecture du fichier : {}", e))?;
    store_skin(&uuid, &bytes).await
}

// 1 Mo — largement suffisant pour un skin (quelques Ko), juste pour éviter
// de rapatrier un fichier énorme si l'URL fournie ne pointe pas vers un skin.
const MAX_SKIN_DOWNLOAD_BYTES: usize = 1024 * 1024;

#[tauri::command]
pub async fn set_account_skin_from_url(uuid: String, url: String) -> Result<String, String> {
    if !(url.starts_with("http://") || url.starts_with("https://")) {
        return Err("URL invalide — doit commencer par http:// ou https://".to_string());
    }

    let resp = reqwest::Client::new()
        .get(&url)
        .send()
        .await
        .map_err(|e| format!("Téléchargement échoué : {}", e))?;
    if !resp.status().is_success() {
        return Err(format!("Le serveur a répondu {}", resp.status()));
    }

    let bytes = resp.bytes().await.map_err(|e| format!("Lecture de la réponse : {}", e))?;
    if bytes.len() > MAX_SKIN_DOWNLOAD_BYTES {
        return Err("Fichier trop volumineux pour un skin (max 1 Mo)".to_string());
    }

    store_skin(&uuid, &bytes).await
}

#[tauri::command]
pub async fn get_account_skin(uuid: String) -> Result<Option<String>, String> {
    match tokio::fs::read(skins_dir().join(format!("{}.png", uuid))).await {
        Ok(bytes) => Ok(Some(to_data_uri(&bytes))),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e.to_string()),
    }
}

#[tauri::command]
pub async fn remove_account_skin(uuid: String) -> Result<(), String> {
    match tokio::fs::remove_file(skins_dir().join(format!("{}.png", uuid))).await {
        Ok(_) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e.to_string()),
    }
}
