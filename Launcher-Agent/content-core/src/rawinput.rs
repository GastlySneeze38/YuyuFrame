//! Souris brute de la 1.8.9 (docs/LauncherAgent/v1.8.9/README.md § 9 bis).
//!
//! Appelé par Java via FFM (`java.lang.foreign`), pas par JNI : les fonctions
//! `yf_mouse_*` sont des fonctions C ordinaires, cherchées par leur nom dans la
//! DLL. Côté Java : `lwjgl2compat/rawinput/NativeMouse.java`.
//!
//! Les déplacements sont cumulés dans deux compteurs qui ne reviennent jamais
//! à zéro. Java garde la valeur lue à l'image précédente et fait la
//! soustraction : aucun mouvement ne peut se perdre entre une lecture et une
//! remise à zéro, puisqu'il n'y a pas de remise à zéro.
//!
//! Ce fichier ne contient pour l'instant que le contrat et les compteurs. La
//! source Windows (thread dédié, fenêtre invisible, `GetRawInputBuffer`) n'est
//! pas encore écrite : `yf_mouse_start` répond « indisponible » et Java garde
//! la souris de GLFW.

use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};

/// Version du contrat. À incrémenter dès qu'une fonction change de signature
/// ou de sens : Java refuse une DLL qui n'annonce pas la version qu'il attend
/// (`NativeMouse.ABI_VERSION`), au lieu de l'appeler de travers.
const ABI_VERSION: u32 = 1;

static TOTAL_X: AtomicI64 = AtomicI64::new(0);
static TOTAL_Y: AtomicI64 = AtomicI64::new(0);

/// Vrai quand le jeu a capturé la souris. Hors capture (menus), les
/// déplacements ne sont pas comptés.
static ACTIVE: AtomicBool = AtomicBool::new(false);

/// Ajoute un déplacement relatif, en comptes bruts de la souris (Y vers le
/// bas, comme Windows le donne). Appelé par le thread de lecture.
// Sans appelant tant que la source Windows n'est pas écrite.
#[cfg_attr(not(test), allow(dead_code))]
pub(crate) fn accumulate(dx: i64, dy: i64) {
    if !ACTIVE.load(Ordering::Relaxed) {
        return;
    }
    TOTAL_X.fetch_add(dx, Ordering::Relaxed);
    TOTAL_Y.fetch_add(dy, Ordering::Relaxed);
}

#[no_mangle]
pub extern "C" fn yf_mouse_abi() -> u32 {
    ABI_VERSION
}

/// Démarre la lecture de la souris. Rend 1 si elle tourne, 0 si elle n'est
/// pas disponible — Java garde alors la souris de GLFW.
#[no_mangle]
pub extern "C" fn yf_mouse_start() -> i32 {
    0
}

/// Arrête la lecture. Sans effet si elle ne tourne pas.
#[no_mangle]
pub extern "C" fn yf_mouse_stop() {
    ACTIVE.store(false, Ordering::Relaxed);
}

/// Java dit si la souris est capturée par le jeu (0 = non).
#[no_mangle]
pub extern "C" fn yf_mouse_set_active(active: i32) {
    ACTIVE.store(active != 0, Ordering::Relaxed);
}

/// Écrit les deux totaux (X puis Y) dans le bloc de 16 octets tendu par Java.
///
/// Ne prend aucun verrou et ne rappelle jamais Java : Java l'appelle avec
/// l'option `critical` de FFM, qui l'interdit.
///
/// # Safety
/// `out` doit pointer sur 16 octets inscriptibles, alignés sur 8.
#[no_mangle]
pub unsafe extern "C" fn yf_mouse_read(out: *mut i64) {
    if out.is_null() {
        return;
    }
    out.write(TOTAL_X.load(Ordering::Relaxed));
    out.add(1).write(TOTAL_Y.load(Ordering::Relaxed));
}

#[cfg(test)]
mod tests {
    use super::*;

    fn read() -> (i64, i64) {
        let mut out = [0i64; 2];
        unsafe { yf_mouse_read(out.as_mut_ptr()) };
        (out[0], out[1])
    }

    // Un seul test : les compteurs sont globaux, deux tests en parallèle se
    // marcheraient dessus.
    #[test]
    fn compteurs_cumules_et_ignores_hors_capture() {
        yf_mouse_set_active(0);
        let (x0, y0) = read();
        accumulate(5, -3);
        assert_eq!(read(), (x0, y0), "hors capture, rien n'est compté");

        yf_mouse_set_active(1);
        accumulate(5, -3);
        accumulate(2, 10);
        assert_eq!(read(), (x0 + 7, y0 + 7));
        assert_eq!(read(), (x0 + 7, y0 + 7), "lire ne remet rien à zéro");

        yf_mouse_stop();
        accumulate(100, 100);
        assert_eq!(read(), (x0 + 7, y0 + 7), "arrêté, rien n'est compté");

        unsafe { yf_mouse_read(std::ptr::null_mut()) };
    }
}
