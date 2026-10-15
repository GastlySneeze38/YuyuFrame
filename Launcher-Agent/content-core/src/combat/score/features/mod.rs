//! D'un duel aux mesures : les opportunités de smash, puis les cinq familles
//! et le bonus bug use (docs/scoring/familles/). Chaque famille rend une
//! valeur entre 0 et 1 et le nombre d'événements sur lequel elle repose ;
//! rien n'est encore jugé.
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
//!   defense     bouclier, ouvertures laissées, placement, atterrissage
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
pub use smash::Smash;
pub use conversion::Split;

use super::parse::{Duel, Sample};

/// Un smash n'existe qu'au-delà de cette distance de chute.
const MIN_FALL: f64 = 1.5;
/// Rayon horizontal dans lequel la cible est à portée d'un joueur qui tombe.
const REACH: f64 = 3.5;
/// Deux ticks d'interruption ne coupent pas une fenêtre.
const GAP_TICKS: u64 = 2;
/// Le paquet de dégâts arrive quelques ticks après la fin de la fenêtre.
const HIT_DELAY_TICKS: u64 = 4;
/// Écart de hauteur à partir duquel un joueur « tient la hauteur ».
const HEIGHT_ADVANTAGE: f64 = 1.5;
/// Hauteur de chute qui sépare les grosses ouvertures des petites : là où le
/// bonus de la masse change de rythme (conversion, *à calibrer*).
const BIG_FALL: f64 = 8.0;
/// Vie sous laquelle un joueur est « bas » : environ 4 cœurs (ressources).
const LOW_HEALTH: f64 = 8.0;
/// Une fenêtre plus courte n'est pas une vraie ouverture : la cible ne fait
/// que traverser la zone (conversion). Posé à la main, *à calibrer* sur les
/// duels du joueur.
const MIN_WINDOW_TICKS: u64 = 3;
/// Taille d'un joueur debout : le smash ne peut plus attendre une fois les
/// pieds de l'attaquant à la hauteur de la tête de la cible.
const PLAYER_HEIGHT: f64 = 1.8;
/// Angle sous lequel un bouclier arrête un coup : 90° de part et d'autre du
/// regard (`BlocksAttacks.DamageReduction` du bouclier, jar 26.3).
const SHIELD_HALF_ANGLE_COS: f64 = 0.0;

// Les nombres d'un tick de notre joueur (record.rs) et d'un autre joueur.
const X: usize = 1;
const Y: usize = 2;
const Z: usize = 3;
const VX: usize = 4;
const VY: usize = 5;
const VZ: usize = 6;
const ME_YAW: usize = 7;
const ME_PITCH: usize = 8;
const ME_HEALTH: usize = 9;
const ME_MAX_HEALTH: usize = 10;
const ME_FALL: usize = 11;
const ME_CHARGE: usize = 12;
const ME_FLAGS: usize = 13;
const ME_PING: usize = 14;
const ME_FOOD: usize = 15;
const ME_GROUND: usize = 18;
const ME_STRENGTH: usize = 19;
const ME_SPEED: usize = 20;
const FOE_FLAGS: usize = 7;
const FOE_HEAD_YAW: usize = 8;
const FOE_GROUND: usize = 9;

// Les drapeaux (NativeCombat.FLAG_*).
const ON_GROUND: i64 = 1;
const SPRINTING: i64 = 1 << 1;
const GLIDING: i64 = 1 << 2;
const IN_WATER: i64 = 1 << 3;
const USING_ITEM: i64 = 1 << 4;
const PASSENGER: i64 = 1 << 7;
const BLOCKING: i64 = 1 << 8;

fn flag(flags: f64, which: i64) -> bool {
    flags >= 0.0 && flags as i64 & which != 0
}

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

/// Un tick vu du côté de l'attaquant.
struct View {
    t: u64,
    fall: f64,
    falling: bool,
    /// L'attaquant, la cible : au sol ?
    grounded: bool,
    target_grounded: bool,
    /// Hauteur de l'attaquant au-dessus de la cible.
    above: f64,
    horizontal: f64,
    distance: f64,
    /// Positions et vitesses (blocs par tick).
    pos: [f64; 3],
    target: [f64; 3],
    vel: [f64; 3],
    target_vel: [f64; 3],
    target_speed: f64,
    /// La cible tient son bouclier levé, et l'attaquant est devant elle.
    shielded: bool,
    front: bool,
    /// Hauteur de la cible au-dessus du sol (lecture du terrain), −1 si
    /// inconnue ou sans sol à portée de lecture.
    target_ground_height: f64,
    /// L'attaquant plane en élytre, tient un objet en cours d'usage, son regard horizontal.
    gliding: bool,
    using: bool,
    yaw: f64,
}

/// La direction du regard sur le plan horizontal (lacet du jeu, en degrés).
fn look(yaw: f64) -> (f64, f64) {
    let r = yaw.to_radians();
    (-r.sin(), r.cos())
}

/// L'attaquant est-il devant la cible qui regarde vers `yaw` ?
fn in_front(target: &[f64; 3], target_yaw: f64, attacker: &[f64; 3]) -> bool {
    let (lx, lz) = look(target_yaw);
    let (dx, dz) = (attacker[0] - target[0], attacker[2] - target[2]);
    let norm = (dx * dx + dz * dz).sqrt();
    norm < 1e-6 || (lx * dx + lz * dz) / norm >= SHIELD_HALF_ANGLE_COS
}

/// La vitesse de l'adversaire, lue dans ses positions : pour un autre joueur,
/// le client ne tient pas la sienne à jour d'un tick à l'autre (vu le
/// 2026-10-13 — avec elle, aucune de ses descentes n'était reconnue).
fn foe_velocities(samples: &[Sample]) -> Vec<[f64; 3]> {
    let mut out = Vec::with_capacity(samples.len());
    let mut last: Option<(u64, [f64; 3])> = None;
    for sample in samples {
        let Some(foe) = &sample.foe else {
            out.push([0.0; 3]);
            continue;
        };
        let pos = [foe[X], foe[Y], foe[Z]];
        let vel = match last {
            Some((t, prev)) if sample.t > t && sample.t - t <= GAP_TICKS => {
                let dt = (sample.t - t) as f64;
                [(pos[0] - prev[0]) / dt, (pos[1] - prev[1]) / dt, (pos[2] - prev[2]) / dt]
            }
            _ => [0.0; 3],
        };
        out.push(vel);
        last = Some((sample.t, pos));
    }
    out
}

/// Les ticks du duel vus par l'un des deux joueurs. Pour nous, la distance
/// de chute est celle du jeu ; pour l'adversaire, que le client ne simule
/// pas, elle est reconstruite : la hauteur perdue depuis son dernier sommet.
fn views(samples: &[Sample], mine: bool) -> Vec<View> {
    let foe_vel = foe_velocities(samples);
    let mut out = Vec::new();
    let mut peak = f64::NAN;
    let mut last_t = 0;
    let mut last_y = 0.0;
    for (sample, fv) in samples.iter().zip(&foe_vel) {
        let Some(foe) = &sample.foe else { continue };
        let me = &sample.me;
        let my_pos = [me[X], me[Y], me[Z]];
        let foe_pos = [foe[X], foe[Y], foe[Z]];
        let (dx, dz) = (foe[X] - me[X], foe[Z] - me[Z]);
        let horizontal = (dx * dx + dz * dz).sqrt();
        let dy = me[Y] - foe[Y];
        let distance = (horizontal * horizontal + dy * dy).sqrt();
        let my_grounded = flag(me[ME_FLAGS], ON_GROUND);
        let foe_grounded = flag(foe[FOE_FLAGS], ON_GROUND);
        // La tête de l'adversaire n'est connue que depuis la version 2 (−1 avant, et son bouclier n'est pas lu).
        let foe_shield = flag(foe[FOE_FLAGS], BLOCKING);
        let my_shield = flag(me[ME_FLAGS], BLOCKING);
        let view = if mine {
            View {
                t: sample.t,
                fall: me[ME_FALL],
                falling: me[VY] < 0.0 && !my_grounded,
                grounded: my_grounded,
                target_grounded: foe_grounded,
                above: dy,
                horizontal,
                distance,
                pos: my_pos,
                target: foe_pos,
                vel: [me[VX], me[VY], me[VZ]],
                target_vel: *fv,
                target_speed: (fv[0] * fv[0] + fv[2] * fv[2]).sqrt(),
                shielded: foe_shield,
                front: foe_shield && in_front(&foe_pos, foe[FOE_HEAD_YAW], &my_pos),
                target_ground_height: foe[FOE_GROUND],
                gliding: flag(me[ME_FLAGS], GLIDING),
                using: flag(me[ME_FLAGS], USING_ITEM),
                yaw: me[ME_YAW],
            }
        } else {
            // Sa montée ou sa descente se lit dans ses positions.
            let continuous = !peak.is_nan() && sample.t - last_t <= GAP_TICKS;
            let rising = !continuous || foe[Y] >= last_y - 0.001;
            // Un trou dans les ticks (adversaire hors de portée) : on repart de zéro.
            if foe_grounded || rising {
                peak = foe[Y];
            }
            last_t = sample.t;
            last_y = foe[Y];
            View {
                t: sample.t,
                fall: peak - foe[Y],
                falling: !foe_grounded && !rising,
                grounded: foe_grounded,
                target_grounded: my_grounded,
                above: -dy,
                horizontal,
                distance,
                pos: foe_pos,
                target: my_pos,
                vel: *fv,
                target_vel: [me[VX], me[VY], me[VZ]],
                target_speed: (me[VX] * me[VX] + me[VZ] * me[VZ]).sqrt(),
                shielded: my_shield,
                front: my_shield && in_front(&my_pos, me[ME_YAW], &foe_pos),
                target_ground_height: me[ME_GROUND],
                gliding: flag(foe[FOE_FLAGS], GLIDING),
                using: flag(foe[FOE_FLAGS], USING_ITEM),
                yaw: foe[FOE_HEAD_YAW],
            }
        };
        out.push(view);
    }
    out
}

/// Le dernier tick enregistré qui ne soit pas postérieur à `t`.
fn sample_at(samples: &[Sample], t: u64) -> Option<&Sample> {
    let index = samples.partition_point(|s| s.t <= t);
    index.checked_sub(1).map(|i| &samples[i])
}

/// La dernière vue qui ne soit pas postérieure à `t`, à `within` ticks près.
fn view_at(views: &[View], t: u64, within: u64) -> Option<&View> {
    let index = views.partition_point(|v| v.t <= t);
    index.checked_sub(1).map(|i| &views[i]).filter(|v| t - v.t <= within)
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

/// Une mesure partielle : sa valeur entre 0 et 1 et le nombre d'événements
/// qui la font. Absente (`None`) quand rien ne la mesure dans ce duel.
type Part = Option<(f64, f64)>;

/// La moyenne de scores entre 0 et 1, et leur nombre ; rien sans score.
fn mean(scores: impl IntoIterator<Item = f64>) -> Part {
    let (sum, n) = scores.into_iter().fold((0.0, 0.0), |(s, n), v| (s + v.clamp(0.0, 1.0), n + 1.0));
    (n > 0.0).then(|| (sum / n, n))
}

/// Des parties pondérées. Une partie absente ne compte pas : son poids se
/// répartit sur les autres (décision du 2026-10-14). Rend la valeur et le
/// nombre d'événements de toutes les parties présentes.
fn blend(parts: &[(f64, Part)]) -> Part {
    let present: Vec<(f64, f64, f64)> = parts.iter().filter_map(|(w, p)| p.map(|(v, n)| (*w, v, n))).collect();
    let weight: f64 = present.iter().map(|(w, _, _)| w).sum();
    (weight > 0.0).then(|| (present.iter().map(|(w, v, _)| w * v).sum::<f64>() / weight, present.iter().map(|(_, _, n)| n).sum()))
}

/// Recentre une partie sur ce que font en moyenne les joueurs observés :
/// `center` y vaut 0,5, et 0 et 1 restent en place (deux droites).
///
/// Calibration provisoire du 2026-10-15 (`SCORE_VERSION` 6) : certaines
/// parties valaient 0,2 ou 0,8 pour TOUS les joueurs, adversaires compris —
/// ce n'était pas un niveau, c'était leur échelle. Sans recentrage, leur
/// seule présence déplaçait la famille (le bouclier faisait baisser la
/// défense des deux joueurs dès qu'il existait). Les centres sont les
/// moyennes relevées sur 26 duels notés, nous et nos adversaires réunis
/// quand la partie se mesure des deux côtés ; *à recalculer* avec la série
/// de 50 duels.
fn centered(part: Part, center: f64) -> Part {
    part.map(|(v, n)| {
        let v = v.clamp(0.0, 1.0);
        (if v <= center { 0.5 * v / center } else { 0.5 + 0.5 * (v - center) / (1.0 - center) }, n)
    })
}

/// Les parties de chaque famille avant recentrage, sous leur nom : ce que
/// relève l'outil de calibration (`score::calibration`).
#[derive(Debug, Clone, Default)]
pub struct Raw(pub Vec<(&'static str, Option<f64>)>);

impl Raw {
    /// Retient une partie et la rend telle quelle.
    fn put(&mut self, name: &'static str, part: Part) -> Part {
        self.0.push((name, part.map(|(v, _)| v)));
        part
    }
}

/// Le détail d'une famille, pour la fenêtre qui s'ouvre au clic sur elle
/// (2026-10-15) : des faits que le joueur peut vérifier de mémoire — des
/// compteurs, des moments datés, la même mesure chez l'adversaire —, jamais
/// le calcul (ni poids, ni centre, ni référence). Le but : qu'en le lisant,
/// il ne conteste pas sa note. Les codes sont traduits côté Java
/// (`PvpFamilyDetail`) ; les nombres sont ceux qui ont fait la note.
#[derive(Debug, Clone, Default)]
pub struct Detail {
    pub facts: Vec<Fact>,
    pub moments: Vec<Moment>,
    pub versus: Vec<Versus>,
}

/// Un fait : il a fait monter (`up`) ou baisser la famille ; `weight` dit
/// combien, pour ne montrer que les plus forts.
#[derive(Debug, Clone, PartialEq)]
pub struct Fact {
    pub code: &'static str,
    pub up: bool,
    pub weight: f64,
    pub values: Vec<f64>,
}

/// Un moment du duel, au tick près ; `weight` dit lesquels montrer d'abord.
#[derive(Debug, Clone, PartialEq)]
pub struct Moment {
    pub t: u64,
    pub code: &'static str,
    pub good: bool,
    pub weight: f64,
    pub values: Vec<f64>,
}

/// La même mesure chez nous et chez lui — seulement quand elle existe de son côté.
#[derive(Debug, Clone, PartialEq)]
pub struct Versus {
    pub code: &'static str,
    pub mine: Vec<f64>,
    pub theirs: Vec<f64>,
}

impl Detail {
    fn fact(&mut self, code: &'static str, up: bool, weight: f64, values: &[f64]) {
        self.facts.push(Fact { code, up, weight: weight.abs(), values: values.to_vec() });
    }

    fn moment(&mut self, t: u64, code: &'static str, good: bool, weight: f64, values: &[f64]) {
        self.moments.push(Moment { t, code, good, weight, values: values.to_vec() });
    }

    fn versus(&mut self, code: &'static str, mine: &[f64], theirs: &[f64]) {
        self.versus.push(Versus { code, mine: mine.to_vec(), theirs: theirs.to_vec() });
    }
}

/// Une famille faite de parties : sa valeur combinée, comptée sur `events`
/// vraies situations (2026-10-15). Avant, le nombre d'événements était la
/// somme de celui des parties, dont certaines comptent des tranches de temps :
/// 100 à 300 « événements » par duel, et le lissage n'agissait plus.
fn family(combined: Part, events: f64) -> Measure {
    measure(combined.map(|(value, _)| (value, events)))
}

fn measure(part: Part) -> Measure {
    part.map(|(value, count)| Measure { value, count }).unwrap_or(Measure::NONE)
}

pub struct Features {
    pub mine: Vec<Opportunity>,
    pub theirs: Vec<Opportunity>,
    /// Secondes passées avec l'adversaire à portée d'engagement.
    pub engaged_secs: f64,
    /// Exploite-t-il ses ouvertures, compte tenu de leur difficulté et de
    /// leur valeur ? 0,5 : comme un joueur moyen sur les mêmes ouvertures.
    pub conversion: Measure,
    /// Grosses et petites ouvertures, à part (affiché, hors note).
    pub split: Split,
    /// Ce qu'il tire de ses gros coups : dégâts tirés ÷ dégâts possibles.
    pub smash_quality: Measure,
    /// La défense : bouclier, ouvertures laissées, temps vulnérable,
    /// esquive, atterrissage, reprise après un coup.
    pub exposure: Measure,
    /// Part des dégâts adverses neutralisés (affiché, hors note).
    pub avoided: Option<f64>,
    /// Contrôle vertical et horizontal : suivre la chute, prendre la
    /// hauteur, se placer, se déplacer, enchaîner.
    pub vertical: Measure,
    /// Les objets au bon moment, pour une bonne raison.
    pub resources: Measure,
    /// Le bonus des techniques les plus dures, et ce qu'on voit des siennes.
    pub bug_use: BugUse,
    /// Les mêmes mesures vues du côté de l'adversaire : son niveau, tel
    /// qu'on peut le lire de ce qu'il a fait contre nous.
    pub foe: FoeMeasures,
    /// Charge d'attaque moyenne à mes smashs, telle que le jeu la compte au coup.
    pub smash_charge: Option<f64>,
    /// Le détail de mes cinq familles, pour la fenêtre qui s'ouvre au clic.
    pub details: [Detail; 5],
    // Pour l'outil de calibration (`score::calibration`) : les parties des
    // familles avant recentrage, de chaque côté ; chaque smash jugé, des deux
    // côtés ; chaque technique et chaque élan de Lunge reconnus. L'outil n'est
    // compilé que pour les tests : ailleurs, personne ne les lit.
    #[cfg_attr(not(test), allow(dead_code))]
    pub raw_mine: Raw,
    #[cfg_attr(not(test), allow(dead_code))]
    pub raw_foe: Raw,
    #[cfg_attr(not(test), allow(dead_code))]
    pub smashes: Vec<Smash>,
    #[cfg_attr(not(test), allow(dead_code))]
    pub detections: Vec<Detection>,
}

/// Ce qu'on sait mesurer de l'adversaire : quatre des cinq familles. Ses
/// ressources ne se voient presque pas d'ici (seulement ses totems).
pub struct FoeMeasures {
    pub conversion: Measure,
    pub smash_quality: Measure,
    pub exposure: Measure,
    pub vertical: Measure,
}

/// Le ping moyen en ticks, pour relier un coup donné à son paquet de dégâts.
fn ping_ticks(samples: &[Sample]) -> u64 {
    let pings: Vec<f64> = samples.iter().map(|s| s.me[ME_PING]).filter(|p| *p >= 0.0).collect();
    if pings.is_empty() {
        return 2;
    }
    ((pings.iter().sum::<f64>() / pings.len() as f64) / 50.0).ceil() as u64
}

pub fn extract(duel: &Duel) -> Features {
    let ticks = |by_me: bool| -> Vec<u64> { duel.hits.iter().filter(|h| h.smash && h.by_me == by_me).map(|h| h.t).collect() };
    let (my_smashes, their_smashes) = (ticks(true), ticks(false));
    let my_views = views(&duel.samples, true);
    let their_views = views(&duel.samples, false);
    let mine = opportunities(&my_views, &my_smashes, duel.my_density);
    let theirs = opportunities(&their_views, &their_smashes, duel.foe_density);
    let ping = ping_ticks(&duel.samples);

    let (mut raw_mine, mut raw_foe) = (Raw::default(), Raw::default());
    let conversion = conversion::measure(&mine, true);
    let (smash_quality, mut smashes) = smash::mine(duel, &mine, &my_views, ping);
    let charges: Vec<f64> = smashes.iter().filter_map(|s| s.charge).collect();
    let smash_charge = (!charges.is_empty()).then(|| charges.iter().sum::<f64>() / charges.len() as f64);
    let lunges = control::lunges(duel, &my_views);
    let vertical = control::mine(duel, &my_views, &mine, &lunges, &mut raw_mine);
    let (exposure, avoided) = defense::mine(duel, &their_views, &mine, &theirs, &mut raw_mine);
    let resources = resources::mine(duel, &my_views, &their_views, &mine, &mut raw_mine);
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
    let foe = FoeMeasures {
        conversion: conversion::measure(&theirs, true),
        smash_quality: foe_smash,
        exposure: defense::foe(duel, &my_views, &mine, &mut raw_foe),
        vertical: control::foe(duel, &their_views, &theirs, &mut raw_foe),
    };

    let engaged = duel.samples.iter().filter(|s| s.foe.is_some()).count();
    Features {
        details,
        raw_mine,
        raw_foe,
        smashes,
        detections,
        engaged_secs: engaged as f64 / 20.0,
        split: Split::of(&mine),
        mine,
        theirs,
        conversion,
        smash_quality,
        exposure,
        avoided,
        vertical,
        resources,
        bug_use,
        foe,
        smash_charge,
    }
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

    pub(super) fn view(t: u64, fall: f64, above: f64, horizontal: f64) -> View {
        View {
            t,
            fall,
            falling: fall > 0.0,
            grounded: fall <= 0.0,
            target_grounded: true,
            above,
            horizontal,
            distance: (above * above + horizontal * horizontal).sqrt(),
            pos: [0.0, above, 0.0],
            target: [horizontal, 0.0, 0.0],
            vel: [0.0; 3],
            target_vel: [0.0; 3],
            target_speed: 0.0,
            shielded: false,
            front: false,
            target_ground_height: -1.0,
            gliding: false,
            using: false,
            yaw: 0.0,
        }
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
    fn le_regard_dit_qui_est_devant() {
        // Lacet 0 : le jeu regarde vers +Z.
        let target = [0.0, 0.0, 0.0];
        assert!(in_front(&target, 0.0, &[0.0, 0.0, 3.0]));
        assert!(!in_front(&target, 0.0, &[0.0, 0.0, -3.0]));
        assert!(in_front(&target, 90.0, &[-3.0, 0.0, 0.0]), "lacet 90 : vers −X");
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

    #[test]
    fn une_partie_absente_laisse_son_poids_aux_autres() {
        assert_eq!(blend(&[(0.3, Some((1.0, 2.0))), (0.7, None)]), Some((1.0, 2.0)));
        assert_eq!(blend(&[(0.5, Some((1.0, 1.0))), (0.5, Some((0.0, 3.0)))]), Some((0.5, 4.0)));
        assert_eq!(blend(&[(1.0, None)]), None);
        assert_eq!(mean([]), None);
    }

    #[test]
    fn le_recentrage_met_la_moyenne_observee_a_cinquante() {
        let at = |v: f64, c: f64| centered(Some((v, 1.0)), c).unwrap().0;
        assert!((at(0.2, 0.2) - 0.5).abs() < 1e-12);
        assert_eq!((at(0.0, 0.2), at(1.0, 0.2)), (0.0, 1.0), "les bouts restent en place");
        assert!((at(0.6, 0.2) - 0.75).abs() < 1e-12);
        assert_eq!(centered(None, 0.2), None);
    }
}
