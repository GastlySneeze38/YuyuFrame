//! Missions et passe de saison (LauncherAPI `GET /v1/pass`), et les images
//! réglées dans le back-office (`GET /v1/assets/{id}`).
//!
//! Une image est téléchargée une fois puis gardée sur le disque
//! (`cache/assets/<id>`) : elle ne change jamais côté serveur (la remplacer
//! = en téléverser une autre, donc un autre identifiant). Une image absente
//! (supprimée, serveur injoignable, fichier qui n'est pas une image) rend
//! `None` : l'interface dessine alors l'icône de la sorte de récompense, rien
//! ne casse.

use std::path::PathBuf;

use base64::Engine as _;
use serde_json::Value;

use crate::server as api;
use crate::state::SharedState;

/// Les missions du moment et le passe de la saison en cours.
#[tauri::command]
pub async fn pass_state(state: tauri::State<'_, SharedState>) -> Result<Value, String> {
    api::get(&state, "/pass", &[]).await.map_err(String::from)
}

/// Remplace une mission du jour par une autre (une relance par jour, réglée
/// par le serveur), puis rend l'état à jour.
#[tauri::command]
pub async fn pass_reroll(state: tauri::State<'_, SharedState>, id: i64) -> Result<Value, String> {
    api::post(&state, &format!("/pass/missions/{id}/reroll"), Value::Null).await.map_err(String::from)?;
    api::get(&state, "/pass", &[]).await.map_err(String::from)
}

/// Une image plus grande n'est pas gardée (le serveur borne à 2 Mio).
const MAX_BYTES: usize = 2 * 1024 * 1024;

fn cache_dir() -> PathBuf {
    crate::paths::root().join("cache").join("assets")
}

/// Le type d'une image d'après ses premiers octets ; `None` : pas une image acceptée.
fn sniff(bytes: &[u8]) -> Option<&'static str> {
    match bytes {
        [0x89, b'P', b'N', b'G', ..] => Some("image/png"),
        [0xFF, 0xD8, 0xFF, ..] => Some("image/jpeg"),
        [b'G', b'I', b'F', b'8', ..] => Some("image/gif"),
        [b'R', b'I', b'F', b'F', _, _, _, _, b'W', b'E', b'B', b'P', ..] => Some("image/webp"),
        _ => None,
    }
}

fn data_uri(bytes: &[u8]) -> Option<String> {
    let mime = sniff(bytes)?;
    Some(format!("data:{mime};base64,{}", base64::engine::general_purpose::STANDARD.encode(bytes)))
}

/// Une image du back-office en data URI, depuis le disque ou le serveur.
/// `None` : pas d'image à montrer (l'interface prend l'icône par défaut).
#[tauri::command]
pub async fn content_asset(id: String) -> Option<String> {
    // Un identifiant sert de nom de fichier et part dans une adresse : un UUID, rien d'autre.
    let id = uuid::Uuid::parse_str(&id).ok()?.to_string();
    let path = cache_dir().join(&id);
    if let Ok(bytes) = tokio::fs::read(&path).await {
        if let Some(uri) = data_uri(&bytes) {
            return Some(uri);
        }
    }
    let resp = reqwest::Client::new().get(format!("{}/assets/{id}", api::base())).send().await.ok()?;
    if !resp.status().is_success() {
        return None;
    }
    let bytes = resp.bytes().await.ok()?;
    if bytes.len() > MAX_BYTES {
        return None;
    }
    let uri = data_uri(&bytes)?;
    // Garder la copie ; un échec d'écriture coûte seulement un téléchargement la prochaine fois.
    if tokio::fs::create_dir_all(cache_dir()).await.is_ok() {
        let _ = tokio::fs::write(&path, &bytes).await;
    }
    Some(uri)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seules_les_images_passent() {
        assert!(data_uri(b"\x89PNG\r\n\x1a\nxxxx").unwrap().starts_with("data:image/png;base64,"));
        assert_eq!(data_uri(b"<html>erreur</html>"), None);
    }
}
