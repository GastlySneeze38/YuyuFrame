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
pub const SCORE_VERSION: u32 = 1;

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

const CONVERSION: Reference = Reference { mean: 0.35, spread: 0.18 };
const SMASH_QUALITY: Reference = Reference { mean: 0.60, spread: 0.15 };
const EXPOSURE: Reference = Reference { mean: 0.65, spread: 0.18 };
const VERTICAL: Reference = Reference { mean: 0.50, spread: 0.15 };
const RESOURCES: Reference = Reference { mean: 0.45, spread: 0.18 };

/// Une famille dans la note : sa valeur observée, lissée, puis son écart à la
/// référence.
#[derive(Debug, Clone, Copy, serde::Serialize)]
pub struct Sub {
    pub value: f64,
    pub count: f64,
    pub smoothed: f64,
    pub z: f64,
}

fn sub(measure: Measure, reference: &Reference) -> Sub {
    // Peu d'événements : la valeur est tirée vers la référence. Aucun : c'est la référence.
    let smoothed = (measure.count * measure.value + K * reference.mean) / (measure.count + K);
    Sub { value: measure.value, count: measure.count, smoothed, z: (smoothed - reference.mean) / reference.spread }
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
    let opportunities = features.mine.len() + features.theirs.len();
    let reason = if features.engaged_secs < MIN_ENGAGED_SECS {
        Some("too_short")
    } else if opportunities < MIN_OPPORTUNITIES {
        Some("too_few_opportunities")
    } else {
        None
    };
    // Poids égaux. La moyenne de cinq écarts peu liés est bien plus resserrée
    // que chacun d'eux : sans la ramener à l'échelle d'un écart (ici la valeur
    // de cinq familles indépendantes, 1/√5, faute de matchs pour la mesurer),
    // toutes les notes se tasseraient autour de 50.
    let mean = subs.iter().map(|s| s.z).sum::<f64>() / subs.len() as f64;
    let z = mean * (subs.len() as f64).sqrt();
    let n = opportunities as f64;
    Rating {
        score: reason.is_none().then(|| (100.0 * phi(z)).round()),
        reason,
        confidence: n / (n + K),
        conversion: subs[0],
        smash_quality: subs[1],
        exposure: subs[2],
        vertical: subs[3],
        resources: subs[4],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn features(opps: usize, secs: f64, each: Measure) -> Features {
        let one = super::super::features::Opportunity { open: 0, close: 0, converted: false, expected: 0.0, realized: 0.0, offset: 0.0, target_speed: 0.0 };
        Features {
            mine: vec![one; opps],
            theirs: Vec::new(),
            engaged_secs: secs,
            conversion: each,
            smash_quality: each,
            exposure: each,
            vertical: each,
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
        // Un très bon match sort haut : sans la re-standardisation il plafonnerait vers 70.
        let good = [2.0, 1.0, 0.0, 0.5, -0.5];
        let z = good.iter().sum::<f64>() / 5.0 * 5f64.sqrt();
        assert!(100.0 * phi(z) > 90.0);
    }
}
