//! Famille Qualité du coup (épée) : chaque coup complet (barre pleine, et
//! critique ou sprinté), et le recul réellement donné.

use super::{charge_factor, family, view_at, weight, Detail, Family, Figures, Hit, Norm, Part, Side, FULL_CHARGE, GAP_TICKS};

const W_HIT: f64 = 0.6;
const W_KNOCKBACK: f64 = 0.4;

/// Le recul se lit sur les ticks qui suivent le coup ; un coup sprinté
/// pousse à environ 0,65 bloc par tick (0,4 de base + 0,25 du sprint).
const KB_TICKS: u64 = 4;
const KB_REFERENCE: f64 = 0.65;

// Relevé le 2026-10-22 sur 23 duels à l'épée notés partagés.
/// Un coup complet (1) : barre pleine et critique ou sprinté ; barre pleine
/// sans l'un ni l'autre : 0,6 ; barre pas pleine : sa part des dégâts.
pub const HIT: Norm = Norm::new("sword.hit", 0.696, 0.26);
/// Le recul lu dans ses positions (elles ont du retard) : 0,23 d'un coup sprinté en moyenne.
pub const KNOCKBACK: Norm = Norm::new("sword.knockback", 0.233, 0.203);

impl Side<'_> {
    /// Chaque coup à l'épée : complet (barre pleine et critique ou sprinté),
    /// plat (barre pleine sans l'un ni l'autre), ou mal chargé.
    fn hit_quality(&self) -> Part {
        let mut part = Part::new(HIT);
        for h in self.hits.iter().filter(|h| self.own(h) && Side::melee(h)) {
            let s = self.stroke(h);
            let Some(charge) = s.charge else { continue };
            let value = if charge <= FULL_CHARGE {
                charge_factor(charge)
            } else if s.crit == Some(true) || s.sprint == Some(true) {
                1.0
            } else {
                0.6
            };
            part.add(Some(h.t), value);
        }
        part
    }

    /// Le recul réellement donné : la vitesse à laquelle la cible s'éloigne
    /// dans les ticks qui suivent le coup, face à celle d'un coup sprinté.
    fn knockback(&self) -> Part {
        let mut part = Part::new(KNOCKBACK);
        for h in self.hits.iter().filter(|h| self.own(h) && Side::melee(h)) {
            let (Some(before), Some(after)) = (self.view(h.t), view_at(self.views, h.t + KB_TICKS, GAP_TICKS)) else { continue };
            if after.t <= before.t {
                continue;
            }
            let (dx, dz) = (before.target[0] - before.pos[0], before.target[2] - before.pos[2]);
            let norm = (dx * dx + dz * dz).sqrt().max(1e-6);
            let moved = ((after.target[0] - before.target[0]) * dx + (after.target[2] - before.target[2]) * dz) / norm;
            part.add(Some(h.t), (moved / (after.t - before.t) as f64 / KB_REFERENCE).clamp(0.0, 1.0));
        }
        part
    }

    pub(super) fn quality_family(&self, own_hits: f64) -> Family {
        family(&[(1.0, vec![(W_HIT, self.hit_quality()), (W_KNOCKBACK, self.knockback())])], own_hits)
    }

    /// Le détail : coups à barre pleine, coups sprintés et critiques, le
    /// recul ; ses critiques en face.
    pub(super) fn quality_detail(&self, foe: &Side, d: &mut Detail, figures: &mut Figures) {
        let melee: Vec<&&Hit> = self.hits.iter().filter(|h| self.own(h) && Side::melee(h)).collect();
        figures.my_hits = melee.len();
        for h in &melee {
            let s = self.stroke(h);
            let full = s.charge.is_some_and(|c| c > FULL_CHARGE);
            figures.full_hits += full as usize;
            figures.crits += (s.crit == Some(true)) as usize;
            figures.sprint_hits += (full && s.sprint == Some(true)) as usize;
        }
        if let Some(w) = weight(&self.hit_quality(), W_HIT) {
            d.fact("sword.full", w >= 0.0, w, &[figures.full_hits as f64, figures.my_hits as f64]);
            d.fact("sword.kinds", true, 0.0, &[figures.sprint_hits as f64, figures.crits as f64]);
        }
        if let Some(w) = weight(&self.knockback(), W_KNOCKBACK) {
            d.fact("sword.knockback", w >= 0.0, w, &[]);
        }
        let their_crits = foe.hits.iter().filter(|h| foe.own(h) && Side::melee(h)).filter(|h| foe.stroke(h).crit == Some(true)).count();
        d.versus("sword.crits", &[figures.crits as f64], &[their_crits as f64]);
    }
}
