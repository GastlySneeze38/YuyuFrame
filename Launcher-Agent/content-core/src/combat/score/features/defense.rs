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
// relevés (journaux de version 2), le reste sur 52. Bouclier et temps
// vulnérable recalés le même soir sur 23 duels : bouclier 0,29 sur 28
// relevés (nous 0,21, nos adversaires 0,38), temps vulnérable 0,38 sur 43.
const SHIELD_CENTER: f64 = 0.29;
const VULNERABLE_CENTER: f64 = 0.38;
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
        let (grounded, threat) = self.exposure_ticks();
        (threat > 0.0).then(|| (1.0 - grounded / threat, threat / 80.0))
    }

    /// Ses descentes menaçantes : (tick de début, esquivée ?).
    fn dodges(&self) -> Vec<(u64, bool)> {
        let smashes: Vec<u64> = self.attacks.iter().filter_map(|o| o.hit).collect();
        descents(self.views)
            .into_iter()
            .filter_map(|d| {
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
                Some((s.t, !smashed && e.horizontal > REACH && moved >= DODGE_MOVE))
            })
            .collect()
    }

    /// Pendant ses descentes, sortir de sa zone à temps.
    fn dodge(&self) -> Part {
        mean(self.dodges().into_iter().map(|(_, dodged)| dodged as u8 as f64))
    }

    /// Après chaque coup reçu, les ticks pour se dégager (`None` : jamais).
    fn recoveries(&self) -> Vec<Option<u64>> {
        self.hits_taken
            .iter()
            .map(|t| self.views.iter().find(|v| v.t >= *t && (v.above < 0.0 || v.horizontal > REACH + 1.0)).map(|v| v.t - t))
            .collect()
    }

    /// Après un coup reçu, reprendre la hauteur ou se remettre hors de portée.
    fn recovery(&self) -> Part {
        mean(self.recoveries().into_iter().map(|d| d.map(|d| 1.0 - (d as f64 / RECOVERY_TICKS).min(1.0)).unwrap_or(0.0)))
    }

    /// Les smashs arrêtés : (tick, chute du smash, en l'air ?).
    fn blocked(&self) -> Vec<(u64, f64, bool)> {
        self.blocks
            .iter()
            .filter_map(|t| {
                let recent: Vec<&View> = self.views.iter().filter(|v| v.t <= *t && t - v.t <= BLOCK_LOOKBACK_TICKS && v.above > 0.0).collect();
                let fall = recent.iter().map(|v| v.fall).fold(0.0, f64::max);
                self.blocked_smash(*t).map(|(_, weight)| (*t, fall, weight == 1.0))
            })
            .collect()
    }

    /// Ticks passés au sol pendant qu'il est au-dessus et à portée, et ticks où il l'était.
    fn exposure_ticks(&self) -> (f64, f64) {
        let threat: Vec<&View> = self.views.iter().filter(|v| v.above > HEIGHT_ADVANTAGE && v.horizontal <= REACH).collect();
        (threat.iter().filter(|v| v.target_grounded).count() as f64, threat.len() as f64)
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
///
/// Ne rien subir est le cas de presque tous les duels (1 dans les 18 duels
/// relevés le 2026-10-15) : ça ne dit rien, et une partie toujours à 1
/// remontait la défense de tout le monde. La partie n'existe donc que
/// quand une chute a fait mal ; elle vaut alors moins que la moyenne (0,5),
/// d'autant moins que la chute a coûté, sur le nombre de chutes qui ont fait mal.
fn landing(duel: &Duel, mine: &[Opportunity]) -> Part {
    let hurt: Vec<f64> = landing_damage(duel, mine).into_iter().map(|(_, damage)| damage).filter(|d| *d > 0.0).collect();
    let total: f64 = hurt.iter().sum();
    (!hurt.is_empty()).then(|| (0.5 * (1.0 - (total / LANDING_WORST).min(1.0)), hurt.len() as f64))
}

/// Pour chacune de nos opportunités ratées : (tick, points de vie perdus en
/// chute ensuite). Une chute n'est rattachée qu'à la dernière opportunité
/// ratée qui la précède : deux ratés d'affilée avant le même atterrissage
/// ne la comptent pas deux fois (corrigé le 2026-10-15).
fn landing_damage(duel: &Duel, mine: &[Opportunity]) -> Vec<(u64, f64)> {
    let missed: Vec<&Opportunity> = mine.iter().filter(|o| !o.converted).collect();
    let mut out: Vec<(u64, f64)> = missed.iter().map(|o| (o.close, 0.0)).collect();
    for t in &duel.my_falls {
        let Some(index) = missed.iter().rposition(|o| *t > o.close && *t <= o.close + LANDING_TICKS) else { continue };
        let before = sample_at(&duel.samples, t - 1).map(|s| s.me[ME_HEALTH]);
        let after = duel.samples.iter().find(|s| s.t > *t).map(|s| s.me[ME_HEALTH]);
        if let (Some(b), Some(a)) = (before, after) {
            out[index] = (*t, out[index].1 + (b - a).max(0.0));
        }
    }
    out
}

fn holds_shield(gear: &[super::super::parse::Gear]) -> bool {
    gear.iter().any(|g| g.item(SLOT_HAND) == "shield" || g.item(SLOT_OFFHAND) == "shield")
}

/// Notre joueur en défense, vu depuis les vues de l'adversaire.
fn me_defending<'a>(duel: &Duel, their_views: &'a [View], theirs: &'a [Opportunity]) -> Defender<'a> {
    let sounds = |broke: bool| duel.shield_sounds.iter().filter(move |s| s.on_me && s.broke == broke).map(|s| s.t);
    Defender {
        views: their_views,
        attacks: theirs,
        density: duel.foe_density,
        blocks: sounds(false).collect(),
        // La recharge mise sur notre bouclier et le son qui l'accompagne disent la même chose.
        breaks: duel.shield_cooldowns.len().max(sounds(true).count()),
        has_shield: holds_shield(&duel.my_gear),
        sees_blocks: duel.detailed(),
        hits_taken: duel.hits.iter().filter(|h| !h.by_me).map(|h| h.t).collect(),
    }
}

/// L'adversaire en défense. Ses gardes cassées : le son seulement, sa
/// recharge ne nous est pas envoyée.
fn foe_defending<'a>(duel: &Duel, my_views: &'a [View], mine: &'a [Opportunity]) -> Defender<'a> {
    let sounds = |broke: bool| duel.shield_sounds.iter().filter(move |s| !s.on_me && s.broke == broke).map(|s| s.t);
    Defender {
        views: my_views,
        attacks: mine,
        density: duel.my_density,
        blocks: sounds(false).collect(),
        breaks: sounds(true).count(),
        has_shield: holds_shield(&duel.foe_gear),
        sees_blocks: duel.detailed(),
        hits_taken: duel.hits.iter().filter(|h| h.by_me).map(|h| h.t).collect(),
    }
}

/// Notre défense, et les dégâts évités.
pub fn mine(duel: &Duel, their_views: &[View], mine: &[Opportunity], theirs: &[Opportunity], raw: &mut Raw) -> (Measure, Option<f64>) {
    let defender = me_defending(duel, their_views, theirs);
    (defender.family(landing(duel, mine), raw), defender.avoided())
}

/// La sienne, en miroir. Atterrir proprement ne se mesure pas : on ne voit
/// pas sa vie.
pub fn foe(duel: &Duel, my_views: &[View], mine: &[Opportunity], raw: &mut Raw) -> Measure {
    foe_defending(duel, my_views, mine).family(None, raw)
}

/// Le détail de la défense : smashs arrêtés, gardes cassées, ses smashs
/// passés, temps au sol sous lui, descentes esquivées, chutes après un raté,
/// reprise après un coup. Chaque fait monte ou baisse selon la même mesure
/// que la note, comparée à ce que font en moyenne les joueurs.
pub fn detail(duel: &Duel, my_views: &[View], their_views: &[View], mine: &[Opportunity], theirs: &[Opportunity], d: &mut super::Detail) {
    let me = me_defending(duel, their_views, theirs);
    let them = foe_defending(duel, my_views, mine);
    // Le poids d'un fait : l'écart de sa mesure à la moyenne, fois le poids de la mesure.
    let weight = |part: Part, center: f64, w: f64| centered(part, center).map(|(v, _)| (v - 0.5) * w);

    if let Some(shield) = weight(me.shield(), SHIELD_CENTER, W_SHIELD) {
        let blocked = me.blocked();
        let air = blocked.iter().filter(|b| b.2).count() as f64;
        d.fact("defense.blocked", shield >= 0.0, shield, &[air, blocked.len() as f64 - air]);
        if me.breaks > 0 {
            d.fact("defense.broken", false, me.breaks as f64 * W_SHIELD / 4.0, &[me.breaks as f64]);
        }
        for (t, fall, in_air) in &blocked {
            d.moment(*t, "defense.blocked", true, fall * if *in_air { 1.0 } else { 0.1 }, &[*fall, *in_air as u8 as f64]);
        }
        let breaks = duel.shield_sounds.iter().filter(|s| s.on_me && s.broke);
        for s in breaks {
            d.moment(s.t, "defense.broken", false, BREAK_FALL, &[]);
        }
    }
    if let Some(openings) = me.openings() {
        let landed = theirs.iter().filter(|o| o.converted).count() as f64;
        d.fact("defense.openings", openings.0 >= 0.5, (openings.0 - 0.5) * W_OPENINGS, &[landed, theirs.len() as f64]);
        for o in theirs.iter().filter(|o| o.converted) {
            d.moment(o.hit.unwrap_or(o.open), "defense.smashed", false, o.realized, &[o.hit_fall]);
        }
    }
    if let Some(w) = weight(me.vulnerable(), VULNERABLE_CENTER, W_VULNERABLE) {
        let (grounded, _) = me.exposure_ticks();
        d.fact("defense.vulnerable", w >= 0.0, w, &[(grounded / 2.0).round() / 10.0]);
    }
    if let Some(w) = weight(me.dodge(), DODGE_CENTER, W_DODGE) {
        let dodges = me.dodges();
        let dodged = dodges.iter().filter(|(_, ok)| *ok).count() as f64;
        d.fact("defense.dodge", w >= 0.0, w, &[dodged, dodges.len() as f64]);
        let theirs_dodges = them.dodges();
        if !theirs_dodges.is_empty() {
            let their_dodged = theirs_dodges.iter().filter(|(_, ok)| *ok).count() as f64;
            d.versus("defense.dodge", &[dodged, dodges.len() as f64], &[their_dodged, theirs_dodges.len() as f64]);
        }
    }
    let falls = landing_damage(duel, mine);
    let lost: f64 = falls.iter().map(|(_, dmg)| dmg).sum();
    if lost > 0.0 {
        d.fact("defense.landing", false, (lost / LANDING_WORST).min(1.0) * W_LANDING, &[(lost / 2.0 * 10.0).round() / 10.0]);
        for (t, dmg) in falls.iter().filter(|(_, dmg)| *dmg > 0.0) {
            d.moment(*t, "defense.landing", false, *dmg, &[(dmg / 2.0 * 10.0).round() / 10.0]);
        }
    }
    if let Some(w) = weight(me.recovery(), RECOVERY_CENTER, W_RECOVERY) {
        let delays: Vec<f64> = me.recoveries().into_iter().map(|d| d.map(|d| d as f64).unwrap_or(RECOVERY_TICKS)).collect();
        let seconds = delays.iter().sum::<f64>() / delays.len() as f64 / 20.0;
        d.fact("defense.recovery", w >= 0.0, w, &[(seconds * 10.0).round() / 10.0]);
    }
    // Ses smashs arrêtés, s'il avait un bouclier et que le journal les voit.
    if me.shield().is_some() && them.shield().is_some() {
        let air = |b: &[(u64, f64, bool)]| b.iter().filter(|x| x.2).count() as f64;
        d.versus("defense.blocked_air", &[air(&me.blocked())], &[air(&them.blocked())]);
    }
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
