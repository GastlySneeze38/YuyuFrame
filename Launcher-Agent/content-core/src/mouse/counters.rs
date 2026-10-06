//! Les totaux cumulés de la souris, écrits par le thread de lecture et lus
//! par le thread du jeu. Atomiques : ni verrou, ni attente d'un côté ou de
//! l'autre.

use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};

static TOTAL_X: AtomicI64 = AtomicI64::new(0);
static TOTAL_Y: AtomicI64 = AtomicI64::new(0);

/// Vrai quand le jeu a capturé la souris. Hors capture (menus), les
/// déplacements ne sont pas comptés.
static ACTIVE: AtomicBool = AtomicBool::new(false);

pub(super) fn set_active(active: bool) {
    ACTIVE.store(active, Ordering::Relaxed);
}

/// Ajoute un déplacement relatif, en comptes bruts de la souris (Y vers le
/// bas, comme Windows le donne). Appelé par le thread de lecture.
// Sans appelant tant que la source Windows n'est pas écrite.
#[cfg_attr(not(test), allow(dead_code))]
pub(super) fn accumulate(dx: i64, dy: i64) {
    if !ACTIVE.load(Ordering::Relaxed) {
        return;
    }
    TOTAL_X.fetch_add(dx, Ordering::Relaxed);
    TOTAL_Y.fetch_add(dy, Ordering::Relaxed);
}

/// Les deux totaux, X puis Y. Lire ne remet rien à zéro.
pub(super) fn totals() -> (i64, i64) {
    (TOTAL_X.load(Ordering::Relaxed), TOTAL_Y.load(Ordering::Relaxed))
}

#[cfg(test)]
mod tests {
    use super::*;

    // Un seul test : les compteurs sont globaux, deux tests en parallèle se
    // marcheraient dessus.
    #[test]
    fn compteurs_cumules_et_ignores_hors_capture() {
        set_active(false);
        let (x0, y0) = totals();
        accumulate(5, -3);
        assert_eq!(totals(), (x0, y0), "hors capture, rien n'est compté");

        set_active(true);
        accumulate(5, -3);
        accumulate(2, 10);
        assert_eq!(totals(), (x0 + 7, y0 + 7));
        assert_eq!(totals(), (x0 + 7, y0 + 7), "lire ne remet rien à zéro");

        set_active(false);
        accumulate(100, 100);
        assert_eq!(totals(), (x0 + 7, y0 + 7), "hors capture à nouveau, rien n'est compté");
    }
}
