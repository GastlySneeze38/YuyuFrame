//! Bonus Bug use (docs/scoring/familles/bug-use.md) : le joueur maîtrise-t-il
//! les techniques les plus dures du PvP à la masse ? Les bugs de la famille
//! de l'attribute swap (MC-28289) et les techniques au tick près.
//!
//! **Il ne fait jamais baisser la note** : hors de la moyenne des familles,
//! il s'ajoute à la note, plafonné à +10. Une tentative ratée est ignorée.
//! Chez l'adversaire, ses techniques sont affichées, jamais comptées.

use super::super::parse::{gear_at, Duel, Gear, SLOT_CHEST, SLOT_HAND};
use super::control::{upward_teleport, Lunge};
use super::{sample_at, smash_bonus, view_at, Opportunity, View, Y};

/// Une technique : son nom (celui de l'outil de calibration) et ses points de base.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Technique {
    pub name: &'static str,
    pub points: f64,
}

// Points de base de chaque technique réussie, selon sa rareté : posés au
// jugé (décision du 2026-10-14), *à calibrer* sur leur fréquence réelle.
const COOLDOWN_SWAP: Technique = Technique { name: "cooldown_swap", points: 1.0 };
const LUNGE_SWAP: Technique = Technique { name: "lunge_swap", points: 1.0 };
const BREACH_SWAP: Technique = Technique { name: "breach_swap", points: 2.0 };
const DENSITY_SWAP: Technique = Technique { name: "density_swap", points: 2.0 };
const REACH_SWAP: Technique = Technique { name: "reach_swap", points: 2.0 };
// L'elytra swap (élytre ↔ plastron en vol) est retiré le 2026-10-15 : en
// 26.x, un clic droit avec le plastron en main l'échange instantanément avec
// l'élytre portée. Ce n'est ni un bug ni un geste au tick près, c'est le
// geste normal du kit masse — relevé chez TOUS nos adversaires, 4 à 30 fois
// par duel, et deux fois chez nous sans le vouloir. Le plus courant ne vaut
// rien selon la règle même de la famille.
const D_HAND: Technique = Technique { name: "d_hand", points: 3.0 };
const STUN_SLAM: Technique = Technique { name: "stun_slam", points: 4.0 };
const INSTANT_PEARL_CATCH: Technique = Technique { name: "instant_pearl_catch", points: 4.0 };
/// Plafond du bonus d'un duel (décision du 2026-10-14).
pub const CAP: f64 = 10.0;

/// Un coup donné et son paquet de dégâts : au plus ce délai, ping compris.
const ATTACK_TO_HIT_TICKS: u64 = 4;
/// Portée d'un coup ordinaire : un coup confirmé au-delà vient de la lance.
const PLAYER_REACH: f64 = 3.0;
/// Stun slam : le smash part dans le tick qui suit la garde cassée (un tick de marge pour l'ordre des paquets).
const STUN_SLAM_TICKS: u64 = 2;
/// Instant pearl catch : perle et charge de vent à 1 ou 2 ticks d'écart.
const PEARL_CATCH_TICKS: u64 = 2;
/// D-hand : deux totems qui sautent coup sur coup.
const D_HAND_TICKS: u64 = 40;
/// Son swap : la masse apparaît dans sa main au plus ce nombre de ticks après que son smash nous arrive.
const FOE_SWAP_TICKS: u64 = 6;
/// Hauteur qui double les points d'un instant pearl catch quand elle mène à une opportunité.
const PEARL_CATCH_HEIGHT: f64 = 20.0;

/// Le bonus d'un duel, et ce qu'on voit des techniques de l'adversaire.
#[derive(Debug, Clone, Copy, Default, PartialEq, serde::Serialize)]
pub struct BugUse {
    /// Nos points, plafonnés à `CAP`, et le nombre de techniques réussies.
    pub points: f64,
    pub techniques: usize,
    /// Les siens, pour l'affichage seulement.
    pub foe_points: f64,
    pub foe_techniques: usize,
}

/// Une technique reconnue : quand, chez qui, et ce qu'elle a rapporté.
#[derive(Debug, Clone, PartialEq)]
pub struct Detection {
    pub t: u64,
    pub by_me: bool,
    pub name: &'static str,
    pub points: f64,
}

#[derive(Default)]
struct Tally {
    points: f64,
    found: Vec<(u64, &'static str, f64)>,
}

/// Ce que vaut une technique, face à ses points de base. Ralenti le
/// 2026-10-15 : un peu de bug use dans un duel faisait monter tout de suite
/// à +4. Il faut maintenant plusieurs techniques rares et réussies pour
/// approcher ce bonus.
const SCALE: f64 = 0.25;
/// Chaque nouvelle réussite de la même technique dans un duel vaut moitié
/// moins que la précédente : répéter un geste ne vaut jamais plus de deux
/// fois sa première réussite.
const REPEAT_DECAY: f64 = 0.5;

impl Tally {
    fn add(&mut self, t: u64, technique: Technique, effect: f64) {
        let repeats = self.found.iter().filter(|(_, name, _)| *name == technique.name).count();
        let points = technique.points * SCALE * effect.clamp(1.0, 2.0) * REPEAT_DECAY.powi(repeats as i32);
        self.points += points;
        self.found.push((t, technique.name, points));
    }

    fn count(&self) -> usize {
        self.found.len()
    }
}

/// Une arme rapide : épée ou main vide.
fn fast(item: &str) -> bool {
    item.is_empty() || item.ends_with("_sword")
}

/// Une arme lente : masse, hache, lance.
fn slow(item: &str) -> bool {
    item == "mace" || item.ends_with("_axe") || item.ends_with("_spear")
}

/// La recharge de chaque lance, en ticks (`Item.Properties.spear`, premier
/// paramètre en secondes, jar 26.3 : 1,15 s en netherite).
fn spear_recharge(item: &str) -> Option<u64> {
    let seconds = match item {
        "wooden_spear" => 0.65,
        "stone_spear" => 0.75,
        "copper_spear" => 0.85,
        "iron_spear" | "golden_spear" => 0.95,
        "diamond_spear" => 1.05,
        "netherite_spear" => 1.15,
        _ => return None,
    };
    Some((seconds * 20.0_f64).round() as u64)
}

/// Le swap d'une arme rapide vers une arme lente : cooldown swap, ou breach
/// et density swap selon l'enchantement de l'objet d'arrivée (lu dans
/// l'équipement juste après le coup). `smash` : le coup confirmé est un smash.
fn attack_swap(tally: &mut Tally, gear: &[Gear], t: u64, old: &str, new: &str, smash: bool) {
    if !(fast(old) && slow(new)) {
        return;
    }
    let arrival = gear.iter().find(|g| g.t >= t && g.t <= t + 5 && g.item(SLOT_HAND) == new);
    let level = |enchantment: &str| arrival.map(|g| g.level(SLOT_HAND, enchantment)).unwrap_or(0.0);
    // L'effet : le coup porté par un breach ou un density swap compte double quand c'est un smash.
    let effect = if smash { 2.0 } else { 1.0 };
    if level("breach") > 0.0 {
        tally.add(t, BREACH_SWAP, effect);
    } else if level("density") > 0.0 {
        tally.add(t, DENSITY_SWAP, effect);
    } else {
        tally.add(t, COOLDOWN_SWAP, 1.0);
    }
}

/// Deux totems qui sautent coup sur coup, sans mourir.
fn d_hand(tally: &mut Tally, totems: &[u64], deaths: &[u64]) {
    let mut i = 0;
    while i + 1 < totems.len() {
        let (a, b) = (totems[i], totems[i + 1]);
        if b - a <= D_HAND_TICKS && !deaths.iter().any(|d| *d >= b && *d - b <= 20) {
            tally.add(b, D_HAND, 1.0);
            i += 2;
        } else {
            i += 1;
        }
    }
}


fn mine(duel: &Duel, my_views: &[View], mine: &[Opportunity], lunges: &[Lunge], ping: u64) -> Tally {
    let mut tally = Tally::default();
    // La famille de l'attribute swap : l'objet change dans le tick du coup.
    // L'équipement en vigueur au tick du coup est celui d'avant le swap ;
    // l'objet de la ligne `a` est celui qui frappe.
    for attack in duel.attacks.iter().filter(|a| a.on_foe) {
        let old = gear_at(&duel.my_gear, attack.t).map(|g| g.item(SLOT_HAND)).unwrap_or("");
        let new = attack.item.as_str();
        if old == new {
            continue;
        }
        // Réussie : le coup est confirmé par le serveur.
        let Some(hit) = duel.hits.iter().find(|h| h.by_me && h.t >= attack.t && h.t <= attack.t + ATTACK_TO_HIT_TICKS + ping) else { continue };
        let far = view_at(my_views, attack.t, 2).is_some_and(|v| v.distance > PLAYER_REACH);
        if new.ends_with("_spear") && !old.ends_with("_spear") && far {
            tally.add(attack.t, REACH_SWAP, 1.0);
        } else {
            attack_swap(&mut tally, &duel.my_gear, attack.t, old, new, hit.smash);
        }
    }
    // Lunge swap : l'élan sans attendre la recharge de la lance — elle vient
    // d'arriver en main, ou l'élan d'avant est trop proche.
    let mut last: Option<u64> = None;
    for lunge in lunges {
        let Some(recharge) = gear_at(&duel.my_gear, lunge.t).and_then(|g| spear_recharge(g.item(SLOT_HAND))) else { continue };
        let held_since = duel.my_gear.iter().rev().filter(|g| g.t <= lunge.t).take_while(|g| g.item(SLOT_HAND).ends_with("_spear")).last().map(|g| g.t);
        let fresh = held_since.is_some_and(|since| lunge.t - since < recharge);
        let quick = last.is_some_and(|prev| lunge.t - prev < recharge);
        if fresh || quick {
            tally.add(lunge.t, LUNGE_SWAP, 1.0);
        }
        last = Some(lunge.t);
    }
    // Stun slam : son bouclier cassé par une hache, notre smash dans le tick suivant.
    let reference = smash_bonus(8.0, duel.my_density);
    for broke in duel.shield_sounds.iter().filter(|s| !s.on_me && s.broke) {
        if let Some(hit) = duel.hits.iter().find(|h| h.by_me && h.smash && h.t >= broke.t && h.t <= broke.t + STUN_SLAM_TICKS) {
            let bonus = mine.iter().find(|o| o.hit == Some(hit.t)).map(|o| o.realized).unwrap_or(0.0);
            tally.add(hit.t, STUN_SLAM, 1.0 + (bonus / reference).min(1.0));
        }
    }
    // Instant pearl catch : perle et charge de vent à un tick d'écart, puis téléportation vers le haut.
    let winds: Vec<u64> = duel.uses.iter().filter(|(_, i)| i == "wind_charge").map(|(t, _)| *t).collect();
    for (pearl, _) in duel.uses.iter().filter(|(_, i)| i == "ender_pearl") {
        let Some(wind) = winds.iter().find(|w| w.abs_diff(*pearl) <= PEARL_CATCH_TICKS) else { continue };
        let start = (*pearl).min(*wind);
        let Some(arrived) = upward_teleport(duel, start, 60) else { continue };
        let y0 = sample_at(&duel.samples, start).map(|s| s.me[Y]).unwrap_or(0.0);
        let peak = duel.samples.iter().filter(|s| s.t >= arrived && s.t <= arrived + 40).map(|s| s.me[Y]).fold(y0, f64::max);
        let leads = mine.iter().any(|o| o.open >= arrived && o.open - arrived <= 60);
        tally.add(start, INSTANT_PEARL_CATCH, if leads { 1.0 + ((peak - y0) / PEARL_CATCH_HEIGHT).min(1.0) } else { 1.0 });
    }
    d_hand(&mut tally, &duel.my_totems, &duel.my_deaths);
    tally
}

/// Ses techniques, telles qu'on peut les voir : son objet en main qui change
/// pile au moment où il nous frappe, ses totems. Ses perles et charges de
/// vent ne se voient pas.
fn foe(duel: &Duel) -> Tally {
    let mut tally = Tally::default();
    // Son swap, vu de chez nous : le serveur nous envoie son changement
    // d'objet APRÈS le coup. On le voit donc tenir l'épée jusqu'au paquet
    // « smash de masse », et la masse n'apparaît qu'ensuite (vu le
    // 2026-10-15, sept fois dans un même duel). Un smash de masse reçu alors
    // qu'on le voit tenir autre chose qu'une masse, c'est le swap : le coup
    // est parti avec la masse, sur les attributs de l'objet d'avant.
    for hit in duel.hits.iter().filter(|h| !h.by_me && h.smash) {
        let seen = gear_at(&duel.foe_gear, hit.t).map(|g| g.item(SLOT_HAND)).unwrap_or("");
        let mace_after = duel.foe_gear.iter().any(|g| g.t > hit.t && g.t <= hit.t + FOE_SWAP_TICKS && g.item(SLOT_HAND) == "mace");
        if seen != "mace" && mace_after {
            attack_swap(&mut tally, &duel.foe_gear, hit.t, seen, "mace", true);
        }
    }
    for broke in duel.shield_sounds.iter().filter(|s| s.on_me && s.broke) {
        if let Some(hit) = duel.hits.iter().find(|h| !h.by_me && h.smash && h.t >= broke.t && h.t <= broke.t + STUN_SLAM_TICKS) {
            tally.add(hit.t, STUN_SLAM, 1.0);
        }
    }
    d_hand(&mut tally, &duel.foe_totems, &duel.foe_deaths);
    tally
}

/// Le bonus, et chaque technique reconnue des deux côtés.
pub fn extract(duel: &Duel, my_views: &[View], opportunities: &[Opportunity], lunges: &[Lunge], ping: u64) -> (BugUse, Vec<Detection>) {
    // Tout repose sur l'objet au coup et l'équipement au tick près (version 2).
    if !duel.detailed() {
        return (BugUse::default(), Vec::new());
    }
    let (me, them) = (mine(duel, my_views, opportunities, lunges, ping), foe(duel));
    let bonus = BugUse { points: me.points.min(CAP), techniques: me.count(), foe_points: them.points.min(CAP), foe_techniques: them.count() };
    let mut found: Vec<Detection> = me.found.iter().map(|(t, name, points)| Detection { t: *t, by_me: true, name, points: *points }).collect();
    found.extend(them.found.iter().map(|(t, name, points)| Detection { t: *t, by_me: false, name, points: *points }));
    found.sort_by_key(|d| d.t);
    (bonus, found)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn gear(t: u64, chest: &str, hand: &str) -> Gear {
        let mut slots: [String; 6] = Default::default();
        slots[SLOT_CHEST] = chest.to_string();
        slots[SLOT_HAND] = hand.to_string();
        Gear { t, slots }
    }

    #[test]
    fn le_swap_se_classe_a_l_enchantement_d_arrivee() {
        let list = vec![gear(10, "", "netherite_sword"), gear(11, "", "mace,breach=4")];
        let mut tally = Tally::default();
        attack_swap(&mut tally, &list, 10, "netherite_sword", "mace", true);
        assert_eq!((tally.points, tally.count()), (BREACH_SWAP.points * SCALE * 2.0, 1));
        assert_eq!(tally.found[0].1, "breach_swap");
        let plain = vec![gear(10, "", "netherite_sword"), gear(11, "", "mace")];
        let mut tally = Tally::default();
        attack_swap(&mut tally, &plain, 10, "netherite_sword", "mace", false);
        assert_eq!(tally.points, COOLDOWN_SWAP.points * SCALE);
        let mut tally = Tally::default();
        attack_swap(&mut tally, &plain, 10, "mace", "netherite_sword", false);
        assert_eq!(tally.count(), 0, "d'une arme lente vers une rapide : pas un swap utile");
    }

    #[test]
    fn deux_totems_coup_sur_coup_sans_mourir() {
        let mut tally = Tally::default();
        d_hand(&mut tally, &[100, 120], &[]);
        assert_eq!(tally.points, D_HAND.points * SCALE);
        assert_eq!(tally.found[0].0, 120, "au tick du deuxième totem");
        let mut tally = Tally::default();
        d_hand(&mut tally, &[100, 120], &[125]);
        assert_eq!(tally.count(), 0, "mort juste après : le deuxième n'a pas sauvé");
    }

    #[test]
    fn repeter_la_meme_technique_rapporte_de_moins_en_moins() {
        let mut tally = Tally::default();
        for t in 0..10 {
            tally.add(t, COOLDOWN_SWAP, 1.0);
        }
        let first = COOLDOWN_SWAP.points * SCALE;
        assert!(tally.points < 2.0 * first, "dix cooldown swaps valent moins de deux fois le premier : {}", tally.points);
        assert_eq!(tally.found[1].2, first / 2.0);
        // Une autre technique repart de sa pleine valeur.
        tally.add(20, STUN_SLAM, 1.0);
        assert_eq!(tally.found[10].2, STUN_SLAM.points * SCALE);
    }

    #[test]
    fn la_recharge_de_la_lance_suit_le_materiau() {
        assert_eq!(spear_recharge("netherite_spear"), Some(23));
        assert_eq!(spear_recharge("mace"), None);
    }
}
