//! La masse (docs/scoring/familles/) : les opportunités de smash, puis les
//! cinq familles et le bonus bug use. Chaque famille est faite d'actions
//! jugées face à ce qu'en tire un joueur moyen (`features::measure`).
//!
//! L'unité est l'**opportunité** : une fenêtre de ticks où un joueur tombe
//! au-dessus de l'autre, assez près pour le frapper. Elle est géométrique —
//! on ne devine pas l'intention. La même définition sert dans les deux sens :
//! les miennes disent ce que j'exploite, celles de l'adversaire ce que je
//! laisse passer.
//!
//!   conversion  ce qu'il fait de ses ouvertures, selon leur difficulté et leur valeur
//!   smash       ce qu'il tire d'un gros coup (smash, charge de lance)
//!   control     contrôle vertical et horizontal : comment il se crée les ouvertures
//!   defense     bouclier, ouvertures laissées, placement, atterrissage, échanges
//!   resources   barre d'inventaire, vie, potions, totems, charges de vent, perles
//!   bug_use     les techniques les plus dures, en bonus
//!
//! Deux conditions du document d'origine ont été retirées en regardant de
//! vrais duels (2026-10-13) :
//! - **la masse en main** : les joueurs tiennent autre chose et passent à la
//!   masse au dernier tick ;
//! - **la charge d'attaque à 90 %** pour qu'une opportunité existe : trois
//!   smashs réussis sont partis à 36, 45 et 84 %. Le bonus de chute ne dépend
//!   pas de la charge (elle compte dans la qualité du smash, pas ici).

mod bug_use;
mod control;
mod conversion;
mod defense;
mod resources;
mod smash;

pub use bug_use::{BugUse, Detection};
pub use conversion::Split;
pub use smash::Smash;

// Ce que les familles de la masse prennent au reste du noyau : elles le
// lisent ici (`super::…`).
use super::super::parse::{gear_at, Duel, Gear, Sample, SLOT_CHEST, SLOT_HAND, SLOT_OFFHAND};
use super::detail::Detail;
use super::exchange::exchanges;
use super::measure::{family, logistic, single, Family, Item, Norm, Part, NO_CONTEXT};
use super::strike::{attack_tick, charge_factor, crit_heard};
use super::views::*;
use super::{Features, FoeMeasures};

/// Un smash n'existe qu'au-delà de cette distance de chute.
const MIN_FALL: f64 = 1.5;
/// Rayon horizontal dans lequel la cible est à portée d'un joueur qui tombe.
const REACH: f64 = 3.5;
/// Le paquet de dégâts arrive quelques ticks après la fin de la fenêtre.
const HIT_DELAY_TICKS: u64 = 4;
/// Écart de hauteur à partir duquel un joueur « tient la hauteur ».
const HEIGHT_ADVANTAGE: f64 = 1.5;
/// Hauteur de chute qui fait une « grosse » ouverture, et un gros coup du
/// premier coup (contrôle). Posée d'abord à 8 blocs, là où le bonus de la
/// masse change de rythme ; relevée le 2026-10-15 sur 346 ouvertures : la
/// médiane est à 6,8 blocs, 90 % sont sous 11,8 — 12 blocs n'est pas une
/// grosse chute. 14 blocs : les 5 % les plus hautes. *À recalculer* avec la
/// série de 50 duels.
const BIG_FALL: f64 = 14.0;
/// Vie sous laquelle un joueur est « bas » : environ 4 cœurs (ressources).
const LOW_HEALTH: f64 = 8.0;
/// Une fenêtre plus courte n'est pas une vraie ouverture : la cible ne fait
/// que traverser la zone (conversion). Posé à la main, *à calibrer* sur les
/// duels du joueur.
const MIN_WINDOW_TICKS: u64 = 3;
/// Taille d'un joueur debout : le smash ne peut plus attendre une fois les
/// pieds de l'attaquant à la hauteur de la tête de la cible.
const PLAYER_HEIGHT: f64 = 1.8;

/// Bonus de dégâts d'un smash après `fall` blocs de chute, avec `density`
/// niveaux de Densité. Relevé dans `MaceItem.getAttackDamageBonus` (jar 26.3,
/// 2026-10-13) et dans `density.json` : 4 par bloc jusqu'à 3, 2 par bloc
/// jusqu'à 8, 1 au-delà, plus 0,5 par niveau de Densité et par bloc.
pub fn smash_bonus(fall: f64, density: f64) -> f64 {
    if fall <= MIN_FALL {
        return 0.0;
    }
    let base = if fall <= 3.0 {
        4.0 * fall
    } else if fall <= 8.0 {
        12.0 + 2.0 * (fall - 3.0)
    } else {
        22.0 + (fall - 8.0)
    };
    base + 0.5 * density * fall
}

/// Une fenêtre où l'attaquant pouvait frapper la cible en tombant.
#[derive(Debug, Clone, PartialEq)]
pub struct Opportunity {
    pub open: u64,
    pub close: u64,
    pub converted: bool,
    /// Tick du paquet de dégâts du smash, s'il y en a un.
    pub hit: Option<u64>,
    /// Bonus de smash possible : celui de la plus grosse chute de la fenêtre.
    /// C'est le poids de l'opportunité dans la conversion.
    pub expected: f64,
    /// Bonus du coup porté ; 0 si l'opportunité est manquée.
    pub realized: f64,
    /// Plus grosse chute de la fenêtre, et chute au coup.
    pub fall: f64,
    pub hit_fall: f64,
    /// La chute qu'aurait eue le coup en attendant le plus possible : celle
    /// du coup plus ce qu'il restait à descendre jusqu'à la tête de la cible
    /// (qualité du smash). La fenêtre se ferme au coup, donc sa plus grosse
    /// chute est toujours celle du coup : sans ce prolongement, frapper trop
    /// tôt ne se verrait jamais. Égale à `fall` pour une opportunité ratée.
    pub possible_fall: f64,
    /// Décalage horizontal et vitesse horizontale de la cible à l'ouverture.
    pub offset: f64,
    pub target_speed: f64,
    /// La cible avait son bouclier levé, l'attaquant dans son dos.
    pub behind_shield: bool,
    /// Chance de réussite d'un joueur moyen sur cette situation (conversion).
    pub chance: f64,
}

/// Les opportunités de l'un des deux joueurs. `smashes` : les ticks de ses
/// coups de masse confirmés.
fn opportunities(views: &[View], smashes: &[u64], density: f64) -> Vec<Opportunity> {
    // Devant un bouclier levé, le coup ne pouvait pas passer : ce n'est pas
    // une opportunité, et ne pas frapper n'est pas un raté.
    let open = |v: &View| v.falling && v.fall >= MIN_FALL && v.above > 0.0 && v.horizontal <= REACH && !(v.shielded && v.front);
    let mut out: Vec<Opportunity> = Vec::new();
    let mut used = vec![false; smashes.len()];
    let mut i = 0;
    while i < views.len() {
        if !open(&views[i]) {
            i += 1;
            continue;
        }
        // La fenêtre court tant que la condition tient, à deux ticks près.
        let start = i;
        let mut end = i;
        let mut j = i + 1;
        while j < views.len() && views[j].t - views[end].t <= GAP_TICKS + 1 {
            if open(&views[j]) {
                end = j;
            }
            j += 1;
        }
        let window = &views[start..=end];
        let (t0, t1) = (views[start].t, views[end].t);
        let hit = smashes.iter().enumerate().position(|(k, t)| !used[k] && *t >= t0 && *t <= t1 + HIT_DELAY_TICKS);
        // Trop courte et ratée : la cible n'a fait que traverser la zone.
        if hit.is_none() && t1 - t0 + 1 < MIN_WINDOW_TICKS {
            i = end + 1;
            continue;
        }
        if let Some(index) = hit {
            used[index] = true;
        }
        let fall = window.iter().map(|v| v.fall).fold(0.0, f64::max);
        // Le coup part avec la chute du dernier tick de la fenêtre : au tick
        // du paquet, le jeu l'a déjà remise à zéro.
        let hit_fall = views[end].fall;
        let realized = hit.map(|_| smash_bonus(hit_fall, density)).unwrap_or(0.0);
        let left_to_fall = if hit.is_some() { (views[end].above - PLAYER_HEIGHT).max(0.0) } else { 0.0 };
        let behind_shield = window.iter().any(|v| v.shielded && !v.front);
        let (offset, target_speed) = (views[start].horizontal, views[start].target_speed);
        out.push(Opportunity {
            open: t0,
            close: t1,
            converted: hit.is_some(),
            hit: hit.map(|index| smashes[index]),
            expected: smash_bonus(fall, density).max(realized),
            realized,
            fall,
            hit_fall,
            possible_fall: fall.max(hit_fall + left_to_fall),
            offset,
            target_speed,
            behind_shield,
            chance: conversion::chance(offset, target_speed, t1 - t0 + 1, fall, behind_shield),
        });
        i = end + 1;
    }
    // Un smash confirmé hors de toute fenêtre (cible un peu plus loin que le
    // rayon, tick manquant) reste une opportunité convertie : la laisser de
    // côté ferait baisser la conversion de celui qui frappe juste.
    for (index, t) in smashes.iter().enumerate() {
        if used[index] {
            continue;
        }
        let fall = views.iter().filter(|v| v.t <= *t && *t - v.t <= HIT_DELAY_TICKS).map(|v| v.fall).fold(0.0, f64::max);
        let bonus = smash_bonus(fall, density);
        out.push(Opportunity {
            open: *t,
            close: *t,
            converted: true,
            hit: Some(*t),
            expected: bonus,
            realized: bonus,
            fall,
            hit_fall: fall,
            possible_fall: fall,
            offset: 0.0,
            target_speed: 0.0,
            behind_shield: false,
            chance: conversion::chance(0.0, 0.0, 1, fall, false),
        });
    }
    out.sort_by_key(|o| o.open);
    out
}

/// Une descente : des ticks d'affilée où l'attaquant tombe, sans toucher le
/// sol ni remonter. Indices de début et de fin dans les vues.
struct Descent {
    start: usize,
    end: usize,
}

fn descents(views: &[View]) -> Vec<Descent> {
    let mut out = Vec::new();
    let mut i = 0;
    while i < views.len() {
        if !views[i].falling {
            i += 1;
            continue;
        }
        let start = i;
        while i + 1 < views.len() && views[i + 1].falling && views[i + 1].t - views[i].t <= GAP_TICKS + 1 {
            i += 1;
        }
        if views[i].fall >= MIN_FALL {
            out.push(Descent { start, end: i });
        }
        i += 1;
    }
    out
}

/// Un duel à la masse : ses opportunités, ses cinq familles, le bonus.
pub(super) fn extract(duel: &Duel) -> Features {
    let ticks = |by_me: bool| -> Vec<u64> { duel.hits.iter().filter(|h| h.smash && h.by_me == by_me).map(|h| h.t).collect() };
    let (my_smashes, their_smashes) = (ticks(true), ticks(false));
    let my_views = views(&duel.samples, true);
    let their_views = views(&duel.samples, false);
    let mine = opportunities(&my_views, &my_smashes, duel.my_density);
    let theirs = opportunities(&their_views, &their_smashes, duel.foe_density);
    let ping = ping_ticks(&duel.samples);

    let conversion = conversion::family(&mine);
    let (smash_quality, mut smashes) = smash::mine(duel, &mine, &my_views, ping);
    let charges: Vec<f64> = smashes.iter().filter_map(|s| s.charge).collect();
    let smash_charge = (!charges.is_empty()).then(|| charges.iter().sum::<f64>() / charges.len() as f64);
    let lunges = control::lunges(duel, &my_views);
    let vertical = control::mine(duel, &my_views, &mine, &theirs, &lunges);
    let (exposure, avoided) = defense::mine(duel, &their_views, &mine, &theirs);
    let resources = resources::mine(duel, &my_views, &their_views, &mine);
    let (bug_use, mut detections) = bug_use::extract(duel, &my_views, &mine, &lunges, ping);
    // Les élans de Lunge aussi, rapportant ou non : leur reconnaissance se vérifie à l'œil.
    detections.extend(lunges.iter().map(|l| Detection { t: l.t, by_me: true, name: "lunge", points: 0.0 }));
    detections.sort_by_key(|d| d.t);

    let (foe_smash, foe_smashes) = smash::foe(duel, &theirs, &their_views);
    smashes.extend(foe_smashes);

    // Le détail de chaque famille, dans l'ordre du résumé.
    let mut details: [Detail; 5] = Default::default();
    conversion::detail(&mine, &theirs, &mut details[0]);
    smash::detail(duel, &smashes, mine.len(), &mut details[1]);
    defense::detail(duel, &my_views, &their_views, &mine, &theirs, &mut details[2]);
    control::detail(&my_views, &their_views, &mine, &theirs, &mut details[3]);
    resources::detail(duel, &my_views, &their_views, &mine, &mut details[4]);
    let [a, b, c, e] = [
        conversion::family(&theirs),
        foe_smash,
        defense::foe(duel, &my_views, &mine),
        control::foe(duel, &their_views, &theirs, &mine),
    ];
    let foe = FoeMeasures { conversion: a.measure, smash_quality: b.measure, exposure: c.measure, vertical: e.measure };

    Features {
        mode: duel.mode,
        evidence: mine.len() + theirs.len(),
        sword: None,
        details,
        smashes,
        detections,
        engaged_secs: super::engaged_secs(duel),
        split: Split::of(&mine),
        mine,
        theirs,
        conversion: conversion.measure,
        smash_quality: smash_quality.measure,
        exposure: exposure.measure,
        avoided,
        vertical: vertical.measure,
        resources: resources.measure,
        actions: [conversion.actions, smash_quality.actions, exposure.actions, vertical.actions, resources.actions],
        foe_actions: [a.actions, b.actions, c.actions, e.actions],
        bug_use,
        foe,
        smash_charge,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    pub(super) use super::super::views::tests::view;

    #[test]
    fn le_bonus_de_smash_suit_les_trois_paliers_du_jeu() {
        assert_eq!(smash_bonus(1.5, 0.0), 0.0, "pas de smash à 1,5 bloc ou moins");
        assert_eq!(smash_bonus(2.0, 0.0), 8.0);
        assert_eq!(smash_bonus(3.0, 0.0), 12.0);
        assert_eq!(smash_bonus(8.0, 0.0), 22.0);
        assert_eq!(smash_bonus(10.0, 0.0), 24.0);
        // Densité 5 : 2,5 de plus par bloc de chute.
        assert_eq!(smash_bonus(8.0, 5.0), 42.0);
    }

    #[test]
    fn une_chute_au_dessus_de_la_cible_ouvre_une_opportunite() {
        // Une descente de six ticks, la cible juste en dessous.
        let falling: Vec<View> = (0..6).map(|i| view(100 + i, 2.0 + i as f64, 6.0 - i as f64, 1.0)).collect();
        let missed = opportunities(&falling, &[], 0.0);
        assert_eq!(missed.len(), 1);
        assert!(!missed[0].converted);
        assert_eq!((missed[0].open, missed[0].close), (100, 105));
        assert_eq!(missed[0].realized, 0.0);
        assert_eq!(missed[0].expected, smash_bonus(7.0, 0.0), "valeur de la plus grosse chute de la fenêtre");
        assert!(missed[0].chance > 0.0 && missed[0].chance < 1.0);

        // Le paquet de dégâts arrive deux ticks après : la même fenêtre, convertie.
        let landed = opportunities(&falling, &[107], 0.0);
        assert_eq!(landed.len(), 1);
        assert!(landed[0].converted);
        assert_eq!(landed[0].hit, Some(107));
        assert_eq!(landed[0].realized, smash_bonus(7.0, 0.0));
    }

    #[test]
    fn ni_un_saut_sur_place_ni_une_cible_trop_loin_ne_comptent() {
        let hop: Vec<View> = (0..4).map(|i| view(i, 0.3 * i as f64, 1.0, 1.0)).collect();
        assert!(opportunities(&hop, &[], 0.0).is_empty(), "chute trop courte");
        let far: Vec<View> = (0..4).map(|i| view(i, 4.0, 3.0, REACH + 0.5)).collect();
        assert!(opportunities(&far, &[], 0.0).is_empty(), "cible hors de portée");
        let under: Vec<View> = (0..4).map(|i| view(i, 4.0, -2.0, 1.0)).collect();
        assert!(opportunities(&under, &[], 0.0).is_empty(), "l'attaquant est en dessous");
    }

    #[test]
    fn une_fenetre_trop_courte_et_ratee_est_ecartee() {
        let flash: Vec<View> = (0..2).map(|i| view(i, 4.0, 3.0, 1.0)).collect();
        assert!(opportunities(&flash, &[], 0.0).is_empty(), "la cible ne fait que traverser");
        assert_eq!(opportunities(&flash, &[2], 0.0).len(), 1, "convertie, elle compte");
    }

    #[test]
    fn devant_un_bouclier_leve_ce_n_est_pas_une_opportunite_dans_son_dos_si() {
        let shielded = |front: bool| -> Vec<View> {
            (0..5)
                .map(|i| {
                    let mut v = view(i, 4.0, 3.0, 1.0);
                    v.shielded = true;
                    v.front = front;
                    v
                })
                .collect()
        };
        assert!(opportunities(&shielded(true), &[], 0.0).is_empty(), "le coup ne pouvait pas passer");
        let behind = opportunities(&shielded(false), &[], 0.0);
        assert_eq!(behind.len(), 1);
        assert!(behind[0].behind_shield);
        let open = opportunities(&(0..5).map(|i| view(i, 4.0, 3.0, 1.0)).collect::<Vec<_>>(), &[], 0.0);
        assert!(behind[0].chance < open[0].chance, "dans son dos, c'est plus difficile");
    }

    #[test]
    fn deux_descentes_font_deux_opportunites() {
        let mut ticks: Vec<View> = (0..4).map(|i| view(i, 3.0, 2.0, 1.0)).collect();
        ticks.extend((0..10).map(|i| view(10 + i, 0.0, 0.0, 1.0)));
        ticks.extend((0..4).map(|i| view(40 + i, 3.0, 2.0, 1.0)));
        assert_eq!(opportunities(&ticks, &[42], 0.0).iter().map(|o| o.converted).collect::<Vec<_>>(), vec![false, true]);
        assert_eq!(descents(&ticks).len(), 2);
    }

    #[test]
    fn un_smash_hors_de_toute_fenetre_reste_une_opportunite_convertie() {
        let far: Vec<View> = (0..4).map(|i| view(i, 5.0, 3.0, REACH + 1.0)).collect();
        let found = opportunities(&far, &[4], 0.0);
        assert_eq!(found.len(), 1);
        assert!(found[0].converted);
        assert_eq!(found[0].realized, smash_bonus(5.0, 0.0));
    }
}
