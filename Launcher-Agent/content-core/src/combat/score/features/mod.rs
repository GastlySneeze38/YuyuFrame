//! D'un duel aux mesures : les familles du mode du duel, chacune faite
//! d'actions jugées face à ce qu'en tire un joueur moyen. Rien n'est encore
//! noté (c'est `rating`).
//!
//!   mace/      la masse : opportunités de smash, cinq familles, bonus bug use
//!   sword/     l'épée : quatre familles (combos, qualité du coup, défense, placement)
//!
//! Ce qui sert aux deux :
//!
//!   measure    une action face à son attendu, les parties, la famille
//!   detail     le détail d'une famille, pour la fenêtre qui s'ouvre au clic
//!   views      les nombres d'un tick, le duel vu par chacun des deux joueurs
//!   strike     ce que le jeu dit d'un coup : la barre, le critique, ses sons
//!   exchange   les échanges : chaque coup porté ou reçu

mod detail;
mod exchange;
mod mace;
mod measure;
mod strike;
mod sword;
mod views;

pub use detail::Detail;
pub use mace::{BugUse, Detection, Opportunity, Smash, Split};
pub use measure::{Action, Measure};
/// Pour l'outil de calibration : il réapprend les modèles de chance.
#[cfg(test)]
pub use measure::logistic;
pub use sword::Figures as SwordFigures;

use super::parse::{Duel, Mode};

pub struct Features {
    /// Le mode du duel : ses familles en dépendent (masse, épée).
    pub mode: Mode,
    /// Sur combien de situations la note repose : les opportunités des deux
    /// joueurs à la masse, les coups échangés à l'épée.
    pub evidence: usize,
    /// Les chiffres d'un duel à l'épée (hors note).
    pub sword: Option<SwordFigures>,
    /// Les opportunités de smash des deux joueurs (masse seulement).
    pub mine: Vec<Opportunity>,
    pub theirs: Vec<Opportunity>,
    /// Secondes passées avec l'adversaire à portée d'engagement.
    pub engaged_secs: f64,
    /// Les cinq familles, dans les cases du résumé. À la masse : conversion,
    /// qualité du smash, défense, contrôle, ressources. À l'épée : combos,
    /// qualité du coup, défense, placement — la cinquième ne mesure rien.
    pub conversion: Measure,
    /// Grosses et petites ouvertures, à part (affiché, hors note).
    pub split: Split,
    pub smash_quality: Measure,
    pub exposure: Measure,
    /// Part des dégâts adverses neutralisés (affiché, hors note).
    pub avoided: Option<f64>,
    pub vertical: Measure,
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
    /// Les actions de mes cinq familles, dans l'ordre du résumé : la note en
    /// tire leurs points, l'outil de calibration ce qu'on attend de chacune.
    pub actions: [Vec<Action>; 5],
    /// Celles des quatre familles de l'adversaire (outil de calibration).
    pub foe_actions: [Vec<Action>; 4],
    // Pour l'outil de calibration (`score::calibration`) : chaque smash jugé,
    // des deux côtés ; chaque technique et chaque élan de Lunge reconnus.
    pub smashes: Vec<Smash>,
    pub detections: Vec<Detection>,
}

/// Ce qu'on sait mesurer de l'adversaire : quatre familles (ses ressources
/// ne se voient presque pas d'ici).
pub struct FoeMeasures {
    pub conversion: Measure,
    pub smash_quality: Measure,
    pub exposure: Measure,
    pub vertical: Measure,
}

/// Secondes passées avec l'adversaire à portée d'engagement.
fn engaged_secs(duel: &Duel) -> f64 {
    duel.samples.iter().filter(|s| s.foe.is_some()).count() as f64 / 20.0
}

/// Les mesures d'un duel, selon son mode. Un duel d'un autre mode est lu
/// comme un duel à la masse : il n'est pas noté (`score::score_with`).
pub fn extract(duel: &Duel) -> Features {
    match duel.mode {
        Mode::Sword => sword::extract(duel),
        _ => mace::extract(duel),
    }
}
