//! Commun à la masse et à l'épée : les échanges (2026-10-22) — chaque coup du
//! duel, toutes armes confondues, porté ou reçu. Ajoutés après l'étude des
//! duels partagés : qui gagne les échanges garde l'écart entre deux joueurs
//! sur un duel long, là où les taux par occasion se resserrent.

use super::measure::{Norm, Part};

/// Chaque coup du duel : porté (1) ou reçu (0) — 0,5 par symétrie.
pub const EXCHANGE: Norm = Norm::new("defense.exchange", 0.5, 0.5);

/// Les échanges d'un joueur : ses coups portés (`own`) et reçus (`other`), par leur tick.
pub(super) fn exchanges(own: impl IntoIterator<Item = u64>, other: impl IntoIterator<Item = u64>) -> Part {
    let dealt = own.into_iter().map(|t| (Some(t), 1.0));
    Part::of(EXCHANGE, dealt.chain(other.into_iter().map(|t| (Some(t), 0.0))))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn porter_autant_que_recevoir_vaut_l_attendu() {
        assert_eq!(exchanges([1, 3], [2, 4]).gap(), Some(0.0));
        assert!(exchanges([1, 2, 3], [4]).gap().unwrap() > 0.0);
    }
}
