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
/// Écart toléré entre le son d'un critique et le paquet de dégâts du coup.
const CRIT_SOUND_TICKS: u64 = 2;
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

/// Un smash réussi, tel que la famille le juge (relevé pour l'outil de calibration).
#[derive(Debug, Clone, PartialEq)]
pub struct Smash {
    pub t: u64,
    pub by_me: bool,
    /// Chute au coup, et chute possible en attendant le plus possible.
    pub hit_fall: f64,
    pub possible_fall: f64,
    /// Charge au coup (la nôtre seulement), critique, sprint au coup ; `None` quand on ne le sait pas.
    pub charge: Option<f64>,
    pub crit: Option<bool>,
    pub sprinting: Option<bool>,
    /// Dégâts tirés ÷ dégâts possibles.
    pub ratio: f64,
}

/// Un critique entendu avec ce coup : le son part du serveur au moment du
/// coup, avec le paquet de dégâts (deux ticks de marge). C'est le coup qui
/// dit qui a frappé — pas la position du son (voir `Duel::crits`). Si un
/// coup de l'autre tombe dans les mêmes ticks, le son reste ambigu : il
/// n'est donné à personne.
fn crit_heard(duel: &Duel, hit: u64, by_me: bool) -> bool {
    let near = |t: u64| t.abs_diff(hit) <= CRIT_SOUND_TICKS;
    duel.crits.iter().any(|t| near(*t)) && !duel.hits.iter().any(|h| h.by_me != by_me && near(h.t))
}

/// Mes gros coups : la famille, et chaque smash jugé.
pub fn mine(duel: &Duel, mine: &[Opportunity], views: &[View], ping: u64) -> (Measure, Vec<Smash>) {
    let mut ratios = Vec::new();
    let mut smashes = Vec::new();
    for o in mine.iter().filter(|o| o.converted) {
        let hit = o.hit.unwrap_or(o.close);
        let bonus_hit = smash_bonus(o.hit_fall, duel.my_density);
        let bonus_max = smash_bonus(o.possible_fall, duel.my_density).max(bonus_hit);
        // L'objet au coup n'est connu que depuis la version 2 : sans lui, on
        // ne sait pas reconnaître un swap, et la charge reste hors de la note
        // (décision du 2026-10-14) — seul le moment du coup est jugé.
        let at = attack_tick(duel, hit, ping).filter(|_| duel.detailed());
        let sample = at.and_then(|t| sample_at(&duel.samples, t));
        let mut smash = Smash { t: hit, by_me: true, hit_fall: o.hit_fall, possible_fall: o.possible_fall, charge: None, crit: None, sprinting: None, ratio: 0.0 };
        smash.ratio = match sample {
            Some(s) => {
                let charge = s.me[ME_CHARGE].clamp(0.0, 1.0);
                let flags = s.me[ME_FLAGS];
                // Le son du critique, quand le journal le porte, plutôt que de le déduire.
                let crit = if duel.sees_crits() {
                    crit_heard(duel, hit, true)
                } else {
                    charge > CRIT_CHARGE && !flag(flags, SPRINTING) && !flag(flags, IN_WATER) && !flag(flags, PASSENGER)
                };
                smash.charge = Some(charge);
                smash.crit = Some(crit);
                smash.sprinting = Some(flag(flags, SPRINTING));
                let dealt = (MACE_BASE * charge_factor(charge) + bonus_hit) * if crit { CRIT } else { 1.0 };
                dealt / ((MACE_BASE + bonus_max) * CRIT)
            }
            None => (MACE_BASE + bonus_hit) / (MACE_BASE + bonus_max),
        }
        .min(1.0);
        ratios.push(smash.ratio);
        smashes.push(smash);
    }
    if duel.detailed() {
        ratios.extend(spear_charges(duel, views, true, ping));
    }
    (judged(&ratios, mine), smashes)
}

/// La famille sur les gros coups portés. Aucun gros coup alors que des
/// ouvertures se sont présentées, c'est 0, compté sur ces ouvertures
/// (2026-10-15) : avant, la famille ne mesurait rien et ne pesait rien —
/// un duel sans un seul coup y valait la moyenne. Sans aucune ouverture,
/// il n'y a rien à juger.
fn judged(ratios: &[f64], opportunities: &[Opportunity]) -> Measure {
    if ratios.is_empty() && !opportunities.is_empty() {
        return Measure { value: 0.0, count: opportunities.len() as f64 };
    }
    Measure::rate(ratios.iter().sum(), ratios.len() as f64)
}

/// Ses gros coups. Sa charge ne se voit pas, son critique si (version 3 :
/// le son joué sur lui) : on le juge comme nous, la charge tenue pour
/// pleine — elle ne pèse que sur les dégâts de base, le critique sur tout.
/// Dans un journal plus ancien, seul le moment du coup est jugé : chute au
/// coup ÷ chute possible.
pub fn foe(duel: &Duel, theirs: &[Opportunity], views: &[View]) -> (Measure, Vec<Smash>) {
    let mut ratios = Vec::new();
    let mut smashes = Vec::new();
    for o in theirs.iter().filter(|o| o.converted && o.possible_fall > 0.0) {
        let hit = o.hit.unwrap_or(o.close);
        let mut smash = Smash { t: hit, by_me: false, hit_fall: o.hit_fall, possible_fall: o.possible_fall, charge: None, crit: None, sprinting: None, ratio: 0.0 };
        smash.ratio = if duel.sees_crits() {
            let crit = crit_heard(duel, hit, false);
            smash.crit = Some(crit);
            let bonus_hit = smash_bonus(o.hit_fall, duel.foe_density);
            let bonus_max = smash_bonus(o.possible_fall, duel.foe_density).max(bonus_hit);
            (MACE_BASE + bonus_hit) * if crit { CRIT } else { 1.0 } / ((MACE_BASE + bonus_max) * CRIT)
        } else {
            o.hit_fall / o.possible_fall
        }
        .min(1.0);
        ratios.push(smash.ratio);
        smashes.push(smash);
    }
    if duel.detailed() {
        ratios.extend(spear_charges(duel, views, false, 0));
    }
    (judged(&ratios, theirs), smashes)
}

/// Frappé « au bon moment » : à un demi-bloc près de la chute possible.
const ON_TIME_MARGIN: f64 = 0.5;

/// Le détail de la qualité du smash : au bon moment, critiques, sprint au
/// coup, barre pas pleine ; chaque smash comme moment ; ses critiques en face.
/// Aucun smash réussi sur des ouvertures : c'est le fait, et ses smashs en face.
pub fn detail(duel: &Duel, smashes: &[Smash], opened: usize, d: &mut super::Detail) {
    let mine: Vec<&Smash> = smashes.iter().filter(|s| s.by_me).collect();
    let n = mine.len() as f64;
    if n == 0.0 {
        if opened > 0 {
            d.fact("smash.none", false, 1.0, &[opened as f64]);
            let theirs = smashes.iter().filter(|s| !s.by_me).count() as f64;
            d.versus("smash.landed", &[0.0], &[theirs]);
        }
        return;
    }
    let on_time = |s: &Smash| s.hit_fall + ON_TIME_MARGIN >= s.possible_fall;
    let timed = mine.iter().filter(|s| on_time(s)).count() as f64;
    d.fact("smash.on_time", timed / n >= 0.75, timed / n - 0.75, &[timed, n]);
    let crits: Vec<bool> = mine.iter().filter_map(|s| s.crit).collect();
    if crits.len() == mine.len() {
        let k = crits.iter().filter(|c| **c).count() as f64;
        d.fact("smash.crit", k / n >= 0.5, 2.0 * (k / n - 0.5), &[k, n]);
    }
    let sprinting = mine.iter().filter(|s| s.sprinting == Some(true)).count() as f64;
    if sprinting > 0.0 {
        d.fact("smash.sprint", false, sprinting / n, &[sprinting]);
    }
    let weak = mine.iter().filter(|s| s.charge.is_some_and(|c| c <= CRIT_CHARGE)).count() as f64;
    if weak > 0.0 {
        d.fact("smash.charge", false, weak / n, &[weak]);
    }
    for s in &mine {
        let flag = |b: Option<bool>| b.map(|b| b as u8 as f64).unwrap_or(-1.0);
        let good = s.crit.unwrap_or(true) && on_time(s);
        let charge = s.charge.map(|c| (100.0 * c).round()).unwrap_or(-1.0);
        // Les smashs ratés d'abord, puis les meilleurs.
        d.moment(s.t, "smash.hit", good, if good { s.ratio } else { 2.0 - s.ratio }, &[s.hit_fall, charge, flag(s.sprinting), flag(s.crit)]);
    }
    let theirs: Vec<&Smash> = smashes.iter().filter(|s| !s.by_me).collect();
    if duel.sees_crits() && !theirs.is_empty() && crits.len() == mine.len() {
        let mine_crits = crits.iter().filter(|c| **c).count() as f64;
        let their_crits = theirs.iter().filter(|s| s.crit == Some(true)).count() as f64;
        d.versus("smash.crit", &[mine_crits, n], &[their_crits, theirs.len() as f64]);
    }
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
    fn aucun_smash_sur_des_ouvertures_vaut_zero() {
        let missed = super::super::Opportunity {
            open: 0,
            close: 5,
            converted: false,
            hit: None,
            expected: 20.0,
            realized: 0.0,
            fall: 6.0,
            hit_fall: 6.0,
            possible_fall: 6.0,
            offset: 1.0,
            target_speed: 0.0,
            behind_shield: false,
            chance: 0.4,
        };
        let none = judged(&[], &[missed.clone(), missed]);
        assert_eq!((none.value, none.count), (0.0, 2.0), "deux ouvertures, aucun smash : 0 sur deux");
        assert_eq!(judged(&[], &[]).count, 0.0, "sans ouverture, rien à juger");
        assert_eq!(judged(&[0.8], &[]).value, 0.8);
    }

    #[test]
    fn la_charge_de_lance_suit_la_vitesse() {
        assert_eq!(spear_damage(10.0, 1.2), 13.0);
        assert_eq!(spear_damage(-3.0, 1.2), 1.0, "en reculant : la base seule");
        assert_eq!(spear_multiplier("netherite_spear"), Some(1.2));
        assert_eq!(spear_multiplier("mace"), None);
    }
}
