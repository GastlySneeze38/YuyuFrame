//! Le classement compétitif (docs/scoring/classement.md) : des rangs, des
//! divisions de 500 points, et chaque duel noté qui en fait gagner ou perdre.
//!
//! Pas de nombre à la Elo ni de note cachée : le rang est une échelle à
//! grimper. Les points d'un duel :
//!
//! ```text
//! (base selon le rang + jeu) × adversaire, bornés, puis × répétition
//! ```
//!
//!   base        victoire et défaite selon le rang (plus on monte, moins on
//!               gagne et plus on perd)
//!   jeu         0,10 × (note du duel − 250), limité à ±15
//!   adversaire  son niveau dans le duel (calculé comme la note) face à 250
//!   bornes      une victoire rapporte au moins 5, une défaite coûte au moins 5
//!   répétition  le même adversaire le même jour : ×1, ×½, ×¼…
//!
//! Tout est rejoué depuis les journaux, dans l'ordre où les duels ont été
//! joués ([`Ladder`]), comme les notes elles-mêmes : un changement de formule
//! s'applique à tout l'historique. Rien n'est stocké, rien n'est envoyé.

use std::collections::HashMap;

use super::score::Mode;

/// Les rangs, du plus bas au plus haut ; le dernier n'a pas de divisions.
pub const TIERS: [&str; 7] = ["Bois", "Pierre", "Fer", "Or", "Diamant", "Netherite", "Champion"];
const DIVISIONS_PER_TIER: usize = 3;
/// La première division de Champion : 6 rangs de 3 divisions avant lui.
pub const CHAMPION: usize = (TIERS.len() - 1) * DIVISIONS_PER_TIER;
/// Points d'une division : les duels sont courts et nombreux, 100 ferait
/// défiler les divisions.
pub const DIVISION_POINTS: i32 = 500;
/// Après une descente, on repart de là (moins le déficit).
const AFTER_DEMOTION: i32 = 400;
/// Duels avant d'avoir un rang affiché.
pub const PLACEMENT_DUELS: usize = 10;

/// Victoire et défaite de base, par rang (inspiré de Brawl Stars) : en Bois
/// on monte même en perdant plus d'un duel sur deux, à partir de l'Or il
/// faut gagner plus de la moitié.
const BASE: [(f64, f64); 7] = [(25.0, -10.0), (24.0, -13.0), (22.0, -16.0), (20.0, -20.0), (19.0, -22.0), (18.0, -24.0), (17.0, -25.0)];

/// Le jeu : points par point de note au-dessus de 250, et sa limite.
const PLAY_PER_POINT: f64 = 0.10;
const PLAY_CAP: f64 = 15.0;
/// L'adversaire : effet d'un point de sa note au-dessus de 250, et bornes.
const FOE_WIN_PER_POINT: f64 = 0.004;
const FOE_WIN_RANGE: (f64, f64) = (0.3, 2.0);
const FOE_LOSS_PER_POINT: f64 = 0.0025;
const FOE_LOSS_RANGE: (f64, f64) = (0.5, 1.6);
/// Une victoire rapporte au moins cela, une défaite coûte au moins cela.
const MIN_POINTS: f64 = 5.0;
const CENTER: f64 = 250.0;
const DAY_MS: u64 = 86_400_000;

/// Le verdict d'un duel, pour le classement.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    Win,
    Loss,
    /// L'adversaire a quitté le duel : seul le jeu compte.
    Draw,
}

/// Un duel noté, tel que le classement le voit.
#[derive(Debug, Clone)]
pub struct Played {
    pub mode: Mode,
    pub outcome: Outcome,
    /// La note du duel, sur 500.
    pub score: f64,
    /// Le niveau de l'adversaire dans ce duel, sur 500 (`Rating::opponent`).
    pub foe_score: f64,
    /// Ce qui le reconnaît d'un duel à l'autre ; `None` si inconnu.
    pub foe_key: Option<String>,
    /// Heure d'ouverture, en millisecondes depuis 1970.
    pub time: u64,
}

/// Où en est un joueur dans un mode.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Standing {
    /// De 0 (Bois 1) à [`CHAMPION`].
    pub division: usize,
    /// Dans la division, de 0 à 499 (sans plafond en Champion).
    pub points: i32,
    /// Duels classés joués dans ce mode.
    pub duels: usize,
    /// Vient de monter : la prochaine défaite ne fait pas redescendre.
    shield: bool,
}

impl Standing {
    /// Le placement est terminé : le rang s'affiche.
    pub fn placed(&self) -> bool {
        self.duels >= PLACEMENT_DUELS
    }

    /// Le rang ([`TIERS`]) : trois divisions chacun, Champion au-delà.
    pub fn tier(&self) -> usize {
        (self.division / DIVISIONS_PER_TIER).min(TIERS.len() - 1)
    }
}

/// Ce qu'un duel a rapporté, et pourquoi.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Change {
    pub base: f64,
    pub play: f64,
    pub foe_factor: f64,
    /// Même adversaire le même jour : 1, ½, ¼…
    pub repeat_factor: f64,
    /// Le total, arrondi : ce qui s'ajoute aux points.
    pub total: i32,
    pub before: Standing,
    pub after: Standing,
}

/// Les points d'un duel, sans le toucher au classement.
fn points(standing: &Standing, played: &Played, repeat_factor: f64) -> (f64, f64, f64, i32) {
    let (win, loss) = BASE[standing.tier()];
    let play = (PLAY_PER_POINT * (played.score - CENTER)).clamp(-PLAY_CAP, PLAY_CAP);
    let foe = played.foe_score.clamp(0.0, 500.0) - CENTER;
    let win_factor = (1.0 + FOE_WIN_PER_POINT * foe).clamp(FOE_WIN_RANGE.0, FOE_WIN_RANGE.1);
    let loss_factor = (1.0 - FOE_LOSS_PER_POINT * foe).clamp(FOE_LOSS_RANGE.0, FOE_LOSS_RANGE.1);
    let (base, factor, raw) = match played.outcome {
        Outcome::Win => (win, win_factor, ((win + play) * win_factor).max(MIN_POINTS)),
        Outcome::Loss => (loss, loss_factor, ((loss + play) * loss_factor).min(-MIN_POINTS)),
        // Pas de vainqueur : le jeu seul, avec l'effet de l'adversaire qui va
        // dans son sens (bien jouer contre plus fort compte plus).
        Outcome::Draw => {
            let factor = if play >= 0.0 { win_factor } else { loss_factor };
            (0.0, factor, play * factor)
        }
    };
    (base, play, factor, (raw * repeat_factor).round() as i32)
}

/// Ajoute des points à une position : montée, descente, filet après une montée.
fn advance(mut s: Standing, total: i32, lost: bool) -> Standing {
    s.duels += 1;
    s.points += total;
    while s.division < CHAMPION && s.points >= DIVISION_POINTS {
        s.division += 1;
        s.points -= DIVISION_POINTS;
        s.shield = true;
    }
    if s.points < 0 {
        if s.shield || s.division == 0 {
            s.points = 0;
        } else {
            // Le déficit (points négatifs) se retire des 400 de départ.
            s.division -= 1;
            s.points = (AFTER_DEMOTION + s.points).max(0);
        }
    }
    // Le filet ne sert qu'une fois : à la première défaite après la montée.
    if lost {
        s.shield = false;
    }
    s
}

/// Le classement rejoué duel après duel, un par mode classé.
#[derive(Debug, Default)]
pub struct Ladder {
    standings: HashMap<Mode, Standing>,
    /// (mode, adversaire, jour) → duels déjà classés.
    met: HashMap<(Mode, String, u64), u32>,
}

impl Ladder {
    /// Un mode a-t-il un classement ? La masse et l'épée.
    pub fn ranks(mode: Mode) -> bool {
        matches!(mode, Mode::Mace | Mode::Sword)
    }

    /// Classe un duel noté et rend ce qu'il a rapporté ; `None` pour un mode
    /// sans classement.
    pub fn play(&mut self, played: &Played) -> Option<Change> {
        if !Self::ranks(played.mode) {
            return None;
        }
        let repeat_factor = match &played.foe_key {
            Some(key) => {
                let seen = self.met.entry((played.mode, key.clone(), played.time / DAY_MS)).or_insert(0);
                let factor = 0.5f64.powi(*seen as i32);
                *seen += 1;
                factor
            }
            None => 1.0,
        };
        let before = self.standing(played.mode);
        let (base, play, foe_factor, total) = points(&before, played, repeat_factor);
        let after = advance(before, total, played.outcome == Outcome::Loss);
        self.standings.insert(played.mode, after);
        Some(Change { base, play, foe_factor, repeat_factor, total, before, after })
    }

    pub fn standing(&self, mode: Mode) -> Standing {
        self.standings.get(&mode).copied().unwrap_or_default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn duel(outcome: Outcome, score: f64, foe_score: f64) -> Played {
        Played { mode: Mode::Sword, outcome, score, foe_score, foe_key: None, time: 0 }
    }

    fn at(division: usize, points: i32) -> Standing {
        Standing { division, points, duels: PLACEMENT_DUELS, shield: false }
    }

    #[test]
    fn les_exemples_de_la_doc() {
        let gold = at(9, 100);
        let total = |o, s, f| points(&gold, &duel(o, s, f), 1.0).3;
        assert_eq!(total(Outcome::Win, 300.0, 250.0), 25);
        assert_eq!(total(Outcome::Win, 400.0, 400.0), 56);
        assert_eq!(total(Outcome::Win, 350.0, 150.0), 18);
        assert_eq!(total(Outcome::Loss, 320.0, 350.0), -10);
        assert_eq!(total(Outcome::Loss, 150.0, 150.0), -38);
        // En Bois, une défaite très bien jouée coûte quand même : la borne.
        assert_eq!(points(&at(0, 100), &duel(Outcome::Loss, 400.0, 250.0), 1.0).3, -5);
    }

    #[test]
    fn gagner_fait_toujours_monter_et_perdre_toujours_descendre() {
        for tier in 0..TIERS.len() {
            let s = at(tier * DIVISIONS_PER_TIER, 100);
            for score in [0.0, 250.0, 500.0] {
                for foe in [0.0, 250.0, 500.0] {
                    assert!(points(&s, &duel(Outcome::Win, score, foe), 1.0).3 >= 5);
                    assert!(points(&s, &duel(Outcome::Loss, score, foe), 1.0).3 <= -5);
                }
            }
        }
    }

    #[test]
    fn le_jeu_et_l_adversaire_sont_continus() {
        let gold = at(9, 100);
        let a = points(&gold, &duel(Outcome::Win, 263.0, 287.0), 1.0);
        assert!((a.1 - 1.3).abs() < 1e-9, "jeu {}", a.1);
        assert!((a.2 - 1.148).abs() < 1e-9, "adversaire {}", a.2);
    }

    #[test]
    fn on_monte_en_gardant_le_surplus_et_le_filet_retient_une_fois() {
        let s = advance(at(3, 490), 25, false);
        assert_eq!((s.division, s.points), (4, 15));
        // Première défaite après la montée : on reste, à 0.
        let s = advance(s, -30, true);
        assert_eq!((s.division, s.points), (4, 0));
        // La suivante fait redescendre, avec 400 moins le déficit.
        let s = advance(s, -30, true);
        assert_eq!((s.division, s.points), (3, 370));
    }

    #[test]
    fn on_ne_descend_pas_sous_bois_1_ni_ne_plafonne_en_champion() {
        assert_eq!(advance(at(0, 3), -20, true).points, 0);
        let s = advance(at(CHAMPION, 480), 40, false);
        assert_eq!((s.division, s.points), (CHAMPION, 520));
    }

    #[test]
    fn le_meme_adversaire_le_meme_jour_rapporte_de_moins_en_moins() {
        let mut ladder = Ladder::default();
        let mut won = |time: u64| {
            let mut d = duel(Outcome::Win, 250.0, 250.0);
            d.foe_key = Some("abc".into());
            d.time = time;
            ladder.play(&d).unwrap().total
        };
        // Bois : +25, puis la moitié, puis le quart ; le lendemain, plein tarif.
        assert_eq!([won(1_000), won(2_000), won(3_000), won(DAY_MS + 1_000)], [25, 13, 6, 25]);
    }

    #[test]
    fn la_masse_et_l_epee_ont_chacune_leur_rang_et_les_autres_modes_aucun() {
        let mut ladder = Ladder::default();
        let mut mace = duel(Outcome::Win, 250.0, 250.0);
        mace.mode = Mode::Mace;
        ladder.play(&mace);
        assert_eq!(ladder.standing(Mode::Mace).points, 25);
        assert_eq!(ladder.standing(Mode::Sword).points, 0);
        let mut other = mace.clone();
        other.mode = Mode::Other;
        assert!(ladder.play(&other).is_none());
    }

    #[test]
    fn le_rang_s_affiche_apres_dix_duels() {
        let mut ladder = Ladder::default();
        for i in 0..PLACEMENT_DUELS {
            assert!(!ladder.standing(Mode::Sword).placed(), "duel {i}");
            ladder.play(&duel(Outcome::Win, 250.0, 250.0));
        }
        assert!(ladder.standing(Mode::Sword).placed());
    }
}
