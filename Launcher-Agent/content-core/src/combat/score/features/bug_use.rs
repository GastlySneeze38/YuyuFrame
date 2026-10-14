//! Bonus Bug use (docs/scoring/familles/bug-use.md) : le joueur maîtrise-t-il
//! les techniques les plus dures du PvP à la masse ? Les bugs de la famille
//! de l'attribute swap (MC-28289) et les techniques au tick près.
//!
//! **Il ne fait jamais baisser la note** : hors de la moyenne des familles,
//! il s'ajoute à la note, plafonné à +10. Une tentative ratée est ignorée.
//! Chez l'adversaire, ses techniques sont affichées, jamais comptées.

use super::super::parse::{gear_at, Duel, Gear, SLOT_CHEST, SLOT_HAND};
use super::control::{upward_teleport, Lunge};
use super::{flag, sample_at, smash_bonus, view_at, Opportunity, View, ME_FLAGS, ON_GROUND, Y};

/// Points de base de chaque technique réussie, selon sa rareté : posés au
/// jugé (décision du 2026-10-14), *à calibrer*.
const COOLDOWN_SWAP: f64 = 1.0;
const LUNGE_SWAP: f64 = 1.0;
const BREACH_SWAP: f64 = 2.0;
const DENSITY_SWAP: f64 = 2.0;
const REACH_SWAP: f64 = 2.0;
const ELYTRA_SWAP: f64 = 2.0;
const D_HAND: f64 = 3.0;
const STUN_SLAM: f64 = 4.0;
const INSTANT_PEARL_CATCH: f64 = 4.0;
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
/// Son swap : son objet en main change au plus ce nombre de ticks avant que son coup nous arrive.
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

#[derive(Default)]
struct Tally {
    points: f64,
    count: usize,
}

impl Tally {
    fn add(&mut self, base: f64, effect: f64) {
        self.points += base * effect.clamp(1.0, 2.0);
        self.count += 1;
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
        tally.add(BREACH_SWAP, effect);
    } else if level("density") > 0.0 {
        tally.add(DENSITY_SWAP, effect);
    } else {
        tally.add(COOLDOWN_SWAP, 1.0);
    }
}

/// Deux totems qui sautent coup sur coup, sans mourir.
fn d_hand(tally: &mut Tally, totems: &[u64], deaths: &[u64]) {
    let mut i = 0;
    while i + 1 < totems.len() {
        let (a, b) = (totems[i], totems[i + 1]);
        if b - a <= D_HAND_TICKS && !deaths.iter().any(|d| *d >= b && *d - b <= 20) {
            tally.add(D_HAND, 1.0);
            i += 2;
        } else {
            i += 1;
        }
    }
}

/// L'emplacement de plastron passe de l'élytre au plastron en plein vol.
fn elytra_swap(tally: &mut Tally, gear: &[Gear], airborne: impl Fn(u64) -> bool) {
    for w in gear.windows(2) {
        if w[0].item(SLOT_CHEST) == "elytra" && w[1].item(SLOT_CHEST).ends_with("_chestplate") && airborne(w[1].t) {
            tally.add(ELYTRA_SWAP, 1.0);
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
            tally.add(REACH_SWAP, 1.0);
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
            tally.add(LUNGE_SWAP, 1.0);
        }
        last = Some(lunge.t);
    }
    // Stun slam : son bouclier cassé par une hache, notre smash dans le tick suivant.
    let reference = smash_bonus(8.0, duel.my_density);
    for broke in duel.shield_sounds.iter().filter(|s| !s.on_me && s.broke) {
        if let Some(hit) = duel.hits.iter().find(|h| h.by_me && h.smash && h.t >= broke.t && h.t <= broke.t + STUN_SLAM_TICKS) {
            let bonus = mine.iter().find(|o| o.hit == Some(hit.t)).map(|o| o.realized).unwrap_or(0.0);
            tally.add(STUN_SLAM, 1.0 + (bonus / reference).min(1.0));
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
        tally.add(INSTANT_PEARL_CATCH, if leads { 1.0 + ((peak - y0) / PEARL_CATCH_HEIGHT).min(1.0) } else { 1.0 });
    }
    elytra_swap(&mut tally, &duel.my_gear, |t| sample_at(&duel.samples, t).is_some_and(|s| !flag(s.me[ME_FLAGS], ON_GROUND)));
    d_hand(&mut tally, &duel.my_totems, &duel.my_deaths);
    tally
}

/// Ses techniques, telles qu'on peut les voir : son objet en main qui change
/// pile au moment où il nous frappe, sa garde-robe en vol, ses totems. Ses
/// perles et charges de vent ne se voient pas.
fn foe(duel: &Duel, their_views: &[View]) -> Tally {
    let mut tally = Tally::default();
    for hit in duel.hits.iter().filter(|h| !h.by_me) {
        // Son objet en main a changé dans les ticks qui précèdent le paquet (ping compris).
        let change = duel.foe_gear.windows(2).find(|w| w[1].t + FOE_SWAP_TICKS >= hit.t && w[1].t <= hit.t && w[0].item(SLOT_HAND) != w[1].item(SLOT_HAND));
        if let Some(w) = change {
            attack_swap(&mut tally, &duel.foe_gear, w[1].t, w[0].item(SLOT_HAND), w[1].item(SLOT_HAND), hit.smash);
        }
    }
    for broke in duel.shield_sounds.iter().filter(|s| s.on_me && s.broke) {
        if duel.hits.iter().any(|h| !h.by_me && h.smash && h.t >= broke.t && h.t <= broke.t + STUN_SLAM_TICKS) {
            tally.add(STUN_SLAM, 1.0);
        }
    }
    elytra_swap(&mut tally, &duel.foe_gear, |t| view_at(their_views, t, 2).is_some_and(|v| !v.grounded));
    d_hand(&mut tally, &duel.foe_totems, &duel.foe_deaths);
    tally
}

pub fn extract(duel: &Duel, my_views: &[View], their_views: &[View], opportunities: &[Opportunity], lunges: &[Lunge], ping: u64) -> BugUse {
    // Tout repose sur l'objet au coup et l'équipement au tick près (version 2).
    if !duel.detailed() {
        return BugUse::default();
    }
    let (me, them) = (mine(duel, my_views, opportunities, lunges, ping), foe(duel, their_views));
    BugUse { points: me.points.min(CAP), techniques: me.count, foe_points: them.points.min(CAP), foe_techniques: them.count }
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
        assert_eq!((tally.points, tally.count), (BREACH_SWAP * 2.0, 1));
        let plain = vec![gear(10, "", "netherite_sword"), gear(11, "", "mace")];
        let mut tally = Tally::default();
        attack_swap(&mut tally, &plain, 10, "netherite_sword", "mace", false);
        assert_eq!(tally.points, COOLDOWN_SWAP);
        let mut tally = Tally::default();
        attack_swap(&mut tally, &plain, 10, "mace", "netherite_sword", false);
        assert_eq!(tally.count, 0, "d'une arme lente vers une rapide : pas un swap utile");
    }

    #[test]
    fn deux_totems_coup_sur_coup_sans_mourir() {
        let mut tally = Tally::default();
        d_hand(&mut tally, &[100, 120], &[]);
        assert_eq!(tally.points, D_HAND);
        let mut tally = Tally::default();
        d_hand(&mut tally, &[100, 120], &[125]);
        assert_eq!(tally.count, 0, "mort juste après : le deuxième n'a pas sauvé");
    }

    #[test]
    fn l_elytre_quittee_en_vol() {
        let list = vec![gear(10, "elytra", "mace"), gear(30, "netherite_chestplate", "mace")];
        let mut tally = Tally::default();
        elytra_swap(&mut tally, &list, |_| true);
        assert_eq!(tally.points, ELYTRA_SWAP);
        let mut tally = Tally::default();
        elytra_swap(&mut tally, &list, |_| false);
        assert_eq!(tally.count, 0, "au sol, ce n'est qu'un changement d'équipement");
    }

    #[test]
    fn la_recharge_de_la_lance_suit_le_materiau() {
        assert_eq!(spear_recharge("netherite_spear"), Some(23));
        assert_eq!(spear_recharge("mace"), None);
    }
}
