//! Liens de partage `yuyuframe://<sorte>/<données>` — un seul module pour
//! tout le launcher (2026-10-02).
//!
//! Partager sans rien héberger : tout ce que le destinataire doit savoir
//! voyage **dans le lien**, qu'on colle dans le launcher ou qu'on clique.
//! Chaque écran qui partage quelque chose choisit une **sorte** (`instance`,
//! `options`…) et fournit un **texte** ; ce module le rend aussi court que
//! possible, et le relit avec les mêmes garde-fous pour tout le monde.
//!
//! ── Compression ─────────────────────────────────────────────────────────────
//! Les deux bouts sont des launchers YuyuFrame : on peut se permettre ce
//! qu'aucun format public ne ferait. Trois étages, mesurés sur une vraie
//! instance (options du jeu + du client, 18 Ko) : 5 852 caractères en
//! deflate + base64, **~1 400** ici — sous la limite d'un message Discord.
//!
//! 1. **zstd au niveau maximal avec un dictionnaire figé** (`share_dict_v1.txt`,
//!    embarqué dans le binaire) : clés d'`options.txt` moderne et 1.8.9, clés
//!    des modules du client, drapeaux JVM des préréglages. Tout ce qui
//!    ressemble aux valeurs par défaut se code en « comme dans le
//!    dictionnaire ». Signature, somme de contrôle et taille du cadre zstd sont
//!    retirées : elles sont connues ou inutiles des deux côtés.
//! 2. **Du texte, pas du JSON** : chaque sorte envoie ses données sous la forme
//!    même des fichiers d'origine (`clé:valeur`, `clé=valeur`), donc sous la
//!    forme du dictionnaire — chaque guillemet évité est une correspondance
//!    de plus.
//! 3. **Base 32 768** : un caractère = 15 bits, contre 6 en base64. Les
//!    caractères sont des idéogrammes CJK (U+3400–U+9FFF, contigus) et des
//!    syllabes hangul (U+AC00…) : tous assignés, stables par normalisation
//!    Unicode, d'un seul code UTF-16 — et c'est en caractères que Discord
//!    compte.
//!
//! ── Ne jamais modifier le dictionnaire ──────────────────────────────────────
//! Un lien se décode avec **le même dictionnaire, à l'octet près**, que celui
//! qui l'a fabriqué. Le premier octet des données dit lequel (`CODEC_V1`).
//! Pour améliorer le dictionnaire : un `share_dict_v2.txt` et un `CODEC_V2`,
//! en gardant le v1 pour relire les anciens liens. Un test fige l'empreinte
//! du v1, et les `\r` sont retirés au chargement : un dépôt cloné sous Windows
//! avec conversion des fins de ligne donne le même dictionnaire.
//!
//! ── Garde-fous ──────────────────────────────────────────────────────────────
//! Le lien vient de n'importe qui : taille décompressée bornée (une bombe de
//! quelques Ko peut en faire des Go), fenêtre zstd bornée (sinon un cadre
//! peut exiger 128 Mo de mémoire), et c'est à chaque sorte de valider le
//! texte qu'elle reçoit.
//!
//! Côté interface : `lib/shareLink.ts` reconnaît la sorte d'un lien,
//! `App.tsx` envoie chaque sorte vers son écran, `ShareLinkButton` copie.

use std::io::Read;
use std::sync::LazyLock;

pub const SCHEME: &str = "yuyuframe";

/// zstd niveau 22 + dictionnaire v1 + base 32 768.
const CODEC_V1: u8 = 1;
static DICT_V1: LazyLock<Vec<u8>> =
    LazyLock::new(|| include_str!("share_dict_v1.txt").replace('\r', "").into_bytes());

const ZSTD_MAGIC: [u8; 4] = [0x28, 0xB5, 0x2F, 0xFD];
const ZSTD_LEVEL: i32 = 22;
/// 4 Mio de fenêtre au plus, à la fabrication comme à la lecture.
const WINDOW_LOG: u32 = 22;
const MAX_UNPACKED: u64 = 512 * 1024;

fn broken() -> String {
    "Lien de partage abîmé".to_string()
}

// ── Lien ────────────────────────────────────────────────────────────────────

/// Fabrique `yuyuframe://<kind>/<données>` à partir d'un texte.
pub fn build(kind: &str, text: &str) -> Result<String, String> {
    Ok(format!("{SCHEME}://{kind}/{}", base32768::encode(&compress(text.as_bytes())?)))
}

/// Relit un lien de la sorte attendue et rend son texte.
pub fn read(link: &str, kind: &str) -> Result<String, String> {
    let (found, data) = split(link).ok_or("Ce n'est pas un lien de partage YuyuFrame")?;
    if found != kind {
        return Err("Ce lien de partage ne sert pas ici".into());
    }
    let bytes = decompress(&base32768::decode(&data).ok_or_else(broken)?)?;
    String::from_utf8(bytes).map_err(|_| broken())
}

/// La sorte d'un lien (`instance`, `options`…), sans rien valider d'autre.
pub fn kind_of(link: &str) -> Option<String> {
    split(link).map(|(kind, _)| kind)
}

/// `yuyuframe://<sorte>/<données>` → (sorte, données). Un lien cliqué arrive
/// souvent encodé en `%XX` par le système : on le décode.
fn split(link: &str) -> Option<(String, String)> {
    let rest = link.trim().strip_prefix(SCHEME)?.strip_prefix("://")?;
    let (kind, data) = rest.split_once('/')?;
    if kind.is_empty() || !kind.chars().all(|c| c.is_ascii_lowercase()) {
        return None;
    }
    Some((kind.to_string(), percent_decode(data)?))
}

fn percent_decode(text: &str) -> Option<String> {
    if !text.contains('%') {
        return Some(text.to_string());
    }
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let hex = std::str::from_utf8(bytes.get(i + 1..i + 3)?).ok()?;
            out.push(u8::from_str_radix(hex, 16).ok()?);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

// ── Compression ─────────────────────────────────────────────────────────────

fn compress(data: &[u8]) -> Result<Vec<u8>, String> {
    use zstd::zstd_safe::CParameter;
    let mut compressor = zstd::bulk::Compressor::with_dictionary(ZSTD_LEVEL, &DICT_V1).map_err(|e| e.to_string())?;
    for parameter in [
        CParameter::ChecksumFlag(false),
        CParameter::ContentSizeFlag(false),
        CParameter::DictIdFlag(false),
        CParameter::WindowLog(WINDOW_LOG),
    ] {
        compressor.set_parameter(parameter).map_err(|e| e.to_string())?;
    }
    let frame = compressor.compress(data).map_err(|e| e.to_string())?;
    let body = frame.strip_prefix(&ZSTD_MAGIC[..]).ok_or("Compression : cadre zstd inattendu")?;
    let mut out = Vec::with_capacity(body.len() + 1);
    out.push(CODEC_V1);
    out.extend_from_slice(body);
    Ok(out)
}

fn decompress(data: &[u8]) -> Result<Vec<u8>, String> {
    let (&codec, body) = data.split_first().ok_or_else(broken)?;
    if codec != CODEC_V1 {
        return Err("Ce lien vient d'une version plus récente de YuyuFrame : mets le launcher à jour".into());
    }
    let mut frame = ZSTD_MAGIC.to_vec();
    frame.extend_from_slice(body);
    let mut decoder = zstd::stream::read::Decoder::with_dictionary(frame.as_slice(), &DICT_V1).map_err(|_| broken())?;
    decoder.window_log_max(WINDOW_LOG).map_err(|_| broken())?;
    let mut out = Vec::new();
    decoder.take(MAX_UNPACKED + 1).read_to_end(&mut out).map_err(|_| broken())?;
    if out.len() as u64 > MAX_UNPACKED {
        return Err("Lien de partage trop volumineux".into());
    }
    Ok(out)
}

// ── Base 32 768 ─────────────────────────────────────────────────────────────

mod base32768 {
    /// U+3400–U+9FFF : extension A, hexagrammes du Yi King, idéogrammes
    /// unifiés — un bloc contigu de caractères assignés.
    const A_START: u32 = 0x3400;
    const A_LEN: u32 = 0x9FFF - 0x3400 + 1;
    /// Syllabes hangul pour le reste (5 120 des 11 172).
    const B_START: u32 = 0xAC00;
    const BITS: u32 = 15;
    const MASK: u64 = (1 << BITS) - 1;

    fn to_char(v: u32) -> char {
        let code = if v < A_LEN { A_START + v } else { B_START + (v - A_LEN) };
        char::from_u32(code).expect("plage vérifiée")
    }

    fn from_char(c: char) -> Option<u32> {
        let code = c as u32;
        if (A_START..A_START + A_LEN).contains(&code) {
            Some(code - A_START)
        } else if (B_START..B_START + (1 << BITS) - A_LEN).contains(&code) {
            Some(code - B_START + A_LEN)
        } else {
            None
        }
    }

    /// Longueur en tête (LEB128) : 15 bits par caractère ne tombent pas juste
    /// sur des octets, et sans elle le décodeur ne saurait pas si le dernier
    /// octet est du bourrage.
    pub fn encode(bytes: &[u8]) -> String {
        let mut framed = Vec::with_capacity(bytes.len() + 3);
        let mut n = bytes.len();
        loop {
            let byte = (n & 0x7F) as u8;
            n >>= 7;
            if n == 0 {
                framed.push(byte);
                break;
            }
            framed.push(byte | 0x80);
        }
        framed.extend_from_slice(bytes);

        let mut out = String::with_capacity(framed.len() * 8 / BITS as usize + 1);
        let (mut acc, mut bits) = (0u64, 0u32);
        for &b in &framed {
            acc = (acc << 8) | b as u64;
            bits += 8;
            while bits >= BITS {
                bits -= BITS;
                out.push(to_char(((acc >> bits) & MASK) as u32));
            }
            acc &= (1 << bits) - 1;
        }
        if bits > 0 {
            out.push(to_char(((acc << (BITS - bits)) & MASK) as u32));
        }
        out
    }

    /// Les espaces et retours à la ligne sont ignorés : une messagerie peut
    /// en glisser en coupant un long message.
    pub fn decode(text: &str) -> Option<Vec<u8>> {
        let mut framed = Vec::with_capacity(text.len());
        let (mut acc, mut bits) = (0u64, 0u32);
        for c in text.chars().filter(|c| !c.is_whitespace()) {
            acc = (acc << BITS) | from_char(c)? as u64;
            bits += BITS;
            while bits >= 8 {
                bits -= 8;
                framed.push(((acc >> bits) & 0xFF) as u8);
            }
            acc &= (1 << bits) - 1;
        }
        let (mut len, mut shift, mut pos) = (0usize, 0u32, 0usize);
        loop {
            let byte = *framed.get(pos)?;
            pos += 1;
            len |= ((byte & 0x7F) as usize).checked_shl(shift)?;
            if byte & 0x80 == 0 {
                break;
            }
            shift += 7;
            if shift > 28 {
                return None;
            }
        }
        framed.get(pos..pos + len).map(<[u8]>::to_vec)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sha2::{Digest, Sha256};

    /// Empreinte du dictionnaire v1. Si ce test échoue, le dictionnaire a été
    /// modifié : tous les liens déjà partagés deviendraient illisibles.
    /// Remettre le fichier tel quel, et créer un v2 à côté.
    #[test]
    fn dictionnaire_v1_fige() {
        let digest = format!("{:x}", Sha256::digest(DICT_V1.as_slice()));
        assert_eq!(digest, DICT_V1_SHA256);
    }

    const DICT_V1_SHA256: &str = "6aa12b881960cc2c1ff3ba29de4f548ad790cd192f7b8a88373310b6147411c7";

    #[test]
    fn aller_retour() {
        let text = "fov:0.5\nmaxFps:260\n\u{1E}zoom.enabled=true\nmacro accentuée é";
        let link = build("options", text).unwrap();
        assert!(link.starts_with("yuyuframe://options/"));
        assert_eq!(kind_of(&link).as_deref(), Some("options"));
        assert_eq!(read(&link, "options").unwrap(), text);
        assert!(read(&link, "instance").unwrap_err().contains("ne sert pas ici"));
    }

    #[test]
    fn lien_encode_par_le_systeme() {
        let link = build("options", "fov:0.5").unwrap();
        let data = link.strip_prefix("yuyuframe://options/").unwrap();
        let encoded: String = data.bytes().map(|b| format!("%{b:02X}")).collect();
        assert_eq!(read(&format!("yuyuframe://options/{encoded}"), "options").unwrap(), "fov:0.5");
    }

    #[test]
    fn base32768_toutes_longueurs() {
        for n in 0..64usize {
            let bytes: Vec<u8> = (0..n).map(|i| (i * 37 + 11) as u8).collect();
            let text = base32768::encode(&bytes);
            assert!(text.chars().all(|c| (c as u32) < 0x10000), "un seul code UTF-16");
            assert_eq!(base32768::decode(&text).unwrap(), bytes, "{n}");
            // Coupé par une messagerie : toujours lisible.
            let cut: String = text.chars().flat_map(|c| [c, '\n']).collect();
            assert_eq!(base32768::decode(&cut).unwrap(), bytes);
        }
    }

    #[test]
    fn liens_invalides() {
        assert!(read("pas un lien", "options").is_err());
        assert!(read("https://yuyuframe.eu/options/x", "options").is_err());
        assert!(read("yuyuframe://options/abc", "options").is_err());
        assert!(read("yuyuframe://options/", "options").is_err());
    }

    /// Quelques octets qui se décompressent en beaucoup trop : refusé sans
    /// tout décompresser.
    #[test]
    fn bombe_de_decompression_refusee() {
        let link = build("options", &"a".repeat(2 * 1024 * 1024)).unwrap();
        assert!(link.chars().count() < 1_000);
        assert!(read(&link, "options").unwrap_err().contains("volumineux"));
    }

    /// Le gain attendu, sur des options proches de celles du dictionnaire.
    #[test]
    fn options_par_defaut_presque_gratuites() {
        let dict = String::from_utf8(DICT_V1.clone()).unwrap();
        let options: String = dict.lines().filter(|l| l.starts_with("key_key.") && l.contains("key.keyboard")).collect::<Vec<_>>().join("\n");
        let link = build("options", &options).unwrap();
        assert!(link.chars().count() < options.len() / 20, "{} pour {}", link.chars().count(), options.len());
    }
}
