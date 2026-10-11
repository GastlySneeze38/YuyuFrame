// Skin d'un compte hors ligne : le rendre visible des autres joueurs.
//
// Un serveur de jeu ne montre un skin que s'il est hébergé sur
// textures.minecraft.net et signé par Mojang — ce qu'un compte hors ligne ne
// peut pas obtenir lui-même. LauncherAPI le fait faire par MineSkin
// (`POST /v1/skins/host`, la clé de MineSkin reste sur le serveur) et rend
// l'adresse chez Mojang. Le client intégré la donne ensuite au serveur de jeu
// (`/skin url`, plugin SkinsRestorer), qui la montre à tout le monde.
//
// Rien ici n'est bloquant : sans compte YuyuFrame connecté, ou si l'envoi
// échoue, le skin reste appliqué et n'est vu que du joueur lui-même.
//
// Voir docs/LauncherAgent/skins-hors-ligne.md.

use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::Mutex;

use base64::Engine as _;
use serde_json::json;

use super::skin::{account_row, cache_path, fetch_preview, local_dir, validate_skin_png};
use crate::db;
use crate::state::SharedState;

/// Une adresse de texture chez Mojang, en HTTPS — ou rien si ce n'en est pas
/// une. Ce qui en sort finit dans une commande tapée en jeu : seule une
/// empreinte hexadécimale est admise derrière le préfixe.
pub(super) fn mojang_texture_url(url: &str) -> Option<String> {
    let hash = url
        .strip_prefix("https://textures.minecraft.net/texture/")
        .or_else(|| url.strip_prefix("http://textures.minecraft.net/texture/"))?;
    if hash.is_empty() || !hash.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    Some(format!("https://textures.minecraft.net/texture/{hash}"))
}

/// Le PNG d'une référence, sur le disque. Un skin pris par adresse vit dans le
/// cache d'aperçu, qui est effaçable : il est retéléchargé s'il n'y est plus.
async fn skin_file(skin: &db::SkinRef) -> Result<PathBuf, String> {
    if skin.kind == "local" {
        return Ok(local_dir().join(&skin.source));
    }
    fetch_preview(&skin.source).await?;
    Ok(cache_path(&skin.source))
}

async fn current_skin(state: &SharedState, uuid: &str) -> Result<Option<db::SkinRef>, String> {
    let s = state.read().await;
    let conn = s.db.lock().await;
    db::get_skin_ref(&conn, uuid).map_err(|e| e.to_string())
}

/// Fait héberger par Mojang le skin que porte ce compte hors ligne, et rend
/// son adresse. Sans effet (et sans appel réseau) s'il l'est déjà.
///
/// Séparée de `skin_apply` : l'envoi prend plusieurs secondes et peut échouer
/// (pas de compte YuyuFrame connecté, service saturé) sans que le skin cesse
/// d'être appliqué. L'interface l'appelle juste après, et propose de réessayer.
#[tauri::command]
pub async fn skin_host(state: tauri::State<'_, SharedState>, uuid: String) -> Result<String, String> {
    host(&state, &uuid).await
}

async fn host(state: &SharedState, uuid: &str) -> Result<String, String> {
    let row = account_row(state, uuid).await?;
    if !row.is_offline {
        return Err("Le skin d'un compte Microsoft est déjà hébergé par Mojang".to_string());
    }
    let skin = current_skin(state, uuid).await?.ok_or("Ce compte n'a pas de skin")?;
    if let Some(hosted) = &skin.hosted {
        return Ok(hosted.clone());
    }

    let path = skin_file(&skin).await?;
    let bytes = tokio::fs::read(&path).await.map_err(|e| format!("Skin introuvable : {e}"))?;
    validate_skin_png(&bytes)?;

    let reply = crate::server::post(
        state,
        "/skins/host",
        json!({
            "png": base64::engine::general_purpose::STANDARD.encode(&bytes),
            "variant": skin.variant,
        }),
    )
    .await
    .map_err(String::from)?;
    let hosted = reply
        .get("url")
        .and_then(|u| u.as_str())
        .and_then(mojang_texture_url)
        .ok_or("Le serveur a rendu une adresse inattendue")?;

    let s = state.read().await;
    let conn = s.db.lock().await;
    match db::set_skin_hosted(&conn, uuid, &skin, &hosted).map_err(|e| e.to_string())? {
        true => Ok(hosted),
        false => Err("Le skin a changé pendant l'envoi".to_string()),
    }
}

// ── Pour le jeu ──────────────────────────────────────────────────────────────

/// Ce que le client intégré a besoin de savoir du skin d'un compte hors ligne.
pub struct GameSkin {
    /// `classic` ou `slim`.
    pub variant: String,
    /// Le PNG, pour l'afficher sur le joueur lui-même.
    pub path: Option<PathBuf>,
    /// L'adresse chez Mojang, pour le poser sur un serveur de jeu.
    pub hosted: Option<String>,
}

/// Skins dont l'hébergement a déjà été tenté depuis une partie : une tentative
/// par skin et par exécution du launcher, pas une à chaque question du jeu.
static TRIED: Mutex<Option<HashSet<String>>> = Mutex::new(None);

fn first_try(key: String) -> bool {
    TRIED.lock().unwrap_or_else(|p| p.into_inner()).get_or_insert_with(HashSet::new).insert(key)
}

/// Le skin d'un compte hors ligne, pour le jeu. `None` pour un compte
/// Microsoft (son skin vient de Mojang), un compte sans skin ou un compte
/// inconnu.
///
/// `host_missing` : pour le compte AVEC LEQUEL le jeu tourne, un skin pas
/// encore hébergé — appliqué hors connexion, ou avant que cette fonction
/// existe — l'est ici en tâche de fond ; le jeu repose la question et reçoit
/// l'adresse à la suivante. Faux pour les autres comptes de la liste, dont le
/// jeu ne veut que la tête : les afficher n'est pas une raison d'envoyer leur
/// skin où que ce soit.
pub async fn game_skin(state: &SharedState, uuid: &str, host_missing: bool) -> Option<GameSkin> {
    let row = account_row(state, uuid).await.ok()?;
    if !row.is_offline {
        return None;
    }
    let skin = match current_skin(state, uuid).await {
        Ok(skin) => skin?,
        Err(e) => {
            tracing::warn!("skin du compte {uuid} illisible : {e}");
            return None;
        }
    };
    let path = match skin_file(&skin).await {
        Ok(path) => Some(path),
        Err(e) => {
            tracing::warn!("skin du compte {uuid} indisponible sur le disque : {e}");
            None
        }
    };
    if host_missing && skin.hosted.is_none() && first_try(format!("{uuid}\t{}\t{}", skin.source, skin.variant)) {
        let state = state.clone();
        let uuid = uuid.to_string();
        tokio::spawn(async move {
            if let Err(e) = host(&state, &uuid).await {
                tracing::info!("skin du compte {uuid} non hébergé : {e}");
            }
        });
    }
    Some(GameSkin { variant: skin.variant, path, hosted: skin.hosted })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seule_une_texture_de_mojang_est_reconnue() {
        assert_eq!(
            mojang_texture_url("http://textures.minecraft.net/texture/ab12").as_deref(),
            Some("https://textures.minecraft.net/texture/ab12")
        );
        assert_eq!(
            mojang_texture_url("https://textures.minecraft.net/texture/AB12").as_deref(),
            Some("https://textures.minecraft.net/texture/AB12")
        );
        assert_eq!(mojang_texture_url("https://ely.by/storage/skins/a.png"), None);
        assert_eq!(mojang_texture_url("https://textures.minecraft.net/texture/"), None);
        // L'adresse part dans une commande de jeu : rien d'autre qu'une empreinte.
        assert_eq!(mojang_texture_url("https://textures.minecraft.net/texture/ab\" slim"), None);
        assert_eq!(mojang_texture_url("https://textures.minecraft.net/texture/ab/../x"), None);
    }

    #[test]
    fn une_seule_tentative_par_skin() {
        assert!(first_try("compte-de-test\ta.png\tclassic".into()));
        assert!(!first_try("compte-de-test\ta.png\tclassic".into()));
        assert!(first_try("compte-de-test\tb.png\tclassic".into()));
    }
}
