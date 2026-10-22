//! L'épée (docs/scoring/epee.md, 2026-10-22) : quatre familles faites
//! d'actions, comme celles de la masse.
//!
//!   combos     garder la main, le W-tap, la longueur des combos
//!   quality    la qualité du coup : barre, critique ou sprint, recul donné
//!   defense    casser ses combos, les échanges, le jump reset
//!   placement  le premier coup de l'échange, la portée, l'espacement, le strafe
//!
//! Pas de ressources ni de bonus : le kit noté n'a qu'un objet, l'épée
//! (`parse::Mode::Sword`, un duel où chacun n'a que son épée).
//!
//! La mécanique, relevée dans `Player.attack` (jar 26.3) : un coup porté en
//! sprint avec la barre au-dessus de 0,9 donne un recul supplémentaire (son
//! `entity.player.attack.knockback`), puis le jeu coupe le sprint de celui
//! qui frappe et le ralentit (×0,6, `causeExtraKnockback`). Pour que le coup
//! suivant donne encore ce recul, il faut relancer le sprint entre les deux :
//! c'est le W-tap. Il ne se juge pas à la touche (maintenir sprint peut le
//! relancer seul) mais à son effet dans le combo : le coup suivant est-il
//! sprinté, et arrive-t-il avant celui de l'adversaire ?

mod combos;
mod defense;
mod placement;
mod quality;

use super::super::parse::{Duel, Hit};
use super::detail::Detail;
use super::exchange::exchanges;
use super::measure::{family, logistic, Family, Item, Measure, Norm, Part};
use super::strike::{attack_sound_heard, attack_tick, charge_factor, crit_heard};
use super::views::{flag, ping_ticks, sample_at, view_at, views, View, GAP_TICKS, ME_CHARGE, ME_FLAGS, SPRINTING, VY};
use super::{engaged_secs, Features, FoeMeasures};

/// Un combo se poursuit si le coup suivant arrive dans ce délai (1,5 s) ;
/// au-delà, ce n'est plus un enchaînement.
const COMBO_TICKS: u64 = 30;
/// Au-dessus, la barre compte comme pleine (`Player.attack`, jar 26.3).
const FULL_CHARGE: f64 = 0.9;
/// Portée d'un coup (`entity_interaction_range`, 3 blocs).
const REACH: f64 = 3.0;
/// Sous cette distance, on lui est rentré dedans : l'espacement est perdu
/// (placement), et c'est la première raison d'un W-tap raté (combos).
const SPACING_LOST: f64 = 0.5;

/// Les chiffres du duel montrés à l'écran (hors note).
#[derive(Debug, Clone, Copy, Default, serde::Serialize)]
pub struct Figures {
    pub wtaps: usize,
    pub wtaps_landed: usize,
    pub longest_combo: usize,
    pub combos: usize,
    pub full_hits: usize,
    pub my_hits: usize,
    pub crits: usize,
    pub sprint_hits: usize,
    pub jump_resets: usize,
}

/// Un joueur vu comme attaquant : ses vues (lui, l'autre en cible), celles
/// de l'autre, et tous les coups du duel dans l'ordre.
struct Side<'a> {
    duel: &'a Duel,
    me: bool,
    views: &'a [View],
    other_views: &'a [View],
    hits: &'a [&'a Hit],
    ping: u64,
}

/// Ce que le jeu sait du coup d'un joueur : barre, sprint, critique ;
/// `None` quand le journal ne le dit pas.
struct Stroke {
    charge: Option<f64>,
    sprint: Option<bool>,
    crit: Option<bool>,
}

impl Side<'_> {
    fn own(&self, h: &Hit) -> bool {
        h.by_me == self.me
    }

    fn melee(h: &Hit) -> bool {
        h.kind == "player_attack"
    }

    /// Le coup suivant, dans le délai d'un combo.
    fn next_after(&self, index: usize) -> Option<&Hit> {
        let t = self.hits[index].t;
        self.hits[index + 1..].iter().find(|n| n.t > t).filter(|n| n.t - t <= COMBO_TICKS).copied()
    }

    /// La barre, le sprint et le critique d'un coup de ce joueur. Pour
    /// nous : la barre et le sprint lus au tick du clic ; pour l'adversaire,
    /// les sons du coup (version 4).
    fn stroke(&self, h: &Hit) -> Stroke {
        let crit = self.duel.sees_crits().then(|| crit_heard(self.duel, h.t, self.me));
        if self.me {
            let sample = attack_tick(self.duel, h.t, self.ping).filter(|_| self.duel.detailed()).and_then(|t| sample_at(&self.duel.samples, t));
            Stroke {
                charge: sample.map(|s| s.me[ME_CHARGE].clamp(0.0, 1.0)),
                sprint: sample.map(|s| flag(s.me[ME_FLAGS], SPRINTING)),
                crit,
            }
        } else {
            let strong = attack_sound_heard(self.duel, "strong", h.t, false);
            let knockback = attack_sound_heard(self.duel, "knockback", h.t, false);
            // Un coup sprinté ou critique demande la barre pleine ; « weak » dit qu'elle ne l'était pas.
            let full = strong.map(|s| s || knockback == Some(true) || crit == Some(true));
            Stroke { charge: full.map(|f| if f { 1.0 } else { 0.5 }), sprint: knockback, crit }
        }
    }

    /// Un coup sprinté, barre pleine : celui qui donne le recul.
    fn sprint_hit(&self, h: &Hit) -> Option<bool> {
        let s = self.stroke(h);
        Some(s.sprint? && s.charge? > FULL_CHARGE)
    }

    /// La vue de ce joueur au tick `t` (lui, l'autre en cible).
    fn view(&self, t: u64) -> Option<&View> {
        view_at(self.views, t, GAP_TICKS)
    }

    /// Ses quatre familles, et les W-taps ratés (tick, raison) pour le détail.
    fn families(&self) -> ([Family; 4], Vec<(u64, f64)>) {
        let own = self.hits.iter().filter(|h| self.own(h)).count() as f64;
        let theirs = self.hits.len() as f64 - own;
        let (combos, failures) = self.combo_family(own);
        // Le placement repose sur les échanges (le strafe n'est qu'une
        // tendance du duel) : c'est leur nombre qui le lisse.
        let openers = self.exchange_openers().len() as f64;
        ([combos, self.quality_family(own), self.defense_family(theirs), self.placement_family(openers)], failures)
    }
}

/// Un duel à l'épée : ses quatre familles, et celles de l'adversaire.
pub(super) fn extract(duel: &Duel) -> Features {
    let my_views = views(&duel.samples, true);
    let their_views = views(&duel.samples, false);
    let ping = ping_ticks(&duel.samples);
    let hits: Vec<&Hit> = duel.hits.iter().collect();
    let me = Side { duel, me: true, views: &my_views, other_views: &their_views, hits: &hits, ping };
    let foe = Side { duel, me: false, views: &their_views, other_views: &my_views, hits: &hits, ping: 0 };
    let ([c0, c1, c2, c3], failures) = me.families();
    let ([f0, f1, f2, f3], _) = foe.families();
    let mut details: [Detail; 5] = Default::default();
    let figures = detail(&me, &foe, &failures, &mut details);
    Features {
        mode: duel.mode,
        evidence: hits.len(),
        sword: Some(figures),
        mine: Vec::new(),
        theirs: Vec::new(),
        engaged_secs: engaged_secs(duel),
        conversion: c0.measure,
        split: Default::default(),
        smash_quality: c1.measure,
        exposure: c2.measure,
        avoided: None,
        vertical: c3.measure,
        resources: Measure::NONE,
        bug_use: Default::default(),
        foe: FoeMeasures { conversion: f0.measure, smash_quality: f1.measure, exposure: f2.measure, vertical: f3.measure },
        smash_charge: None,
        details,
        actions: [c0.actions, c1.actions, c2.actions, c3.actions, Vec::new()],
        foe_actions: [f0.actions, f1.actions, f2.actions, f3.actions],
        smashes: Vec::new(),
        detections: Vec::new(),
    }
}

/// Le détail des quatre familles, et les chiffres du duel.
fn detail(me: &Side, foe: &Side, failures: &[(u64, f64)], d: &mut [Detail; 5]) -> Figures {
    let mut figures = Figures::default();
    me.combo_detail(foe, failures, &mut d[0], &mut figures);
    me.quality_detail(foe, &mut d[1], &mut figures);
    me.defense_detail(foe, &mut d[2], &mut figures);
    me.placement_detail(foe, &mut d[3]);
    figures
}

/// Le poids d'un fait : l'écart de sa partie à l'attendu, fois le poids de la partie.
fn weight(part: &Part, w: f64) -> Option<f64> {
    part.gap().map(|g| g * w)
}

/// Les actions réussies d'une partie (valeur d'au moins 0,5), et toutes.
fn count(part: &Part) -> (f64, f64) {
    (part.items.iter().filter(|i| i.value >= 0.5).count() as f64, part.items.len() as f64)
}

#[cfg(test)]
mod tests {
    use super::*;

    pub(super) fn hit(t: u64, by_me: bool) -> Hit {
        Hit { t, by_me, smash: false, kind: "player_attack".to_string() }
    }

    pub(super) fn side<'a>(duel: &'a Duel, hits: &'a [&'a Hit]) -> Side<'a> {
        Side { duel, me: true, views: &[], other_views: &[], hits, ping: 0 }
    }

    pub(super) fn empty_duel() -> Duel {
        super::super::super::parse::parse(r#"{"k":"h","v":4,"me":1,"t":0}"#).unwrap()
    }
}
