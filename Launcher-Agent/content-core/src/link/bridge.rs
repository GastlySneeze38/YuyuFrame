//! Les fonctions `yf_link_*` : des fonctions C ordinaires, que Java cherche
//! par leur nom dans la DLL (FFM). Leur contrat est ici ; un changement de
//! signature ou de sens se fait avec `ABI_VERSION`.
//!
//! Aucune n'attend le launcher : une question est déposée, sa réponse relevée
//! plus tard. Le seul verrou pris est celui de la file, que le thread du
//! canal ne garde jamais pendant un échange.

use super::channel::{link, Poll};

/// Version du contrat. À incrémenter dès qu'une fonction change de signature
/// ou de sens : Java refuse une DLL qui n'annonce pas la version qu'il attend
/// (`NativeLink.ABI_VERSION`), au lieu de l'appeler de travers.
const ABI_VERSION: u32 = 1;

/// `yf_link_poll` : la réponse n'est pas encore là.
const PENDING: i32 = -1;
/// Numéro inconnu, ou réponse déjà relevée.
const UNKNOWN: i32 = -2;
/// La réponse ne tient pas dans le tampon donné ; elle est perdue.
const TOO_LARGE: i32 = -3;

#[no_mangle]
pub extern "C" fn yf_link_abi() -> u32 {
    ABI_VERSION
}

/// Ouvre le canal, la première fois. Rend 1 si le jeu a un launcher au bout
/// du fil, 0 sinon (jeu lancé hors launcher).
#[no_mangle]
pub extern "C" fn yf_link_start() -> i32 {
    link().is_some() as i32
}

unsafe fn text<'a>(ptr: *const u8, len: i32) -> Option<&'a str> {
    if ptr.is_null() || len <= 0 {
        return None;
    }
    std::str::from_utf8(std::slice::from_raw_parts(ptr, len as usize)).ok()
}

/// Dépose une question (une ligne, champs séparés par des tabulations — voir
/// `game_link.rs` côté launcher). Rend son numéro, à présenter à
/// `yf_link_poll`, ou 0 si elle ne peut pas partir.
///
/// # Safety
/// `question` doit pointer sur `len` octets lisibles.
#[no_mangle]
pub unsafe extern "C" fn yf_link_send(question: *const u8, len: i32) -> i64 {
    match (link(), text(question, len)) {
        (Some(link), Some(question)) => link.send(question) as i64,
        _ => 0,
    }
}

/// Dépose un message dont la réponse n'intéresse personne (« le jeu est
/// prêt »). Rend 1 s'il a été pris.
///
/// # Safety
/// `question` doit pointer sur `len` octets lisibles.
#[no_mangle]
pub unsafe extern "C" fn yf_link_notify(question: *const u8, len: i32) -> i32 {
    match (link(), text(question, len)) {
        (Some(link), Some(question)) => link.notify(question) as i32,
        _ => 0,
    }
}

/// Relève la réponse à la question `id` : écrit ses octets (UTF-8, sans fin
/// de ligne) dans `out` et rend leur nombre — ou -1 tant qu'elle n'est pas
/// là, -2 pour un numéro inconnu ou déjà relevé, -3 si elle dépasse `cap`.
///
/// # Safety
/// `out` doit pointer sur `cap` octets inscriptibles.
#[no_mangle]
pub unsafe extern "C" fn yf_link_poll(id: i64, out: *mut u8, cap: i32) -> i32 {
    let Some(link) = link() else { return UNKNOWN };
    if out.is_null() || cap <= 0 || id <= 0 {
        return UNKNOWN;
    }
    match link.poll(id as u64) {
        Poll::Pending => PENDING,
        Poll::Unknown => UNKNOWN,
        Poll::Done(reply) if reply.len() > cap as usize => TOO_LARGE,
        Poll::Done(reply) => {
            std::ptr::copy_nonoverlapping(reply.as_ptr(), out, reply.len());
            reply.len() as i32
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Hors launcher (le cas des tests) : rien ne s'ouvre, rien ne part, et
    /// aucun pointeur douteux ne fait planter.
    #[test]
    fn hors_launcher_rien_ne_part() {
        assert_eq!(yf_link_start(), 0);
        let question = b"accounts";
        assert_eq!(unsafe { yf_link_send(question.as_ptr(), question.len() as i32) }, 0);
        assert_eq!(unsafe { yf_link_notify(std::ptr::null(), 4) }, 0);
        let mut out = [0u8; 16];
        assert_eq!(unsafe { yf_link_poll(1, out.as_mut_ptr(), out.len() as i32) }, UNKNOWN);
        assert_eq!(unsafe { yf_link_poll(1, std::ptr::null_mut(), 16) }, UNKNOWN);
    }
}
