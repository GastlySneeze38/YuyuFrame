//! Le codec des liens `yuyuframe://<sorte>/<données>` : zstd au niveau maximal
//! avec le dictionnaire figé du launcher, puis base 32 768.
//!
//! **Copie du launcher** (`Launcher-Client/Backend/src/share_link/mod.rs`, où
//! tout est expliqué) : même dictionnaire — le fichier du launcher lui-même,
//! inclus d'ici —, mêmes paramètres zstd, même découpage en parties. Un lien
//! fait dans le jeu se lit dans le launcher et inversement ; corriger l'un,
//! c'est corriger l'autre.

use std::io::Read;
use std::sync::LazyLock;

pub const SCHEME: &str = "yuyuframe";

/// zstd niveau 22 + dictionnaire v1 + base 32 768.
const CODEC_V1: u8 = 1;
/// Le dictionnaire du launcher, à l'octet près : un lien ne se décode qu'avec
/// celui qui l'a fabriqué. Ne jamais le modifier (voir le launcher).
static DICT_V1: LazyLock<Vec<u8>> = LazyLock::new(|| {
    include_str!("../../../../Backend/src/share_link/share_dict_v1.txt").replace('\r', "").into_bytes()
});

const ZSTD_MAGIC: [u8; 4] = [0x28, 0xB5, 0x2F, 0xFD];
const ZSTD_LEVEL: i32 = 22;
/// 4 Mio de fenêtre au plus, à la fabrication comme à la lecture.
const WINDOW_LOG: u32 = 22;
/// Le lien vient de n'importe qui : quelques Ko peuvent se décompresser en Go.
const MAX_UNPACKED: u64 = 8 * 1024 * 1024;

/// Un message Discord standard.
pub const PART_MAX_CHARS: usize = 2000;
/// Au-delà, personne ne recolle les morceaux.
pub const MAX_PARTS: usize = 12;

fn broken() -> String {
    "Lien de partage abîmé".to_string()
}

/// Fabrique le lien d'un texte, ou ses parties s'il ne tient pas en un.
pub fn build_text(kind: &str, text: &str) -> Result<Vec<String>, String> {
    let compressed = compress(text.as_bytes())?;
    let single = format!("{SCHEME}://{kind}/{}", base32768::encode(&compressed));
    if single.chars().count() <= PART_MAX_CHARS {
        return Ok(vec![single]);
    }
    let id = short_id(&compressed);
    let prefix_len = SCHEME.len() + "://".len() + kind.len() + "/".len() + "99.99.".len() + id.len() + "/".len();
    let bytes_per_part = (PART_MAX_CHARS - prefix_len) * 15 / 8 - 4;
    let parts = compressed.len().div_ceil(bytes_per_part);
    if parts > MAX_PARTS {
        return Err(format!("Trop volumineux pour un lien ({parts} parties, {MAX_PARTS} au plus)"));
    }
    Ok(compressed
        .chunks(bytes_per_part)
        .enumerate()
        .map(|(i, chunk)| format!("{SCHEME}://{kind}/{}.{parts}.{id}/{}", i + 1, base32768::encode(chunk)))
        .collect())
}

/// Relit un lien (ou toutes ses parties, collées ensemble dans `text`) de la
/// sorte attendue.
pub fn read_text(text: &str, kind: &str) -> Result<String, String> {
    let links = links_in(text);
    if links.is_empty() {
        return Err("Ce n'est pas un lien de partage YuyuFrame".into());
    }
    if links.iter().any(|l| l.kind != kind) {
        return Err("Ce lien de partage ne sert pas ici".into());
    }
    let compressed = match links.iter().find(|l| l.part.is_none()) {
        Some(single) => single.data.clone(),
        None => join_parts(&links)?,
    };
    String::from_utf8(decompress(&compressed)?).map_err(|_| broken())
}

struct ParsedLink {
    kind: String,
    /// (numéro, total, identifiant) pour une partie.
    part: Option<(u32, u32, String)>,
    data: Vec<u8>,
}

fn links_in(text: &str) -> Vec<ParsedLink> {
    let marker = format!("{SCHEME}://");
    text.split(marker.as_str()).skip(1).filter_map(|chunk| parse_one(chunk.trim())).collect()
}

fn parse_one(rest: &str) -> Option<ParsedLink> {
    let rest = percent_decode(rest)?;
    let (kind, rest) = rest.split_once('/')?;
    if kind.is_empty() || !kind.chars().all(|c| c.is_ascii_lowercase()) {
        return None;
    }
    let is_part = rest.starts_with(|c: char| c.is_ascii_digit());
    let (part, data) = match rest.split_once('/').filter(|_| is_part) {
        Some((header, data)) => {
            let mut fields = header.split('.');
            let i: u32 = fields.next()?.parse().ok()?;
            let n: u32 = fields.next()?.parse().ok()?;
            let id = fields.next()?.to_string();
            if fields.next().is_some() || i == 0 || i > n || n as usize > MAX_PARTS || id.len() != 8 {
                return None;
            }
            (Some((i, n, id)), data)
        }
        None => (None, rest),
    };
    let data: String = data.chars().take_while(|c| !c.is_ascii() || c.is_ascii_whitespace()).collect();
    Some(ParsedLink { kind: kind.to_string(), part, data: base32768::decode(&data)? })
}

fn join_parts(links: &[ParsedLink]) -> Result<Vec<u8>, String> {
    let (_, total, id) = links[0].part.clone().ok_or_else(broken)?;
    let mut parts: Vec<Option<&[u8]>> = vec![None; total as usize];
    for link in links {
        let Some((i, n, part_id)) = &link.part else { return Err(broken()) };
        if *n != total || *part_id != id {
            return Err("Ces parties viennent de liens différents".into());
        }
        parts[*i as usize - 1] = Some(&link.data);
    }
    let missing: Vec<String> = parts
        .iter()
        .enumerate()
        .filter(|(_, p)| p.is_none())
        .map(|(i, _)| (i + 1).to_string())
        .collect();
    if !missing.is_empty() {
        return Err(format!("Il manque la ou les parties {} (sur {total})", missing.join(", ")));
    }
    let joined: Vec<u8> = parts.into_iter().flatten().flatten().copied().collect();
    if short_id(&joined) != id {
        return Err("Ces parties ne vont pas ensemble, ou l'une est abîmée".into());
    }
    Ok(joined)
}

/// Les 8 premiers caractères hexadécimaux du SHA-256.
fn short_id(data: &[u8]) -> String {
    let digest = ring::digest::digest(&ring::digest::SHA256, data);
    digest.as_ref()[..4].iter().map(|b| format!("{b:02x}")).collect()
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
        return Err("Ce lien vient d'une version plus récente de YuyuFrame".into());
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

mod base32768 {
    /// U+3400–U+9FFF : un bloc contigu de caractères assignés.
    const A_START: u32 = 0x3400;
    const A_LEN: u32 = 0x9FFF - 0x3400 + 1;
    /// Syllabes hangul pour le reste.
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
    /// sur des octets.
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

    /// La même empreinte que dans le launcher (`dictionnaire_v1_fige`) : les
    /// deux codecs lisent bien le même dictionnaire.
    #[test]
    fn meme_dictionnaire_que_le_launcher() {
        let digest = ring::digest::digest(&ring::digest::SHA256, DICT_V1.as_slice());
        let hex: String = digest.as_ref().iter().map(|b| format!("{b:02x}")).collect();
        assert_eq!(hex, "6aa12b881960cc2c1ff3ba29de4f548ad790cd192f7b8a88373310b6147411c7");
    }

    #[test]
    fn aller_retour() {
        let text = "\u{1E}fps.enabled=true\nzoom.setting.zoomKey=C\naccent é";
        let links = build_text("options", text).unwrap();
        assert_eq!(links.len(), 1);
        assert!(links[0].starts_with("yuyuframe://options/"));
        assert_eq!(read_text(&links[0], "options").unwrap(), text);
        assert_eq!(read_text(&format!("tiens : {} merci", links[0]), "options").unwrap(), text);
        assert!(read_text(&links[0], "instance").unwrap_err().contains("ne sert pas ici"));
    }

    fn incompressible(len: usize) -> String {
        let mut x: u32 = 0x1234_5678;
        (0..len)
            .map(|_| {
                x ^= x << 13;
                x ^= x >> 17;
                x ^= x << 5;
                char::from(b'a' + (x % 26) as u8)
            })
            .collect()
    }

    #[test]
    fn plusieurs_parties_dans_le_desordre() {
        let text = incompressible(12_000);
        let parts = build_text("options", &text).unwrap();
        assert!(parts.len() > 1);
        for part in &parts {
            assert!(part.chars().count() <= PART_MAX_CHARS);
        }
        let shuffled = parts.iter().rev().cloned().collect::<Vec<_>>().join("\n\n");
        assert_eq!(read_text(&shuffled, "options").unwrap(), text);
        assert!(read_text(&parts[0], "options").unwrap_err().contains("Il manque"));
    }

    #[test]
    fn liens_invalides_et_bombe() {
        assert!(read_text("pas un lien", "options").is_err());
        assert!(read_text("yuyuframe://options/abc", "options").is_err());
        let bomb = build_text("options", &"a".repeat(16 * 1024 * 1024)).unwrap();
        assert!(read_text(&bomb[0], "options").unwrap_err().contains("volumineux"));
    }

    /// Fabrique un lien à relire avec le launcher, pour vérifier que les deux
    /// codecs s'entendent : `cargo test --lib lien_pour_le_launcher -- --ignored --nocapture`,
    /// puis dans `Backend` : `YF_LINK=<lien> cargo test --lib decoder_un_lien -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn lien_pour_le_launcher() {
        let link = build_text("options", "\u{1E}fps.enabled=true\nzoom.setting.zoomKey=C").unwrap();
        println!("{}", link[0]);
    }
}
