//! Famille Conversion (docs/scoring/familles/conversion.md) : est-ce que le
//! joueur exploite ses ouvertures, compte tenu de leur difficulté et de leur
//! valeur ?
//!
//! Chaque opportunité a une **chance de réussite** — celle d'un joueur moyen
//! sur la même situation — et un **poids**, son bonus de smash possible. La
//! famille compare ce que le joueur a converti à ce qu'un joueur moyen aurait
//! converti sur les opportunités de ce duel : la référence est propre au duel.
//!
//! La Défense s'en sert à l'envers, sur les opportunités de l'adversaire
//! (« ouvertures laissées ») : les deux familles reposent sur le même modèle.

use super::{logistic, single, Family, Item, Norm, Opportunity, Part, BIG_FALL};

// La chance de réussite d'une opportunité : une régression logistique sur
// cinq critères, apprise sur les opportunités des deux joueurs, tous duels
// confondus (`calibration`, test `proposition_de_calibrage`). Posée d'abord
// à la main, pour qu'une opportunité ordinaire — 1,5 bloc de décalage, cible
// qui marche, six ticks, six blocs de chute — tourne autour de 35 %.
const BIAS: f64 = 0.9;
/// Dans l'ordre de [`criteria`] : par bloc de décalage horizontal à
/// l'ouverture (collé à la cible, c'est facile) ; par bloc par tick de
/// vitesse horizontale de la cible (en sprint, sur une charge de vent, elle
/// est dure à suivre) ; par tick de fenêtre jusqu'à `MAX_WINDOW_TICKS` (une
/// fenêtre longue laisse le temps) ; par bloc de chute (on arrive vite, la
/// cible a le temps de se replacer, décision du 2026-10-14) ; cible au
/// bouclier levé, attaquant dans son dos (il faut l'avoir contourné).
const COEFS: [f64; 5] = [-0.5, -4.0, 0.08, -0.08, -1.0];
pub(super) const MAX_WINDOW_TICKS: f64 = 20.0;

/// Ce qu'on attend d'une ouverture : la moyenne n'y sert pas (chacune a sa
/// chance), l'écart est celui d'un tirage à environ 35 %.
pub const OPENING: Norm = Norm::new("conversion.opening", 0.35, 0.45);
/// Les ouvertures de l'adversaire vues de la défense : qu'elles ne passent pas.
pub const OPENING_LEFT: Norm = Norm::new("defense.openings", 0.65, 0.45);

/// Les cinq critères d'une opportunité, dans l'ordre de `COEFS`.
pub fn criteria(offset: f64, target_speed: f64, ticks: u64, fall: f64, behind_shield: bool) -> [f64; 5] {
    [offset, target_speed, (ticks as f64).min(MAX_WINDOW_TICKS), fall, behind_shield as u8 as f64]
}

/// La chance qu'aurait un joueur moyen de convertir cette opportunité.
pub fn chance(offset: f64, target_speed: f64, ticks: u64, fall: f64, behind_shield: bool) -> f64 {
    logistic(BIAS, &COEFS, &criteria(offset, target_speed, ticks, fall, behind_shield))
}

/// Le poids d'une opportunité : son bonus de smash possible. Une ouverture
/// sans bonus connu (smash relevé hors fenêtre, chute perdue) pèse un peu.
fn weight(o: &Opportunity) -> f64 {
    o.expected.max(1.0)
}

/// Les ouvertures comme actions : convertie (1) ou non (0), face à sa
/// chance, pesée de sa valeur. `attacker` faux : ce sont celles de
/// l'adversaire, et c'est qu'elles ne passent pas qui compte (défense).
pub fn part(list: &[Opportunity], attacker: bool) -> Part {
    let mut part = Part::new(if attacker { OPENING } else { OPENING_LEFT });
    for o in list {
        let (value, expected) = if attacker { (o.converted as u8 as f64, o.chance) } else { ((!o.converted) as u8 as f64, 1.0 - o.chance) };
        let context = criteria(o.offset, o.target_speed, o.close - o.open + 1, o.fall, o.behind_shield);
        part.push(Item { t: Some(o.hit.unwrap_or(o.open)), value, expected, weight: weight(o), context });
    }
    part
}

/// La famille : ses ouvertures, chacune face à sa chance.
pub fn family(list: &[Opportunity]) -> Family {
    single(part(list, true), list.len() as f64)
}

/// Converti, et attendu d'un joueur moyen, pondérés par la valeur.
pub fn observed_and_expected(list: &[Opportunity]) -> Option<(f64, f64)> {
    let total: f64 = list.iter().map(weight).sum();
    if total <= 0.0 {
        return None;
    }
    let observed = list.iter().filter(|o| o.converted).map(weight).sum::<f64>() / total;
    let expected = list.iter().map(|o| weight(o) * o.chance).sum::<f64>() / total;
    Some((observed, expected))
}

/// Une ouverture « facile » : un joueur moyen la convertit plus d'une fois sur deux.
const EASY: f64 = 0.5;
/// Une ouverture « difficile » : moins de trois fois sur dix.
const HARD: f64 = 0.3;

/// Le détail de la conversion : converties face à ce qu'aurait converti un
/// joueur moyen, les ouvertures faciles ratées, les difficiles réussies.
pub fn detail(mine: &[Opportunity], theirs: &[Opportunity], d: &mut super::Detail) {
    let Some((observed, expected)) = observed_and_expected(mine) else { return };
    let converted = mine.iter().filter(|o| o.converted).count() as f64;
    let average = mine.iter().map(|o| o.chance).sum::<f64>();
    d.fact("conversion.converted", observed >= expected, observed - expected, &[converted, mine.len() as f64, (average * 10.0).round() / 10.0]);
    let n = mine.len() as f64;
    let easy_missed: Vec<&Opportunity> = mine.iter().filter(|o| !o.converted && o.chance >= EASY).collect();
    if !easy_missed.is_empty() {
        d.fact("conversion.easy_missed", false, easy_missed.len() as f64 / n, &[easy_missed.len() as f64]);
    }
    let hard_landed: Vec<&Opportunity> = mine.iter().filter(|o| o.converted && o.chance < HARD).collect();
    if !hard_landed.is_empty() {
        d.fact("conversion.hard_landed", true, hard_landed.len() as f64 / n, &[hard_landed.len() as f64]);
    }
    for o in &easy_missed {
        d.moment(o.open, "conversion.easy_missed", false, o.chance, &[o.fall, o.offset]);
    }
    for o in &hard_landed {
        d.moment(o.hit.unwrap_or(o.open), "conversion.hard_landed", true, 1.0 - o.chance, &[o.fall]);
    }
    if !theirs.is_empty() {
        let theirs_converted = theirs.iter().filter(|o| o.converted).count() as f64;
        d.versus("conversion.converted", &[converted, n], &[theirs_converted, theirs.len() as f64]);
    }
}

/// Les grosses et les petites ouvertures, comptées à part pour la page d'un
/// duel (frontière : `BIG_FALL`). Hors note.
#[derive(Debug, Clone, Copy, Default, PartialEq, serde::Serialize)]
pub struct Split {
    pub big_opened: usize,
    pub big_converted: usize,
    pub small_opened: usize,
    pub small_converted: usize,
}

impl Split {
    pub fn of(list: &[Opportunity]) -> Self {
        let mut split = Split::default();
        for o in list {
            let (opened, converted) = if o.fall >= BIG_FALL {
                (&mut split.big_opened, &mut split.big_converted)
            } else {
                (&mut split.small_opened, &mut split.small_converted)
            };
            *opened += 1;
            *converted += o.converted as usize;
        }
        split
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn opportunity(converted: bool, fall: f64, chance: f64) -> Opportunity {
        Opportunity {
            open: 0,
            close: 5,
            converted,
            hit: converted.then_some(5),
            expected: super::super::smash_bonus(fall, 0.0),
            realized: 0.0,
            fall,
            hit_fall: fall,
            possible_fall: fall,
            offset: 1.0,
            target_speed: 0.0,
            behind_shield: false,
            chance,
        }
    }

    #[test]
    fn la_chance_suit_la_difficulte() {
        let easy = chance(0.2, 0.0, 15, 3.0, false);
        let hard = chance(3.4, 0.4, 3, 15.0, true);
        assert!(easy > 0.6 && hard < 0.1, "{easy} {hard}");
        assert!(chance(1.5, 0.0, 6, 15.0, false) < chance(1.5, 0.0, 6, 3.0, false), "une grosse chute est plus difficile");
        let ordinary = chance(1.5, 0.2, 6, 6.0, false);
        assert!(ordinary > 0.25 && ordinary < 0.45, "{ordinary}");
    }

    fn gap(list: &[Opportunity], attacker: bool) -> f64 {
        part(list, attacker).gap().unwrap()
    }

    #[test]
    fn faire_comme_un_joueur_moyen_vaut_zero() {
        let list = [opportunity(true, 5.0, 0.5), opportunity(false, 5.0, 0.5)];
        assert!(gap(&list, true).abs() < 1e-9);
        assert!(gap(&list, false).abs() < 1e-9);
    }

    #[test]
    fn reussir_une_ouverture_difficile_rapporte_rater_une_facile_coute() {
        let hard_landed = [opportunity(true, 5.0, 0.1)];
        let easy_missed = [opportunity(false, 5.0, 0.9)];
        let easy_landed = [opportunity(true, 5.0, 0.9)];
        assert!(gap(&hard_landed, true) > 1.5);
        assert!(gap(&easy_missed, true) < -1.5);
        assert!(gap(&hard_landed, true) > 5.0 * gap(&easy_landed, true), "réussir une facile rapporte peu");
        assert!(gap(&easy_missed, false) > 1.5, "l'adversaire qui rate une ouverture facile : bonne défense");
    }

    #[test]
    fn une_grosse_chute_pese_plus() {
        // Une grosse réussie et une petite ratée valent mieux que l'inverse.
        let big_landed = [opportunity(true, 15.0, 0.3), opportunity(false, 2.0, 0.3)];
        let small_landed = [opportunity(false, 15.0, 0.3), opportunity(true, 2.0, 0.3)];
        assert!(gap(&big_landed, true) > gap(&small_landed, true));
        let split = Split::of(&big_landed);
        assert_eq!((split.big_opened, split.big_converted, split.small_opened, split.small_converted), (1, 1, 1, 0));
    }
}
