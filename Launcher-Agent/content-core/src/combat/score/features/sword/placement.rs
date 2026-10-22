//! Famille Placement (épée) : le premier coup de chaque échange, la portée
//! au coup, l'espacement entre deux coups, le strafe.

use super::{count, family, weight, Detail, Family, Norm, Part, Side, REACH, SPACING_LOST};

const W_FIRST_HIT: f64 = 0.35;
const W_RANGE: f64 = 0.25;
const W_SPACING: f64 = 0.2;
const W_STRAFE: f64 = 0.2;

/// Un échange s'arrête après 2 s sans coup : le suivant en ouvre un autre.
const EXCHANGE_GAP_TICKS: u64 = 40;
/// Distance sous laquelle un coup se donne collé à l'autre.
const CLOSE_RANGE: f64 = 1.5;
/// Au-dessus, l'espacement est gardé.
const SPACING_KEPT: f64 = 1.5;
/// Le strafe se mesure quand il est à moins de 5 blocs et qu'on bouge.
const STRAFE_RANGE: f64 = 5.0;
const STRAFE_MIN_SPEED: f64 = 0.1;

// Relevé le 2026-10-22 sur 23 duels à l'épée notés partagés.
/// Le premier coup de chaque échange (0,5 par symétrie).
pub const FIRST_HIT: Norm = Norm::new("sword.first_hit", 0.5, 0.5);
pub const RANGE: Norm = Norm::new("sword.range", 0.461, 0.364);
pub const SPACING: Norm = Norm::new("sword.spacing", 0.793, 0.342);
/// Écart relevé sous le plancher de 0,15.
pub const STRAFE: Norm = Norm::new("sword.strafe", 0.416, 0.15);

impl Side<'_> {
    /// Les échanges : des coups séparés par moins de 2 s. Rend le tick et
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

    /// Chaque coup à l'épée : donné près de la portée maximale, là où l'autre
    /// ne touche pas encore, plutôt que collé à lui.
    fn range(&self) -> Part {
        let scores = self.hits.iter().filter(|h| self.own(h) && Side::melee(h)).filter_map(|h| {
            let v = self.view(h.t)?;
            Some((Some(h.t), ((v.distance - CLOSE_RANGE) / (REACH - CLOSE_RANGE)).clamp(0.0, 1.0)))
        });
        Part::of(RANGE, scores)
    }

    /// Après chacun de ses coups, jusqu'au coup suivant : ne pas lui rentrer dedans.
    fn spacing(&self) -> Part {
        let mut part = Part::new(SPACING);
        for (i, h) in self.hits.iter().enumerate().filter(|(_, h)| self.own(h)) {
            let Some(n) = self.next_after(i) else { continue };
            let closest = self.views.iter().filter(|v| v.t > h.t && v.t < n.t).map(|v| v.horizontal).fold(f64::MAX, f64::min);
            if closest < f64::MAX {
                part.add(Some(h.t), ((closest - SPACING_LOST) / (SPACING_KEPT - SPACING_LOST)).clamp(0.0, 1.0));
            }
        }
        part
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
        let parts = vec![(W_FIRST_HIT, self.first_hits()), (W_RANGE, self.range()), (W_SPACING, self.spacing()), (W_STRAFE, self.strafe())];
        family(&[(1.0, parts)], situations)
    }

    /// Le détail : les échanges ouverts, la distance moyenne au coup,
    /// l'espacement perdu, le strafe.
    pub(super) fn placement_detail(&self, d: &mut Detail) {
        let first = self.first_hits();
        if let Some(w) = weight(&first, W_FIRST_HIT) {
            let (k, n) = count(&first);
            d.fact("sword.first_hit", w >= 0.0, w, &[k, n]);
        }
        if let Some(w) = weight(&self.range(), W_RANGE) {
            let ranges: Vec<f64> = self.hits.iter().filter(|h| self.own(h) && Side::melee(h)).filter_map(|h| self.view(h.t)).map(|v| v.distance).collect();
            let mean = ranges.iter().sum::<f64>() / ranges.len().max(1) as f64;
            d.fact("sword.range", w >= 0.0, w, &[(mean * 10.0).round() / 10.0]);
        }
        let spacing = self.spacing();
        if let Some(w) = weight(&spacing, W_SPACING) {
            let lost = spacing.items.iter().filter(|i| i.value < 0.5).count() as f64;
            d.fact("sword.spacing", w >= 0.0, w, &[lost]);
        }
        if let Some(w) = weight(&self.strafe(), W_STRAFE) {
            d.fact("sword.strafe", w >= 0.0, w, &[]);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::super::tests::{empty_duel, hit, side};
    use super::super::Hit;

    #[test]
    fn un_echange_s_ouvre_apres_deux_secondes_de_calme() {
        let duel = empty_duel();
        let list = [hit(0, true), hit(10, false), hit(100, false), hit(105, true)];
        let refs: Vec<&Hit> = list.iter().collect();
        assert_eq!(side(&duel, &refs).exchange_openers(), vec![(0, true), (100, false)]);
    }
}
