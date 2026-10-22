//! Famille Défense (épée) : casser ses combos, les échanges, le jump reset.

use super::{count, exchanges, family, weight, Detail, Family, Figures, Hit, Norm, Part, Side, VY};

const W_BREAK: f64 = 0.3;
const W_EXCHANGES: f64 = 0.4;
const W_JUMP_RESET: f64 = 0.3;

/// Un saut au moment du coup reçu (vitesse verticale d'un saut : 0,42).
const JUMP_VY: f64 = 0.3;

// Relevé le 2026-10-22 sur 23 duels à l'épée notés partagés.
/// Après chacun des coups de l'adversaire, le coup suivant est le sien : combo cassé.
pub const BREAK: Norm = Norm::new("sword.break", 0.441, 0.506);
/// Chaque coup reçu au sol : sauté au moment du coup (moins de recul).
pub const JUMP_RESET: Norm = Norm::new("sword.jump_reset", 0.407, 0.507);

impl Side<'_> {
    fn breaks(&self) -> Part {
        let mut part = Part::new(BREAK);
        for (i, h) in self.hits.iter().enumerate().filter(|(_, h)| !self.own(h)) {
            if let Some(n) = self.next_after(i) {
                part.add(Some(h.t), self.own(n) as u8 as f64);
            }
        }
        part
    }

    fn exchanges(&self) -> Part {
        let (own, other): (Vec<&&Hit>, Vec<&&Hit>) = self.hits.iter().partition(|h| self.own(h));
        exchanges(own.iter().map(|h| h.t), other.iter().map(|h| h.t))
    }

    /// Chaque coup reçu au sol : sauté au moment du coup (moins de recul) ?
    /// Reçu en l'air, il n'y avait pas de saut possible : il ne compte pas.
    fn jump_resets(&self) -> Part {
        let mut part = Part::new(JUMP_RESET);
        for h in self.hits.iter().filter(|h| !self.own(h)) {
            // Dans les vues de l'autre, ce joueur est la cible.
            let around = |from: u64, to: u64| self.other_views.iter().filter(move |v| v.t >= from && v.t <= to);
            if !around(h.t.saturating_sub(4), h.t).any(|v| v.target_grounded) {
                continue;
            }
            // Notre vitesse verticale est celle du jeu ; la sienne, lue dans ses positions.
            let jumped = if self.me {
                self.duel.samples.iter().filter(|s| s.t + 2 >= h.t && s.t <= h.t + 1).any(|s| s.me[VY] > JUMP_VY)
            } else {
                around(h.t.saturating_sub(2), h.t + 1).any(|v| v.target_vel[1] > JUMP_VY)
            };
            part.add(Some(h.t), jumped as u8 as f64);
        }
        part
    }

    pub(super) fn defense_family(&self, their_hits: f64) -> Family {
        family(&[(1.0, vec![(W_BREAK, self.breaks()), (W_EXCHANGES, self.exchanges()), (W_JUMP_RESET, self.jump_resets())])], their_hits)
    }

    /// Le détail : ses combos cassés, les coups portés et reçus, les jump
    /// resets ; les siens en face.
    pub(super) fn defense_detail(&self, foe: &Side, d: &mut Detail, figures: &mut Figures) {
        let breaks = self.breaks();
        if let Some(w) = weight(&breaks, W_BREAK) {
            let (k, n) = count(&breaks);
            d.fact("sword.break", w >= 0.0, w, &[k, n]);
        }
        if let Some(w) = weight(&self.exchanges(), W_EXCHANGES) {
            let dealt = self.hits.iter().filter(|h| self.own(h)).count() as f64;
            d.fact("defense.exchange", w >= 0.0, w, &[dealt, self.hits.len() as f64 - dealt]);
        }
        let jumps = self.jump_resets();
        figures.jump_resets = jumps.items.iter().filter(|i| i.value >= 0.5).count();
        if let Some(w) = weight(&jumps, W_JUMP_RESET) {
            d.fact("sword.jump_reset", w >= 0.0, w, &[figures.jump_resets as f64, jumps.items.len() as f64]);
            let (their_k, their_n) = count(&foe.jump_resets());
            d.versus("sword.jump_reset", &[figures.jump_resets as f64, jumps.items.len() as f64], &[their_k, their_n]);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::super::tests::{empty_duel, hit, side};
    use super::super::Hit;

    #[test]
    fn un_coup_qui_suit_le_sien_casse_son_combo() {
        let duel = empty_duel();
        let list = [hit(0, false), hit(10, true), hit(20, false), hit(100, false)];
        let refs: Vec<&Hit> = list.iter().collect();
        let s = side(&duel, &refs);
        // Après son premier coup : moi, cassé ; après le deuxième : rien dans le délai.
        assert_eq!(s.breaks().items.iter().map(|i| i.value).collect::<Vec<_>>(), vec![1.0]);
        assert!(s.exchanges().gap().unwrap() < 0.0, "un coup porté pour trois reçus");
    }
}
