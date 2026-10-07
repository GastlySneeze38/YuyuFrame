//! L'empreinte d'un joueur, telle que le serveur la calcule de son côté
//! (`Server/LauncherAPI/src/launcher/presence.rs`, fonction `fingerprint`) :
//! SHA-256(domaine ‖ 16 octets de l'UUID ‖ pseudo en minuscules ASCII).
//!
//! UUID ET pseudo : un joueur dont le serveur de jeu masque le pseudo (/nick)
//! ne correspond plus à rien, et n'est donc jamais démasqué.

use ring::digest::{Context, SHA256};

/// Sépare cette empreinte de tout autre SHA-256 d'un UUID. La changer, c'est
/// changer de protocole : le serveur a la même, figée par un test.
const DOMAIN: &[u8] = b"yuyuframe-presence-v1";

/// `name_lower` : le pseudo déjà en minuscules (voir `PlayerKey`).
pub fn fingerprint(uuid: u128, name_lower: &[u8]) -> [u8; 32] {
    let mut hasher = Context::new(&SHA256);
    hasher.update(DOMAIN);
    hasher.update(&uuid.to_be_bytes());
    hasher.update(name_lower);
    let mut out = [0u8; 32];
    out.copy_from_slice(hasher.finish().as_ref());
    out
}

pub fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push(DIGITS[(b >> 4) as usize] as char);
        out.push(DIGITS[(b & 15) as usize] as char);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// La valeur de référence du serveur (test `empreinte_figee` de
    /// LauncherAPI) : si l'une des deux bouge, plus personne n'est reconnu.
    #[test]
    fn meme_empreinte_que_le_serveur() {
        assert_eq!(hex(&fingerprint(0, b"steve")), "b5a84f3e49672fa1978e411000c411db1bd08bb6fc3f8214ace278ca2faeb85d");
    }

    #[test]
    fn uuid_et_pseudo_comptent_tous_les_deux() {
        assert_ne!(fingerprint(1, b"steve"), fingerprint(2, b"steve"));
        assert_ne!(fingerprint(1, b"steve"), fingerprint(1, b"alex"));
    }
}
