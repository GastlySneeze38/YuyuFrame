//! Liens de partage `yuyuframe://<sorte>?…` — un seul module pour tout le
//! launcher (2026-10-02).
//!
//! Partager sans rien héberger : tout ce que le destinataire doit savoir
//! voyage **dans le lien lui-même**, qu'on colle dans le launcher ou qu'on
//! clique. Chaque écran qui partage quelque chose choisit une **sorte**
//! (`instance`, `options`…) et ses paramètres ; ce module s'occupe du reste,
//! pour que tous les liens aient la même forme et soient lus avec les mêmes
//! garde-fous :
//!
//! - `build` / `parse` : le lien et ses paramètres, avec un numéro de version
//!   par sorte (`v`) — un lien fabriqué par une version plus récente est
//!   refusé avec un message clair plutôt que mal lu.
//! - `pack` / `unpack` : une charge utile quelconque (JSON → deflate →
//!   base64 URL) dans un paramètre `d`, pour les données qui se compressent
//!   (du texte de réglages). Des identifiants aléatoires, eux, ne gagnent rien
//!   à la compression : ceux-là vont en paramètres simples (`instance`).
//!
//! Le lien reçu vient de n'importe qui : `unpack` borne la taille **après
//! décompression** (une bombe deflate de quelques Ko peut en faire des Go), et
//! c'est à chaque sorte de valider ce qu'elle en tire.
//!
//! Côté interface, `lib/shareLink.ts` reconnaît la sorte d'un lien et
//! `App.tsx` envoie chaque sorte vers son écran. Une sorte nouvelle = un
//! paramètre `kind` ici, une entrée dans ce routage.

use std::collections::HashMap;
use std::io::{Read, Write};

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use serde::de::DeserializeOwned;
use serde::Serialize;

pub const SCHEME: &str = "yuyuframe";

/// Paramètre de la charge utile compressée.
pub const DATA_PARAM: &str = "d";

/// Taille maximale d'une charge utile une fois décompressée.
const MAX_UNPACKED: u64 = 512 * 1024;

/// Fabrique `yuyuframe://<kind>?v=<version>&…`.
pub fn build(kind: &str, version: u32, params: &[(&str, &str)]) -> String {
    let mut url = reqwest::Url::parse(&format!("{SCHEME}://{kind}")).expect("sorte de lien valide");
    {
        let mut query = url.query_pairs_mut();
        query.append_pair("v", &version.to_string());
        for (key, value) in params {
            query.append_pair(key, value);
        }
    }
    url.to_string()
}

/// Lit un lien de la sorte attendue et rend ses paramètres (sans `v`).
pub fn parse(link: &str, kind: &str, version: u32) -> Result<HashMap<String, String>, String> {
    let url = reqwest::Url::parse(link.trim()).map_err(|_| "Ce n'est pas un lien de partage YuyuFrame".to_string())?;
    if url.scheme() != SCHEME {
        return Err("Ce n'est pas un lien de partage YuyuFrame".into());
    }
    if url.host_str() != Some(kind) {
        return Err("Ce lien de partage ne sert pas ici".into());
    }
    let mut params: HashMap<String, String> = url.query_pairs().into_owned().collect();
    match params.remove("v").and_then(|v| v.parse::<u32>().ok()) {
        Some(v) if v == version => Ok(params),
        Some(v) if v > version => Err("Ce lien vient d'une version plus récente de YuyuFrame : mets le launcher à jour".into()),
        _ => Err("Lien de partage abîmé".into()),
    }
}

/// La sorte d'un lien (`instance`, `options`…), sans rien valider d'autre.
pub fn kind_of(link: &str) -> Option<String> {
    let url = reqwest::Url::parse(link.trim()).ok()?;
    (url.scheme() == SCHEME).then(|| url.host_str().map(str::to_string)).flatten()
}

/// Valeur → JSON → deflate → base64 URL.
pub fn pack<T: Serialize>(value: &T) -> Result<String, String> {
    let json = serde_json::to_vec(value).map_err(|e| e.to_string())?;
    let mut encoder = flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::best());
    encoder.write_all(&json).map_err(|e| e.to_string())?;
    let compressed = encoder.finish().map_err(|e| e.to_string())?;
    Ok(URL_SAFE_NO_PAD.encode(compressed))
}

/// Inverse de `pack`, taille décompressée bornée.
pub fn unpack<T: DeserializeOwned>(data: &str) -> Result<T, String> {
    let broken = || "Lien de partage abîmé".to_string();
    let compressed = URL_SAFE_NO_PAD.decode(data.trim().trim_end_matches('=')).map_err(|_| broken())?;
    let mut json = Vec::new();
    flate2::read::DeflateDecoder::new(compressed.as_slice())
        .take(MAX_UNPACKED + 1)
        .read_to_end(&mut json)
        .map_err(|_| broken())?;
    if json.len() as u64 > MAX_UNPACKED {
        return Err("Lien de partage trop volumineux".into());
    }
    serde_json::from_slice(&json).map_err(|_| broken())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn aller_retour_des_parametres() {
        let link = build("options", 1, &[("n", "Ma config & co"), ("x", "a b")]);
        assert!(link.starts_with("yuyuframe://options?v=1&"));
        assert_eq!(kind_of(&link).as_deref(), Some("options"));
        let params = parse(&link, "options", 1).unwrap();
        assert_eq!(params.get("n").map(String::as_str), Some("Ma config & co"));
        assert_eq!(params.get("x").map(String::as_str), Some("a b"));
        assert!(!params.contains_key("v"));
    }

    #[test]
    fn mauvaise_sorte_ou_version() {
        let link = build("options", 2, &[]);
        assert!(parse(&link, "instance", 2).is_err());
        assert!(parse(&link, "options", 1).unwrap_err().contains("plus récente"));
        assert!(parse("https://yuyuframe.eu/options?v=1", "options", 1).is_err());
        assert!(parse("pas un lien", "options", 1).is_err());
    }

    #[test]
    fn charge_utile_aller_retour() {
        let value = vec![("fov".to_string(), "0.5".to_string()); 50];
        let packed = pack(&value).unwrap();
        assert!(packed.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'));
        let back: Vec<(String, String)> = unpack(&packed).unwrap();
        assert_eq!(back, value);
        assert!(unpack::<Vec<String>>("%%%").is_err());
    }

    /// Quelques octets qui se décompressent en beaucoup trop : refusé sans
    /// tout décompresser.
    #[test]
    fn bombe_de_decompression_refusee() {
        let huge = format!("\"{}\"", "a".repeat(2 * 1024 * 1024));
        let mut encoder = flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::best());
        encoder.write_all(huge.as_bytes()).unwrap();
        let data = URL_SAFE_NO_PAD.encode(encoder.finish().unwrap());
        assert!(data.len() < 10_000);
        assert!(unpack::<String>(&data).unwrap_err().contains("volumineux"));
    }
}
