//! Les fonctions `yf_share_*` : des fonctions C ordinaires, que Java cherche
//! par leur nom dans la DLL (FFM). Leur contrat est ici ; un changement de
//! signature ou de sens se fait avec `ABI_VERSION`.
//!
//! Convention commune : un texte UTF-8 est écrit dans `out`, et la valeur
//! rendue dit ce que c'est — positive ou nulle, le résultat et sa longueur ;
//! négative, un message d'erreur de cette longueur (en valeur absolue), à
//! montrer au joueur. `i32::MIN` : rien n'a pu être écrit.

use super::{clipboard, options};

/// À incrémenter dès qu'une fonction change de signature ou de sens : Java
/// refuse une DLL qui n'annonce pas la version qu'il attend
/// (`NativeShare.ABI_VERSION`).
///
/// 2 (2026-10-11) : le lien est rendu au jeu, qui l'affiche, au lieu d'aller
/// droit au presse-papiers ; le presse-papiers a ses deux fonctions à lui.
const ABI_VERSION: u32 = 2;

#[no_mangle]
pub extern "C" fn yf_share_abi() -> u32 {
    ABI_VERSION
}

/// Écrit `text` dans `out`. Rend sa longueur, ou `None` s'il ne tient pas.
unsafe fn write(text: &str, out: *mut u8, cap: i32) -> Option<i32> {
    if out.is_null() || cap < 0 || text.len() > cap as usize {
        return None;
    }
    std::ptr::copy_nonoverlapping(text.as_ptr(), out, text.len());
    Some(text.len() as i32)
}

unsafe fn fail(message: &str, out: *mut u8, cap: i32) -> i32 {
    match write(message, out, cap) {
        Some(len) if len > 0 => -len,
        _ => i32::MIN,
    }
}

unsafe fn text<'a>(ptr: *const u8, len: i32) -> Option<&'a str> {
    if ptr.is_null() || len <= 0 {
        return None;
    }
    std::str::from_utf8(std::slice::from_raw_parts(ptr, len as usize)).ok()
}

unsafe fn give(result: Result<String, String>, too_large: &str, out: *mut u8, cap: i32) -> i32 {
    match result {
        Ok(value) => match write(&value, out, cap) {
            Some(len) => len,
            None => fail(too_large, out, cap),
        },
        Err(message) => fail(&message, out, cap),
    }
}

/// Le lien des réglages donnés (`clé=valeur`, une par ligne), écrit dans
/// `out`. Un lien en plusieurs parties est rendu avec une ligne vide entre
/// elles : collé tel quel dans une messagerie, chaque partie reste lisible.
///
/// # Safety
/// `settings` doit pointer sur `len` octets lisibles, `out` sur `cap` octets
/// inscriptibles.
#[no_mangle]
pub unsafe extern "C" fn yf_share_link(settings: *const u8, len: i32, out: *mut u8, cap: i32) -> i32 {
    let Some(settings) = text(settings, len) else {
        return fail("Rien à partager", out, cap);
    };
    give(options::link_of(settings).map(|links| links.join("\n\n")), "Trop volumineux pour un lien", out, cap)
}

/// Les réglages que porte le lien donné (`clé=valeur`, une par ligne), déjà
/// filtrés, écrits dans `out`.
///
/// # Safety
/// `link` doit pointer sur `len` octets lisibles, `out` sur `cap` octets
/// inscriptibles.
#[no_mangle]
pub unsafe extern "C" fn yf_share_settings(link: *const u8, len: i32, out: *mut u8, cap: i32) -> i32 {
    let Some(link) = text(link, len) else {
        return fail("Ce n'est pas un lien de partage YuyuFrame", out, cap);
    };
    give(options::settings_of(link).map(|lines| lines.join("\n")), "Lien de partage trop volumineux", out, cap)
}

/// Pose un texte dans le presse-papiers du système. Rend 0 ; rien n'est écrit
/// dans `out` en cas de succès.
///
/// # Safety
/// `value` doit pointer sur `len` octets lisibles, `out` sur `cap` octets
/// inscriptibles.
#[no_mangle]
pub unsafe extern "C" fn yf_share_clip_set(value: *const u8, len: i32, out: *mut u8, cap: i32) -> i32 {
    let Some(value) = text(value, len) else {
        return fail("Rien à copier", out, cap);
    };
    match clipboard::set(value) {
        Ok(()) => 0,
        Err(message) => fail(&message, out, cap),
    }
}

/// Le texte du presse-papiers du système, écrit dans `out`.
///
/// # Safety
/// `out` doit pointer sur `cap` octets inscriptibles.
#[no_mangle]
pub unsafe extern "C" fn yf_share_clip_get(out: *mut u8, cap: i32) -> i32 {
    give(clipboard::get(), "Le presse-papiers contient trop de texte", out, cap)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn read(out: &[u8], len: i32) -> String {
        String::from_utf8(out[..len.unsigned_abs() as usize].to_vec()).unwrap()
    }

    /// Le chemin du jeu, de bout en bout, sans toucher au presse-papiers de la
    /// machine : des réglages, leur lien, puis les réglages relus du lien.
    #[test]
    fn des_reglages_au_lien_et_retour() {
        let settings = "fps.enabled=true\nmacros.setting.logins=secret\nzoom.setting.zoomKey=C";
        let mut link = vec![0u8; 4096];
        let len = unsafe { yf_share_link(settings.as_ptr(), settings.len() as i32, link.as_mut_ptr(), link.len() as i32) };
        assert!(len > 0);
        assert!(read(&link, len).starts_with("yuyuframe://options/"));

        let mut back = vec![0u8; 4096];
        let got = unsafe { yf_share_settings(link.as_ptr(), len, back.as_mut_ptr(), back.len() as i32) };
        assert_eq!(read(&back, got), "fps.enabled=true\nzoom.setting.zoomKey=C");
    }

    /// Des entrées douteuses rendent un message, jamais un plantage.
    #[test]
    fn entrees_douteuses() {
        let mut out = [0u8; 128];
        let code = unsafe { yf_share_link(std::ptr::null(), 4, out.as_mut_ptr(), out.len() as i32) };
        assert!(code < 0);
        assert_eq!(read(&out, code), "Rien à partager");

        // Que des réglages interdits.
        let secrets = b"macros.setting.logins=secret";
        let code = unsafe { yf_share_link(secrets.as_ptr(), secrets.len() as i32, out.as_mut_ptr(), out.len() as i32) };
        assert_eq!(read(&out, code), "Rien à partager");

        let junk = b"pas un lien";
        let code = unsafe { yf_share_settings(junk.as_ptr(), junk.len() as i32, out.as_mut_ptr(), out.len() as i32) };
        assert_eq!(read(&out, code), "Ce n'est pas un lien de partage YuyuFrame");

        // Pas de place pour le message : le code le dit, sans écrire.
        assert_eq!(unsafe { yf_share_link(std::ptr::null(), 0, std::ptr::null_mut(), 0) }, i32::MIN);
        assert_eq!(unsafe { yf_share_clip_set(std::ptr::null(), 0, std::ptr::null_mut(), 0) }, i32::MIN);
    }
}
