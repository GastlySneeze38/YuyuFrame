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
// Version 4 (2026-10-14) : la sixième famille est la force de l'adversaire
// connue avant le duel, et non plus son niveau mesuré pendant.
// Version 5 (2026-10-14) : les familles refaites d'après docs/scoring/familles/
// et le bonus bug use, hors de la moyenne.
// Version 6 (2026-10-15) : les parties des familles recentrées sur ce que
// font en moyenne les joueurs observés (`features::centered`).
// Version 7 (2026-10-15) : la chute possible d'un smash va jusqu'à la tête
// de la cible, le critique de l'adversaire se lit au son, et les familles
// comptent de vraies situations au lieu de tranches de temps.
// Version 8 (2026-10-15) : une chute n'est plus comptée deux fois après deux
// smashs ratés ; « gros coup du premier coup » ne regarde que les descentes
// qui visent l'adversaire, et pas celles ratées face à son bouclier levé.
// Version 9 (2026-10-15) : bug use sans l'elytra swap, au quart de ses
// points, et de moins en moins pour une même technique répétée.
pub const SCORE_VERSION: u32 = 9;

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

// Version 5 (2026-10-14) : posées à la main en attendant la série de 50
// duels. La conversion et les ouvertures laissées de la défense valent 0,5
// par construction quand le joueur fait comme un joueur moyen sur ses
// opportunités : la référence est déjà propre au duel. Les autres sont des
// repères, pas une calibration.
const CONVERSION: Reference = Reference { mean: 0.50, spread: 0.15 };
const SMASH_QUALITY: Reference = Reference { mean: 0.75, spread: 0.15 };
const EXPOSURE: Reference = Reference { mean: 0.50, spread: 0.15 };
const VERTICAL: Reference = Reference { mean: 0.50, spread: 0.15 };
// Version 6 : ses parties sont recentrées (`features::centered`), sa
// référence devient 0,5 comme celle des autres familles recentrées.
const RESOURCES: Reference = Reference { mean: 0.50, spread: 0.15 };

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
    /// De 0 à 100, bonus bug use compris ; absente si le duel n'est pas noté
    /// (`reason` dit pourquoi).
    pub score: Option<f64>,
    /// La note des six familles, avant le bonus (« 62 » dans « 62 + 4 bug use »).
    pub base_score: Option<f64>,
    /// Le bonus bug use : il ne fait jamais baisser la note, et reste hors
    /// de la moyenne des familles (docs/scoring/familles/bug-use.md).
    pub bug_use: f64,
    pub reason: Option<&'static str>,
    /// De 0 à 1 : à quel point la note repose sur assez d'opportunités.
    pub confidence: f64,
    pub conversion: Sub,
    pub smash_quality: Sub,
    pub exposure: Sub,
    pub vertical: Sub,
    pub resources: Sub,
    /// Le niveau de l'adversaire PENDANT ce duel : les mêmes familles,
    /// mesurées de son côté et jugées avec les mêmes références. Affiché,
    /// mais il n'entre plus dans la note (2026-10-14) : on ne le voit jouer
    /// que contre nous, donc il est en grande partie le miroir de notre jeu —
    /// sa conversion est l'envers de notre défense, sa défense l'envers de
    /// notre conversion. Dans la note, il annulait une partie de notre propre
    /// jeu : dominer faisait paraître l'adversaire faible et plafonnait la note.
    pub opponent: Sub,
    /// La force de l'adversaire connue AVANT ce duel ([`Prior`]) : c'est elle
    /// qui entre dans la note comme sixième famille. Contre un inconnu, elle
    /// vaut la référence et ne pèse rien.
    pub opponent_known: Sub,
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

/// Poids du lissage de la force connue : un adversaire vu dans `K_PRIOR`
/// duels compte pour moitié ce qu'on en a mesuré, pour moitié la référence.
/// Un seul duel passé compte pour un tiers.
pub const K_PRIOR: f64 = 2.0;

/// La force d'un adversaire telle que nos duels précédents contre lui la
/// font connaître : la moyenne de son niveau dans ces duels (en écarts à la
/// référence), tirée vers la référence tant qu'on l'a peu vu.
///
/// Elle ne vient jamais du duel qu'on note : c'est ce qui la rend
/// indépendante de notre jeu ce jour-là.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct Prior {
    /// En écarts à la référence ; 0 pour un inconnu.
    pub z: f64,
    /// Duels passés contre lui.
    pub duels: usize,
}

impl Prior {
    /// `levels` : son niveau (`Rating::opponent.z`) dans chacun de nos duels précédents contre lui.
    pub fn from_levels(levels: &[f64]) -> Self {
        let n = levels.len() as f64;
        Prior { z: levels.iter().sum::<f64>() / (n + K_PRIOR), duels: levels.len() }
    }
}

pub fn rate(features: &Features, prior: &Prior) -> Rating {
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
    // Poids égaux — mes cinq familles et la force connue de l'adversaire —
    // puis la moyenne ramenée à l'échelle d'un écart (`SPREAD_FACTOR`).
    let known = phi(prior.z);
    let opponent_known = Sub { value: known, count: prior.duels as f64, smoothed: known, z: prior.z, score: (100.0 * known).round() };
    let mean = (subs.iter().map(|s| s.z).sum::<f64>() + opponent_known.z) / (subs.len() + 1) as f64;
    let z = mean * SPREAD_FACTOR;
    let n = opportunities as f64;
    let own = combined(&subs, n);
    let base_score = reason.is_none().then(|| (100.0 * phi(z)).round());
    // Le bonus s'ajoute à la note des six familles, plafonné à +10 (déjà
    // fait à l'extraction), la note restant plafonnée à 100.
    let bug_use = features.bug_use.points;
    Rating {
        score: base_score.map(|base| (base + bug_use).min(100.0).round()),
        base_score,
        bug_use,
        reason,
        confidence: n / (n + K),
        conversion: subs[0],
        smash_quality: subs[1],
        exposure: subs[2],
        vertical: subs[3],
        resources: subs[4],
        opponent,
        opponent_known,
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
/// nous, donc sa conversion est l'envers de notre défense. C'est pourquoi il
/// n'entre pas dans la note de CE duel ; moyenné sur nos duels précédents
/// contre lui, il devient sa force connue ([`Prior`]), qui y entre.
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
        let one = super::super::features::Opportunity {
            open: 0,
            close: 0,
            converted: false,
            hit: None,
            expected: 0.0,
            realized: 0.0,
            fall: 0.0,
            hit_fall: 0.0,
            possible_fall: 0.0,
            offset: 0.0,
            target_speed: 0.0,
            behind_shield: false,
            chance: 0.5,
        };
        Features {
            mine: vec![one; opps],
            theirs: Vec::new(),
            engaged_secs: secs,
            conversion: each,
            split: Default::default(),
            smash_quality: each,
            exposure: each,
            avoided: None,
            vertical: each,
            foe: super::super::features::FoeMeasures { conversion: foe, smash_quality: foe, exposure: foe, vertical: foe },
            resources: each,
            bug_use: Default::default(),
            smash_charge: None,
            details: Default::default(),
            raw_mine: Default::default(),
            raw_foe: Default::default(),
            smashes: Vec::new(),
            detections: Vec::new(),
        }
    }

    #[test]
    fn le_bonus_bug_use_ne_fait_que_monter_la_note() {
        let mut played = features(20, 120.0, Measure { value: 0.5, count: 20.0 });
        let without = unknown(&played);
        played.bug_use.points = 4.0;
        let with = unknown(&played);
        assert_eq!(with.base_score, without.score, "la note des familles ne bouge pas");
        assert_eq!(with.score, without.score.map(|s| s + 4.0));
        let mut strong = features(20, 120.0, Measure { value: 1.0, count: 200.0 });
        strong.bug_use.points = 10.0;
        assert_eq!(unknown(&strong).score, Some(100.0), "plafonnée à 100");
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
        assert!((perfect_on_two.smoothed - (2.0 + K * CONVERSION.mean) / (2.0 + K)).abs() < 1e-12);
        assert!(perfect_on_two.smoothed < 0.7, "un 100 % sur deux opportunités ne vaut pas 100 %");
        let nothing = sub(Measure { value: 0.0, count: 0.0 }, &CONVERSION);
        assert_eq!(nothing.z, 0.0, "aucun événement : la famille ne pèse rien");
    }

    #[test]
    fn un_duel_sans_evenement_vaut_cinquante_et_un_duel_court_n_est_pas_note() {
        let empty = Measure { value: 0.0, count: 0.0 };
        assert_eq!(unknown(&features(5, 60.0, empty)).score, Some(50.0));
        assert_eq!(unknown(&features(5, 60.0, empty)).own.score, 50.0, "mon jeu seul, sans rien mesurer : moyen");
        assert_eq!(unknown(&features(5, 10.0, empty)).reason, Some("too_short"));
        assert_eq!(unknown(&features(5, 10.0, empty)).score, None);
        assert_eq!(unknown(&features(2, 60.0, empty)).reason, Some("too_few_opportunities"));
    }

    #[test]
    fn la_note_monte_avec_les_familles_et_la_confiance_avec_les_opportunites() {
        let strong = unknown(&features(20, 120.0, Measure { value: 0.9, count: 20.0 }));
        let weak = unknown(&features(20, 120.0, Measure { value: 0.1, count: 20.0 }));
        assert!(strong.score.unwrap() > 90.0 && weak.score.unwrap() < 10.0);
        assert!(unknown(&features(20, 120.0, Measure { value: 0.9, count: 20.0 })).confidence > unknown(&features(4, 120.0, Measure { value: 0.9, count: 4.0 })).confidence);
        // La note reste à portée de ses familles : cinq écarts qui valent 52,
        // 83, 86, 68 et 97 ne font pas un 97 (c'était le cas avec ×√5).
        let seen = [0.05, 0.95, 1.08, 0.47, 1.88];
        let combined = 100.0 * phi(seen.iter().sum::<f64>() / 5.0 * SPREAD_FACTOR);
        assert!(combined > 80.0 && combined < 92.0, "{combined}");
    }

    /// Contre un adversaire jamais affronté.
    fn unknown(features: &Features) -> Rating {
        rate(features, &Prior::default())
    }

    #[test]
    fn le_niveau_mesure_pendant_le_duel_est_affiche_mais_ne_compte_pas() {
        let mine = Measure { value: 0.5, count: 20.0 };
        let strong = unknown(&against(20, 120.0, mine, Measure { value: 0.9, count: 20.0 }));
        let weak = unknown(&against(20, 120.0, mine, Measure { value: 0.1, count: 20.0 }));
        assert!(strong.opponent.score > 90.0 && weak.opponent.score < 10.0, "il est bien mesuré");
        assert_eq!(strong.score, weak.score, "mais c'est le miroir de notre jeu : il ne touche pas la note");
        assert_eq!(strong.opponent_known.score, 50.0, "un inconnu vaut la référence");
    }

    #[test]
    fn la_force_connue_avant_le_duel_pese_dans_la_note() {
        let mine = Measure { value: 0.5, count: 20.0 };
        let features = features(20, 120.0, mine);
        let strong = rate(&features, &Prior::from_levels(&[1.5, 1.2, 1.8]));
        let weak = rate(&features, &Prior::from_levels(&[-1.5, -1.2, -1.8]));
        let first = unknown(&features);
        // Le même jeu vaut plus contre un adversaire connu pour être fort.
        assert!(strong.score.unwrap() > first.score.unwrap());
        assert!(first.score.unwrap() > weak.score.unwrap());
        assert_eq!(strong.opponent_known.count, 3.0);
    }

    #[test]
    fn un_adversaire_peu_vu_reste_proche_de_la_reference() {
        let once = Prior::from_levels(&[1.5]);
        assert!((once.z - 0.5).abs() < 1e-12, "un duel passé compte pour un tiers");
        let often = Prior::from_levels(&[1.5; 8]);
        assert!((often.z - 1.2).abs() < 1e-12, "huit duels passés : quatre cinquièmes");
        assert_eq!(Prior::from_levels(&[]), Prior::default());
    }
}
