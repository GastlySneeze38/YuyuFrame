//! Famille Combos (épée) : garder la main après son coup, le W-tap, la
//! longueur des combos.

use super::{count, family, logistic, sample_at, weight, Detail, Family, Figures, Item, Norm, Part, Side, View, FULL_CHARGE, ME_CHARGE, REACH, SPACING_LOST};

const W_KEEP: f64 = 0.35;
const W_WTAP: f64 = 0.4;
const W_LENGTH: f64 = 0.25;

// Ce qu'on attend de chaque sorte d'action : relevé le 2026-10-22 sur 23
// duels à l'épée notés partagés, les deux joueurs (`calibration`, test
// `proposition_de_calibrage` avec `YF_MODE=sword`). Peu de duels : à refaire.
/// Après chacun de ses coups, le coup suivant est encore le sien (57 %).
pub const KEEP: Norm = Norm::new("sword.keep", 0.567, 0.507);
/// Un W-tap : réussi (1) si son coup suivant est sprinté et arrive avant
/// celui de l'adversaire, raté (0) si l'adversaire le touche avant. 26 % de
/// réussite en moyenne : c'est le geste difficile du mode.
pub const WTAP: Norm = Norm::new("sword.wtap", 0.264, 0.46);
pub const LENGTH: Norm = Norm::new("sword.length", 0.191, 0.268);

/// La chance qu'un W-tap réussisse, d'après la distance au coup et la
/// vitesse à laquelle l'adversaire revient vers lui. Apprise le 2026-10-22
/// sur 323 W-taps : plus le coup part loin et plus l'adversaire revient
/// vite, plus le coup suivant passe (il revient dans la portée, barre pleine).
const WTAP_BIAS: f64 = -1.921;
const WTAP_COEFS: [f64; 2] = [0.48, 0.59];

/// Pourquoi un W-tap a raté (détail de la famille) : distance perdue (il
/// lui est rentré dedans), barre pas rechargée quand l'adversaire a frappé,
/// ou trop tard (la barre était pleine et il n'a pas frappé).
const FAIL_DISTANCE: f64 = 1.0;
const FAIL_CHARGE: f64 = 2.0;
const FAIL_LATE: f64 = 3.0;

/// La vitesse à laquelle la cible revient vers l'attaquant (blocs par tick).
fn approach(v: &View) -> f64 {
    let (dx, dz) = (v.pos[0] - v.target[0], v.pos[2] - v.target[2]);
    let norm = (dx * dx + dz * dz).sqrt().max(1e-6);
    (v.target_vel[0] * dx + v.target_vel[2] * dz) / norm
}

impl Side<'_> {
    fn keep(&self) -> Part {
        let mut part = Part::new(KEEP);
        for (i, h) in self.hits.iter().enumerate().filter(|(_, h)| self.own(h)) {
            if let Some(n) = self.next_after(i) {
                part.add(Some(h.t), self.own(n) as u8 as f64);
            }
        }
        part
    }

    /// Les W-taps : après chacun de ses coups à l'épée, un coup suivant
    /// sprinté qui passe avant l'autre (réussi), ou l'autre qui touche avant
    /// (raté). Un coup suivant sans sprint n'est pas un W-tap, et rien dans
    /// le délai, c'est dans le vide : ni l'un ni l'autre ne compte. Sans la
    /// trace du sprint (adversaire, journal d'avant la version 4), aucun
    /// W-tap n'est jugé : ses ratés seuls le feraient paraître mauvais.
    pub(super) fn wtaps(&self) -> (Part, Vec<(u64, f64)>) {
        let mut part = Part::new(WTAP);
        let mut failures = Vec::new();
        for (i, h) in self.hits.iter().enumerate().filter(|(_, h)| self.own(h) && Side::melee(h)) {
            let Some(n) = self.next_after(i) else { continue };
            let (value, failed) = if self.own(n) {
                match self.sprint_hit(n) {
                    Some(true) => (1.0, false),
                    Some(false) => continue,
                    None => return (Part::new(WTAP), Vec::new()),
                }
            } else {
                if self.sprint_hit(h).is_none() {
                    return (Part::new(WTAP), Vec::new());
                }
                (0.0, true)
            };
            let at = self.view(h.t);
            let context = [at.map(|v| v.distance).unwrap_or(REACH), at.map(approach).unwrap_or(0.0), 0.0, 0.0, 0.0];
            part.push(Item { t: Some(h.t), value, expected: logistic(WTAP_BIAS, &WTAP_COEFS, &context[..2]), weight: 1.0, context });
            if failed {
                failures.push((n.t, self.failure(h.t, n.t)));
            }
        }
        (part, failures)
    }

    /// Pourquoi l'autre a touché avant : distance perdue, barre pas pleine,
    /// ou trop tard.
    fn failure(&self, from: u64, to: u64) -> f64 {
        let closest = self.views.iter().filter(|v| v.t > from && v.t <= to).map(|v| v.horizontal).fold(f64::MAX, f64::min);
        if closest < SPACING_LOST {
            return FAIL_DISTANCE;
        }
        // Notre barre seulement : celle de l'adversaire ne se voit pas.
        let charge = self.me.then(|| sample_at(&self.duel.samples, to)).flatten().map(|s| s.me[ME_CHARGE]);
        if charge.is_some_and(|c| c <= FULL_CHARGE) {
            FAIL_CHARGE
        } else {
            FAIL_LATE
        }
    }

    /// Ses combos : (tick du premier coup, longueur).
    pub(super) fn combos(&self) -> Vec<(u64, usize)> {
        let mut out = Vec::new();
        let mut run: Option<(u64, usize, u64)> = None;
        for h in self.hits.iter() {
            let continues = run.is_some_and(|(_, _, last)| h.t - last <= super::COMBO_TICKS);
            match (self.own(h), run) {
                (true, Some((start, len, _))) if continues => run = Some((start, len + 1, h.t)),
                (true, previous) => {
                    if let Some((start, len, _)) = previous {
                        out.push((start, len));
                    }
                    run = Some((h.t, 1, h.t));
                }
                (false, Some((start, len, _))) => {
                    out.push((start, len));
                    run = None;
                }
                (false, None) => {}
            }
        }
        if let Some((start, len, _)) = run {
            out.push((start, len));
        }
        out
    }

    fn length(&self) -> Part {
        Part::of(LENGTH, self.combos().into_iter().map(|(t, len)| (Some(t), (len.saturating_sub(1) as f64 / 3.0).min(1.0))))
    }

    /// La famille, et les W-taps ratés (tick, raison) pour le détail.
    pub(super) fn combo_family(&self, own_hits: f64) -> (Family, Vec<(u64, f64)>) {
        let (wtaps, failures) = self.wtaps();
        (family(&[(1.0, vec![(W_KEEP, self.keep()), (W_WTAP, wtaps), (W_LENGTH, self.length())])], own_hits), failures)
    }

    /// Le détail : la main gardée, les W-taps réussis et pourquoi les autres
    /// ont raté, les combos ; son plus long combo en face.
    pub(super) fn combo_detail(&self, foe: &Side, failures: &[(u64, f64)], d: &mut Detail, figures: &mut Figures) {
        let keep = self.keep();
        if let Some(w) = weight(&keep, W_KEEP) {
            let (k, n) = count(&keep);
            d.fact("sword.keep", w >= 0.0, w, &[k, n]);
        }
        let (wtaps, _) = self.wtaps();
        figures.wtaps = wtaps.items.len();
        figures.wtaps_landed = wtaps.items.iter().filter(|i| i.value >= 0.5).count();
        if let Some(w) = weight(&wtaps, W_WTAP) {
            d.fact("sword.wtap", w >= 0.0, w, &[figures.wtaps_landed as f64, figures.wtaps as f64]);
            for (reason, code) in [(FAIL_DISTANCE, "sword.wtap_distance"), (FAIL_CHARGE, "sword.wtap_charge"), (FAIL_LATE, "sword.wtap_late")] {
                let n = failures.iter().filter(|(_, r)| *r == reason).count() as f64;
                if n > 0.0 {
                    d.fact(code, false, n / figures.wtaps.max(1) as f64 * W_WTAP, &[n]);
                }
            }
            for (t, reason) in failures {
                d.moment(*t, "sword.wtap_failed", false, 1.0, &[*reason]);
            }
        }
        let combos = self.combos();
        figures.longest_combo = combos.iter().map(|(_, l)| *l).max().unwrap_or(0);
        figures.combos = combos.iter().filter(|(_, l)| *l >= 2).count();
        if figures.longest_combo >= 2 {
            if let Some(w) = weight(&self.length(), W_LENGTH) {
                d.fact("sword.length", w >= 0.0, w, &[figures.longest_combo as f64, figures.combos as f64]);
            }
            for (t, len) in combos.iter().filter(|(_, l)| *l >= 3) {
                d.moment(*t, "sword.combo", true, *len as f64, &[*len as f64]);
            }
        }
        let their_longest = foe.combos().iter().map(|(_, l)| *l).max().unwrap_or(0);
        d.versus("sword.longest", &[figures.longest_combo as f64], &[their_longest as f64]);
    }
}

#[cfg(test)]
mod tests {
    use super::super::tests::{empty_duel, hit, side};
    use super::super::Hit;

    #[test]
    fn un_combo_se_lit_dans_l_ordre_des_coups() {
        let duel = empty_duel();
        let list = [hit(0, true), hit(10, true), hit(20, true), hit(25, false), hit(100, true)];
        let refs: Vec<&Hit> = list.iter().collect();
        let s = side(&duel, &refs);
        assert_eq!(s.combos(), vec![(0, 3), (100, 1)]);
        // Après mes trois coups : deux fois encore moi, une fois lui ; après le dernier, rien.
        assert_eq!(s.keep().items.iter().map(|i| i.value).collect::<Vec<_>>(), vec![1.0, 1.0, 0.0]);
    }

    #[test]
    fn sans_trace_du_sprint_aucun_w_tap_n_est_juge() {
        // Un journal sans lignes `a` : le sprint au coup n'est pas connu.
        let duel = empty_duel();
        let list = [hit(0, true), hit(10, true), hit(20, false)];
        let refs: Vec<&Hit> = list.iter().collect();
        let (wtaps, failures) = side(&duel, &refs).wtaps();
        assert!(wtaps.items.is_empty() && failures.is_empty());
    }
}
