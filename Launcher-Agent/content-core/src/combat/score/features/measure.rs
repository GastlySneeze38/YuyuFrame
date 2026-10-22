//! Commun à la masse et à l'épée : une action jugée face à ce qu'on en
//! attendait, les parties qu'elles forment, et la famille qui les pèse.

/// Une famille telle que la note la lit (2026-10-22) : l'écart entre ce que
/// le joueur a tiré de ses actions et ce qu'en tire un joueur moyen, et sur
/// combien de situations il repose.
///
/// `gap` est en écarts ordinaires : chaque action est comparée à ce qu'on en
/// attendait, divisée par l'écart habituel des joueurs sur ce genre
/// d'action ([`Norm`]), puis les parties sont pondérées. 0 : comme un joueur
/// moyen sur les mêmes situations ; il n'y a plus de référence par famille,
/// chaque action porte la sienne.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Measure {
    pub gap: f64,
    pub count: f64,
}

impl Measure {
    pub const NONE: Measure = Measure { gap: 0.0, count: 0.0 };
}

impl Default for Measure {
    fn default() -> Self {
        Measure::NONE
    }
}

/// Ce qu'on attend d'une sorte d'action : ce qu'en tire en moyenne un
/// joueur (`mean`, de 0 à 1 — pour une action à chance, ce qu'en dit son
/// modèle remplace cette moyenne) et de combien les joueurs s'en écartent
/// d'ordinaire d'une action à l'autre (`spread`). `code` la nomme dans
/// l'outil de calibration et à l'écran. Les deux se recalculent sur les
/// duels partagés (`calibration`, test `proposition_de_calibrage`).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Norm {
    pub code: &'static str,
    pub mean: f64,
    pub spread: f64,
}

impl Norm {
    pub const fn new(code: &'static str, mean: f64, spread: f64) -> Self {
        Norm { code, mean, spread }
    }
}

/// Une action jugée : ce qu'elle a donné (de 0 à 1) face à ce qu'on en
/// attendait. `t` : son tick ; `None` pour une tendance tenue sur tout le
/// duel (le temps passé au sol sous lui, la barre de faim…). `weight` : son
/// poids parmi les actions de sa partie (la valeur d'une ouverture, la
/// fuite de la cible…). `context` : ce dont dépend sa chance, pour les
/// actions qui en ont une (relevé par l'outil de calibration).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Item {
    pub t: Option<u64>,
    pub value: f64,
    pub expected: f64,
    pub weight: f64,
    pub context: Context,
}

/// Ce dont dépend la chance d'une action : jusqu'à cinq critères, dans
/// l'ordre des coefficients de son modèle.
pub type Context = [f64; 5];
pub const NO_CONTEXT: Context = [0.0; 5];

/// Une partie d'une famille : les actions d'une même sorte.
#[derive(Debug, Clone, PartialEq)]
pub struct Part {
    pub norm: Norm,
    pub items: Vec<Item>,
}

impl Part {
    pub(super) fn new(norm: Norm) -> Self {
        Part { norm, items: Vec::new() }
    }

    /// Des actions jugées sur la moyenne de leur sorte.
    pub(super) fn of(norm: Norm, values: impl IntoIterator<Item = (Option<u64>, f64)>) -> Self {
        let mut part = Part::new(norm);
        for (t, value) in values {
            part.add(t, value);
        }
        part
    }

    /// Une tendance tenue sur tout le duel : une seule « action », sans tick.
    pub(super) fn trend(norm: Norm, value: Option<f64>) -> Self {
        Part::of(norm, value.map(|v| (None, v)))
    }

    pub(super) fn add(&mut self, t: Option<u64>, value: f64) {
        let expected = self.norm.mean;
        self.push(Item { t, value, expected, weight: 1.0, context: NO_CONTEXT });
    }

    pub(super) fn push(&mut self, mut item: Item) {
        item.value = item.value.clamp(0.0, 1.0);
        self.items.push(item);
    }

    fn total_weight(&self) -> f64 {
        self.items.iter().map(|i| i.weight).sum()
    }

    /// Mesurée dans ce duel ? Une partie vide ne compte pas : son poids va aux autres.
    pub(super) fn present(&self) -> bool {
        self.total_weight() > 0.0
    }

    /// Sa valeur moyenne, pondérée ; `None` si rien ne la mesure.
    pub fn value(&self) -> Option<f64> {
        let w = self.total_weight();
        (w > 0.0).then(|| self.items.iter().map(|i| i.weight * i.value).sum::<f64>() / w)
    }

    /// Son écart à l'attendu, en écarts ordinaires ; `None` si rien ne la mesure.
    pub fn gap(&self) -> Option<f64> {
        let w = self.total_weight();
        (w > 0.0).then(|| self.items.iter().map(|i| i.weight * (i.value - i.expected)).sum::<f64>() / w / self.norm.spread)
    }
}

/// Une action dans sa famille : ce qu'elle lui a fait gagner ou perdre.
/// L'écart de la famille vaut exactement Σ `share` × (`value` − `expected`)
/// ÷ `spread` sur ses actions ; la note en tire les points de chacune.
#[derive(Debug, Clone, PartialEq)]
pub struct Action {
    pub t: Option<u64>,
    pub code: &'static str,
    pub value: f64,
    pub expected: f64,
    pub share: f64,
    pub spread: f64,
    pub context: Context,
}

impl Action {
    /// Sa contribution à l'écart de la famille.
    pub fn gap(&self) -> f64 {
        self.share * (self.value - self.expected) / self.spread
    }
}

/// Une famille : sa mesure, et les actions qui la font.
#[derive(Debug, Clone, Default)]
pub struct Family {
    pub measure: Measure,
    pub actions: Vec<Action>,
}

/// Une famille faite de groupes de parties pondérés. Une partie absente
/// laisse son poids aux autres de son groupe, un groupe vide aux autres
/// groupes (décision du 2026-10-14). `events` : sur combien de vraies
/// situations elle repose (2026-10-15 — pas des tranches de temps).
pub(super) fn family(groups: &[(f64, Vec<(f64, Part)>)], events: f64) -> Family {
    let present: Vec<(f64, Vec<&(f64, Part)>)> = groups
        .iter()
        .map(|(w, parts)| (*w, parts.iter().filter(|(pw, p)| *pw > 0.0 && p.present()).collect::<Vec<_>>()))
        .filter(|(w, parts)| *w > 0.0 && !parts.is_empty())
        .collect();
    let total: f64 = present.iter().map(|(w, _)| w).sum();
    if total <= 0.0 {
        return Family::default();
    }
    let mut actions = Vec::new();
    for (w, parts) in &present {
        let inner: f64 = parts.iter().map(|(pw, _)| pw).sum();
        for (pw, part) in parts {
            let weights = part.total_weight();
            for item in &part.items {
                actions.push(Action {
                    t: item.t,
                    code: part.norm.code,
                    value: item.value,
                    expected: item.expected,
                    share: w / total * pw / inner * item.weight / weights,
                    spread: part.norm.spread,
                    context: item.context,
                });
            }
        }
    }
    let gap = actions.iter().map(Action::gap).sum();
    Family { measure: Measure { gap, count: events }, actions }
}

/// Une famille d'une seule partie.
pub(super) fn single(part: Part, events: f64) -> Family {
    family(&[(1.0, vec![(1.0, part)])], events)
}

/// Une régression logistique : la chance d'une action d'après sa situation.
pub fn logistic(bias: f64, coefs: &[f64], x: &[f64]) -> f64 {
    let logit = bias + coefs.iter().zip(x).map(|(c, v)| c * v).sum::<f64>();
    (1.0 / (1.0 + (-logit).exp())).clamp(0.02, 0.98)
}

#[cfg(test)]
mod tests {
    use super::*;

    const NORM: Norm = Norm::new("essai", 0.4, 0.2);

    #[test]
    fn une_partie_absente_laisse_son_poids_aux_autres() {
        let done = Part::of(NORM, [(Some(1), 1.0)]);
        let alone = family(&[(0.3, vec![(1.0, done.clone())]), (0.7, vec![(1.0, Part::new(NORM))])], 1.0);
        assert!((alone.measure.gap - 3.0).abs() < 1e-12, "1 face à 0,4, en écarts de 0,2 : +3, tout le poids");
        // Dans un groupe aussi : la partie vide laisse sa place à sa voisine.
        let grouped = family(&[(1.0, vec![(0.5, done), (0.5, Part::new(NORM))])], 1.0);
        assert!((grouped.measure.gap - 3.0).abs() < 1e-12);
        assert_eq!(family(&[(1.0, vec![(1.0, Part::new(NORM))])], 0.0).measure, Measure::NONE);
    }

    #[test]
    fn les_actions_font_exactement_l_ecart_de_la_famille() {
        let good = Part::of(NORM, [(Some(1), 0.9), (Some(2), 0.6)]);
        let trend = Part::trend(Norm::new("tendance", 0.5, 0.1), Some(0.3));
        let f = family(&[(0.6, vec![(1.0, good)]), (0.4, vec![(1.0, trend)])], 3.0);
        assert_eq!(f.actions.len(), 3);
        let sum: f64 = f.actions.iter().map(Action::gap).sum();
        assert!((sum - f.measure.gap).abs() < 1e-12);
        // Partie 1 : (0,5 + 0,2) / 2 ÷ 0,2 = 1,75, poids 0,6 ; tendance : −0,2 ÷ 0,1 = −2, poids 0,4.
        assert!((f.measure.gap - (0.6 * 1.75 - 0.4 * 2.0)).abs() < 1e-12, "{}", f.measure.gap);
        assert_eq!(f.actions[2].t, None, "une tendance n'a pas de tick");
        assert_eq!(f.measure.count, 3.0);
    }

    #[test]
    fn la_chance_logistique_reste_entre_ses_bornes() {
        assert!((logistic(0.0, &[], &[]) - 0.5).abs() < 1e-12);
        assert_eq!(logistic(50.0, &[], &[]), 0.98);
        assert!(logistic(0.0, &[1.0], &[2.0]) > logistic(0.0, &[1.0], &[1.0]));
    }
}
