//! Des cinq familles à la note (docs/scoring/score.md) : lissage,
//! normalisation, combinaison, re-standardisation, note sur 100, confiance.
//!
//! Les références sont **posées à la main** : aucune ne vient de matchs
//! collectés. Elles seront remplacées par la calibration
//! (docs/scoring/calibration.md), et `SCORE_VERSION` montera avec elles —
//! deux formules ne se mélangent jamais.

use super::features::{Features, Measure};

/// Version de la formule. Tout changement de coefficient ou de référence
/// l'incrémente.
pub const SCORE_VERSION: u32 = 3;

/// Poids du lissage : une famille observée sur `K` événements compte autant
/// que sa référence.
const K: f64 = 5.0;
/// En dessous, le duel n'est pas noté.
const MIN_OPPORTUNITIES: usize = 3;
const MIN_ENGAGED_SECS: f64 = 20.0;

/// La référence d'une famille : ce qu'on attend d'un joueur moyen, et de
/// combien les joueurs s'en écartent d'ordinaire. Valeurs APRÈS lissage.
struct Reference {
    mean: f64,
    spread: f64,
}

// Version 2 (2026-10-13) : les premières valeurs étaient trop basses — sur
// quatre vrais duels, la qualité du smash sortait entre 0,71 et 0,81, la
// défense à 0,88 deux fois, les ressources à 0,68 et 0,80, donc ces trois
// familles affichaient 85 à 97 à chaque duel. Les moyennes sont remontées
// vers ce qui a été observé. Quatre duels du même joueur : c'est un repère,
// pas une calibration.
const CONVERSION: Reference = Reference { mean: 0.35, spread: 0.15 };
const SMASH_QUALITY: Reference = Reference { mean: 0.78, spread: 0.12 };
const EXPOSURE: Reference = Reference { mean: 0.82, spread: 0.12 };
const VERTICAL: Reference = Reference { mean: 0.50, spread: 0.15 };
const RESOURCES: Reference = Reference { mean: 0.65, spread: 0.15 };

/// De la moyenne des écarts à l'écart de la note. Une moyenne de familles
/// est plus resserrée qu'une famille seule, il faut donc l'élargir — mais
/// pas comme si elles étaient indépendantes (×√6) : un joueur bon dans l'une
/// l'est souvent dans les autres, et ce facteur-là faisait sortir 97 d'un
/// duel dont les familles valaient 52 à 97. Valeur pour des familles à
/// moitié liées, à mesurer sur les matchs collectés.
const SPREAD_FACTOR: f64 = 1.3;

/// Une famille dans la note : sa valeur observée, lissée, puis son écart à la
/// référence.
#[derive(Debug, Clone, Copy, serde::Serialize)]
pub struct Sub {
    pub value: f64,
    pub count: f64,
    pub smoothed: f64,
    pub z: f64,
    /// La famille seule, sur 100 : ce que l'interface montre.
    pub score: f64,
}

fn sub(measure: Measure, reference: &Reference) -> Sub {
    // Peu d'événements : la valeur est tirée vers la référence. Aucun : c'est la référence.
    let smoothed = (measure.count * measure.value + K * reference.mean) / (measure.count + K);
    let z = (smoothed - reference.mean) / reference.spread;
    Sub { value: measure.value, count: measure.count, smoothed, z, score: (100.0 * phi(z)).round() }
}

#[derive(Debug, serde::Serialize)]
pub struct Rating {
    /// De 0 à 100 ; absente si le duel n'est pas noté (`reason` dit pourquoi).
    pub score: Option<f64>,
    pub reason: Option<&'static str>,
    /// De 0 à 1 : à quel point la note repose sur assez d'opportunités.
    pub confidence: f64,
    pub conversion: Sub,
    pub smash_quality: Sub,
    pub exposure: Sub,
    pub vertical: Sub,
    pub resources: Sub,
    /// Le niveau de l'adversaire : les mêmes familles, mesurées de son côté
    /// et jugées avec les mêmes références. Il entre dans la note comme une
    /// sixième famille — bien jouer contre un adversaire fort vaut plus, et
    /// dominer un adversaire faible vaut moins.
    pub opponent: Sub,
    /// Les quatre familles dont le niveau de l'adversaire est fait, dans
    /// l'ordre : conversion, qualité du smash, défense, contrôle vertical.
    pub opponent_families: [Sub; 4],
    /// Mon jeu seul : mes cinq familles combinées comme la note, sans le
    /// niveau de l'adversaire. À lire à côté de `opponent` — c'est la
    /// comparaison des deux que la note résume.
    pub own: Sub,
}

/// Fonction de répartition de la loi normale (Abramowitz et Stegun 7.1.26,
/// erreur sous 10⁻⁷ : bien assez pour une note arrondie à l'unité).
fn phi(z: f64) -> f64 {
    let x = z.abs() / std::f64::consts::SQRT_2;
    let t = 1.0 / (1.0 + 0.3275911 * x);
    let poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))));
    let erf = 1.0 - poly * (-x * x).exp();
    if z >= 0.0 { 0.5 * (1.0 + erf) } else { 0.5 * (1.0 - erf) }
}

pub fn rate(features: &Features) -> Rating {
    let subs = [
        sub(features.conversion, &CONVERSION),
        sub(features.smash_quality, &SMASH_QUALITY),
        sub(features.exposure, &EXPOSURE),
        sub(features.vertical, &VERTICAL),
        sub(features.resources, &RESOURCES),
    ];
    let (opponent, opponent_families) = opponent_level(features);
    let opportunities = features.mine.len() + features.theirs.len();
    let reason = if features.engaged_secs < MIN_ENGAGED_SECS {
        Some("too_short")
    } else if opportunities < MIN_OPPORTUNITIES {
        Some("too_few_opportunities")
    } else {
        None
    };
    // Poids égaux — mes cinq familles et le niveau de l'adversaire — puis la
    // moyenne ramenée à l'échelle d'un écart (`SPREAD_FACTOR`).
    let mean = (subs.iter().map(|s| s.z).sum::<f64>() + opponent.z) / (subs.len() + 1) as f64;
    let z = mean * SPREAD_FACTOR;
    let n = opportunities as f64;
    let own = combined(&subs, n);
    Rating {
        score: reason.is_none().then(|| (100.0 * phi(z)).round()),
        reason,
        confidence: n / (n + K),
        conversion: subs[0],
        smash_quality: subs[1],
        exposure: subs[2],
        vertical: subs[3],
        resources: subs[4],
        opponent,
        opponent_families,
        own,
    }
}

/// Des familles combinées comme une note : moyenne des écarts, élargie par
/// `SPREAD_FACTOR`, ramenée sur l'échelle d'une famille.
fn combined(subs: &[Sub], count: f64) -> Sub {
    let z = subs.iter().map(|s| s.z).sum::<f64>() / subs.len() as f64 * SPREAD_FACTOR;
    let level = phi(z);
    Sub { value: level, count, smoothed: level, z, score: (100.0 * level).round() }
}

/// Le niveau de l'adversaire, sur l'échelle d'une famille.
///
/// Ce sont ses quatre familles mesurables, jugées avec les mêmes références
/// que les nôtres, puis combinées comme une note. `value` est ce niveau entre
/// 0 et 1, `count` le nombre d'opportunités sur lequel il repose.
///
/// Il n'est pas indépendant de notre jeu : on ne le voit jouer que contre
/// nous, donc sa conversion est l'envers de notre défense. C'est voulu ici —
/// perdre contre quelqu'un qui convertit tout baisse notre défense et monte
/// son niveau, et les deux se compensent en partie dans la note.
fn opponent_level(features: &Features) -> (Sub, [Sub; 4]) {
    let foe = &features.foe;
    let subs = [
        sub(foe.conversion, &CONVERSION),
        sub(foe.smash_quality, &SMASH_QUALITY),
        sub(foe.exposure, &EXPOSURE),
        sub(foe.vertical, &VERTICAL),
    ];
    (combined(&subs, (features.mine.len() + features.theirs.len()) as f64), subs)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Un adversaire dont on ne sait rien : il pèse comme la référence.
    const UNKNOWN_FOE: Measure = Measure { value: 0.0, count: 0.0 };

    fn features(opps: usize, secs: f64, each: Measure) -> Features {
        against(opps, secs, each, UNKNOWN_FOE)
    }

    fn against(opps: usize, secs: f64, each: Measure, foe: Measure) -> Features {
        let one = super::super::features::Opportunity { open: 0, close: 0, converted: false, expected: 0.0, realized: 0.0, offset: 0.0, target_speed: 0.0 };
        Features {
            mine: vec![one; opps],
            theirs: Vec::new(),
            engaged_secs: secs,
            conversion: each,
            smash_quality: each,
            exposure: each,
            vertical: each,
            foe: super::super::features::FoeMeasures { conversion: foe, smash_quality: foe, exposure: foe, vertical: foe },
            resources: each,
            smash_charge: None,
        }
    }

    #[test]
    fn la_loi_normale_tombe_sur_ses_valeurs_connues() {
        assert!((phi(0.0) - 0.5).abs() < 1e-6);
        assert!((phi(1.0) - 0.841345).abs() < 1e-5);
        assert!((phi(-1.96) - 0.025).abs() < 1e-4);
    }

    #[test]
    fn peu_d_evenements_ramene_vers_la_reference() {
        let perfect_on_two = sub(Measure { value: 1.0, count: 2.0 }, &CONVERSION);
        assert!((perfect_on_two.smoothed - (2.0 + K * 0.35) / (2.0 + K)).abs() < 1e-12);
        assert!(perfect_on_two.smoothed < 0.6, "un 100 % sur deux opportunités ne vaut pas 100 %");
        let nothing = sub(Measure { value: 0.0, count: 0.0 }, &CONVERSION);
        assert_eq!(nothing.z, 0.0, "aucun événement : la famille ne pèse rien");
    }

    #[test]
    fn un_duel_sans_evenement_vaut_cinquante_et_un_duel_court_n_est_pas_note() {
        let empty = Measure { value: 0.0, count: 0.0 };
        assert_eq!(rate(&features(5, 60.0, empty)).score, Some(50.0));
        assert_eq!(rate(&features(5, 60.0, empty)).own.score, 50.0, "mon jeu seul, sans rien mesurer : moyen");
        assert_eq!(rate(&features(5, 10.0, empty)).reason, Some("too_short"));
        assert_eq!(rate(&features(5, 10.0, empty)).score, None);
        assert_eq!(rate(&features(2, 60.0, empty)).reason, Some("too_few_opportunities"));
    }

    #[test]
    fn la_note_monte_avec_les_familles_et_la_confiance_avec_les_opportunites() {
        let strong = rate(&features(20, 120.0, Measure { value: 0.9, count: 20.0 }));
        let weak = rate(&features(20, 120.0, Measure { value: 0.1, count: 20.0 }));
        assert!(strong.score.unwrap() > 90.0 && weak.score.unwrap() < 10.0);
        assert!(rate(&features(20, 120.0, Measure { value: 0.9, count: 20.0 })).confidence > rate(&features(4, 120.0, Measure { value: 0.9, count: 4.0 })).confidence);
        // La note reste à portée de ses familles : cinq écarts qui valent 52,
        // 83, 86, 68 et 97 ne font pas un 97 (c'était le cas avec ×√5).
        let seen = [0.05, 0.95, 1.08, 0.47, 1.88];
        let combined = 100.0 * phi(seen.iter().sum::<f64>() / 5.0 * SPREAD_FACTOR);
        assert!(combined > 80.0 && combined < 92.0, "{combined}");
    }

    #[test]
    fn le_niveau_de_l_adversaire_pese_dans_la_note() {
        let mine = Measure { value: 0.5, count: 20.0 };
        let strong = rate(&against(20, 120.0, mine, Measure { value: 0.9, count: 20.0 }));
        let unknown = rate(&against(20, 120.0, mine, UNKNOWN_FOE));
        let weak = rate(&against(20, 120.0, mine, Measure { value: 0.1, count: 20.0 }));
        // Le même jeu vaut plus contre un adversaire fort, moins contre un faible.
        assert!(strong.score.unwrap() > unknown.score.unwrap());
        assert!(unknown.score.unwrap() > weak.score.unwrap());
        assert!(strong.opponent.score > 90.0 && weak.opponent.score < 10.0);
        assert_eq!(unknown.opponent.score, 50.0, "sans rien savoir de lui, il est moyen");
    }
}
