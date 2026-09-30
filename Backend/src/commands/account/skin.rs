// Skins — une référence, jamais un fichier.
//
// ── Pourquoi ce choix ─────────────────────────────────────────────────────────
// Un skin ne sert vraiment que s'il est vu par les AUTRES joueurs, et pour ça
// il faut qu'il soit hébergé quelque part d'accessible à tout le monde. Nous
// n'avons pas de stockage à offrir pour les skins des comptes hors ligne, alors
// on ne stocke rien : on ne manipule que des skins DÉJÀ hébergés.
//
// Deux sources, et deux seulement :
//
//   1. le skin d'un compte premium — on demande son profil à Mojang, qui nous
//      rend l'URL de sa texture sur textures.minecraft.net ;
//   2. une URL fournie par l'utilisateur, vers n'importe quel PNG public.
//
// Ce que le launcher garde en base est donc un triplet {url, modèle, origine},
// pas des pixels. Le disque ne sert que de cache d'aperçu, effaçable sans
// conséquence.
//
// ── Les deux moitiés de « appliquer » ────────────────────────────────────────
// Pour un compte Microsoft, appliquer veut dire le poser chez Mojang : le skin
// devient réel partout, y compris pour les joueurs qui n'utilisent pas
// YuyuFrame, et c'est Mojang qui l'héberge à partir de l'URL. C'est une
// modification du compte de l'utilisateur, jamais faite en passant.
//
// Pour un compte hors ligne, il n'y a personne à qui le déclarer : la référence
// reste dans notre base et ne change pour l'instant que l'affichage du
// launcher. C'est l'agent, plus tard, qui la rendra visible en jeu.

use base64::Engine as _;
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::path::PathBuf;

use crate::db;
use crate::state::SharedState;

const PROFILE_BY_NAME_URL: &str = "https://api.mojang.com/users/profiles/minecraft/";
const SESSION_PROFILE_URL: &str = "https://sessionserver.mojang.com/session/minecraft/profile/";
const MC_SKINS_URL: &str = "https://api.minecraftservices.com/minecraft/profile/skins";
const MC_ACTIVE_SKIN_URL: &str = "https://api.minecraftservices.com/minecraft/profile/skins/active";

/// 1 Mo — un skin pèse quelques Ko. Ce plafond n'est pas là pour trier les
/// skins mais pour ne pas rapatrier un fichier énorme si l'URL donnée pointe
/// vers autre chose.
const MAX_SKIN_BYTES: usize = 1024 * 1024;

const PNG_SIGNATURE: [u8; 8] = [0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1a, b'\n'];

// ── Types rendus au frontend ─────────────────────────────────────────────────

#[derive(Serialize)]
pub struct ResolvedSkin {
    /// Pseudo tel que Mojang l'orthographie (la recherche est insensible à la
    /// casse, on renvoie donc la bonne graphie plutôt que celle tapée).
    pub username: String,
    pub uuid: String,
    pub url: String,
    pub variant: String,
    /// Aperçu prêt à afficher, pour ne pas dépendre du CORS de l'hébergeur.
    pub data_uri: String,
}

#[derive(Serialize)]
pub struct CheckedSkin {
    pub url: String,
    pub data_uri: String,
}

// ── Fonctions pures (testées en bas de fichier) ──────────────────────────────

/// Valide la signature PNG et les dimensions (64×64 moderne ou 64×32 legacy,
/// les deux seuls formats de skin Minecraft) en lisant le chunk IHDR
/// directement — largeur et hauteur en big-endian aux octets 16-23. Pas besoin
/// d'un décodeur d'image complet pour ça.
fn validate_skin_png(bytes: &[u8]) -> Result<(), String> {
    if bytes.len() < 24 || bytes[0..8] != PNG_SIGNATURE {
        return Err("Ce n'est pas un PNG — vérifie l'URL".to_string());
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

/// Mojang annonce ses textures en `http://`. Les servir en clair n'apporte
/// rien et le même hôte répond en TLS, donc on remonte la référence en
/// `https://` avant de l'enregistrer — c'est cette URL qui sera repartagée.
fn normalize_texture_url(url: &str) -> String {
    match url.strip_prefix("http://textures.minecraft.net/") {
        Some(rest) => format!("https://textures.minecraft.net/{}", rest),
        None => url.to_string(),
    }
}

fn check_public_url(url: &str) -> Result<(), String> {
    if !(url.starts_with("http://") || url.starts_with("https://")) {
        return Err("L'URL doit commencer par http:// ou https://".to_string());
    }
    Ok(())
}

/// `classic` par défaut : c'est le modèle de Steve, et une valeur inconnue vaut
/// mieux rendue en bras larges qu'en erreur.
fn normalize_variant(variant: &str) -> String {
    if variant.eq_ignore_ascii_case("slim") { "slim".to_string() } else { "classic".to_string() }
}

/// Extrait l'URL de la texture et le modèle de la propriété `textures` d'un
/// profil de session (déjà décodée depuis son base64).
fn parse_textures_payload(json: &serde_json::Value) -> Result<(String, String), String> {
    let skin = json
        .get("textures")
        .and_then(|t| t.get("SKIN"))
        .ok_or("Ce compte n'a pas de skin — il utilise l'apparence par défaut")?;
    let url = skin
        .get("url")
        .and_then(|u| u.as_str())
        .ok_or("Réponse de Mojang inattendue (texture sans URL)")?;
    // `metadata.model` n'est présent que pour les skins fins : son absence
    // signifie « classic », ce n'est pas une donnée manquante.
    let model = skin
        .get("metadata")
        .and_then(|m| m.get("model"))
        .and_then(|m| m.as_str())
        .unwrap_or("classic");
    Ok((normalize_texture_url(url), normalize_variant(model)))
}

/// UUID sans tirets, tel que l'attendent les adresses de Mojang.
fn undashed(uuid: &str) -> String {
    uuid.chars().filter(|c| *c != '-').collect()
}

fn to_data_uri(bytes: &[u8]) -> String {
    format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(bytes))
}

// ── Cache d'aperçu ───────────────────────────────────────────────────────────

fn cache_dir() -> PathBuf {
    crate::paths::root().join("skins").join("cache")
}

fn cache_path(url: &str) -> PathBuf {
    let digest: [u8; 32] = Sha256::digest(url.as_bytes()).into();
    let name: String = digest.iter().take(16).map(|b| format!("{:02x}", b)).collect();
    cache_dir().join(format!("{}.png", name))
}

/// Télécharge le PNG d'une URL, le valide, et rend son aperçu en data URI.
///
/// Le cache disque évite de retélécharger à chaque affichage, et permet de
/// montrer le skin même hors ligne. Il est indexé par URL : une URL dont le
/// contenu change derrière notre dos gardera l'ancien aperçu, ce qui est
/// acceptable pour un aperçu — la référence enregistrée, elle, reste juste.
async fn fetch_preview(url: &str) -> Result<String, String> {
    check_public_url(url)?;
    let path = cache_path(url);
    if let Ok(bytes) = tokio::fs::read(&path).await {
        if validate_skin_png(&bytes).is_ok() {
            return Ok(to_data_uri(&bytes));
        }
    }

    let resp = reqwest::Client::new()
        .get(url)
        .send()
        .await
        .map_err(|e| format!("Téléchargement impossible : {}", e))?;
    if !resp.status().is_success() {
        return Err(format!("L'hébergeur a répondu {}", resp.status()));
    }
    let bytes = resp.bytes().await.map_err(|e| format!("Lecture de la réponse : {}", e))?;
    if bytes.len() > MAX_SKIN_BYTES {
        return Err("Fichier trop volumineux pour un skin (max 1 Mo)".to_string());
    }
    validate_skin_png(&bytes)?;

    // Le cache est un confort : son échec ne doit pas empêcher l'aperçu.
    if let Err(e) = async {
        tokio::fs::create_dir_all(cache_dir()).await?;
        tokio::fs::write(&path, &bytes).await
    }
    .await
    {
        tracing::warn!("Aperçu de skin non mis en cache : {}", e);
    }

    Ok(to_data_uri(&bytes))
}

// ── Lecture d'un profil Mojang ───────────────────────────────────────────────

async fn textures_of_uuid(uuid: &str) -> Result<(String, String, String), String> {
    let resp = reqwest::Client::new()
        .get(format!("{}{}", SESSION_PROFILE_URL, undashed(uuid)))
        .send()
        .await
        .map_err(|e| format!("Mojang injoignable : {}", e))?;
    if resp.status() == reqwest::StatusCode::NO_CONTENT || resp.status() == reqwest::StatusCode::NOT_FOUND {
        return Err("Profil introuvable chez Mojang".to_string());
    }
    if !resp.status().is_success() {
        return Err(format!("Mojang a répondu {}", resp.status()));
    }
    let profile: serde_json::Value = resp.json().await.map_err(|e| format!("Réponse de Mojang illisible : {}", e))?;

    let name = profile
        .get("name")
        .and_then(|n| n.as_str())
        .unwrap_or_default()
        .to_string();
    let encoded = profile
        .get("properties")
        .and_then(|p| p.as_array())
        .and_then(|props| props.iter().find(|p| p.get("name").and_then(|n| n.as_str()) == Some("textures")))
        .and_then(|p| p.get("value"))
        .and_then(|v| v.as_str())
        .ok_or("Ce profil ne déclare aucune texture")?;
    let raw = base64::engine::general_purpose::STANDARD
        .decode(encoded)
        .map_err(|_| "Propriété de textures illisible".to_string())?;
    let payload: serde_json::Value =
        serde_json::from_slice(&raw).map_err(|_| "Propriété de textures illisible".to_string())?;

    let (url, variant) = parse_textures_payload(&payload)?;
    Ok((name, url, variant))
}

/// Skin d'un joueur premium, trouvé par son pseudo.
///
/// Deux appels anonymes chez Mojang (pseudo → UUID, puis UUID → textures) :
/// aucun compte requis de notre côté, et le skin de n'importe quel joueur est
/// utilisable comme source. C'est la première des deux sources annoncées en
/// tête de fichier.
#[tauri::command]
pub async fn skin_resolve_player(username: String) -> Result<ResolvedSkin, String> {
    let username = username.trim().to_string();
    if username.is_empty() || username.len() > 16 || !username.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
        return Err("Pseudo invalide (1-16 caractères, lettres/chiffres/_)".to_string());
    }

    let resp = reqwest::Client::new()
        .get(format!("{}{}", PROFILE_BY_NAME_URL, username))
        .send()
        .await
        .map_err(|e| format!("Mojang injoignable : {}", e))?;
    if resp.status() == reqwest::StatusCode::NO_CONTENT || resp.status() == reqwest::StatusCode::NOT_FOUND {
        return Err(format!("Aucun compte Minecraft nommé « {} »", username));
    }
    if !resp.status().is_success() {
        return Err(format!("Mojang a répondu {}", resp.status()));
    }
    let found: serde_json::Value = resp.json().await.map_err(|e| format!("Réponse de Mojang illisible : {}", e))?;
    let uuid = found
        .get("id")
        .and_then(|i| i.as_str())
        .ok_or("Réponse de Mojang inattendue (profil sans identifiant)")?
        .to_string();

    let (name, url, variant) = textures_of_uuid(&uuid).await?;
    let data_uri = fetch_preview(&url).await?;
    Ok(ResolvedSkin {
        username: if name.is_empty() { username } else { name },
        uuid,
        url,
        variant,
        data_uri,
    })
}

/// Skin que Mojang sert actuellement pour ce compte — ce que voient les autres
/// joueurs, indépendamment de ce que le launcher a enregistré. Sert à afficher
/// « skin actuel » sur un compte Microsoft qui n'est jamais passé par ici.
#[tauri::command]
pub async fn skin_of_account(uuid: String) -> Result<ResolvedSkin, String> {
    let (name, url, variant) = textures_of_uuid(&uuid).await?;
    let data_uri = fetch_preview(&url).await?;
    Ok(ResolvedSkin { username: name, uuid, url, variant, data_uri })
}

/// Vérifie qu'une URL donnée à la main mène bien à un skin, et rend son aperçu.
/// Le modèle n'est pas deviné : rien dans les pixels ne distingue de façon
/// fiable un skin fin d'un skin classique, c'est donc à l'utilisateur de le
/// dire.
#[tauri::command]
pub async fn skin_check_url(url: String) -> Result<CheckedSkin, String> {
    let url = url.trim().to_string();
    let data_uri = fetch_preview(&url).await?;
    Ok(CheckedSkin { url, data_uri })
}

// ── Application ──────────────────────────────────────────────────────────────

async fn account_row(state: &SharedState, uuid: &str) -> Result<db::McSessionRow, String> {
    let s = state.read().await;
    let conn = s.db.lock().await;
    db::get_mc_session(&conn, uuid)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "Compte Minecraft introuvable".to_string())
}

/// Pose le skin chez Mojang pour un compte Microsoft. C'est Mojang qui va
/// chercher l'URL : elle doit donc être joignable depuis l'extérieur, et une
/// URL locale sera refusée par eux, pas par nous.
async fn push_to_mojang(access_token: &str, url: &str, variant: &str) -> Result<(), String> {
    let resp = reqwest::Client::new()
        .post(MC_SKINS_URL)
        .bearer_auth(access_token)
        .json(&serde_json::json!({ "variant": variant, "url": url }))
        .send()
        .await
        .map_err(|e| format!("Mojang injoignable : {}", e))?;
    if resp.status().is_success() {
        return Ok(());
    }
    let status = resp.status();
    let body = resp.text().await.unwrap_or_default();
    // Mojang détaille souvent la cause (URL inaccessible, image refusée) : la
    // transmettre vaut mieux qu'un code nu.
    Err(if body.trim().is_empty() {
        format!("Mojang a refusé le skin ({})", status)
    } else {
        format!("Mojang a refusé le skin ({}) : {}", status, body.trim())
    })
}

/// Enregistre le skin choisi pour ce compte.
///
/// Compte Microsoft : posé chez Mojang d'abord. Si Mojang refuse, rien n'est
/// enregistré — le launcher ne doit pas afficher un skin que le monde ne voit
/// pas. Compte hors ligne : enregistré tel quel, sans appel réseau.
#[tauri::command]
pub async fn skin_apply(
    state: tauri::State<'_, SharedState>,
    uuid: String,
    url: String,
    variant: String,
    origin: String,
) -> Result<db::SkinRef, String> {
    check_public_url(&url)?;
    // Vérifié avant d'aller plus loin : une URL qui n'est pas un skin ne doit
    // ni partir chez Mojang ni entrer en base.
    fetch_preview(&url).await?;

    let row = account_row(&state, &uuid).await?;
    let variant = normalize_variant(&variant);

    if !row.is_offline {
        let session = super::fresh_session(&state, &uuid).await?;
        push_to_mojang(&session.access_token, &url, &variant).await?;
    }

    let skin = db::SkinRef { url, variant, origin };
    {
        let s = state.read().await;
        let conn = s.db.lock().await;
        db::set_skin_ref(&conn, &uuid, &skin).map_err(|e| e.to_string())?;
    }
    crate::integrations::analytics::capture(
        "skin_applied",
        serde_json::json!({ "offline": row.is_offline, "variant": skin.variant }),
    );
    Ok(skin)
}

/// Retire le skin choisi. Pour un compte Microsoft, remet aussi l'apparence par
/// défaut chez Mojang : ne rendre la référence que localement laisserait le
/// skin en place pour tout le monde sauf pour nous.
#[tauri::command]
pub async fn skin_remove(state: tauri::State<'_, SharedState>, uuid: String) -> Result<(), String> {
    let row = account_row(&state, &uuid).await?;

    if !row.is_offline {
        let session = super::fresh_session(&state, &uuid).await?;
        let resp = reqwest::Client::new()
            .delete(MC_ACTIVE_SKIN_URL)
            .bearer_auth(&session.access_token)
            .send()
            .await
            .map_err(|e| format!("Mojang injoignable : {}", e))?;
        if !resp.status().is_success() {
            return Err(format!("Mojang a refusé la remise à zéro ({})", resp.status()));
        }
    }

    let s = state.read().await;
    let conn = s.db.lock().await;
    db::clear_skin_ref(&conn, &uuid).map_err(|e| e.to_string())
}

/// Référence enregistrée pour ce compte, sans appel réseau.
#[tauri::command]
pub async fn skin_current(
    state: tauri::State<'_, SharedState>,
    uuid: String,
) -> Result<Option<db::SkinRef>, String> {
    let s = state.read().await;
    let conn = s.db.lock().await;
    db::get_skin_ref(&conn, &uuid).map_err(|e| e.to_string())
}

/// Aperçu du skin enregistré pour ce compte, en data URI — ce que l'accueil et
/// la liste des comptes affichent. `None` = aucun skin choisi.
#[tauri::command]
pub async fn skin_preview_for_account(
    state: tauri::State<'_, SharedState>,
    uuid: String,
) -> Result<Option<String>, String> {
    let skin = {
        let s = state.read().await;
        let conn = s.db.lock().await;
        db::get_skin_ref(&conn, &uuid).map_err(|e| e.to_string())?
    };
    let Some(skin) = skin else { return Ok(None) };
    // Un aperçu indisponible (hébergeur éteint, pas de réseau) n'est pas une
    // erreur à remonter à l'écran : l'appelant affichera son repli.
    match fetch_preview(&skin.url).await {
        Ok(data_uri) => Ok(Some(data_uri)),
        Err(e) => {
            tracing::warn!("Aperçu du skin de {} indisponible : {}", uuid, e);
            Ok(None)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn png_header(width: u32, height: u32) -> Vec<u8> {
        let mut bytes = PNG_SIGNATURE.to_vec();
        bytes.extend_from_slice(&[0, 0, 0, 13]); // longueur du chunk IHDR
        bytes.extend_from_slice(b"IHDR");
        bytes.extend_from_slice(&width.to_be_bytes());
        bytes.extend_from_slice(&height.to_be_bytes());
        bytes
    }

    #[test]
    fn les_deux_formats_de_skin_sont_acceptes() {
        assert!(validate_skin_png(&png_header(64, 64)).is_ok());
        assert!(validate_skin_png(&png_header(64, 32)).is_ok());
    }

    #[test]
    fn une_image_aux_mauvaises_dimensions_est_refusee() {
        assert!(validate_skin_png(&png_header(128, 128)).is_err());
        assert!(validate_skin_png(&png_header(32, 32)).is_err());
    }

    #[test]
    fn ce_qui_n_est_pas_un_png_est_refuse() {
        assert!(validate_skin_png(b"pas du tout un png, mais assez long").is_err());
        assert!(validate_skin_png(&[]).is_err());
    }

    #[test]
    fn les_textures_mojang_passent_en_https() {
        assert_eq!(
            normalize_texture_url("http://textures.minecraft.net/texture/abc"),
            "https://textures.minecraft.net/texture/abc"
        );
        // Un autre hôte n'est pas réécrit : on ne peut pas présumer qu'il
        // répond en TLS.
        assert_eq!(normalize_texture_url("http://exemple.test/s.png"), "http://exemple.test/s.png");
        assert_eq!(
            normalize_texture_url("https://textures.minecraft.net/texture/abc"),
            "https://textures.minecraft.net/texture/abc"
        );
    }

    #[test]
    fn le_modele_absent_vaut_classic() {
        let payload = serde_json::json!({
            "textures": { "SKIN": { "url": "http://textures.minecraft.net/texture/abc" } }
        });
        let (url, variant) = parse_textures_payload(&payload).unwrap();
        assert_eq!(url, "https://textures.minecraft.net/texture/abc");
        assert_eq!(variant, "classic");
    }

    #[test]
    fn le_modele_slim_est_lu() {
        let payload = serde_json::json!({
            "textures": {
                "SKIN": {
                    "url": "https://exemple.test/s.png",
                    "metadata": { "model": "slim" }
                }
            }
        });
        assert_eq!(parse_textures_payload(&payload).unwrap().1, "slim");
    }

    #[test]
    fn un_profil_sans_skin_le_dit() {
        let payload = serde_json::json!({ "textures": {} });
        assert!(parse_textures_payload(&payload).is_err());
    }

    #[test]
    fn un_modele_inconnu_retombe_sur_classic() {
        assert_eq!(normalize_variant("SLIM"), "slim");
        assert_eq!(normalize_variant("n'importe quoi"), "classic");
        assert_eq!(normalize_variant(""), "classic");
    }

    #[test]
    fn seules_les_urls_http_sont_acceptees() {
        assert!(check_public_url("https://exemple.test/s.png").is_ok());
        assert!(check_public_url("http://exemple.test/s.png").is_ok());
        // Un chemin local n'est pas partageable : c'est tout l'objet de ce
        // système de n'accepter que des skins déjà hébergés.
        assert!(check_public_url("C:\\skins\\moi.png").is_err());
        assert!(check_public_url("file:///C:/skins/moi.png").is_err());
    }

    #[test]
    fn l_uuid_perd_ses_tirets_pour_mojang() {
        assert_eq!(
            undashed("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
            "069a79f444e94726a5befca90e38aaf5"
        );
    }

    #[test]
    fn deux_urls_differentes_ne_partagent_pas_leur_cache() {
        assert_ne!(cache_path("https://a.test/s.png"), cache_path("https://b.test/s.png"));
        assert_eq!(cache_path("https://a.test/s.png"), cache_path("https://a.test/s.png"));
    }
}
