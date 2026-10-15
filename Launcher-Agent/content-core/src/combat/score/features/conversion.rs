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

use super::{Measure, Opportunity, BIG_FALL};

// La chance de réussite d'une opportunité : une régression logistique sur
// cinq critères. Les poids seront appris sur les opportunités des deux
// joueurs, tous duels confondus ; en attendant, ils sont posés à la main
// (*à calibrer*), pour qu'une opportunité ordinaire — 1,5 bloc de décalage,
// cible qui marche, six ticks, six blocs de chute — tourne autour de 35 %.
const BIAS: f64 = 0.9;
/// Par bloc de décalage horizontal à l'ouverture : collé à la cible, c'est facile.
const PER_OFFSET_BLOCK: f64 = -0.5;
/// Par bloc par tick de vitesse horizontale de la cible : une cible en sprint, sur une charge de vent, est dure à suivre.
const PER_TARGET_SPEED: f64 = -4.0;
/// Par tick de fenêtre, jusqu'à `MAX_WINDOW_TICKS` : une fenêtre longue laisse le temps.
const PER_WINDOW_TICK: f64 = 0.08;
const MAX_WINDOW_TICKS: f64 = 20.0;
/// Par bloc de chute : on arrive vite, la cible a le temps de se replacer (décision du 2026-10-14).
const PER_FALL_BLOCK: f64 = -0.08;
/// Cible au bouclier levé, attaquant dans son dos : il faut l'avoir contourné.
const BEHIND_SHIELD: f64 = -1.0;

/// La chance qu'aurait un joueur moyen de convertir cette opportunité.
pub fn chance(offset: f64, target_speed: f64, ticks: u64, fall: f64, behind_shield: bool) -> f64 {
    let logit = BIAS
        + PER_OFFSET_BLOCK * offset
        + PER_TARGET_SPEED * target_speed
        + PER_WINDOW_TICK * (ticks as f64).min(MAX_WINDOW_TICKS)
        + PER_FALL_BLOCK * fall
        + if behind_shield { BEHIND_SHIELD } else { 0.0 };
    (1.0 / (1.0 + (-logit).exp())).clamp(0.02, 0.98)
}

/// Le poids d'une opportunité : son bonus de smash possible. Une ouverture
/// sans bonus connu (smash relevé hors fenêtre, chute perdue) pèse un peu.
fn weight(o: &Opportunity) -> f64 {
    o.expected.max(1.0)
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

/// La famille, entre 0 et 1 : 0,5 quand le joueur fait comme un joueur
/// moyen sur ces opportunités, au-dessus quand il fait mieux. `attacker` :
/// ce sont ses opportunités (conversion) ; sinon ce sont celles de
/// l'adversaire et le joueur gagne quand elles ne passent pas (défense).
pub fn measure(list: &[Opportunity], attacker: bool) -> Measure {
    match observed_and_expected(list) {
        Some((observed, expected)) => {
            let gap = if attacker { observed - expected } else { expected - observed };
            Measure { value: (0.5 + gap / 2.0).clamp(0.0, 1.0), count: list.len() as f64 }
        }
        None => Measure::NONE,
    }
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

    #[test]
    fn faire_comme_un_joueur_moyen_vaut_cinquante() {
        let list = [opportunity(true, 5.0, 0.5), opportunity(false, 5.0, 0.5)];
        assert!((measure(&list, true).value - 0.5).abs() < 1e-9);
        assert!((measure(&list, false).value - 0.5).abs() < 1e-9);
    }

    #[test]
    fn reussir_une_ouverture_difficile_rapporte_rater_une_facile_coute() {
        let hard_landed = [opportunity(true, 5.0, 0.1)];
        let easy_missed = [opportunity(false, 5.0, 0.9)];
        assert!(measure(&hard_landed, true).value > 0.9);
        assert!(measure(&easy_missed, true).value < 0.1);
        assert!(measure(&easy_missed, false).value > 0.9, "l'adversaire qui rate une ouverture facile : bonne défense");
    }

    #[test]
    fn une_grosse_chute_pese_plus() {
        // Une grosse réussie et une petite ratée valent mieux que l'inverse.
        let big_landed = [opportunity(true, 15.0, 0.3), opportunity(false, 2.0, 0.3)];
        let small_landed = [opportunity(false, 15.0, 0.3), opportunity(true, 2.0, 0.3)];
        assert!(measure(&big_landed, true).value > measure(&small_landed, true).value);
        let split = Split::of(&big_landed);
        assert_eq!((split.big_opened, split.big_converted, split.small_opened, split.small_converted), (1, 1, 1, 0));
    }
}
