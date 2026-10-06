//! Les fonctions `yf_mouse_*` : des fonctions C ordinaires, que Java cherche
//! par leur nom dans la DLL (FFM). Leur contrat est ici ; un changement de
//! signature ou de sens se fait avec `ABI_VERSION`.

use super::counters;

/// Version du contrat. À incrémenter dès qu'une fonction change de signature
/// ou de sens : Java refuse une DLL qui n'annonce pas la version qu'il attend
/// (`NativeMouse.ABI_VERSION`), au lieu de l'appeler de travers.
const ABI_VERSION: u32 = 1;

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
    counters::set_active(false);
}

/// Java dit si la souris est capturée par le jeu (0 = non).
#[no_mangle]
pub extern "C" fn yf_mouse_set_active(active: i32) {
    counters::set_active(active != 0);
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
    let (x, y) = counters::totals();
    out.write(x);
    out.add(1).write(y);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lecture_dans_le_bloc_de_java() {
        // Pas de comparaison aux totaux : le test des compteurs tourne en
        // parallèle et les fait bouger. On vérifie que les deux cases sont écrites.
        let mut out = [i64::MIN; 2];
        unsafe { yf_mouse_read(out.as_mut_ptr()) };
        assert!(out[0] != i64::MIN && out[1] != i64::MIN);

        unsafe { yf_mouse_read(std::ptr::null_mut()) };
    }
}
