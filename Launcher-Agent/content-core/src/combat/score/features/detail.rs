//! Commun à la masse et à l'épée : le détail d'une famille, pour la fenêtre
//! qui s'ouvre au clic sur elle (2026-10-15) — des faits que le joueur peut
//! vérifier de mémoire : des compteurs, des moments datés, la même mesure
//! chez l'adversaire. Jamais le calcul (ni poids, ni écart, ni formule) : le
//! but est qu'en le lisant, il ne conteste pas sa note. Les codes sont
//! traduits côté Java (`PvpFamilyModal`) ; les nombres sont ceux qui ont fait
//! la note.

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
    pub(super) fn fact(&mut self, code: &'static str, up: bool, weight: f64, values: &[f64]) {
        self.facts.push(Fact { code, up, weight: weight.abs(), values: values.to_vec() });
    }

    pub(super) fn moment(&mut self, t: u64, code: &'static str, good: bool, weight: f64, values: &[f64]) {
        self.moments.push(Moment { t, code, good, weight, values: values.to_vec() });
    }

    pub(super) fn versus(&mut self, code: &'static str, mine: &[f64], theirs: &[f64]) {
        self.versus.push(Versus { code, mine: mine.to_vec(), theirs: theirs.to_vec() });
    }
}
