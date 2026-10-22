//! Famille Placement (épée) : le premier coup de chaque échange (75 %) et
//! le strafe (25 %).
//!
//! La portée au coup et l'espacement entre deux coups ne sont plus notés
//! (2026-10-22) : la distance entre deux joueurs est la même pour les deux,
//! donc elles notaient le duel et non le joueur — sur 13 duels, 0,71 de
//! portée des deux côtés, et tout le monde au-dessus de l'attendu appris sur
//! d'autres serveurs (les positions de l'adversaire arrivent en retard selon
//! le ping). Elles restent affichées, face à face, dans le détail. Ce qui
//! distingue celui qui tient la distance, c'est de frapper le premier au
//! bord de la portée : le premier coup de l'échange.

use super::{count, family, weight, Detail, Family, Norm, Part, Side, SPACING_LOST};

const W_FIRST_HIT: f64 = 0.75;
const W_STRAFE: f64 = 0.25;

/// Un échange s'arrête après 1 s sans coup : le suivant en ouvre un autre.
/// (2 s jusqu'au 2026-10-22 : à peine 2 échanges par duel, trop peu pour
/// porter la famille ; un combo s'arrête à 1,5 s.)
const EXCHANGE_GAP_TICKS: u64 = 20;
/// Au-dessus, l'espacement est gardé.
const SPACING_KEPT: f64 = 1.5;
/// Le strafe se mesure quand il est à moins de 5 blocs et qu'on bouge.
const STRAFE_RANGE: f64 = 5.0;
const STRAFE_MIN_SPEED: f64 = 0.1;

/// Le premier coup de chaque échange (0,5 par symétrie).
pub const FIRST_HIT: Norm = Norm::new("sword.first_hit", 0.5, 0.5);
/// Relevé le 2026-10-22 sur 23 duels à l'épée partagés ; écart sous le
/// plancher de 0,15.
pub const STRAFE: Norm = Norm::new("sword.strafe", 0.416, 0.15);

impl Side<'_> {
    /// Les échanges : des coups séparés par moins de 1 s. Rend le tick et
    /// l'auteur (ce joueur ?) de leur premier coup.
    pub(super) fn exchange_openers(&self) -> Vec<(u64, bool)> {
        let mut out = Vec::new();
        let mut last: Option<u64> = None;
        for h in self.hits.iter() {
            if last.is_none_or(|l| h.t - l > EXCHANGE_GAP_TICKS) {
                out.push((h.t, self.own(h)));
            }
            last = Some(h.t);
        }
        out
    }

    fn first_hits(&self) -> Part {
        Part::of(FIRST_HIT, self.exchange_openers().into_iter().map(|(t, own)| (Some(t), own as u8 as f64)))
    }

    /// La distance moyenne de ses coups à l'épée (affichée, pas notée).
    fn mean_range(&self) -> Option<f64> {
        let ranges: Vec<f64> = self.hits.iter().filter(|h| self.own(h) && Side::melee(h)).filter_map(|h| self.view(h.t)).map(|v| v.distance).collect();
        (!ranges.is_empty()).then(|| ranges.iter().sum::<f64>() / ranges.len() as f64)
    }

    /// Combien de fois, entre un de ses coups et le coup suivant, il est
    /// rentré dans l'autre (sous la moitié du chemin entre 0,5 et 1,5 bloc).
    /// Affiché, pas noté.
    fn spacing_lost(&self) -> usize {
        let mut lost = 0;
        for (i, h) in self.hits.iter().enumerate().filter(|(_, h)| self.own(h)) {
            let Some(n) = self.next_after(i) else { continue };
            let closest = self.views.iter().filter(|v| v.t > h.t && v.t < n.t).map(|v| v.horizontal).fold(f64::MAX, f64::min);
            if closest < (SPACING_LOST + SPACING_KEPT) / 2.0 {
                lost += 1;
            }
        }
        lost
    }

    /// La part de son déplacement de côté plutôt que droit sur l'autre,
    /// quand il est proche : une tendance sur tout le duel.
    fn strafe(&self) -> Part {
        let ratios: Vec<f64> = self
            .views
            .iter()
            .filter(|v| v.horizontal <= STRAFE_RANGE)
            .filter_map(|v| {
                let speed = (v.vel[0] * v.vel[0] + v.vel[2] * v.vel[2]).sqrt();
                let (dx, dz) = (v.target[0] - v.pos[0], v.target[2] - v.pos[2]);
                let norm = (dx * dx + dz * dz).sqrt();
                (speed > STRAFE_MIN_SPEED && norm > 1e-6).then(|| ((v.vel[0] * dz - v.vel[2] * dx) / norm).abs() / speed)
            })
            .collect();
        Part::trend(STRAFE, (!ratios.is_empty()).then(|| ratios.iter().sum::<f64>() / ratios.len() as f64))
    }

    pub(super) fn placement_family(&self, situations: f64) -> Family {
        let parts = vec![(W_FIRST_HIT, self.first_hits()), (W_STRAFE, self.strafe())];
        family(&[(1.0, parts)], situations)
    }

    /// Le détail : les échanges ouverts et le strafe (notés), puis la
    /// distance au coup et l'espacement perdu, face à face (hors note).
    pub(super) fn placement_detail(&self, foe: &Side, d: &mut Detail) {
        let first = self.first_hits();
        if let Some(w) = weight(&first, W_FIRST_HIT) {
            let (k, n) = count(&first);
            d.fact("sword.first_hit", w >= 0.0, w, &[k, n]);
        }
        if let Some(w) = weight(&self.strafe(), W_STRAFE) {
            d.fact("sword.strafe", w >= 0.0, w, &[]);
        }
        let tenth = |v: f64| (v * 10.0).round() / 10.0;
        if let (Some(mine), Some(theirs)) = (self.mean_range(), foe.mean_range()) {
            d.versus("sword.range", &[tenth(mine)], &[tenth(theirs)]);
        }
        d.versus("sword.spacing", &[self.spacing_lost() as f64], &[foe.spacing_lost() as f64]);
    }
}

#[cfg(test)]
mod tests {
    use super::super::tests::{empty_duel, hit, side};
    use super::super::Hit;

    #[test]
    fn un_echange_s_ouvre_apres_une_seconde_de_calme() {
        let duel = empty_duel();
        let list = [hit(0, true), hit(10, false), hit(25, false), hit(100, false), hit(105, true)];
        let refs: Vec<&Hit> = list.iter().collect();
        assert_eq!(side(&duel, &refs).exchange_openers(), vec![(0, true), (100, false)]);
    }
}
