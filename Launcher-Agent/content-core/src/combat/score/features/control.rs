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
use super::{descents, family, logistic, Family, Item, Norm, Opportunity, Part, View, BIG_FALL, GAP_TICKS, HEIGHT_ADVANTAGE, HIT_DELAY_TICKS, MIN_FALL, REACH, Y};

// Poids des groupes (décision du 2026-10-14), *à calibrer*.
const W_FOLLOW: f64 = 0.35;
const W_CLIMB: f64 = 0.20;
const W_PEAK: f64 = 0.15;
const W_HORIZONTAL: f64 = 0.15;
const W_CHAIN: f64 = 0.15;
/// F. L'initiative (2026-10-22) : qui se crée les ouvertures. Ajoutée après
/// l'étude des duels partagés — la note jugeait ce que chacun fait de ses
/// ouvertures, jamais combien il s'en crée, et celui qui domine un duel long
/// n'en tirait rien. 0,6 (un tiers du contrôle) : essayé à 0,3 puis 0,6 sur
/// 31 duels, 0,6 sépare mieux deux joueurs sur les duels longs et rend la
/// note plus stable d'un duel à l'autre pour un même joueur. À revoir sur
/// plus de duels.
const W_INITIATIVE: f64 = 0.6;

// Ce qu'on attend de chaque sorte d'action : relevé le 2026-10-22 sur les
// duels notés partagés, les deux joueurs (`calibration`, test
// `proposition_de_calibrage`) — 31 duels complets, plus 11 d'un serveur où
// aucun smash n'est relevé pour les sortes qui n'en dépendent pas (premier
// coup et enchaînements viennent des 31 seuls). Un écart relevé sous 0,15
// est porté à 0,15 : la vitesse de montée (0,98) et la longueur des chaînes
// (0,04) valent presque toujours la même chose, une seule action ordinaire
// y ferait sinon un écart de dix.
// C. Suivre pendant la chute.
pub const REACHED: Norm = Norm::new("control.reached", 0.631, 0.415);
pub const CAUGHT: Norm = Norm::new("control.caught", 0.518, 0.45);
pub const FIRST_TIME: Norm = Norm::new("control.first_time", 0.204, 0.343);
// A. Prendre la hauteur.
pub const CLIMB_SPEED: Norm = Norm::new("control.climb_speed", 0.985, 0.15);
pub const RETAKE: Norm = Norm::new("control.retake", 0.523, 0.33);
pub const WIND_HEIGHT_NORM: Norm = Norm::new("control.wind_height", 0.698, 0.413);
pub const DOMINATION: Norm = Norm::new("control.domination", 0.552, 0.15);
// B. Se placer au sommet.
pub const PEAK: Norm = Norm::new("control.peak", 0.686, 0.391);
// D. Se déplacer à l'horizontale.
pub const CLOSING: Norm = Norm::new("control.closing", 0.425, 0.15);
pub const LUNGE: Norm = Norm::new("control.lunge", 0.293, 0.527);
pub const GLIDE: Norm = Norm::new("control.glide", 0.426, 0.494);
// E. Enchaîner et reconstruire.
pub const FOLLOW_UP: Norm = Norm::new("control.follow_up", 0.293, 0.469);
pub const CHAIN_LENGTH: Norm = Norm::new("control.chain_length", 0.039, 0.15);
pub const REBUILD: Norm = Norm::new("control.rebuild", 0.419, 0.366);
// F. L'initiative : chaque ouverture du duel, la sienne (1) ou celle de
// l'adversaire (0) — 0,5 par symétrie.
pub const INITIATIVE: Norm = Norm::new("control.initiative", 0.5, 0.5);

/// La chance qu'une descente qui le vise arrive à portée, d'après sa
/// situation au départ : distance horizontale et hauteur au-dessus de lui.
/// Apprise le 2026-10-22 sur 2 539 descentes : plus elle part loin, moins
/// elle arrive ; plus elle part haut, plus elle a le temps (63 % en moyenne).
const REACH_BIAS: f64 = 1.232;
const REACH_COEFS: [f64; 2] = [-0.492, 0.163];

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

/// Suivre pendant la chute, descente par descente.
struct Follow {
    /// Les descentes qui commencent au-dessus de lui et pas trop loin :
    /// (tick, arrivée à portée ?, distance horizontale et hauteur au départ).
    reached: Vec<(u64, bool, f64, f64)>,
    /// La distance rattrapée, pondérée par sa fuite : une action par descente.
    caught: Part,
    /// Les grosses descentes (`BIG_FALL`) : (tick, chute, smash du premier coup ?).
    first_time: Vec<(u64, f64, bool)>,
}

impl Follow {
    /// Descentes qui arrivent à portée (chacune face à sa chance), distance
    /// rattrapée, gros coup du premier coup.
    fn parts(&self) -> [Part; 3] {
        let mut reached = Part::new(REACHED);
        for (t, arrived, horizontal, above) in &self.reached {
            let context = [*horizontal, *above, 0.0, 0.0, 0.0];
            reached.push(Item { t: Some(*t), value: *arrived as u8 as f64, expected: logistic(REACH_BIAS, &REACH_COEFS, &context[..2]), weight: 1.0, context });
        }
        let first = Part::of(FIRST_TIME, self.first_time.iter().map(|(t, _, landed)| (Some(*t), *landed as u8 as f64)));
        [reached, self.caught.clone(), first]
    }
}

fn follow(views: &[View], smashes: &[u64]) -> Follow {
    let mut reached = Vec::new();
    let mut caught = Part::new(CAUGHT);
    let mut first_time = Vec::new();
    for d in descents(views) {
        let span = &views[d.start..=d.end];
        let (s, e) = (&views[d.start], &views[d.end]);
        // Seules les descentes qui le visent : au-dessus de lui et pas trop loin.
        if s.above <= 0.0 || s.horizontal >= NEAR_DESCENT {
            continue;
        }
        let arrived = span.iter().any(|v| v.above > 0.0 && v.horizontal <= REACH && v.fall >= MIN_FALL);
        reached.push((s.t, arrived, s.horizontal, s.above));
        if s.horizontal > REACH {
            // Rattraper compte surtout quand l'adversaire fuit.
            let flee = horizontal_dir(s).map(|(x, z)| s.target_vel[0] * x + s.target_vel[2] * z).unwrap_or(0.0);
            let weight = 1.0 + 10.0 * flee.max(0.0);
            let score = ((s.horizontal - e.horizontal) / (s.horizontal - REACH)).clamp(0.0, 1.0);
            caught.push(Item { t: Some(s.t), value: score, expected: CAUGHT.mean, weight, context: [s.horizontal, flee, 0.0, 0.0, 0.0] });
        }
        // Un gros coup du premier coup : vu le 2026-10-15, compter aussi les
        // grosses descentes loin de lui donnait « 4 sur 26 » à un joueur
        // dont la plupart ne le visaient pas.
        // Une descente ratée qui finit face à son bouclier levé ne pouvait pas
        // passer : ce n'est pas un raté (même règle que la conversion). Une
        // réussie compte toujours — le bouclier était baissé au coup.
        let biggest = span.iter().map(|v| v.fall).fold(0.0, f64::max);
        let landed = smashes.iter().any(|t| *t >= s.t && *t <= e.t + HIT_DELAY_TICKS);
        let blocked = span.iter().rev().take(3).any(|v| v.shielded && v.front);
        if biggest > BIG_FALL && (landed || !blocked) {
            first_time.push((s.t, biggest, landed));
        }
    }
    Follow { reached, caught, first_time }
}

// --- A. Prendre la hauteur ------------------------------------------------

/// Les montées de l'attaquant : (tick de départ, gain en blocs, durée en secondes).
fn climbs(views: &[View]) -> Vec<(u64, f64, f64)> {
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
            out.push((views[start].t, gain, ((views[end].t - views[start].t) as f64 / 20.0).max(0.05)));
        }
    }
    out
}

/// Le temps pour passer de « il est au-dessus de moi » à « je suis au-dessus
/// de lui » : une action par fois où il est passé au-dessus.
fn retake(views: &[View]) -> Part {
    let mut part = Part::new(RETAKE);
    let mut since: Option<u64> = None;
    for v in views {
        match since {
            None if v.above < -HEIGHT_ADVANTAGE => since = Some(v.t),
            Some(start) if v.above > HEIGHT_ADVANTAGE => {
                part.add(Some(start), 1.0 - ((v.t - start) as f64 / RETAKE_TICKS).min(1.0));
                since = None;
            }
            _ => {}
        }
    }
    if let (Some(start), Some(last)) = (since, views.last()) {
        part.add(Some(start), 1.0 - ((last.t - start) as f64 / RETAKE_TICKS).min(1.0));
    }
    part
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
        return Part::new(DOMINATION);
    }
    gaps.sort_by(f64::total_cmp);
    let above = gaps.iter().filter(|g| **g > HEIGHT_ADVANTAGE).count() as f64;
    let below = gaps.iter().filter(|g| **g < -HEIGHT_ADVANTAGE).count() as f64;
    let time = if above + below > 0.0 { above / (above + below) } else { 0.5 };
    let tenth = ((gaps.len() - 1) as f64 * 0.1).round() as usize;
    let high = gaps[gaps.len() - 1 - tenth].max(0.0);
    let their_high = (-gaps[tenth]).max(0.0);
    let share = if high + their_high > 0.0 { high / (high + their_high) } else { 0.5 };
    Part::trend(DOMINATION, Some(0.5 * time + 0.25 * (high / REFERENCE_HEIGHT).min(1.0) + 0.25 * share))
}

/// La hauteur gagnée par charge de vent, et par combo perle + charge de vent
/// (une perle et une charge à quelques ticks d'écart, suivies d'une
/// téléportation vers le haut). Notre joueur seulement : les objets de
/// l'adversaire ne se voient pas.
fn height_per_resource(duel: &Duel, armor: f64) -> Part {
    let y_at = |t: u64| super::sample_at(&duel.samples, t).map(|s| s.me[Y]);
    let peak = |from: u64, ticks: u64| duel.samples.iter().filter(|s| s.t >= from && s.t <= from + ticks).map(|s| s.me[Y]).fold(f64::MIN, f64::max);
    let pearls: Vec<u64> = duel.uses.iter().filter(|(_, i)| i == "ender_pearl").map(|(t, _)| *t).collect();
    let mut part = Part::new(WIND_HEIGHT_NORM);
    for (t, _) in duel.uses.iter().filter(|(_, i)| i == "wind_charge") {
        let Some(y0) = y_at(*t) else { continue };
        let combo = pearls.iter().find(|p| p.abs_diff(*t) <= COMBO_GAP_TICKS).filter(|p| upward_teleport(duel, (**p).min(*t), 60).is_some());
        let (window, reference) = if combo.is_some() { (60, COMBO_HEIGHT) } else { (30, WIND_HEIGHT) };
        part.add(Some(*t), (peak(*t, window) - y0) / (reference * armor));
    }
    part
}

/// Le tick d'une téléportation vers le haut de notre joueur dans les `ticks` qui suivent `from`.
pub(super) fn upward_teleport(duel: &Duel, from: u64, ticks: u64) -> Option<u64> {
    duel.samples.windows(2).find_map(|w| {
        let (a, b) = (&w[0], &w[1]);
        let moved = ((b.me[1] - a.me[1]).powi(2) + (b.me[2] - a.me[2]).powi(2) + (b.me[3] - a.me[3]).powi(2)).sqrt();
        (a.t >= from && b.t <= from + ticks && b.t - a.t <= GAP_TICKS && moved >= TELEPORT_BLOCKS && b.me[2] > a.me[2]).then_some(b.t)
    })
}

/// Vitesse des montées, reprise de la hauteur, hauteur par ressource, domination.
fn climb(views: &[View], armor: f64, resources: Part) -> [Part; 4] {
    let speed = Part::of(CLIMB_SPEED, climbs(views).iter().map(|(t, gain, secs)| (Some(*t), gain / secs / (CLIMB_REFERENCE * armor))));
    [speed, retake(views), resources, domination(views)]
}

// --- B. Se placer au sommet ------------------------------------------------

fn peaks(views: &[View]) -> Part {
    let scores = views.windows(2).filter(|w| !w[0].falling && w[1].falling && !w[0].grounded && w[0].above > 0.0).map(|w| {
        (Some(w[0].t), 1.0 - ((w[0].horizontal - REACH) / (PEAK_FAR - REACH)).clamp(0.0, 1.0))
    });
    Part::of(PEAK, scores)
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

/// Se rapprocher, les élans de Lunge, les vols planés.
fn horizontal(views: &[View], mine: &[Opportunity], lunges: &[Lunge]) -> [Part; 3] {
    // Vitesse de rapprochement face à sa vitesse de fuite, quand on engage.
    let (mut toward, mut away, mut ticks) = (0.0, 0.0, 0.0);
    for v in views.iter().filter(|v| v.horizontal > REACH && v.horizontal < ENGAGE_RANGE) {
        if let Some((x, z)) = horizontal_dir(v) {
            toward += (v.vel[0] * x + v.vel[2] * z).max(0.0);
            away += (v.target_vel[0] * x + v.target_vel[2] * z).max(0.0);
            ticks += 1.0;
        }
    }
    let _ = ticks;
    let closing = Part::trend(CLOSING, (toward + away > 0.0).then(|| toward / (toward + away)));
    let lunge = Part::of(LUNGE, lunges.iter().map(|l| (Some(l.t), l.reached as u8 as f64)));
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
        glides.push((Some(start), mine.iter().any(|o| o.open >= start && o.open <= end + GLIDE_FOLLOW_TICKS) as u8 as f64));
    }
    [closing, lunge, Part::of(GLIDE, glides)]
}

// --- E. Enchaîner et reconstruire --------------------------------------------

/// Après chaque smash, en enchaîner un autre ; la longueur des chaînes ; le
/// temps pour reconstruire une opportunité. Leurs poids : 0,25, 0,25, 0,5.
fn chain(views: &[View], opportunities: &[Opportunity]) -> [Part; 3] {
    let touched_ground = |from: u64, to: u64| views.iter().any(|v| v.t > from && v.t < to && v.grounded);
    // Après chaque smash : une nouvelle opportunité sans repasser par le sol ?
    let mut follow_up = Part::new(FOLLOW_UP);
    let mut lengths = Part::new(CHAIN_LENGTH);
    let mut length: usize = 0;
    let mut first: Option<u64> = None;
    for (index, o) in opportunities.iter().enumerate() {
        let Some(hit) = o.hit else { continue };
        let next = opportunities[index + 1..].iter().find(|n| n.open > hit);
        let linked = next.is_some_and(|n| n.open - hit <= CHAIN_TICKS && !touched_ground(hit, n.open));
        follow_up.add(Some(hit), linked as u8 as f64);
        length += 1;
        first.get_or_insert(hit);
        // La chaîne continue si la suivante est convertie aussi.
        if !(linked && next.is_some_and(|n| n.converted)) {
            lengths.add(first.take(), (length.saturating_sub(1) as f64 / 3.0).min(1.0));
            length = 0;
        }
    }
    // Reconstruction : du bout d'un engagement, réussi ou raté, à la prochaine opportunité.
    let rebuild = Part::of(REBUILD, opportunities.windows(2).map(|w| (Some(w[1].open), 1.0 - ((w[1].open.saturating_sub(w[0].close)) as f64 / REBUILD_TICKS).min(1.0))));
    [follow_up, lengths, rebuild]
}

const CHAIN_WEIGHTS: [f64; 3] = [0.25, 0.25, 0.5];

// --- F. L'initiative -------------------------------------------------------

/// Chaque ouverture du duel est une action : la sienne vaut 1, celle de
/// l'adversaire 0, chacune pesée de sa valeur (son bonus de smash possible).
/// Celui qui s'en crée deux fois plus gagne à chacune, et l'écart grandit
/// avec la durée du duel. Ça juge le jeu, pas le résultat : se créer une
/// ouverture n'est pas encore toucher.
fn initiative(own: &[Opportunity], other: &[Opportunity]) -> Part {
    let mut part = Part::new(INITIATIVE);
    for (list, value) in [(own, 1.0), (other, 0.0)] {
        for o in list {
            part.push(Item { t: Some(o.open), value, expected: INITIATIVE.mean, weight: o.expected.max(1.0), context: super::NO_CONTEXT });
        }
    }
    part
}

/// Les smashs enchaînés sans toucher le sol, et le nombre de smashs.
fn chained(views: &[View], opportunities: &[Opportunity]) -> (f64, f64) {
    let touched_ground = |from: u64, to: u64| views.iter().any(|v| v.t > from && v.t < to && v.grounded);
    let mut linked = 0.0;
    let mut smashes = 0.0;
    for (index, o) in opportunities.iter().enumerate() {
        let Some(hit) = o.hit else { continue };
        smashes += 1.0;
        let next = opportunities[index + 1..].iter().find(|n| n.open > hit);
        if next.is_some_and(|n| n.open - hit <= CHAIN_TICKS && !touched_ground(hit, n.open)) {
            linked += 1.0;
        }
    }
    (linked, smashes)
}

/// Le détail du contrôle : descentes arrivées à portée, gros coups du
/// premier coup, temps pour repasser au-dessus, distance à lui au sommet,
/// enchaînements, temps pour reconstruire ; les grosses descentes comme
/// moments ; ses descentes et ses gros coups en face.
pub fn detail(views: &[View], their_views: &[View], mine: &[Opportunity], theirs: &[Opportunity], d: &mut super::Detail) {
    // Le poids d'un fait : l'écart de sa mesure à ce que fait en moyenne un joueur, fois le poids du groupe.
    let weight = |value: f64, norm: Norm, w: f64| (value - norm.mean) / norm.spread * w;
    let me = follow(views, &hits(mine));
    let them = follow(their_views, &hits(theirs));
    let count = |list: &[(u64, bool, f64, f64)]| (list.iter().filter(|r| r.1).count() as f64, list.len() as f64);
    if !me.reached.is_empty() {
        let (k, n) = count(&me.reached);
        let w = weight(k / n, REACHED, W_FOLLOW);
        d.fact("control.reached", w >= 0.0, w, &[k, n]);
        if !them.reached.is_empty() {
            let (hk, hn) = count(&them.reached);
            d.versus("control.reached", &[k, n], &[hk, hn]);
        }
    }
    if !me.first_time.is_empty() {
        let k = me.first_time.iter().filter(|f| f.2).count() as f64;
        let n = me.first_time.len() as f64;
        let w = weight(k / n, FIRST_TIME, W_FOLLOW);
        d.fact("control.first_time", w >= 0.0, w, &[k, n]);
        for (t, fall, landed) in &me.first_time {
            d.moment(*t, "control.big_descent", *landed, *fall, &[*fall]);
        }
        if !them.first_time.is_empty() {
            let hk = them.first_time.iter().filter(|f| f.2).count() as f64;
            d.versus("control.first_time", &[k, n], &[hk, them.first_time.len() as f64]);
        }
    }
    if let Some(score) = retake(views).value() {
        let w = weight(score, RETAKE, W_CLIMB);
        d.fact("control.retake", w >= 0.0, w, &[((1.0 - score) * RETAKE_TICKS / 20.0 * 10.0).round() / 10.0]);
    }
    let tops: Vec<f64> = views.windows(2).filter(|w| !w[0].falling && w[1].falling && !w[0].grounded && w[0].above > 0.0).map(|w| w[0].horizontal).collect();
    if let Some(score) = peaks(views).value() {
        let w = weight(score, PEAK, W_PEAK);
        let distance = tops.iter().sum::<f64>() / tops.len().max(1) as f64;
        d.fact("control.peak", w >= 0.0, w, &[(distance * 10.0).round() / 10.0]);
    }
    let (linked, smashes) = chained(views, mine);
    if smashes > 0.0 {
        let w = weight(linked / smashes, FOLLOW_UP, W_CHAIN / 2.0);
        d.fact("control.chained", w >= 0.0, w, &[linked, smashes]);
    }
    // L'initiative : ses ouvertures face aux miennes.
    if let Some(w) = initiative(mine, theirs).gap() {
        d.fact("control.initiative", w >= 0.0, w * W_INITIATIVE, &[mine.len() as f64, theirs.len() as f64]);
        d.versus("control.initiative", &[mine.len() as f64], &[theirs.len() as f64]);
    }
    if mine.len() > 1 {
        let gaps: Vec<f64> = mine.windows(2).map(|w| w[1].open.saturating_sub(w[0].close) as f64 / 20.0).collect();
        let seconds = gaps.iter().sum::<f64>() / gaps.len() as f64;
        let w = weight(1.0 - (seconds * 20.0 / REBUILD_TICKS).min(1.0), REBUILD, W_CHAIN / 2.0);
        d.fact("control.rebuild", w >= 0.0, w, &[(seconds * 10.0).round() / 10.0]);
    }
}

/// Les six groupes, chacun de ses parties : suivre, prendre la hauteur, se
/// placer, se déplacer, enchaîner, l'initiative.
fn groups(follow: [Part; 3], climb: [Part; 4], peak: Part, horizontal: [Part; 3], chain: [Part; 3], initiative: Part, views: &[View]) -> Family {
    let equal = |parts: Vec<Part>| parts.into_iter().map(|p| (1.0, p)).collect::<Vec<_>>();
    let [a, b, c] = chain;
    let groups = [
        (W_FOLLOW, equal(follow.into())),
        (W_CLIMB, equal(climb.into())),
        (W_PEAK, vec![(1.0, peak)]),
        (W_HORIZONTAL, equal(horizontal.into())),
        (W_CHAIN, vec![(CHAIN_WEIGHTS[0], a), (CHAIN_WEIGHTS[1], b), (CHAIN_WEIGHTS[2], c)]),
        (W_INITIATIVE, vec![(1.0, initiative)]),
    ];
    // Une situation, c'est une descente : chacune se juge (suivre, se placer,
    // enchaîner). Avant le 2026-10-15, des tranches de quatre secondes.
    family(&groups, descents(views).len() as f64)
}

fn hits(opportunities: &[Opportunity]) -> Vec<u64> {
    opportunities.iter().filter_map(|o| o.hit).collect()
}

/// Notre contrôle : les six groupes. `theirs` : ses ouvertures, pour l'initiative.
pub fn mine(duel: &Duel, views: &[View], opportunities: &[Opportunity], theirs: &[Opportunity], lunges: &[Lunge]) -> Family {
    let armor = armor_factor(duel.my_gear.last());
    let resources = height_per_resource(duel, armor);
    groups(
        follow(views, &hits(opportunities)).parts(),
        climb(views, armor, resources),
        peaks(views),
        horizontal(views, opportunities, lunges),
        chain(views, opportunities),
        initiative(opportunities, theirs),
        views,
    )
}

/// Le sien, en miroir : sans le déplacement horizontal (ses élans et ses
/// objets ne se voient pas) ni la hauteur par ressource. `mine` : nos
/// ouvertures, pour son initiative.
pub fn foe(duel: &Duel, views: &[View], opportunities: &[Opportunity], mine: &[Opportunity]) -> Family {
    let armor = armor_factor(duel.foe_gear.last());
    let nothing = [Part::new(CLOSING), Part::new(LUNGE), Part::new(GLIDE)];
    groups(
        follow(views, &hits(opportunities)).parts(),
        climb(views, armor, Part::new(WIND_HEIGHT_NORM)),
        peaks(views),
        nothing,
        chain(views, opportunities),
        initiative(opportunities, mine),
        views,
    )
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
        let [reached, caught, _] = follow(&ticks, &[9]).parts();
        assert_eq!(reached.value(), Some(1.0));
        assert!(caught.value().unwrap() > 0.9, "{:?}", caught.value());
        assert!(reached.gap().unwrap() > 0.0, "arriver à portée : mieux que l'attendu");
        let [missed, _, _] = follow(&ticks[..2], &[]).parts();
        assert_eq!(missed.value(), Some(0.0));
        assert_eq!(missed.items[0].context[0], 6.0, "la distance au départ, pour apprendre sa chance");
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
        let fast = retake(&ticks).value().unwrap();
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
        let [follow_up, lengths, rebuild] = chain(&air, &list);
        assert_eq!(follow_up.value(), Some(1.0), "deux smashs, chacun suivi d'une ouverture en l'air");
        assert_eq!(lengths.items.len(), 1, "une seule chaîne");
        assert!(rebuild.value().unwrap() > 0.8);
    }
}
