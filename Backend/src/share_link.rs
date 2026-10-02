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
/// Une instance avec son `config/` tient en quelques centaines de Ko ; 12
/// parties ne peuvent pas porter plus de quelques Mo de texte réel.
const MAX_UNPACKED: u64 = 8 * 1024 * 1024;

fn broken() -> String {
    "Lien de partage abîmé".to_string()
}

// ── Lien, et liens en plusieurs parties ─────────────────────────────────────
//
// Un lien plus long que `PART_MAX_CHARS` (un message Discord) est découpé en
// parties : `yuyuframe://<sorte>/<i>.<n>.<id>/<données>`, `id` = début du
// SHA-256 des données compressées. Le destinataire colle toutes les parties,
// dans n'importe quel ordre, en une fois ou en plusieurs ; `status` dit ce
// qui manque, et l'empreinte vérifie à la fin que les parties vont ensemble.

/// Un message Discord standard.
pub const PART_MAX_CHARS: usize = 2000;
/// Au-delà, mieux vaut le fichier : personne ne recolle vingt messages.
pub const MAX_PARTS: usize = 12;

#[derive(Debug)]
pub enum LinkError {
    /// Il faudrait ce nombre de parties, au-delà de `MAX_PARTS`.
    TooLarge { parts: usize },
    Other(String),
}

impl From<String> for LinkError {
    fn from(e: String) -> Self {
        LinkError::Other(e)
    }
}

impl From<LinkError> for String {
    fn from(e: LinkError) -> Self {
        match e {
            LinkError::TooLarge { parts } => format!(
                "Trop volumineux pour un lien ({parts} parties, {MAX_PARTS} au plus) : décoche les éléments les plus lourds, ou partage le fichier."
            ),
            LinkError::Other(e) => e,
        }
    }
}

/// Fabrique le lien d'une donnée, ou ses parties s'il ne tient pas en un.
pub fn build(kind: &str, data: &[u8]) -> Result<Vec<String>, LinkError> {
    let compressed = compress(data)?;
    let single = format!("{SCHEME}://{kind}/{}", base32768::encode(&compressed));
    if single.chars().count() <= PART_MAX_CHARS {
        return Ok(vec![single]);
    }
    let id = short_id(&compressed);
    // Préfixe le plus long possible (`12.12.`), pour que chaque partie tienne.
    let prefix_len = SCHEME.len() + "://".len() + kind.len() + "/".len() + "99.99.".len() + id.len() + "/".len();
    // 15 bits par caractère, moins la longueur LEB128 (2 octets ici) et une
    // marge pour le dernier caractère entamé.
    let bytes_per_part = (PART_MAX_CHARS - prefix_len) * 15 / 8 - 4;
    let parts = compressed.len().div_ceil(bytes_per_part);
    if parts > MAX_PARTS {
        return Err(LinkError::TooLarge { parts });
    }
    Ok(compressed
        .chunks(bytes_per_part)
        .enumerate()
        .map(|(i, chunk)| format!("{SCHEME}://{kind}/{}.{parts}.{id}/{}", i + 1, base32768::encode(chunk)))
        .collect())
}

pub fn build_text(kind: &str, text: &str) -> Result<Vec<String>, LinkError> {
    build(kind, text.as_bytes())
}

/// Relit un lien (ou toutes ses parties, collées ensemble dans `text`) de la
/// sorte attendue.
pub fn read(text: &str, kind: &str) -> Result<Vec<u8>, String> {
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
    decompress(&compressed)
}

pub fn read_text(text: &str, kind: &str) -> Result<String, String> {
    String::from_utf8(read(text, kind)?).map_err(|_| broken())
}

/// La sorte d'un lien (`instance`, `options`…), sans rien valider d'autre.
pub fn kind_of(link: &str) -> Option<String> {
    links_in(link).into_iter().next().map(|l| l.kind)
}

/// Où en est un collage : quelles parties on a, combien il en faut.
#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LinkStatus {
    pub kind: String,
    /// 1 pour un lien d'un seul tenant.
    pub total: u32,
    /// Numéros des parties reçues (1 pour un lien d'un seul tenant).
    pub received: Vec<u32>,
    pub complete: bool,
}

/// Pour l'interface : reconnaître ce qu'on vient de coller, avant de le lire.
#[tauri::command]
pub fn share_link_status(text: String) -> Result<LinkStatus, String> {
    let links = links_in(&text);
    let first = links.first().ok_or("Ce n'est pas un lien de partage YuyuFrame")?;
    if links.iter().any(|l| l.kind != first.kind) {
        return Err("Ces liens ne sont pas de la même sorte".into());
    }
    if links.iter().any(|l| l.part.is_none()) {
        return Ok(LinkStatus { kind: first.kind.clone(), total: 1, received: vec![1], complete: true });
    }
    let (_, total, id) = first.part.clone().expect("vérifié juste au-dessus");
    if links.iter().any(|l| l.part.as_ref().is_some_and(|(_, n, i)| *n != total || *i != id)) {
        return Err("Ces parties viennent de liens différents".into());
    }
    let mut received: Vec<u32> = links.iter().filter_map(|l| l.part.as_ref().map(|(i, _, _)| *i)).collect();
    received.sort_unstable();
    received.dedup();
    Ok(LinkStatus { kind: first.kind.clone(), total, complete: received.len() as u32 == total, received })
}

struct ParsedLink {
    kind: String,
    /// (numéro, total, identifiant) pour une partie.
    part: Option<(u32, u32, String)>,
    data: Vec<u8>,
}

/// Tous les liens d'un texte collé : un message par partie, des retours à
/// la ligne au milieu, du texte autour — on prend ce qui ressemble à un lien.
fn links_in(text: &str) -> Vec<ParsedLink> {
    let marker = format!("{SCHEME}://");
    text.split(marker.as_str()).skip(1).filter_map(|chunk| parse_one(chunk.trim())).collect()
}

/// `<sorte>/[<i>.<n>.<id>/]<données>` ; un lien cliqué arrive souvent encodé
/// en `%XX` par le système.
fn parse_one(rest: &str) -> Option<ParsedLink> {
    let rest = percent_decode(rest)?;
    let (kind, rest) = rest.split_once('/')?;
    if kind.is_empty() || !kind.chars().all(|c| c.is_ascii_lowercase()) {
        return None;
    }
    // Une partie commence par son numéro ; des données, jamais par un chiffre
    // ASCII. Tester le premier caractère plutôt que chercher un `/` : un
    // lien suivi d'une autre adresse dans le même message en contient un.
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

fn short_id(data: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    format!("{:x}", Sha256::digest(data))[..8].to_string()
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

    fn one(kind: &str, text: &str) -> String {
        let mut links = build_text(kind, text).unwrap();
        assert_eq!(links.len(), 1);
        links.remove(0)
    }

    #[test]
    fn aller_retour() {
        let text = "fov:0.5\nmaxFps:260\n\u{1E}zoom.enabled=true\nmacro accentuée é";
        let link = one("options", text);
        assert!(link.starts_with("yuyuframe://options/"));
        assert_eq!(kind_of(&link).as_deref(), Some("options"));
        assert_eq!(read_text(&link, "options").unwrap(), text);
        assert!(read(&link, "instance").unwrap_err().contains("ne sert pas ici"));
        // Collé au milieu d'un message.
        assert_eq!(read_text(&format!("tiens : {link} merci !"), "options").unwrap(), text);
    }

    #[test]
    fn lien_encode_par_le_systeme() {
        let link = one("options", "fov:0.5");
        let data = link.strip_prefix("yuyuframe://options/").unwrap();
        let encoded: String = data.bytes().map(|b| format!("%{b:02X}")).collect();
        assert_eq!(read_text(&format!("yuyuframe://options/{encoded}"), "options").unwrap(), "fov:0.5");
    }

    /// Des données qui ne se compressent pas : plusieurs parties, chacune
    /// sous la limite, relues dans le désordre.
    fn incompressible(len: usize) -> Vec<u8> {
        let mut x: u32 = 0x1234_5678;
        (0..len)
            .map(|_| {
                x ^= x << 13;
                x ^= x >> 17;
                x ^= x << 5;
                x as u8
            })
            .collect()
    }

    #[test]
    fn lien_en_plusieurs_parties() {
        let data = incompressible(9_000);
        let parts = build("instance", &data).unwrap();
        assert!(parts.len() > 1);
        for (i, part) in parts.iter().enumerate() {
            assert!(part.chars().count() <= PART_MAX_CHARS, "partie {} : {}", i + 1, part.chars().count());
            assert!(part.starts_with(&format!("yuyuframe://instance/{}.{}.", i + 1, parts.len())));
        }
        let shuffled: String = parts.iter().rev().cloned().collect::<Vec<_>>().join("\n\n");
        assert_eq!(read(&shuffled, "instance").unwrap(), data);

        let status = share_link_status(parts[0].clone()).unwrap();
        assert_eq!((status.total as usize, status.received.clone(), status.complete), (parts.len(), vec![1], false));
        assert!(share_link_status(shuffled).unwrap().complete);

        let missing = read(&parts[0], "instance").unwrap_err();
        assert!(missing.contains("Il manque"), "{missing}");
    }

    #[test]
    fn parties_de_liens_differents_refusees() {
        let a = build("instance", &incompressible(9_000)).unwrap();
        let mut other = incompressible(9_000);
        other[0] ^= 1;
        let b = build("instance", &other).unwrap();
        let mixed = format!("{}\n{}", a[0], b[1]);
        assert!(read(&mixed, "instance").is_err());
        assert!(share_link_status(mixed).is_err());
    }

    #[test]
    fn trop_de_parties() {
        match build("instance", &incompressible(60_000)) {
            Err(LinkError::TooLarge { parts }) => assert!(parts > MAX_PARTS),
            other => panic!("attendu TooLarge, obtenu {other:?}"),
        }
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
        let link = one("options", &"a".repeat(16 * 1024 * 1024));
        assert!(link.chars().count() < 2_000);
        assert!(read(&link, "options").unwrap_err().contains("volumineux"));
    }

    /// Le gain attendu, sur des options proches de celles du dictionnaire.
    #[test]
    fn options_par_defaut_presque_gratuites() {
        let dict = String::from_utf8(DICT_V1.clone()).unwrap();
        let options: String = dict.lines().filter(|l| l.starts_with("key_key.") && l.contains("key.keyboard")).collect::<Vec<_>>().join("\n");
        let link = one("options", &options);
        assert!(link.chars().count() < options.len() / 20, "{} pour {}", link.chars().count(), options.len());
    }
}
