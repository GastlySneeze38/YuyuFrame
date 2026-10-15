//! Famille Défense (docs/scoring/familles/defense.md) : le joueur empêche-t-il
//! l'adversaire de lui mettre ses gros coups ? Avec le bouclier au centre,
//! sans récompenser le joueur planté au sol derrière lui.
//!
//! Un coup arrêté au bouclier n'envoie aucun paquet de dégâts
//! (`LivingEntity.hurtServer` → `BlocksAttacks.onBlocked`, jar 26.3) : sa
//! seule trace est le son du blocage, capturé depuis le journal de version 2.
//!
//! Six mesures : le bouclier, les ouvertures laissées (miroir exact de la
//! conversion), le temps vulnérable, l'esquive horizontale, atterrir
//! proprement, la reprise après un coup. Les dégâts évités résument les
//! trois premières : affichés, hors note.

use super::super::parse::{Duel, SLOT_HAND, SLOT_OFFHAND};
use super::{blend, centered, conversion, descents, family, mean, sample_at, smash_bonus, Measure, Opportunity, Part, Raw, View, HEIGHT_ADVANTAGE, HIT_DELAY_TICKS, ME_HEALTH, MIN_FALL, REACH};

// Poids des mesures, posés au jugé (décision du 2026-10-14), *à calibrer*.
const W_SHIELD: f64 = 0.30;
const W_OPENINGS: f64 = 0.25;
const W_VULNERABLE: f64 = 0.15;
const W_DODGE: f64 = 0.15;
const W_LANDING: f64 = 0.10;
const W_RECOVERY: f64 = 0.05;

// Dans le bouclier : les smashs arrêtés en font l'essentiel, le temps au sol
// bouclier levé pèse peu.
const W_BLOCKED: f64 = 0.75;
const W_GROUND_SHIELD: f64 = 0.10;
const W_EFFICIENCY: f64 = 0.15;

// Ce que valent ces mesures en moyenne, nous et nos adversaires réunis
// (calibration provisoire du 2026-10-15, voir `centered`) : bouclier sur 6
// relevés (journaux de version 2), le reste sur 52.
const SHIELD_CENTER: f64 = 0.20;
const VULNERABLE_CENTER: f64 = 0.52;
const DODGE_CENTER: f64 = 0.26;
const RECOVERY_CENTER: f64 = 0.88;

/// « En l'air » : 4 blocs et plus au-dessus du sol. Un blocage en l'air vaut
/// ×1, au sol ×0,1, sans rampe entre les deux (décision du 2026-10-14).
const AIR_HEIGHT: f64 = 4.0;
const GROUND_BLOCK_WEIGHT: f64 = 0.1;
/// La garde cassée par une hache (bouclier inutilisable 5 s) coûte de
/// l'ordre d'un smash de 8 blocs arrêté en l'air, en négatif — pénalité
/// modérée, remontée le 2026-10-14, *à calibrer*.
const BREAK_FALL: f64 = 8.0;
/// Smashs arrêtés par seconde de bouclier levé qui valent le maximum : le
/// lever au bon moment, compte tenu des 5 ticks d'armement, est un
/// savoir-faire ; le garder levé en permanence non (*à calibrer*).
const BLOCKS_PER_SECOND: f64 = 0.5;
/// Le son du blocage suit le coup de quelques ticks (ping).
const BLOCK_LOOKBACK_TICKS: u64 = 8;
/// Une descente qui commence plus loin ne menaçait pas.
const DODGE_THREAT: f64 = 6.0;
/// Esquiver, c'est s'être déplacé d'au moins un bloc pendant sa chute.
const DODGE_MOVE: f64 = 1.0;
/// Après un coup reçu, au-delà de 3 s pour se dégager, la reprise ne vaut rien.
const RECOVERY_TICKS: f64 = 60.0;
/// Des dégâts de chute dans les 3 s qui suivent une opportunité ratée en viennent.
const LANDING_TICKS: u64 = 60;
/// 10 points de vie (5 cœurs) de chute après un smash raté : le pire.
const LANDING_WORST: f64 = 10.0;

/// Ce qui défend : vu depuis les vues de l'ATTAQUANT (la cible y est le défenseur).
struct Defender<'a> {
    views: &'a [View],
    /// Les opportunités de l'attaquant.
    attacks: &'a [Opportunity],
    density: f64,
    /// Ticks des coups arrêtés et nombre de gardes cassées.
    blocks: Vec<u64>,
    breaks: usize,
    /// Le défenseur a-t-il eu un bouclier dans le duel ?
    has_shield: bool,
    /// Le journal enregistre-t-il les coups arrêtés (version 2) ? Sans eux,
    /// chaque smash arrêté passerait pour un smash évité de rien, et chaque
    /// bouclier pour inutile : la mesure du bouclier ne compte pas.
    sees_blocks: bool,
    /// Ticks des coups reçus.
    hits_taken: Vec<u64>,
}

impl Defender<'_> {
    /// Le bonus du smash arrêté à `t`, et le poids selon la hauteur du défenseur.
    fn blocked_smash(&self, t: u64) -> Option<(f64, f64)> {
        let recent: Vec<&View> = self.views.iter().filter(|v| v.t <= t && t - v.t <= BLOCK_LOOKBACK_TICKS && v.above > 0.0).collect();
        let fall = recent.iter().map(|v| v.fall).fold(0.0, f64::max);
        // Seuls les smashs arrêtés comptent, pas les coups ordinaires.
        if fall < MIN_FALL {
            return None;
        }
        // Hauteur au-dessus du sol par lecture du terrain ; −1 : pas de sol à portée de lecture, donc en l'air.
        let height = recent.last().map(|v| v.target_ground_height).unwrap_or(0.0);
        let weight = if height < 0.0 || height >= AIR_HEIGHT { 1.0 } else { GROUND_BLOCK_WEIGHT };
        Some((smash_bonus(fall, self.density), weight))
    }

    fn shield(&self) -> Part {
        if !self.sees_blocks || (!self.has_shield && self.blocks.is_empty() && self.breaks == 0) {
            return None;
        }
        let blocked: Vec<(f64, f64)> = self.blocks.iter().filter_map(|t| self.blocked_smash(*t)).collect();
        let gain: f64 = blocked.iter().map(|(bonus, weight)| bonus * weight).sum();
        let landed: Vec<&Opportunity> = self.attacks.iter().filter(|o| o.converted).collect();
        let taken: f64 = landed.iter().map(|o| o.realized).sum();
        let broken = self.breaks as f64 * smash_bonus(BREAK_FALL, self.density);
        let total = gain + taken + broken;
        let stopped = (total > 0.0).then(|| (gain / total, (blocked.len() + landed.len() + self.breaks) as f64));
        // Planté au sol bouclier levé pendant qu'il est au-dessus.
        let above: Vec<&View> = self.views.iter().filter(|v| v.above > HEIGHT_ADVANTAGE).collect();
        let planted = above.iter().filter(|v| v.target_grounded && v.shielded).count() as f64;
        let ground = (!above.is_empty()).then(|| (1.0 - planted / above.len() as f64, above.len() as f64 / 80.0));
        // Efficacité : smashs arrêtés par seconde de bouclier levé.
        let raised = self.views.iter().filter(|v| v.shielded).count() as f64 / 20.0;
        let efficiency = (raised >= 1.0).then(|| ((blocked.len() as f64 / raised / BLOCKS_PER_SECOND).min(1.0), raised));
        blend(&[(W_BLOCKED, stopped), (W_GROUND_SHIELD, ground), (W_EFFICIENCY, efficiency)])
    }

    fn openings(&self) -> Part {
        let m = conversion::measure(self.attacks, false);
        (m.count > 0.0).then_some((m.value, m.count))
    }

    /// Au sol pendant qu'il est au-dessus et à portée : la position où on se fait smasher.
    fn vulnerable(&self) -> Part {
        let threat: Vec<&View> = self.views.iter().filter(|v| v.above > HEIGHT_ADVANTAGE && v.horizontal <= REACH).collect();
        let grounded = threat.iter().filter(|v| v.target_grounded).count() as f64;
        (!threat.is_empty()).then(|| (1.0 - grounded / threat.len() as f64, threat.len() as f64 / 80.0))
    }

    /// Pendant ses descentes, sortir de sa zone à temps.
    fn dodge(&self) -> Part {
        let smashes: Vec<u64> = self.attacks.iter().filter_map(|o| o.hit).collect();
        let scores = descents(self.views).into_iter().filter_map(|d| {
            let (s, e) = (&self.views[d.start], &self.views[d.end]);
            if s.above <= 0.0 || s.horizontal > DODGE_THREAT {
                return None;
            }
            // Un smash arrêté au bouclier compte dans le bouclier, pas ici.
            if self.blocks.iter().any(|t| *t >= s.t && *t <= e.t + HIT_DELAY_TICKS) {
                return None;
            }
            let smashed = smashes.iter().any(|t| *t >= s.t && *t <= e.t + HIT_DELAY_TICKS);
            let moved = ((e.target[0] - s.target[0]).powi(2) + (e.target[2] - s.target[2]).powi(2)).sqrt();
            Some((!smashed && e.horizontal > REACH && moved >= DODGE_MOVE) as u8 as f64)
        });
        mean(scores)
    }

    /// Après un coup reçu, reprendre la hauteur ou se remettre hors de portée.
    fn recovery(&self) -> Part {
        mean(self.hits_taken.iter().map(|t| {
            let out = self.views.iter().find(|v| v.t >= *t && (v.above < 0.0 || v.horizontal > REACH + 1.0));
            out.map(|v| 1.0 - ((v.t - t) as f64 / RECOVERY_TICKS).min(1.0)).unwrap_or(0.0)
        }))
    }

    fn family(&self, landing: Part, raw: &mut Raw) -> Measure {
        // Les ouvertures laissées sont déjà centrées par construction (0,5 :
        // comme un joueur moyen), atterrir proprement ne mesure qu'une erreur
        // (1 : aucune) : ni l'une ni l'autre n'est recentrée.
        let combined = blend(&[
            (W_SHIELD, centered(raw.put("defense.shield", self.shield()), SHIELD_CENTER)),
            (W_OPENINGS, raw.put("defense.openings", self.openings())),
            (W_VULNERABLE, centered(raw.put("defense.vulnerable", self.vulnerable()), VULNERABLE_CENTER)),
            (W_DODGE, centered(raw.put("defense.dodge", self.dodge()), DODGE_CENTER)),
            (W_LANDING, raw.put("defense.landing", landing)),
            (W_RECOVERY, centered(raw.put("defense.recovery", self.recovery()), RECOVERY_CENTER)),
        ]);
        // Les situations défensives : ses opportunités, ses smashs arrêtés, les gardes cassées.
        family(combined, (self.attacks.len() + self.blocks.len() + self.breaks) as f64)
    }

    /// Les dégâts évités : la part de ce qu'il pouvait mettre qu'il n'a pas
    /// mis — opportunités ratées et smashs arrêtés.
    fn avoided(&self) -> Option<f64> {
        let missed: f64 = self.attacks.iter().filter(|o| !o.converted).map(|o| o.expected).sum();
        let blocked: f64 = self.blocks.iter().filter_map(|t| self.blocked_smash(*t)).map(|(bonus, _)| bonus).sum();
        let landed: f64 = self.attacks.iter().filter(|o| o.converted).map(|o| o.realized).sum();
        let total = missed + blocked + landed;
        (total > 0.0).then(|| (missed + blocked) / total)
    }
}

/// Atterrir proprement : les dégâts de chute subis après une de ses propres
/// opportunités ratées (un smash raté ne remet pas la chute à zéro). Le
/// montant se lit dans la vie juste avant et juste après le paquet.
fn landing(duel: &Duel, mine: &[Opportunity]) -> Part {
    let scores = mine.iter().filter(|o| !o.converted).map(|o| {
        let damage: f64 = duel
            .my_falls
            .iter()
            .filter(|t| **t > o.close && **t <= o.close + LANDING_TICKS)
            .map(|t| {
                let before = sample_at(&duel.samples, t - 1).map(|s| s.me[ME_HEALTH]);
                let after = duel.samples.iter().find(|s| s.t > *t).map(|s| s.me[ME_HEALTH]);
                match (before, after) {
                    (Some(b), Some(a)) => (b - a).max(0.0),
                    _ => 0.0,
                }
            })
            .sum();
        1.0 - (damage / LANDING_WORST).min(1.0)
    });
    mean(scores)
}

fn holds_shield(gear: &[super::super::parse::Gear]) -> bool {
    gear.iter().any(|g| g.item(SLOT_HAND) == "shield" || g.item(SLOT_OFFHAND) == "shield")
}

/// Notre défense, et les dégâts évités.
pub fn mine(duel: &Duel, their_views: &[View], mine: &[Opportunity], theirs: &[Opportunity], raw: &mut Raw) -> (Measure, Option<f64>) {
    let sounds = |broke: bool| duel.shield_sounds.iter().filter(move |s| s.on_me && s.broke == broke).map(|s| s.t);
    let defender = Defender {
        views: their_views,
        attacks: theirs,
        density: duel.foe_density,
        blocks: sounds(false).collect(),
        // La recharge mise sur notre bouclier et le son qui l'accompagne disent la même chose.
        breaks: duel.shield_cooldowns.len().max(sounds(true).count()),
        has_shield: holds_shield(&duel.my_gear),
        sees_blocks: duel.detailed(),
        hits_taken: duel.hits.iter().filter(|h| !h.by_me).map(|h| h.t).collect(),
    };
    (defender.family(landing(duel, mine), raw), defender.avoided())
}

/// La sienne, en miroir. Atterrir proprement ne se mesure pas : on ne voit
/// pas sa vie. Ses gardes cassées : le son seulement, sa recharge ne nous
/// est pas envoyée.
pub fn foe(duel: &Duel, my_views: &[View], mine: &[Opportunity], raw: &mut Raw) -> Measure {
    let sounds = |broke: bool| duel.shield_sounds.iter().filter(move |s| !s.on_me && s.broke == broke).map(|s| s.t);
    let defender = Defender {
        views: my_views,
        attacks: mine,
        density: duel.my_density,
        blocks: sounds(false).collect(),
        breaks: sounds(true).count(),
        has_shield: holds_shield(&duel.foe_gear),
        sees_blocks: duel.detailed(),
        hits_taken: duel.hits.iter().filter(|h| h.by_me).map(|h| h.t).collect(),
    };
    defender.family(None, raw)
}

#[cfg(test)]
mod tests {
    use super::super::tests::view;
    use super::*;

    fn defender<'a>(views: &'a [View], attacks: &'a [Opportunity], blocks: Vec<u64>, breaks: usize) -> Defender<'a> {
        Defender { views, attacks, density: 0.0, blocks, breaks, has_shield: true, sees_blocks: true, hits_taken: Vec::new() }
    }

    #[test]
    fn un_smash_arrete_en_l_air_vaut_dix_fois_plus_qu_au_sol() {
        let falling = |height: f64| -> Vec<View> {
            (0..6)
                .map(|i| {
                    let mut v = view(i, 2.0 + i as f64, 6.0 - i as f64, 1.0);
                    v.target_ground_height = height;
                    v
                })
                .collect()
        };
        let air = falling(5.0);
        let ground = falling(0.0);
        let (bonus_air, w_air) = defender(&air, &[], vec![5], 0).blocked_smash(5).unwrap();
        let (bonus_ground, w_ground) = defender(&ground, &[], vec![5], 0).blocked_smash(5).unwrap();
        assert_eq!(bonus_air, bonus_ground);
        assert_eq!((w_air, w_ground), (1.0, GROUND_BLOCK_WEIGHT));
        // Rien ne tombait : un coup ordinaire arrêté ne compte pas.
        let still: Vec<View> = (0..6).map(|i| view(i, 0.0, 0.0, 1.0)).collect();
        assert!(defender(&still, &[], vec![5], 0).blocked_smash(5).is_none());
    }

    #[test]
    fn la_garde_cassee_coute() {
        let air: Vec<View> = (0..6)
            .map(|i| {
                let mut v = view(i, 2.0 + i as f64, 6.0 - i as f64, 1.0);
                v.target_ground_height = 5.0;
                v
            })
            .collect();
        let (clean, _) = defender(&air, &[], vec![5], 0).shield().unwrap();
        let (broken, _) = defender(&air, &[], vec![5], 1).shield().unwrap();
        assert!(clean > broken, "{clean} {broken}");
    }

    #[test]
    fn au_sol_sous_lui_c_est_etre_vulnerable() {
        let mut ticks: Vec<View> = (0..10).map(|i| view(i, 0.0, 3.0, 1.0)).collect();
        for v in ticks.iter_mut().take(5) {
            v.target_grounded = false;
        }
        let (value, _) = defender(&ticks, &[], Vec::new(), 0).vulnerable().unwrap();
        assert!((value - 0.5).abs() < 1e-9);
    }
}
