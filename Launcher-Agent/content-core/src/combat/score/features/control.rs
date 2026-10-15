//! Famille Contrôle vertical et horizontal
//! (docs/scoring/familles/controle-vertical-et-horizontal.md) : le joueur
//! se déplace-t-il pour mettre le coup ? On juge le déplacement obtenu —
//! hauteur, placement, distance rattrapée — quelle que soit la façon de
//! l'obtenir : un combo perle + charge de vent qui n'amène nulle part ne
//! rapporte rien, un joueur sans élytre n'est pas pénalisé de ne pas planer.
//!
//! Cinq groupes : C suivre pendant la chute (le cœur), A prendre la hauteur,
//! B se placer au sommet, D se déplacer à l'horizontale, E enchaîner et
//! reconstruire. Chez l'adversaire, A, B, C et E se mesurent en miroir.

use super::super::parse::{gear_at, Duel, Gear, SLOT_HAND};
use super::{blend, centered, descents, family, mean, Measure, Opportunity, Part, Raw, View, BIG_FALL, GAP_TICKS, HEIGHT_ADVANTAGE, HIT_DELAY_TICKS, MIN_FALL, REACH, Y};

// Poids des groupes (décision du 2026-10-14), *à calibrer*.
const W_FOLLOW: f64 = 0.35;
const W_CLIMB: f64 = 0.20;
const W_PEAK: f64 = 0.15;
const W_HORIZONTAL: f64 = 0.15;
const W_CHAIN: f64 = 0.15;

// Ce que valent les groupes en moyenne (calibration provisoire du
// 2026-10-15, voir `centered`) : nous et nos adversaires réunis sur 52
// relevés ; le déplacement horizontal, mesuré chez nous seuls, sur 26.
const FOLLOW_CENTER: f64 = 0.43;
const CLIMB_CENTER: f64 = 0.71;
const PEAK_CENTER: f64 = 0.82;
const HORIZONTAL_CENTER: f64 = 0.39;
const CHAIN_CENTER: f64 = 0.34;

/// Une descente qui commence plus loin ne vise personne (*à calibrer*).
const NEAR_DESCENT: f64 = 10.0;
/// Une montée de moins de 3 blocs n'est qu'un saut.
const MIN_CLIMB: f64 = 3.0;
/// Vitesse de montée qui vaut le maximum, en blocs par seconde, en armure
/// sans résistance au recul (*à calibrer*).
const CLIMB_REFERENCE: f64 = 10.0;
/// Hauteur qui vaut le maximum pour une charge de vent seule, et pour un
/// combo perle + charge de vent (*à vérifier* dans nos journaux : les vidéos
/// parlent d'environ 20 blocs).
const WIND_HEIGHT: f64 = 8.0;
const COMBO_HEIGHT: f64 = 20.0;
/// Une perle et une charge de vent à ce nombre de ticks d'écart font un combo.
const COMBO_GAP_TICKS: u64 = 5;
/// Un déplacement plus grand en un tick est une téléportation.
const TELEPORT_BLOCKS: f64 = 3.0;
/// Délai au-delà duquel reprendre la hauteur ne vaut plus rien (5 s).
const RETAKE_TICKS: f64 = 100.0;
/// Au sommet, au-delà de cette distance horizontale, le placement ne vaut rien.
const PEAK_FAR: f64 = 10.0;
/// Au-delà de cette distance, on n'est pas en train d'engager.
const ENGAGE_RANGE: f64 = 16.0;
/// Un élan de Lunge : au moins ce gain de vitesse horizontale en un tick
/// (0,458 bloc par tick au niveau I, `lunge.json`), dans l'axe du regard.
const LUNGE_JUMP: f64 = 0.35;
const LUNGE_ALIGN: f64 = 0.8;
/// Portée de la lance (`AttackRange` de `Item.Properties.spear`, jar 26.3).
const SPEAR_REACH: f64 = 4.5;
/// Un élan qui met à portée le fait dans la seconde.
const LUNGE_REACH_TICKS: u64 = 20;
/// Une opportunité qui suit un vol plané d'au plus une seconde lui doit quelque chose.
const GLIDE_FOLLOW_TICKS: u64 = 20;
/// Un enchaînement : la nouvelle opportunité s'ouvre dans les 5 s, sans toucher le sol.
const CHAIN_TICKS: u64 = 100;
/// Reconstruction : au-delà de 10 s pour retrouver une opportunité, elle ne vaut rien.
const REBUILD_TICKS: f64 = 200.0;

/// Ce que permet l'armure : la résistance au recul de la netherite (0,1 par
/// pièce) et la Protection contre les explosions réduisent la hauteur d'une
/// charge de vent (wiki). À geste égal, un joueur en netherite monte moins
/// haut : on le juge par rapport à ce que son armure permet.
fn armor_factor(gear: Option<&Gear>) -> f64 {
    gear.map(|g| ((1.0 - 0.1 * g.netherite_pieces()) * (1.0 - 0.15 * g.blast_protection())).max(0.3)).unwrap_or(1.0)
}

fn horizontal_dir(v: &View) -> Option<(f64, f64)> {
    let (dx, dz) = (v.target[0] - v.pos[0], v.target[2] - v.pos[2]);
    let h = (dx * dx + dz * dz).sqrt();
    (h > 1e-6).then(|| (dx / h, dz / h))
}

// --- C. Suivre pendant la chute -------------------------------------------

/// Descentes qui arrivent à portée, distance rattrapée, gros coup du premier coup.
fn follow(views: &[View], smashes: &[u64]) -> Part {
    let mut reached = Vec::new();
    let (mut caught, mut caught_weight, mut caught_n) = (0.0, 0.0, 0.0);
    let mut first_time = Vec::new();
    for d in descents(views) {
        let span = &views[d.start..=d.end];
        let (s, e) = (&views[d.start], &views[d.end]);
        if s.above > 0.0 && s.horizontal < NEAR_DESCENT {
            let arrived = span.iter().any(|v| v.above > 0.0 && v.horizontal <= REACH && v.fall >= MIN_FALL);
            reached.push(arrived as u8 as f64);
            if s.horizontal > REACH {
                // Rattraper compte surtout quand l'adversaire fuit.
                let flee = horizontal_dir(s).map(|(x, z)| s.target_vel[0] * x + s.target_vel[2] * z).unwrap_or(0.0);
                let weight = 1.0 + 10.0 * flee.max(0.0);
                let score = ((s.horizontal - e.horizontal) / (s.horizontal - REACH)).clamp(0.0, 1.0);
                caught += weight * score;
                caught_weight += weight;
                caught_n += 1.0;
            }
        }
        let biggest = span.iter().map(|v| v.fall).fold(0.0, f64::max);
        if biggest > BIG_FALL {
            first_time.push(smashes.iter().any(|t| *t >= s.t && *t <= e.t + HIT_DELAY_TICKS) as u8 as f64);
        }
    }
    let caught = (caught_weight > 0.0).then(|| (caught / caught_weight, caught_n));
    blend(&[(1.0, mean(reached)), (1.0, caught), (1.0, mean(first_time))])
}

// --- A. Prendre la hauteur ------------------------------------------------

/// Les montées de l'attaquant : (gain en blocs, durée en secondes).
fn climbs(views: &[View]) -> Vec<(f64, f64)> {
    let mut out = Vec::new();
    let mut i = 1;
    while i < views.len() {
        let rising = |k: usize| views[k].pos[1] > views[k - 1].pos[1] + 0.01 && views[k].t - views[k - 1].t <= GAP_TICKS + 1;
        if !rising(i) {
            i += 1;
            continue;
        }
        let start = i - 1;
        while i < views.len() && rising(i) {
            i += 1;
        }
        let end = i - 1;
        let gain = views[end].pos[1] - views[start].pos[1];
        if gain >= MIN_CLIMB {
            out.push((gain, ((views[end].t - views[start].t) as f64 / 20.0).max(0.05)));
        }
    }
    out
}

/// Le temps pour passer de « il est au-dessus de moi » à « je suis au-dessus de lui ».
fn retake(views: &[View]) -> Part {
    let mut scores = Vec::new();
    let mut since: Option<u64> = None;
    for v in views {
        match since {
            None if v.above < -HEIGHT_ADVANTAGE => since = Some(v.t),
            Some(start) if v.above > HEIGHT_ADVANTAGE => {
                scores.push(1.0 - ((v.t - start) as f64 / RETAKE_TICKS).min(1.0));
                since = None;
            }
            _ => {}
        }
    }
    if let (Some(start), Some(last)) = (since, views.last()) {
        scores.push(1.0 - ((last.t - start) as f64 / RETAKE_TICKS).min(1.0));
    }
    mean(scores)
}

/// Écart de hauteur à partir duquel la hauteur prise vaut le maximum.
const REFERENCE_HEIGHT: f64 = 12.0;

/// La domination de la hauteur (ce qui existait avant) : pour moitié la part
/// du temps passé avec l'avantage, un quart pour la hauteur prise au-dessus
/// de l'autre (son neuvième décile, plafonnée à `REFERENCE_HEIGHT`), un quart
/// pour sa part de cette hauteur face à celle de l'autre.
fn domination(views: &[View]) -> Part {
    let mut gaps: Vec<f64> = views.iter().map(|v| v.above).collect();
    if gaps.is_empty() {
        return None;
    }
    gaps.sort_by(f64::total_cmp);
    let above = gaps.iter().filter(|g| **g > HEIGHT_ADVANTAGE).count() as f64;
    let below = gaps.iter().filter(|g| **g < -HEIGHT_ADVANTAGE).count() as f64;
    let time = if above + below > 0.0 { above / (above + below) } else { 0.5 };
    let tenth = ((gaps.len() - 1) as f64 * 0.1).round() as usize;
    let high = gaps[gaps.len() - 1 - tenth].max(0.0);
    let their_high = (-gaps[tenth]).max(0.0);
    let share = if high + their_high > 0.0 { high / (high + their_high) } else { 0.5 };
    // Compté en tranches de quatre secondes.
    Some((0.5 * time + 0.25 * (high / REFERENCE_HEIGHT).min(1.0) + 0.25 * share, gaps.len() as f64 / 80.0))
}

/// La hauteur gagnée par charge de vent, et par combo perle + charge de vent
/// (une perle et une charge à quelques ticks d'écart, suivies d'une
/// téléportation vers le haut). Notre joueur seulement : les objets de
/// l'adversaire ne se voient pas.
fn height_per_resource(duel: &Duel, armor: f64) -> Part {
    let y_at = |t: u64| super::sample_at(&duel.samples, t).map(|s| s.me[Y]);
    let peak = |from: u64, ticks: u64| duel.samples.iter().filter(|s| s.t >= from && s.t <= from + ticks).map(|s| s.me[Y]).fold(f64::MIN, f64::max);
    let pearls: Vec<u64> = duel.uses.iter().filter(|(_, i)| i == "ender_pearl").map(|(t, _)| *t).collect();
    let mut scores = Vec::new();
    for (t, _) in duel.uses.iter().filter(|(_, i)| i == "wind_charge") {
        let Some(y0) = y_at(*t) else { continue };
        let combo = pearls.iter().find(|p| p.abs_diff(*t) <= COMBO_GAP_TICKS).filter(|p| upward_teleport(duel, (**p).min(*t), 60).is_some());
        let (window, reference) = if combo.is_some() { (60, COMBO_HEIGHT) } else { (30, WIND_HEIGHT) };
        scores.push(((peak(*t, window) - y0) / (reference * armor)).clamp(0.0, 1.0));
    }
    mean(scores)
}

/// Le tick d'une téléportation vers le haut de notre joueur dans les `ticks` qui suivent `from`.
pub(super) fn upward_teleport(duel: &Duel, from: u64, ticks: u64) -> Option<u64> {
    duel.samples.windows(2).find_map(|w| {
        let (a, b) = (&w[0], &w[1]);
        let moved = ((b.me[1] - a.me[1]).powi(2) + (b.me[2] - a.me[2]).powi(2) + (b.me[3] - a.me[3]).powi(2)).sqrt();
        (a.t >= from && b.t <= from + ticks && b.t - a.t <= GAP_TICKS && moved >= TELEPORT_BLOCKS && b.me[2] > a.me[2]).then_some(b.t)
    })
}

fn climb(views: &[View], armor: f64, resources: Part) -> Part {
    let speed = mean(climbs(views).iter().map(|(gain, secs)| gain / secs / (CLIMB_REFERENCE * armor)));
    blend(&[(1.0, speed), (1.0, retake(views)), (1.0, resources), (1.0, domination(views))])
}

// --- B. Se placer au sommet ------------------------------------------------

fn peaks(views: &[View]) -> Part {
    let scores = views.windows(2).filter(|w| !w[0].falling && w[1].falling && !w[0].grounded && w[0].above > 0.0).map(|w| {
        1.0 - ((w[0].horizontal - REACH) / (PEAK_FAR - REACH)).clamp(0.0, 1.0)
    });
    mean(scores)
}

// --- D. Se déplacer à l'horizontale (notre joueur) ----------------------------

/// Un élan de Lunge, et s'il a mis à portée.
pub struct Lunge {
    pub t: u64,
    pub reached: bool,
}

/// Les élans de Lunge de notre joueur : une saute de vitesse horizontale
/// dans la direction du regard, lance en main, même sans toucher personne.
pub fn lunges(duel: &Duel, views: &[View]) -> Vec<Lunge> {
    if !duel.detailed() {
        return Vec::new();
    }
    let mut out = Vec::new();
    for w in views.windows(2) {
        let (a, b) = (&w[0], &w[1]);
        if b.t - a.t != 1 || !gear_at(&duel.my_gear, b.t).is_some_and(|g| g.item(SLOT_HAND).ends_with("_spear")) {
            continue;
        }
        let jump = [b.vel[0] - a.vel[0], b.vel[2] - a.vel[2]];
        let size = (jump[0] * jump[0] + jump[1] * jump[1]).sqrt();
        let (lx, lz) = super::look(b.yaw);
        if size >= LUNGE_JUMP && (jump[0] * lx + jump[1] * lz) / size >= LUNGE_ALIGN {
            let reached = views.iter().any(|v| v.t >= b.t && v.t <= b.t + LUNGE_REACH_TICKS && v.distance <= SPEAR_REACH);
            out.push(Lunge { t: b.t, reached });
        }
    }
    out
}

fn horizontal(views: &[View], mine: &[Opportunity], lunges: &[Lunge]) -> Part {
    // Vitesse de rapprochement face à sa vitesse de fuite, quand on engage.
    let (mut toward, mut away, mut ticks) = (0.0, 0.0, 0.0);
    for v in views.iter().filter(|v| v.horizontal > REACH && v.horizontal < ENGAGE_RANGE) {
        if let Some((x, z)) = horizontal_dir(v) {
            toward += (v.vel[0] * x + v.vel[2] * z).max(0.0);
            away += (v.target_vel[0] * x + v.target_vel[2] * z).max(0.0);
            ticks += 1.0;
        }
    }
    let closing = (toward + away > 0.0).then(|| (toward / (toward + away), ticks / 80.0));
    let lunge = mean(lunges.iter().map(|l| l.reached as u8 as f64));
    // Élytre : les plongées qui finissent en opportunité.
    let mut glides = Vec::new();
    let mut i = 0;
    while i < views.len() {
        if !views[i].gliding {
            i += 1;
            continue;
        }
        let start = views[i].t;
        while i < views.len() && views[i].gliding {
            i += 1;
        }
        let end = views[i - 1].t;
        glides.push(mine.iter().any(|o| o.open >= start && o.open <= end + GLIDE_FOLLOW_TICKS) as u8 as f64);
    }
    blend(&[(1.0, closing), (1.0, lunge), (1.0, mean(glides))])
}

// --- E. Enchaîner et reconstruire --------------------------------------------

fn chain(views: &[View], opportunities: &[Opportunity]) -> Part {
    let touched_ground = |from: u64, to: u64| views.iter().any(|v| v.t > from && v.t < to && v.grounded);
    // Après chaque smash : une nouvelle opportunité sans repasser par le sol ?
    let mut follow_up = Vec::new();
    let mut chains: Vec<usize> = Vec::new();
    let mut length = 0;
    for (index, o) in opportunities.iter().enumerate() {
        let Some(hit) = o.hit else { continue };
        let next = opportunities[index + 1..].iter().find(|n| n.open > hit);
        let linked = next.is_some_and(|n| n.open - hit <= CHAIN_TICKS && !touched_ground(hit, n.open));
        follow_up.push(linked as u8 as f64);
        length += 1;
        // La chaîne continue si la suivante est convertie aussi.
        if !(linked && next.is_some_and(|n| n.converted)) {
            chains.push(length);
            length = 0;
        }
    }
    let lengths = mean(chains.iter().map(|l| (l.saturating_sub(1) as f64 / 3.0).min(1.0)));
    // Reconstruction : du bout d'un engagement, réussi ou raté, à la prochaine opportunité.
    let rebuild = mean(opportunities.windows(2).map(|w| 1.0 - ((w[1].open.saturating_sub(w[0].close)) as f64 / REBUILD_TICKS).min(1.0)));
    blend(&[(0.25, mean(follow_up)), (0.25, lengths), (0.5, rebuild)])
}

fn groups(parts: [Part; 5], views: &[View], raw: &mut Raw) -> Measure {
    let combined = blend(&[
        (W_FOLLOW, centered(raw.put("control.follow", parts[0]), FOLLOW_CENTER)),
        (W_CLIMB, centered(raw.put("control.climb", parts[1]), CLIMB_CENTER)),
        (W_PEAK, centered(raw.put("control.peak", parts[2]), PEAK_CENTER)),
        (W_HORIZONTAL, centered(raw.put("control.horizontal", parts[3]), HORIZONTAL_CENTER)),
        (W_CHAIN, centered(raw.put("control.chain", parts[4]), CHAIN_CENTER)),
    ]);
    // Une situation, c'est une descente : chacune se juge (suivre, se placer,
    // enchaîner). Avant le 2026-10-15, des tranches de quatre secondes.
    family(combined, descents(views).len() as f64)
}

fn hits(opportunities: &[Opportunity]) -> Vec<u64> {
    opportunities.iter().filter_map(|o| o.hit).collect()
}

/// Notre contrôle : les cinq groupes.
pub fn mine(duel: &Duel, views: &[View], opportunities: &[Opportunity], lunges: &[Lunge], raw: &mut Raw) -> Measure {
    let armor = armor_factor(duel.my_gear.last());
    let resources = height_per_resource(duel, armor);
    groups(
        [
            follow(views, &hits(opportunities)),
            climb(views, armor, resources),
            peaks(views),
            horizontal(views, opportunities, lunges),
            chain(views, opportunities),
        ],
        views,
        raw,
    )
}

/// Le sien, en miroir : sans le déplacement horizontal (ses élans et ses
/// objets ne se voient pas) ni la hauteur par ressource.
pub fn foe(duel: &Duel, views: &[View], opportunities: &[Opportunity], raw: &mut Raw) -> Measure {
    let armor = armor_factor(duel.foe_gear.last());
    groups([follow(views, &hits(opportunities)), climb(views, armor, None), peaks(views), None, chain(views, opportunities)], views, raw)
}

#[cfg(test)]
mod tests {
    use super::super::tests::view;
    use super::*;

    #[test]
    fn une_descente_qui_arrive_a_portee_compte() {
        // Une descente qui commence à 6 blocs et finit à 1 bloc, smashée.
        let ticks: Vec<View> = (0..8)
            .map(|i| {
                let mut v = view(i, 2.0 + i as f64, 8.0 - i as f64, 6.0 - 0.7 * i as f64);
                v.pos = [0.0, 8.0 - i as f64, 0.0];
                v
            })
            .collect();
        let (value, _) = follow(&ticks, &[9]).unwrap();
        assert!(value > 0.9, "{value}");
        let (missed, _) = follow(&ticks[..2], &[]).unwrap();
        assert!(missed < 0.5, "{missed}");
    }

    #[test]
    fn l_armure_netherite_attend_moins_de_hauteur() {
        let plain = super::super::super::parse::Gear { t: 0, slots: Default::default() };
        let mut heavy = super::super::super::parse::Gear { t: 0, slots: Default::default() };
        for slot in heavy.slots[..4].iter_mut() {
            *slot = "netherite_boots".to_string();
        }
        assert_eq!(armor_factor(Some(&plain)), 1.0);
        assert!((armor_factor(Some(&heavy)) - 0.6).abs() < 1e-9);
    }

    #[test]
    fn reprendre_la_hauteur_vite_vaut_plus() {
        let mut ticks: Vec<View> = (0..10).map(|i| view(i, 0.0, -3.0, 2.0)).collect();
        ticks.extend((10..12).map(|i| view(i, 0.0, 3.0, 2.0)));
        let (fast, _) = retake(&ticks).unwrap();
        assert!((fast - 0.9).abs() < 1e-9, "{fast}");
    }

    #[test]
    fn enchainer_sans_toucher_le_sol() {
        let opportunity = |open: u64, hit: Option<u64>| Opportunity {
            open,
            close: open + 4,
            converted: hit.is_some(),
            hit,
            expected: 10.0,
            realized: 0.0,
            fall: 5.0,
            hit_fall: 5.0,
            possible_fall: 5.0,
            offset: 1.0,
            target_speed: 0.0,
            behind_shield: false,
            chance: 0.3,
        };
        let air: Vec<View> = (0..60).map(|i| view(i, 3.0, 3.0, 1.0)).collect();
        let list = [opportunity(0, Some(5)), opportunity(20, Some(25)), opportunity(40, None)];
        let (value, _) = chain(&air, &list).unwrap();
        assert!(value > 0.6, "{value}");
    }
}
