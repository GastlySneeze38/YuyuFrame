//! D'un duel aux mesures : les opportunités de smash, puis les cinq familles
//! (docs/scoring/features.md). Chaque famille rend une valeur entre 0 et 1 et
//! le nombre d'événements sur lequel elle repose ; rien n'est encore jugé.
//!
//! L'unité est l'**opportunité** : une fenêtre de ticks où un joueur tombe
//! au-dessus de l'autre, assez près pour le frapper. Elle est géométrique —
//! on ne devine pas l'intention. La même définition sert dans les deux sens :
//! les miennes disent ce que j'exploite, celles de l'adversaire ce que je
//! laisse passer.
//!
//! Deux conditions du document d'origine ont été retirées en regardant de
//! vrais duels (2026-10-13) :
//! - **la masse en main** : les joueurs tiennent autre chose et passent à la
//!   masse au dernier tick ;
//! - **la charge d'attaque à 90 %** : trois smashs réussis sont partis à 36,
//!   45 et 84 %. Le bonus de chute ne dépend pas de la charge.

use super::parse::{Duel, Sample};

/// Un smash n'existe qu'au-delà de cette distance de chute.
const MIN_FALL: f64 = 1.5;
/// Rayon horizontal dans lequel la cible est à portée d'un joueur qui tombe.
const REACH: f64 = 3.5;
/// Deux ticks d'interruption ne coupent pas une fenêtre.
const GAP_TICKS: u64 = 2;
/// Le paquet de dégâts arrive quelques ticks après la fin de la fenêtre.
const HIT_DELAY_TICKS: u64 = 4;
/// Hauteur de chute d'un smash de référence, pour noter la qualité.
const REFERENCE_FALL: f64 = 8.0;
/// Écart de hauteur à partir duquel un joueur « tient la hauteur ».
const HEIGHT_ADVANTAGE: f64 = 1.5;
/// Hauteur au-dessus de l'adversaire qui vaut le maximum pour la famille
/// « prise de hauteur » : au-delà, monter plus haut ne rapporte plus.
const REFERENCE_HEIGHT: f64 = 12.0;
/// Une charge de vent est utile si une opportunité s'ouvre dans ce délai (3 s).
const WIND_USEFUL_TICKS: u64 = 60;

const X: usize = 1;
const Y: usize = 2;
const Z: usize = 3;
const VY: usize = 5;
const ME_FALL: usize = 11;
const ME_CHARGE: usize = 12;
const FOE_FLAGS: usize = 7;
const ON_GROUND: i64 = 1;

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
    /// Bonus de smash au point le plus proche de la cible.
    pub expected: f64,
    /// Bonus du coup porté ; 0 si l'opportunité est manquée.
    pub realized: f64,
    /// Décalage horizontal et vitesse horizontale de la cible à l'ouverture :
    /// gardés pour pondérer la conversion plus tard (docs/scoring/pistes.md).
    pub offset: f64,
    pub target_speed: f64,
}

/// Un tick vu du côté de l'attaquant.
struct View {
    t: u64,
    fall: f64,
    falling: bool,
    /// Hauteur de l'attaquant au-dessus de la cible.
    above: f64,
    horizontal: f64,
    distance: f64,
    target_speed: f64,
}

/// Les ticks du duel vus par l'un des deux joueurs. Pour nous, la distance
/// de chute est celle du jeu ; pour l'adversaire, que le client ne simule
/// pas, elle est reconstruite : la hauteur perdue depuis son dernier sommet.
fn views(samples: &[Sample], mine: bool) -> Vec<View> {
    let mut out = Vec::new();
    let mut peak = f64::NAN;
    let mut last_t = 0;
    let mut last_y = 0.0;
    for sample in samples {
        let Some(foe) = &sample.foe else { continue };
        let me = &sample.me;
        let (dx, dz) = (foe[X] - me[X], foe[Z] - me[Z]);
        let horizontal = (dx * dx + dz * dz).sqrt();
        let dy = me[Y] - foe[Y];
        let distance = (horizontal * horizontal + dy * dy).sqrt();
        let view = if mine {
            View {
                t: sample.t,
                fall: me[ME_FALL],
                falling: me[VY] < 0.0,
                above: dy,
                horizontal,
                distance,
                target_speed: (foe[4] * foe[4] + foe[6] * foe[6]).sqrt(),
            }
        } else {
            let grounded = foe[FOE_FLAGS] as i64 & ON_GROUND != 0;
            // Sa montée ou sa descente se lit dans ses positions, pas dans sa
            // vitesse : pour un autre joueur, le client ne tient pas celle-ci
            // à jour d'un tick à l'autre (vu le 2026-10-13 — avec elle, aucune
            // de ses descentes n'était reconnue).
            let continuous = !peak.is_nan() && sample.t - last_t <= GAP_TICKS;
            let rising = !continuous || foe[Y] >= last_y - 0.001;
            // Un trou dans les ticks (adversaire hors de portée) : on repart de zéro.
            if grounded || rising {
                peak = foe[Y];
            }
            last_t = sample.t;
            last_y = foe[Y];
            View {
                t: sample.t,
                fall: peak - foe[Y],
                falling: !grounded && !rising,
                above: -dy,
                horizontal,
                distance,
                target_speed: (me[4] * me[4] + me[6] * me[6]).sqrt(),
            }
        };
        out.push(view);
    }
    out
}

/// Les opportunités de l'un des deux joueurs. `smashes` : les ticks de ses
/// coups de masse confirmés.
fn opportunities(views: &[View], smashes: &[u64], density: f64) -> Vec<Opportunity> {
    let open = |v: &View| v.falling && v.fall >= MIN_FALL && v.above > 0.0 && v.horizontal <= REACH;
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
        let closest = window.iter().min_by(|a, b| a.distance.total_cmp(&b.distance)).unwrap_or(&views[start]);
        let (t0, t1) = (views[start].t, views[end].t);
        let hit = smashes.iter().position(|t| *t >= t0 && *t <= t1 + HIT_DELAY_TICKS);
        if let Some(index) = hit {
            used[index] = true;
        }
        // Le coup part avec la chute du dernier tick de la fenêtre : au tick
        // du paquet, le jeu l'a déjà remise à zéro.
        let realized = hit.map(|_| smash_bonus(views[end].fall, density)).unwrap_or(0.0);
        out.push(Opportunity {
            open: t0,
            close: t1,
            converted: hit.is_some(),
            expected: smash_bonus(closest.fall, density).max(realized),
            realized,
            offset: views[start].horizontal,
            target_speed: views[start].target_speed,
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
        out.push(Opportunity { open: *t, close: *t, converted: true, expected: bonus, realized: bonus, offset: 0.0, target_speed: 0.0 });
    }
    out.sort_by_key(|o| o.open);
    out
}

/// Une famille : sa valeur entre 0 et 1, et sur combien d'événements.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Measure {
    pub value: f64,
    pub count: f64,
}

impl Measure {
    const NONE: Measure = Measure { value: 0.0, count: 0.0 };

    fn rate(part: f64, whole: f64) -> Measure {
        if whole > 0.0 {
            Measure { value: (part / whole).clamp(0.0, 1.0), count: whole }
        } else {
            Measure::NONE
        }
    }
}

pub struct Features {
    pub mine: Vec<Opportunity>,
    pub theirs: Vec<Opportunity>,
    /// Secondes passées avec l'adversaire à portée d'engagement.
    pub engaged_secs: f64,
    /// Exploite-t-il ses ouvertures ? Part de mes opportunités converties.
    pub conversion: Measure,
    /// Quand il frappe, le coup est-il bon ? Bonus moyen de mes smashs,
    /// rapporté à celui d'une chute de référence.
    pub smash_quality: Measure,
    /// Laisse-t-il des ouvertures ? Part des opportunités adverses NON converties.
    pub exposure: Measure,
    /// Qui tient la hauteur ? Ma part du temps passé avec l'avantage de hauteur.
    pub vertical: Measure,
    /// Prend-il de la hauteur, et plus que l'adversaire ? Pour moitié la
    /// hauteur atteinte au-dessus de lui, pour moitié ma part de cette
    /// hauteur face à la sienne au-dessus de moi.
    pub height: Measure,
    /// Les ressources servent-elles ? Charges de vent suivies d'une
    /// opportunité, totems forcés face aux totems consommés.
    pub resources: Measure,
    /// Charge d'attaque moyenne à mes smashs : relevée, pas notée (le bonus
    /// de chute n'en dépend pas).
    pub smash_charge: Option<f64>,
}

pub fn extract(duel: &Duel) -> Features {
    let ticks = |by_me: bool| -> Vec<u64> { duel.hits.iter().filter(|h| h.smash && h.by_me == by_me).map(|h| h.t).collect() };
    let my_smashes = ticks(true);
    let mine = opportunities(&views(&duel.samples, true), &my_smashes, duel.my_density);
    let theirs = opportunities(&views(&duel.samples, false), &ticks(false), duel.foe_density);

    let converted = |list: &[Opportunity]| list.iter().filter(|o| o.converted).count() as f64;
    let conversion = Measure::rate(converted(&mine), mine.len() as f64);
    let exposure = Measure::rate(theirs.len() as f64 - converted(&theirs), theirs.len() as f64);

    let reference = smash_bonus(REFERENCE_FALL, duel.my_density);
    let landed: Vec<f64> = mine.iter().filter(|o| o.converted).map(|o| (o.realized / reference).min(1.0)).collect();
    let smash_quality = Measure::rate(landed.iter().sum(), landed.len() as f64);

    let engaged: Vec<&Sample> = duel.samples.iter().filter(|s| s.foe.is_some()).collect();
    let height = |s: &&Sample| s.me[Y] - s.foe.as_ref().map(|f| f[Y]).unwrap_or(s.me[Y]);
    let above = engaged.iter().filter(|s| height(s) > HEIGHT_ADVANTAGE).count() as f64;
    let below = engaged.iter().filter(|s| height(s) < -HEIGHT_ADVANTAGE).count() as f64;
    // Compté en tranches de quatre secondes : une minute de duel pèse autant
    // que quinze opportunités, pas que mille deux cents.
    let vertical = Measure { count: (above + below) / 80.0, ..Measure::rate(above, above + below) };

    // La hauteur prise : celle qu'on atteint au-dessus de l'adversaire dans
    // ses meilleurs moments (le neuvième décile, pour qu'un seul envol ne
    // fasse pas la mesure), et la même chose vue de son côté.
    let mut gaps: Vec<f64> = engaged.iter().map(height).collect();
    gaps.sort_by(f64::total_cmp);
    let decile = |from_top: bool| -> f64 {
        if gaps.is_empty() {
            return 0.0;
        }
        let index = (gaps.len() - 1) as f64 * 0.9;
        let index = if from_top { index.round() as usize } else { gaps.len() - 1 - index.round() as usize };
        if from_top { gaps[index].max(0.0) } else { (-gaps[index]).max(0.0) }
    };
    let (mine_high, theirs_high) = (decile(true), decile(false));
    let share = if mine_high + theirs_high > 0.0 { mine_high / (mine_high + theirs_high) } else { 0.5 };
    let height_value = 0.5 * (mine_high / REFERENCE_HEIGHT).min(1.0) + 0.5 * share;
    let height = Measure { value: height_value, count: engaged.len() as f64 / 80.0 };

    let winds: Vec<u64> = duel.uses.iter().filter(|(_, item)| item == "wind_charge").map(|(t, _)| *t).collect();
    let useful = winds.iter().filter(|t| mine.iter().any(|o| o.open >= **t && o.open - **t <= WIND_USEFUL_TICKS)).count() as f64;
    let (forced, spent) = (duel.foe_totems.len() as f64, duel.my_totems.len() as f64);
    let resources = Measure::rate(useful + forced, winds.len() as f64 + forced + spent);

    // La charge au moment du coup : le dernier tick avant que l'attaque ne la remette à zéro.
    let charges: Vec<f64> = my_smashes
        .iter()
        .filter_map(|t| duel.samples.iter().filter(|s| s.t <= *t && *t - s.t <= HIT_DELAY_TICKS).map(|s| s.me[ME_CHARGE]).reduce(f64::max))
        .collect();
    let smash_charge = (!charges.is_empty()).then(|| charges.iter().sum::<f64>() / charges.len() as f64);

    Features { engaged_secs: engaged.len() as f64 / 20.0, mine, theirs, conversion, smash_quality, exposure, vertical, height, resources, smash_charge }
}

#[cfg(test)]
mod tests {
    use super::*;

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

    fn view(t: u64, fall: f64, above: f64, horizontal: f64) -> View {
        View { t, fall, falling: fall > 0.0, above, horizontal, distance: (above * above + horizontal * horizontal).sqrt(), target_speed: 0.0 }
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
        assert_eq!(missed[0].expected, smash_bonus(7.0, 0.0), "valeur au point le plus proche");

        // Le paquet de dégâts arrive deux ticks après : la même fenêtre, convertie.
        let landed = opportunities(&falling, &[107], 0.0);
        assert_eq!(landed.len(), 1);
        assert!(landed[0].converted);
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
    fn deux_descentes_font_deux_opportunites() {
        let mut ticks: Vec<View> = (0..4).map(|i| view(i, 3.0, 2.0, 1.0)).collect();
        ticks.extend((0..10).map(|i| view(10 + i, 0.0, 0.0, 1.0)));
        ticks.extend((0..4).map(|i| view(40 + i, 3.0, 2.0, 1.0)));
        assert_eq!(opportunities(&ticks, &[42], 0.0).iter().map(|o| o.converted).collect::<Vec<_>>(), vec![false, true]);
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
