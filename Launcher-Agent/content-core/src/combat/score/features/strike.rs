//! Commun à la masse et à l'épée : ce que le jeu dit d'un coup — la barre au
//! moment du clic, le critique et les autres sons qu'il fait jouer.

use super::super::parse::Duel;

/// Un coup donné et son paquet de dégâts : au plus ce délai, ping compris.
const ATTACK_TO_HIT_TICKS: u64 = 4;
/// Écart toléré entre le son d'un coup et son paquet de dégâts.
const SOUND_TICKS: u64 = 2;

/// Le facteur que le jeu applique aux dégâts de base selon la charge :
/// `0,2 + 0,8 × charge²` (`Player.baseDamageScaleFactor`, jar 26.3).
pub(super) fn charge_factor(charge: f64) -> f64 {
    0.2 + 0.8 * charge * charge
}

/// Le tick du clic qui a donné ce coup confirmé (notre joueur) : la charge
/// telle que le jeu la compte au coup est celle du dernier tick relevé avant.
/// Elle suit les attributs de l'objet tenu jusque-là — c'est le principe de
/// l'attribute swap : la barre repart à zéro au tick SUIVANT un changement
/// d'objet (`Player.tick`, jar 26.3).
pub(super) fn attack_tick(duel: &Duel, hit: u64, ping: u64) -> Option<u64> {
    duel.attacks.iter().rev().find(|a| a.on_foe && a.t <= hit && hit - a.t <= ATTACK_TO_HIT_TICKS + ping).map(|a| a.t)
}

/// Un critique entendu avec ce coup : le son part du serveur au moment du
/// coup, avec le paquet de dégâts (deux ticks de marge). C'est le coup qui
/// dit qui a frappé — pas la position du son (voir `Duel::crits`). Si un
/// coup de l'autre tombe dans les mêmes ticks, le son reste ambigu : il
/// n'est donné à personne.
pub(super) fn crit_heard(duel: &Duel, hit: u64, by_me: bool) -> bool {
    let near = |t: u64| t.abs_diff(hit) <= SOUND_TICKS;
    duel.crits.iter().any(|t| near(*t)) && !duel.hits.iter().any(|h| h.by_me != by_me && near(h.t))
}

/// Un autre son du coup (`knockback`, `strong`, `weak`…) entendu avec ce
/// coup, rattaché de la même façon que le critique ; `None` si le journal
/// ne porte pas ces sons (avant la version 4).
pub(super) fn attack_sound_heard(duel: &Duel, kind: &str, hit: u64, by_me: bool) -> Option<bool> {
    if !duel.sees_attack_sounds() {
        return None;
    }
    let near = |t: u64| t.abs_diff(hit) <= SOUND_TICKS;
    let heard = duel.attack_sounds.iter().any(|(t, k)| k == kind && near(*t));
    Some(heard && !duel.hits.iter().any(|h| h.by_me != by_me && near(h.t)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn la_charge_suit_la_formule_du_jeu() {
        assert!((charge_factor(1.0) - 1.0).abs() < 1e-12);
        assert!((charge_factor(0.0) - 0.2).abs() < 1e-12);
        assert!((charge_factor(0.5) - 0.4).abs() < 1e-12);
    }
}
