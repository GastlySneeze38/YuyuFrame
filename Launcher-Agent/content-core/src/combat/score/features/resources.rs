//! Famille Ressources (docs/scoring/familles/ressources.md) : le joueur
//! utilise-t-il ses objets au bon moment, pour une bonne raison, et les
//! a-t-il en main quand il le faut ?
//!
//! Elle ne mesure pas combien une charge de vent ou une perle a déplacé le
//! joueur (c'est le contrôle vertical) : seulement si l'utilisation était
//! justifiée et utile. Mesurée du côté du joueur : les objets de
//! l'adversaire ne se voient pas.

use super::super::parse::{gear_at, Duel, Gear, SLOT_CHEST, SLOT_HAND, SLOT_OFFHAND};
use super::{
    blend, centered, family, flag, mean, sample_at, Raw, view_at, Measure, Opportunity, Part, View, GAP_TICKS, HEIGHT_ADVANTAGE, HIT_DELAY_TICKS, LOW_HEALTH, ME_FALL, ME_FLAGS, ME_FOOD,
    ME_HEALTH, ME_MAX_HEALTH, ME_SPEED, ME_STRENGTH, ON_GROUND, REACH, X, Y, Z,
};

// Poids des mesures, posés au jugé (décision du 2026-10-14), *à calibrer*.
const W_WIND_AND_PEARLS: f64 = 0.30;
const W_HOTBAR: f64 = 0.20;
const W_FOOD_AND_LIFE: f64 = 0.20;
const W_TOTEMS: f64 = 0.15;
const W_POTIONS: f64 = 0.10;
const W_ROCKETS: f64 = 0.05;

// Ce que valent ces mesures en moyenne (calibration provisoire du
// 2026-10-15, voir `centered`). Rien ne se mesure chez l'adversaire : les
// centres viennent de NOS duels seuls (26 notés, 4 en version 2 pour la
// barre d'inventaire et le délai des totems). Tant que d'autres joueurs ne
// sont pas mesurés, 50 veut dire « comme d'habitude », pas « comme un
// joueur moyen ». Recalés le même soir sur 23 duels : charges de vent et
// perles 0,32 (20), nourriture et vie 0,80 (23), délai des totems 0,10 (16).
const WIND_AND_PEARLS_CENTER: f64 = 0.32;
const HOTBAR_CENTER: f64 = 0.53;
const FOOD_AND_LIFE_CENTER: f64 = 0.80;
const TOTEM_REFILL_CENTER: f64 = 0.10;

/// Masse en main : au-delà de 10 ticks après l'ouverture, c'est trop tard.
const MACE_LATE_TICKS: f64 = 10.0;
/// Charge de vent en main après l'atterrissage, pomme quand on est bas.
const WIND_LATE_TICKS: f64 = 20.0;
const APPLE_LATE_TICKS: f64 = 40.0;
/// Entre deux objets, les ticks passés sur la barre.
const SWITCH_LATE_TICKS: f64 = 10.0;
/// Une pomme « juste après un totem » : dans les 3 s.
const AFTER_TOTEM_TICKS: u64 = 60;
/// Manger ou boire prend 1,6 s de vulnérabilité.
const EAT_TICKS: u64 = 32;
/// Barre de faim : sous 18 la vie ne se régénère plus seule, sous 7 le
/// Lunge ne se déclenche plus (sous 6 on ne sprinte plus).
const FOOD_REGEN: f64 = 18.0;
const FOOD_LUNGE: f64 = 7.0;
/// Remonter au-dessus de la vie basse : au-delà de 10 s, ça ne vaut plus rien.
const HEAL_TICKS: f64 = 200.0;
/// Au contact : à moins de 6 blocs.
const CONTACT: f64 = 6.0;
/// Reprendre une potion : au-delà de 10 s après que l'effet tombe, c'est tard.
const RENEW_TICKS: f64 = 200.0;
/// Remettre un totem : au-delà de 2 s, c'est tard.
const TOTEM_LATE_TICKS: f64 = 40.0;
/// Une charge de vent qui crée une ouverture le fait dans les 3 s
/// (*à calibrer*, ce qui existait).
const USEFUL_TICKS: u64 = 60;
/// Fuir, toucher l'adversaire : dans les 2 s.
const REACTION_TICKS: u64 = 40;
/// Une chute de plus de 4 blocs fait des dégâts ; annulée si l'on atterrit
/// ensuite sans dégâts de chute.
const HARMFUL_FALL: f64 = 4.0;
const LANDING_TICKS: u64 = 80;
/// Un déplacement plus grand en un tick est une téléportation.
const TELEPORT_BLOCKS: f64 = 3.0;

fn is_golden_apple(item: &str) -> bool {
    item == "golden_apple" || item == "enchanted_golden_apple"
}

fn is_food(item: &str) -> bool {
    is_golden_apple(item)
        || matches!(
            item,
            "apple" | "bread" | "cooked_beef" | "cooked_porkchop" | "cooked_mutton" | "cooked_chicken" | "cooked_rabbit" | "cooked_cod"
                | "cooked_salmon" | "baked_potato" | "golden_carrot" | "carrot" | "pumpkin_pie" | "mushroom_stew" | "rabbit_stew"
                | "beetroot_soup" | "suspicious_stew" | "sweet_berries" | "glow_berries" | "melon_slice" | "cookie" | "honey_bottle"
                | "dried_kelp" | "chorus_fruit"
        )
}

fn is_potion(item: &str) -> bool {
    item.ends_with("potion")
}

/// Le premier tick de `[from, to]` où la case tient un objet qui convient.
fn first_held(duel: &Duel, slot: usize, from: u64, to: u64, wanted: impl Fn(&str) -> bool) -> Option<u64> {
    first_held_in(&duel.my_gear, slot, from, to, wanted)
}

fn first_held_in(gear: &[Gear], slot: usize, from: u64, to: u64, wanted: impl Fn(&str) -> bool) -> Option<u64> {
    if gear_at(gear, from).is_some_and(|g| wanted(g.item(slot))) {
        return Some(from);
    }
    gear.iter().filter(|g| g.t > from && g.t <= to).find(|g| wanted(g.item(slot))).map(|g| g.t)
}

/// Après chaque totem qui saute (sans mourir juste après) : (tick, ticks
/// pour en remettre un dans l'autre main, `None` s'il n'est jamais revenu).
/// La main secondaire se vide d'abord — un totem tenu dans la main
/// principale ne laisse pas de trou, il n'est pas compté.
fn refills(gear: &[Gear], totems: &[u64], deaths: &[u64]) -> Vec<(u64, Option<u64>)> {
    totems
        .iter()
        .filter(|t| !deaths.iter().any(|d| *d >= **t && *d - **t <= 10))
        .filter_map(|t| {
            let emptied = gear.iter().find(|g| g.t >= *t && g.t <= t + 20 && g.item(SLOT_OFFHAND) != "totem_of_undying")?.t;
            Some((*t, first_held_in(gear, SLOT_OFFHAND, emptied, t + 200, |i| i == "totem_of_undying").map(|r| r - t)))
        })
        .collect()
}

fn late(ticks: u64, limit: f64) -> f64 {
    1.0 - (ticks as f64 / limit).min(1.0)
}

/// L'adversaire est-il au-dessus et à portée à `t` (la position où on se fait smasher) ?
fn threatened(theirs: &[View], t: u64) -> bool {
    view_at(theirs, t, GAP_TICKS).is_some_and(|v| v.above > HEIGHT_ADVANTAGE && v.horizontal <= REACH)
}

/// Manger ou boire à `t` : était-on à l'abri pendant les 1,6 s ?
fn safe_meal(theirs: &[View], t: u64) -> f64 {
    let exposed = theirs.iter().any(|v| v.t >= t && v.t <= t + EAT_TICKS && v.above > HEIGHT_ADVANTAGE && v.horizontal <= REACH);
    (!exposed) as u8 as f64
}

// --- 1. La barre d'inventaire : le bon objet au bon moment -----------------------

fn hotbar(duel: &Duel, mine: &[Opportunity]) -> Part {
    if !duel.detailed() {
        return None;
    }
    // Masse à l'ouverture : le bon joueur tient autre chose et passe à la masse au dernier moment.
    let mace = mean(mine.iter().map(|o| {
        first_held(duel, SLOT_HAND, o.open, o.close + HIT_DELAY_TICKS, |i| i == "mace").map(|t| late(t - o.open, MACE_LATE_TICKS)).unwrap_or(0.0)
    }));
    // Charge de vent en main après l'atterrissage, si le joueur en utilise.
    let uses_wind = duel.uses.iter().any(|(_, i)| i == "wind_charge");
    let wind = if uses_wind {
        let landings = duel.samples.windows(2).filter(|w| {
            w[1].t - w[0].t <= GAP_TICKS && !flag(w[0].me[ME_FLAGS], ON_GROUND) && w[0].me[ME_FALL] >= 2.0 && flag(w[1].me[ME_FLAGS], ON_GROUND)
        });
        mean(landings.filter_map(|w| first_held(duel, SLOT_HAND, w[1].t, w[1].t + USEFUL_TICKS, |i| i == "wind_charge").map(|t| late(t - w[1].t, WIND_LATE_TICKS))))
    } else {
        None
    };
    // Pomme d'or en main quand on passe sous 4 cœurs, si le joueur en mange.
    let eats_apples = duel.uses.iter().any(|(_, i)| is_golden_apple(i));
    let apple = if eats_apples {
        let drops = duel.samples.windows(2).filter(|w| w[0].me[ME_HEALTH] >= LOW_HEALTH && w[1].me[ME_HEALTH] < LOW_HEALTH && w[1].me[ME_HEALTH] > 0.0);
        mean(drops.map(|w| first_held(duel, SLOT_HAND, w[1].t, w[1].t + 100, is_golden_apple).map(|t| late(t - w[1].t, APPLE_LATE_TICKS)).unwrap_or(0.0)))
    } else {
        None
    };
    // Entre deux objets utilisés à la suite, les ticks passés sur la barre.
    let switches = mean(duel.uses.windows(2).filter(|w| w[0].1 != w[1].1 && w[1].0 - w[0].0 <= 100).filter_map(|w| {
        let left = duel.my_gear.iter().find(|g| g.t > w[0].0 && g.item(SLOT_HAND) != w[0].1)?.t;
        let arrived = first_held(duel, SLOT_HAND, left, w[1].0, |i| i == w[1].1)?;
        Some(late(arrived - left, SWITCH_LATE_TICKS))
    }));
    blend(&[(1.0, mace), (1.0, wind), (1.0, apple), (1.0, switches)])
}

// --- 2. La nourriture et la vie ------------------------------------------------------

fn food_and_life(duel: &Duel, theirs: &[View]) -> Part {
    let health = |t: u64| sample_at(&duel.samples, t).map(|s| (s.me[ME_HEALTH], s.me[ME_MAX_HEALTH]));
    // Pomme d'or sous environ 4 cœurs, ou juste après un totem (toujours bonne), plutôt qu'à pleine vie.
    let apples = mean(duel.uses.iter().filter(|(_, i)| is_golden_apple(i)).filter_map(|(t, _)| {
        let (hp, max) = health(*t)?;
        let after_totem = duel.my_totems.iter().any(|p| *p <= *t && t - p <= AFTER_TOTEM_TICKS);
        Some(if hp < LOW_HEALTH || after_totem { 1.0 } else { 1.0 - ((hp - LOW_HEALTH) / (max - LOW_HEALTH).max(1.0)).clamp(0.0, 1.0) })
    }));
    let meals = mean(duel.uses.iter().filter(|(_, i)| is_food(i)).map(|(t, _)| safe_meal(theirs, *t)));
    let engaged: Vec<_> = duel.samples.iter().filter(|s| s.foe.is_some()).collect();
    // La barre de faim (journal de version 2).
    let fed: Vec<f64> = engaged
        .iter()
        .map(|s| s.me[ME_FOOD])
        .filter(|f| *f >= 0.0)
        .map(|f| if f >= FOOD_REGEN { 1.0 } else if f >= FOOD_LUNGE { 0.5 } else { 0.0 })
        .collect();
    let hunger = (!fed.is_empty()).then(|| (fed.iter().sum::<f64>() / fed.len() as f64, fed.len() as f64 / 80.0));
    // Le temps passé bas en vie, et le temps pour remonter.
    let low = engaged.iter().filter(|s| s.me[ME_HEALTH] > 0.0 && s.me[ME_HEALTH] < LOW_HEALTH).count() as f64;
    let low_time = (!engaged.is_empty()).then(|| (1.0 - low / engaged.len() as f64, engaged.len() as f64 / 80.0));
    let mut climbs = Vec::new();
    let mut since: Option<u64> = None;
    for s in &duel.samples {
        let hp = s.me[ME_HEALTH];
        match since {
            None if hp > 0.0 && hp < LOW_HEALTH => since = Some(s.t),
            Some(start) if hp >= LOW_HEALTH => {
                climbs.push(late(s.t - start, HEAL_TICKS));
                since = None;
            }
            _ => {}
        }
    }
    if since.is_some() {
        climbs.push(0.0);
    }
    blend(&[(1.0, apples), (1.0, meals), (1.0, hunger), (1.0, blend(&[(1.0, low_time), (1.0, mean(climbs))]))])
}

// --- 3. Les potions : force et vitesse seulement -------------------------------------------

fn potions(duel: &Duel, mine: &[View], theirs: &[View]) -> Part {
    let effect = |s: &super::super::parse::Sample| s.me[ME_STRENGTH] > 0.0 || s.me[ME_SPEED] > 0.0;
    // Si aucune potion n'est utilisée, la partie ne compte pas : un kit sans
    // potions n'est pas pénalisé. Des effets sans potion bue viennent du
    // serveur (vu sur Minemen le 2026-10-15 : force et vitesse II données
    // par le kit, effacées par chaque totem) et ne disent rien du joueur.
    if !duel.uses.iter().any(|(_, i)| is_potion(i)) {
        return None;
    }
    let contact: Vec<_> = if duel.detailed() { mine.iter().filter(|v| v.horizontal <= CONTACT).filter_map(|v| sample_at(&duel.samples, v.t)).collect() } else { Vec::new() };
    let active = (!contact.is_empty()).then(|| (contact.iter().filter(|s| effect(s)).count() as f64 / contact.len() as f64, contact.len() as f64 / 80.0));
    // Reprendre la potion avant que l'effet tombe, pas longtemps après.
    let mut renewals = Vec::new();
    if duel.detailed() {
        for index in [ME_STRENGTH, ME_SPEED] {
            for (i, w) in duel.samples.windows(2).enumerate() {
                if w[0].me[index] > 0.0 && w[1].me[index] == 0.0 {
                    let back = duel.samples[i + 1..].iter().find(|s| s.me[index] > 0.0);
                    renewals.push(back.map(|s| late(s.t - w[1].t, RENEW_TICKS)).unwrap_or(0.0));
                }
            }
        }
    }
    let safe = mean(duel.uses.iter().filter(|(_, i)| i == "potion").map(|(t, _)| safe_meal(theirs, *t)));
    blend(&[(1.0, active), (1.0, mean(renewals)), (1.0, safe)])
}

// --- 4. Les totems -------------------------------------------------------------------------------

fn totems(duel: &Duel, raw: &mut Raw) -> Part {
    let refill = if duel.detailed() {
        mean(refills(&duel.my_gear, &duel.my_totems, &duel.my_deaths).into_iter().map(|(_, delay)| delay.map(|d| late(d, TOTEM_LATE_TICKS)).unwrap_or(0.0)))
    } else {
        None
    };
    let (forced, spent) = (duel.foe_totems.len() as f64, duel.my_totems.len() as f64);
    let balance = (forced + spent > 0.0).then(|| (forced / (forced + spent), forced + spent));
    // Le bilan forcés / consommés vaut 0,5 en moyenne par nature : seul le délai est recentré.
    blend(&[(1.0, centered(raw.put("resources.totem_refill", refill), TOTEM_REFILL_CENTER)), (1.0, raw.put("resources.totem_balance", balance))])
}

// --- 5. Les charges de vent et les perles --------------------------------------------------------

/// Le tick d'une téléportation de notre joueur dans les `ticks` qui suivent `from`.
fn teleport(duel: &Duel, from: u64, ticks: u64) -> Option<u64> {
    duel.samples.windows(2).find_map(|w| {
        let (a, b) = (&w[0].me, &w[1].me);
        let moved = ((b[X] - a[X]).powi(2) + (b[Y] - a[Y]).powi(2) + (b[Z] - a[Z]).powi(2)).sqrt();
        (w[0].t >= from && w[1].t <= from + ticks && w[1].t - w[0].t <= GAP_TICKS && moved >= TELEPORT_BLOCKS).then_some(w[1].t)
    })
}

/// Ce qu'une charge de vent ou une perle a fait : (utile, efficace).
fn reason(duel: &Duel, t: u64, item: &str, mine_views: &[View], theirs: &[View], mine: &[Opportunity]) -> (bool, bool) {
    // Monter pour attaquer : une opportunité du joueur s'ouvre dans les 3 s.
    let opening = mine.iter().find(|o| o.open > t && o.open - t <= USEFUL_TICKS);
    // Casser son smash : il tombait sur le joueur, et sa descente finit hors de portée sans smash.
    let broke = view_at(theirs, t, GAP_TICKS).filter(|v| v.falling && v.above > 0.0 && v.horizontal <= REACH + 2.0).is_some_and(|_| {
        let end = theirs.iter().skip_while(|v| v.t < t).take_while(|v| v.falling).last();
        let smashed = duel.hits.iter().any(|h| !h.by_me && h.smash && h.t >= t && end.is_none_or(|e| h.t <= e.t + HIT_DELAY_TICKS));
        end.is_some_and(|e| e.horizontal > REACH) && !smashed
    });
    // Fuir : bas en vie ou sous lui, et hors de portée ensuite.
    let low = sample_at(&duel.samples, t).is_some_and(|s| s.me[ME_HEALTH] > 0.0 && s.me[ME_HEALTH] < LOW_HEALTH);
    let fled = (low || threatened(theirs, t))
        && mine_views.iter().any(|v| v.t > t && v.t <= t + REACTION_TICKS && (v.horizontal > REACH + 1.0 || v.above > HEIGHT_ADVANTAGE));
    let (useful, effective) = if item == "wind_charge" {
        // Toucher l'adversaire : la charge le frappe et le repousse.
        let hit = duel.hits.iter().any(|h| h.by_me && h.kind == "wind_charge" && h.t >= t && h.t <= t + REACTION_TICKS);
        // Annuler sa chute : une chute qui allait faire mal, puis un atterrissage sans dégâts.
        let cancelled = sample_at(&duel.samples, t).is_some_and(|s| s.me[ME_FALL] >= HARMFUL_FALL && !flag(s.me[ME_FLAGS], ON_GROUND))
            && !duel.my_falls.iter().any(|f| *f > t && *f <= t + LANDING_TICKS);
        (opening.is_some() || broke || fled || hit || cancelled, opening.is_some_and(|o| o.converted) || broke || hit || cancelled)
    } else {
        // Perle utile : la téléportation met au-dessus de lui ou hors de danger.
        let landed = teleport(duel, t, USEFUL_TICKS).and_then(|at| mine_views.iter().find(|v| v.t > at));
        let placed = landed.is_some_and(|v| v.above > HEIGHT_ADVANTAGE || v.horizontal > REACH + 2.0);
        (opening.is_some() || broke || fled || placed, opening.is_some_and(|o| o.converted) || broke || placed)
    };
    (useful, effective)
}

fn wind_and_pearls(duel: &Duel, mine_views: &[View], theirs: &[View], mine: &[Opportunity]) -> Part {
    let uses: Vec<&(u64, String)> = duel.uses.iter().filter(|(_, i)| i == "wind_charge" || i == "ender_pearl").collect();
    if uses.is_empty() {
        return None;
    }
    let reasons: Vec<(bool, bool)> = uses.iter().map(|(t, item)| reason(duel, *t, item, mine_views, theirs, mine)).collect();
    // Efficience : la part des utilisations utiles — le spam est pénalisé
    // naturellement, chaque charge sans raison comptant comme gâchée.
    let efficiency = mean(reasons.iter().map(|(useful, _)| *useful as u8 as f64));
    // Efficacité : ce qu'elles ont apporté.
    let efficacy = mean(reasons.iter().map(|(_, effective)| *effective as u8 as f64));
    // Recharges : quand il est au-dessus et à portée, s'en servir plutôt que d'attendre.
    let mut answered = Vec::new();
    let mut i = 0;
    while i < theirs.len() {
        if !(theirs[i].above > HEIGHT_ADVANTAGE && theirs[i].horizontal <= REACH) {
            i += 1;
            continue;
        }
        let start = theirs[i].t;
        while i < theirs.len() && theirs[i].above > HEIGHT_ADVANTAGE && theirs[i].horizontal <= REACH {
            i += 1;
        }
        let end = theirs[i - 1].t;
        // Un passage d'un tick n'est pas une menace qu'on a le temps de traiter.
        if end > start {
            answered.push(uses.iter().any(|(t, _)| *t >= start && *t <= end) as u8 as f64);
        }
    }
    blend(&[(1.0, efficiency), (1.0, efficacy), (1.0, mean(answered))])
}

// --- 6. Les fusées d'élytre --------------------------------------------------------------------

fn rockets(duel: &Duel, mine: &[Opportunity]) -> Part {
    // Ne compte que si le joueur porte une élytre.
    if !duel.my_gear.iter().any(|g| g.item(SLOT_CHEST) == "elytra") {
        return None;
    }
    mean(duel.uses.iter().filter(|(_, i)| i == "firework_rocket").map(|(t, _)| mine.iter().any(|o| o.open > *t && o.open - t <= USEFUL_TICKS) as u8 as f64))
}

/// Le détail des ressources : charges de vent et perles utiles ou gâchées,
/// masse en main à temps, pommes au bon moment, délai pour remettre un totem ;
/// les charges gâchées et les totems remis comme moments ; ses totems en face.
pub fn detail(duel: &Duel, mine_views: &[View], theirs: &[View], mine: &[Opportunity], d: &mut super::Detail) {
    let weight = |part: Part, center: f64, w: f64| centered(part, center).map(|(v, _)| (v - 0.5) * w);
    let uses: Vec<&(u64, String)> = duel.uses.iter().filter(|(_, i)| i == "wind_charge" || i == "ender_pearl").collect();
    if let Some(w) = weight(wind_and_pearls(duel, mine_views, theirs, mine), WIND_AND_PEARLS_CENTER, W_WIND_AND_PEARLS) {
        let reasons: Vec<bool> = uses.iter().map(|(t, item)| reason(duel, *t, item, mine_views, theirs, mine).0).collect();
        let useful = reasons.iter().filter(|r| **r).count() as f64;
        d.fact("resources.useful", w >= 0.0, w, &[useful, reasons.len() as f64]);
        for ((t, item), useful) in uses.iter().zip(&reasons) {
            if !useful {
                d.moment(*t, "resources.wasted", false, 1.0, &[(item == "ender_pearl") as u8 as f64]);
            }
        }
    }
    if let Some(w) = weight(hotbar(duel, mine), HOTBAR_CENTER, W_HOTBAR) {
        // À temps : la masse en main au plus tard à mi-chemin du délai qui ne vaut plus rien.
        let ready = mine
            .iter()
            .filter(|o| first_held(duel, SLOT_HAND, o.open, o.close + HIT_DELAY_TICKS, |i| i == "mace").is_some_and(|t| t - o.open <= (MACE_LATE_TICKS / 2.0) as u64))
            .count() as f64;
        if !mine.is_empty() {
            d.fact("resources.mace_ready", w >= 0.0, w, &[ready, mine.len() as f64]);
        }
    }
    let apples: Vec<bool> = duel
        .uses
        .iter()
        .filter(|(_, i)| is_golden_apple(i))
        .filter_map(|(t, _)| {
            let hp = sample_at(&duel.samples, *t)?.me[ME_HEALTH];
            Some(hp < LOW_HEALTH || duel.my_totems.iter().any(|p| *p <= *t && t - p <= AFTER_TOTEM_TICKS))
        })
        .collect();
    if !apples.is_empty() {
        let good = apples.iter().filter(|a| **a).count() as f64;
        let share = good / apples.len() as f64;
        d.fact("resources.apples", share >= 0.5, (share - 0.5) * W_FOOD_AND_LIFE, &[good, apples.len() as f64]);
    }
    if duel.detailed() {
        let mine_refills = refills(&duel.my_gear, &duel.my_totems, &duel.my_deaths);
        let seconds = |list: &[(u64, Option<u64>)]| {
            let delays: Vec<f64> = list.iter().filter_map(|(_, d)| *d).map(|d| d as f64 / 20.0).collect();
            (!delays.is_empty()).then(|| (delays.iter().sum::<f64>() / delays.len() as f64 * 10.0).round() / 10.0)
        };
        if let (Some(s), Some(w)) = (seconds(&mine_refills), weight(mean(mine_refills.iter().map(|(_, d)| d.map(|d| late(d, TOTEM_LATE_TICKS)).unwrap_or(0.0))), TOTEM_REFILL_CENTER, W_TOTEMS)) {
            d.fact("resources.totem_refill", w >= 0.0, w, &[s]);
            for (t, delay) in &mine_refills {
                let s = delay.map(|d| (d as f64 / 2.0).round() / 10.0).unwrap_or(-1.0);
                d.moment(*t, "resources.totem", delay.is_some_and(|d| d <= TOTEM_LATE_TICKS as u64 / 2), delay.map(|d| d as f64).unwrap_or(200.0), &[s]);
            }
            // Ses totems se voient aussi : son équipement à chaque changement.
            if let Some(theirs_s) = seconds(&refills(&duel.foe_gear, &duel.foe_totems, &duel.foe_deaths)) {
                d.versus("resources.totem_refill", &[s], &[theirs_s]);
            }
        }
    }
}

pub fn mine(duel: &Duel, mine_views: &[View], theirs: &[View], mine: &[Opportunity], raw: &mut Raw) -> Measure {
    let combined = blend(&[
        (W_WIND_AND_PEARLS, centered(raw.put("resources.wind_and_pearls", wind_and_pearls(duel, mine_views, theirs, mine)), WIND_AND_PEARLS_CENTER)),
        (W_HOTBAR, centered(raw.put("resources.hotbar", hotbar(duel, mine)), HOTBAR_CENTER)),
        (W_FOOD_AND_LIFE, centered(raw.put("resources.food_and_life", food_and_life(duel, theirs)), FOOD_AND_LIFE_CENTER)),
        (W_TOTEMS, totems(duel, raw)),
        (W_POTIONS, raw.put("resources.potions", potions(duel, mine_views, theirs))),
        (W_ROCKETS, raw.put("resources.rockets", rockets(duel, mine))),
    ]);
    // Les situations : chaque objet utilisé et chaque totem qui saute, des deux côtés.
    let events = duel.uses.len() + duel.my_totems.len() + duel.foe_totems.len();
    family(combined, events as f64)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn les_objets_se_reconnaissent() {
        assert!(is_golden_apple("enchanted_golden_apple") && !is_golden_apple("apple"));
        assert!(is_food("golden_apple") && is_food("cooked_beef") && !is_food("wind_charge"));
        assert!(is_potion("potion") && is_potion("splash_potion") && !is_potion("totem_of_undying"));
        assert_eq!(late(0, 10.0), 1.0);
        assert_eq!(late(20, 10.0), 0.0);
    }
}
