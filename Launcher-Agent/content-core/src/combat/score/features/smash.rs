//! Famille Qualité du smash et des gros coups
//! (docs/scoring/familles/qualite-du-smash.md) : ce que le joueur tire d'un
//! gros coup, une fois l'opportunité là. Pour chaque smash réussi et chaque
//! charge de lance qui touche : dégâts tirés ÷ dégâts possibles.
//!
//! La hauteur n'y compte plus : elle est dans la conversion (valeur des
//! opportunités) et le contrôle vertical. Frapper trop tôt dans une chute et
//! frapper mal chargé y coûtent, frapper avec la charge accumulée sur l'objet
//! d'avant (attribute swap) y compte comme un coup bien chargé.

use super::super::parse::{gear_at, Duel, SLOT_HAND};
use super::{flag, sample_at, smash_bonus, Measure, Opportunity, View, IN_WATER, ME_CHARGE, ME_FLAGS, ME_PITCH, PASSENGER, SPRINTING};

/// Dégâts de base d'un coup de masse : 1 pour le joueur, 5 pour la masse
/// (`MaceItem.createAttributes`, jar 26.3, 2026-10-14).
const MACE_BASE: f64 = 6.0;
/// Le critique : ×1,5 sur les dégâts de base ET le bonus de chute, qui lui
/// est ajouté avant (`Player.attack`, jar 26.3). Il demande une charge
/// au-dessus de 0,9 et `canCriticalAttack` (en chute, pas au sol, pas dans
/// l'eau, pas en monture, pas en sprint).
const CRIT: f64 = 1.5;
const CRIT_CHARGE: f64 = 0.9;
/// Un coup donné et son paquet de dégâts : au plus ce délai, ping compris.
const ATTACK_TO_HIT_TICKS: u64 = 4;
/// Durée maximale d'approche d'une charge de lance qu'on remonte.
const APPROACH_TICKS: u64 = 100;

/// Le facteur que le jeu applique aux dégâts de base selon la charge :
/// `0,2 + 0,8 × charge²` (`Player.baseDamageScaleFactor`, jar 26.3).
fn charge_factor(charge: f64) -> f64 {
    0.2 + 0.8 * charge * charge
}

/// Multiplicateur de vitesse de la charge de chaque lance
/// (`Item.Properties.spear`, second paramètre, jar 26.3).
fn spear_multiplier(item: &str) -> Option<f64> {
    Some(match item {
        "wooden_spear" | "golden_spear" => 0.7,
        "stone_spear" | "copper_spear" => 0.82,
        "iron_spear" => 0.95,
        "diamond_spear" => 1.075,
        "netherite_spear" => 1.2,
        _ => return None,
    })
}

/// Dégâts d'une charge de lance à une vitesse relative donnée (blocs par
/// seconde, le long du regard) : la base du joueur plus la vitesse fois le
/// multiplicateur de la lance, arrondi en dessous
/// (`KineticWeapon.damageEntities`, jar 26.3).
fn spear_damage(speed: f64, multiplier: f64) -> f64 {
    1.0 + (speed.max(0.0) * multiplier).floor()
}

/// La charge telle que le jeu la compte au coup : celle du dernier tick
/// relevé avant le coup. Elle suit les attributs de l'objet tenu jusque-là —
/// c'est le principe de l'attribute swap : la barre repart à zéro au tick
/// SUIVANT un changement d'objet (`Player.tick`, jar 26.3).
fn attack_tick(duel: &Duel, hit: u64, ping: u64) -> Option<u64> {
    duel.attacks.iter().rev().find(|a| a.on_foe && a.t <= hit && hit - a.t <= ATTACK_TO_HIT_TICKS + ping).map(|a| a.t)
}

/// Mes gros coups : la famille, et la charge moyenne à mes smashs.
pub fn mine(duel: &Duel, mine: &[Opportunity], views: &[View], ping: u64) -> (Measure, Option<f64>) {
    let mut ratios = Vec::new();
    let mut charges = Vec::new();
    for o in mine.iter().filter(|o| o.converted) {
        let hit = o.hit.unwrap_or(o.close);
        let bonus_hit = smash_bonus(o.hit_fall, duel.my_density);
        let bonus_max = smash_bonus(o.fall, duel.my_density).max(bonus_hit);
        // L'objet au coup n'est connu que depuis la version 2 : sans lui, on
        // ne sait pas reconnaître un swap, et la charge reste hors de la note
        // (décision du 2026-10-14) — seul le moment du coup est jugé.
        let at = attack_tick(duel, hit, ping).filter(|_| duel.detailed());
        let sample = at.and_then(|t| sample_at(&duel.samples, t));
        let ratio = match sample {
            Some(s) => {
                let charge = s.me[ME_CHARGE].clamp(0.0, 1.0);
                charges.push(charge);
                let flags = s.me[ME_FLAGS];
                let crit = charge > CRIT_CHARGE && !flag(flags, SPRINTING) && !flag(flags, IN_WATER) && !flag(flags, PASSENGER);
                let dealt = (MACE_BASE * charge_factor(charge) + bonus_hit) * if crit { CRIT } else { 1.0 };
                dealt / ((MACE_BASE + bonus_max) * CRIT)
            }
            None => (MACE_BASE + bonus_hit) / (MACE_BASE + bonus_max),
        };
        ratios.push(ratio.min(1.0));
    }
    if duel.detailed() {
        ratios.extend(spear_charges(duel, views, true, ping));
    }
    let smash_charge = (!charges.is_empty()).then(|| charges.iter().sum::<f64>() / charges.len() as f64);
    (Measure::rate(ratios.iter().sum(), ratios.len() as f64), smash_charge)
}

/// Ses gros coups. Sa charge ne se voit pas : sa qualité ne mesure que le
/// moment du coup — chute au coup ÷ plus grosse chute de sa fenêtre.
pub fn foe(duel: &Duel, theirs: &[Opportunity], views: &[View]) -> Measure {
    let mut ratios: Vec<f64> = theirs.iter().filter(|o| o.converted && o.fall > 0.0).map(|o| (o.hit_fall / o.fall).min(1.0)).collect();
    if duel.detailed() {
        ratios.extend(spear_charges(duel, views, false, 0));
    }
    Measure::rate(ratios.iter().sum(), ratios.len() as f64)
}

/// Les charges de lance qui ont touché, chacune en dégâts tirés ÷ dégâts
/// permis par la plus grande vitesse de l'approche. Une charge se reconnaît
/// à un paquet de dégâts de lance (`spear`) que ne précède aucun coup donné
/// — le jab, lui, est un coup ordinaire, précédé d'un clic, et ne compte pas.
/// L'approche : les ticks où la lance est baissée, prête à partir.
fn spear_charges(duel: &Duel, views: &[View], mine: bool, ping: u64) -> Vec<f64> {
    let gear = if mine { &duel.my_gear } else { &duel.foe_gear };
    let mut out = Vec::new();
    for hit in duel.hits.iter().filter(|h| h.by_me == mine && h.kind == "spear") {
        // Notre jab laisse une ligne `a` ; celui de l'adversaire ne se voit
        // pas, mais sa lance n'était alors pas baissée (pas d'approche).
        if mine && attack_tick(duel, hit.t, ping).is_some() {
            continue;
        }
        let impact = hit.t.saturating_sub(ping);
        let Some(multiplier) = gear_at(gear, impact).and_then(|g| spear_multiplier(g.item(SLOT_HAND))) else { continue };
        let approach: Vec<f64> = views
            .iter()
            .filter(|v| v.t <= impact && impact - v.t <= APPROACH_TICKS)
            .rev()
            .take_while(|v| v.using)
            .map(|v| relative_speed(duel, v, mine))
            .collect();
        let Some(at_impact) = approach.first().copied() else { continue };
        let best = approach.iter().copied().fold(at_impact, f64::max);
        out.push((spear_damage(at_impact, multiplier) / spear_damage(best, multiplier)).min(1.0));
    }
    out
}

/// La vitesse de l'attaquant par rapport à la cible, le long de son regard,
/// en blocs par seconde (`KineticWeapon.getMotion`, ×20). Notre regard a son
/// inclinaison ; celui de l'adversaire n'est connu qu'à l'horizontale.
fn relative_speed(duel: &Duel, v: &View, mine: bool) -> f64 {
    let yaw = v.yaw.to_radians();
    let pitch = if mine { sample_at(&duel.samples, v.t).map(|s| s.me[ME_PITCH]).unwrap_or(0.0).to_radians() } else { 0.0 };
    let look = [-yaw.sin() * pitch.cos(), -pitch.sin(), yaw.cos() * pitch.cos()];
    let rel = [v.vel[0] - v.target_vel[0], v.vel[1] - v.target_vel[1], v.vel[2] - v.target_vel[2]];
    20.0 * (look[0] * rel[0] + look[1] * rel[1] + look[2] * rel[2])
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

    #[test]
    fn la_charge_de_lance_suit_la_vitesse() {
        assert_eq!(spear_damage(10.0, 1.2), 13.0);
        assert_eq!(spear_damage(-3.0, 1.2), 1.0, "en reculant : la base seule");
        assert_eq!(spear_multiplier("netherite_spear"), Some(1.2));
        assert_eq!(spear_multiplier("mace"), None);
    }
}
