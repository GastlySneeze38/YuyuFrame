//! Des cinq familles à la note (docs/scoring/score.md) : lissage, mise à
//! l'échelle, combinaison, note sur 500, points de chaque action, confiance.
//!
//! **Note sur 500 et actions récompensées (2026-10-22).** Chaque action d'un
//! duel est jugée face à ce qu'en tire un joueur moyen (`features::Norm`) ;
//! une famille est la somme de ces écarts, une note la moyenne des
//! familles. La note se lit `250 + 100 × z`, en ligne droite : un même
//! progrès vaut autant partout, et 500 n'est atteint que par un écart de
//! 2,5 (environ un duel sur 160). Comme tout y est une somme, la note se
//! découpe exactement en points d'action : c'est ce que l'écran montre.
//!
//! Les écarts habituels se recalculent sur les duels partagés
//! (docs/scoring/calibration.md), et `SCORE_VERSION` monte avec eux — deux
//! formules ne se mélangent jamais.

use super::features::{Features, Measure};
use super::parse::Mode;

/// Version de la formule. Tout changement de coefficient ou de référence
/// l'incrémente.
// Version 4 (2026-10-14) : la sixième famille est la force de l'adversaire
// connue avant le duel, et non plus son niveau mesuré pendant.
// Version 5 (2026-10-14) : les familles refaites d'après docs/scoring/familles/
// et le bonus bug use, hors de la moyenne.
// Version 6 (2026-10-15) : les parties des familles recentrées sur ce que
// font en moyenne les joueurs observés.
// Version 7 (2026-10-15) : la chute possible d'un smash va jusqu'à la tête
// de la cible, le critique de l'adversaire se lit au son, et les familles
// comptent de vraies situations au lieu de tranches de temps.
// Version 8 (2026-10-15) : une chute n'est plus comptée deux fois après deux
// smashs ratés ; « gros coup du premier coup » ne regarde que les descentes
// qui visent l'adversaire, et pas celles ratées face à son bouclier levé.
// Version 9 (2026-10-15) : bug use sans l'elytra swap, au quart de ses
// points, et de moins en moins pour une même technique répétée.
// Version 10 (2026-10-15) : aucun smash réussi sur des ouvertures vaut 0 en
// qualité du smash, au lieu de ne rien peser.
// Version 11 (2026-10-15) : critiques rattachés au coup et non à la position
// du son ; référence de la qualité du smash à 0,63 ; swap de l'adversaire
// reconnu à la masse qui apparaît après son smash.
// Version 12 (2026-10-15) : centres recalés sur 23 duels, grosse chute à 14
// blocs, « atterrir proprement » seulement quand une chute a fait mal.
// Version 13 (2026-10-15) : lissage à 2 événements au lieu de 5.
// Version 14 (2026-10-22) : note sur 500 en ligne droite, chaque action
// jugée face à ce qu'en tire un joueur moyen, points d'action.
// Version 15 (2026-10-22) : attendus, modèles de chance, écarts des
// familles et élargissement calibrés sur 31 duels notés partagés.
// Version 16 (2026-10-22) : l'initiative dans le contrôle, les échanges
// dans la défense, et le calibrage qui va avec.
// Version 17 (2026-10-22) : la force connue de l'adversaire sort de la note
// (gardée pour le classement) ; cinq familles.
// Version 18 (2026-10-22) : l'épée est notée.
// Version 19 (2026-10-22) : l'élargissement se déduit de la corrélation
// mesurée entre familles (masse 1,37, épée 1,37 au lieu de 2,0).
// Version 20 (2026-10-22) : placement à l'épée = premier coup de l'échange
// (ouvert après 1 s de calme) et strafe ; portée et espacement hors note.
// Version 21 (2026-10-22) : un duel à l'épée se note dès 5 s au contact et
// 15 coups (20 s et 6 coups avant).
pub const SCORE_VERSION: u32 = 21;

/// Poids du lissage : une famille observée sur `K` événements est tirée à
/// moitié vers l'attendu. Ramené de 5 à 2 le 2026-10-15 : sur peu de
/// matière, la moyenne effaçait ce qui s'était passé. Un seul événement
/// compte pour un tiers.
pub(super) const K: f64 = 2.0;

/// L'échelle d'un mode : ce qui ramène ses familles à la note.
pub(super) struct Scale {
    /// De combien une famille s'écarte d'ordinaire d'un duel à l'autre,
    /// après lissage (dans l'unité de `Measure::gap`) : ce qui la ramène à
    /// un écart de note. Dans l'ordre des cases du résumé — les quatre
    /// premières servent aussi à l'adversaire.
    pub spread: [f64; 5],
    /// À quel point les familles montent ensemble chez un même joueur
    /// (corrélation moyenne entre deux familles), mesuré par `calibration`.
    /// C'est elle qui donne l'élargissement (`factor`) : jamais posé à la main.
    pub correlation: f64,
    /// Combien de familles font la note, les premières cases du résumé.
    pub families: usize,
    /// En dessous de ce temps au contact, le duel n'est pas noté.
    pub min_engaged_secs: f64,
    /// En dessous de ce nombre de situations, le duel n'est pas noté.
    pub min_evidence: usize,
    /// La confiance : situations / (situations + ce nombre).
    pub k_confidence: f64,
}

/// La masse. Écarts relevés le 2026-10-22 sur 31 duels notés partagés, les
/// deux joueurs (`calibration`, test `proposition_de_calibrage`) — celui de
/// la qualité du smash est large : un duel sans aucun smash sur ses
/// ouvertures y pèse lourd. Élargissement posé à 1,3 au jugé, relevé à 1,83
/// sur ces duels (ce qui donne à la note un écart de 100), à 1,68 avec
/// l'initiative et les échanges, qui lient un peu plus les familles, puis à
/// 1,40 quand la force connue de l'adversaire est sortie de la moyenne (cinq
/// familles au lieu de six : la même échelle). Depuis le 2026-10-22 il se
/// déduit de la corrélation entre familles, mesurée sur ces 31 joueurs :
/// 0,412, soit un élargissement de 1,37. La confiance garde son échelle
/// d'origine : 5 opportunités donnent 50 %, 20 en donnent 80 %.
const MACE: Scale = Scale { spread: [0.365, 0.708, 0.256, 0.249, 0.211], correlation: 0.412, families: 5, min_engaged_secs: 20.0, min_evidence: 3, k_confidence: 5.0 };
/// L'épée (2026-10-22) : combos, qualité du coup, défense, placement — pas
/// de ressources, le kit noté n'a que l'épée. Écarts relevés sur 23 duels à
/// l'épée partagés (kits avec totems compris : les coups sont les mêmes) ;
/// seuls 8 sont à l'épée seule, trop peu pour calibrer dessus. Placement
/// refait le 2026-10-22 (premier coup de l'échange et strafe, portée et
/// espacement sortis) : écart 0,308 sur les 8 duels à l'épée seule.
/// Corrélation entre familles : +0,173 mesurée sur ces 8 joueurs (±0,34),
/// tirée vers celle de la masse comme si elle valait 30 joueurs, soit 0,362
/// et un élargissement de 1,39. (Un 2,0 posé à la main la supposait nulle :
/// un joueur bon partout passait au-dessus de toutes ses familles, et au
/// plafond.) *À recalibrer* sur les duels à l'épée seule (`YF_MODE=sword`)
/// dès qu'il y en aura une cinquantaine. Les situations sont les coups
/// échangés : 12 donnent une confiance de 50 %. Un duel à l'épée est court
/// (la moitié des 18 duels locaux durent moins de 25 s au contact, un kill
/// propre en 8 s) : les 20 s de la masse en écartaient de vrais. Il est
/// donc jugé sur ses coups — 15 au moins — et 5 s ne servent qu'à écarter
/// une rencontre de passage.
const SWORD: Scale = Scale { spread: [0.304, 0.416, 0.287, 0.308, 1.0], correlation: 0.362, families: 4, min_engaged_secs: 5.0, min_evidence: 15, k_confidence: 12.0 };

impl Scale {
    /// De la moyenne des écarts à l'écart de la note, comme un QI total est
    /// recalé sur la répartition des totaux et non sur celle d'un indice :
    /// une moyenne de familles est plus resserrée qu'une famille seule, et
    /// d'autant moins qu'elles montent ensemble. Avec n familles de
    /// corrélation moyenne ρ, l'écart de leur moyenne vaut
    /// √((1 + (n − 1)ρ) / n) ; l'élargissement en est l'inverse, de sorte
    /// que la note ait le même étalement qu'une famille (100 points).
    /// Indépendantes, √n ; parfaitement liées, 1 (la moyenne).
    pub fn factor(&self) -> f64 {
        let n = self.families as f64;
        (n / (1.0 + (n - 1.0) * self.correlation)).sqrt()
    }
}

pub(super) fn scale_of(mode: Mode) -> &'static Scale {
    match mode {
        Mode::Sword => &SWORD,
        _ => &MACE,
    }
}

/// La note d'un joueur moyen, ce que vaut un écart, et le plafond.
pub const CENTER: f64 = 250.0;
const PER_Z: f64 = 100.0;
pub const MAX: f64 = 500.0;
/// Ce que pèse un écart d'une seule famille dans la note : les familles du
/// mode sont à poids égaux. La force connue de l'adversaire n'y entre plus
/// (2026-10-22) : elle est gardée pour le classement.
fn points_per_z(s: &Scale) -> f64 {
    PER_Z * s.factor() / s.families as f64
}

/// Un écart, sur l'échelle de la note.
fn scale(z: f64) -> f64 {
    (CENTER + PER_Z * z).clamp(0.0, MAX)
}

/// Une famille dans la note : son écart à l'attendu, lissé, puis ramené à
/// un écart de note.
#[derive(Debug, Clone, Copy, serde::Serialize)]
pub struct Sub {
    pub gap: f64,
    pub count: f64,
    pub smoothed: f64,
    pub z: f64,
    /// La famille seule, sur 500 : ce que l'interface montre.
    pub score: f64,
    /// Ce qu'elle a fait gagner ou perdre à la note (0 pour un niveau
    /// combiné, qui n'y entre pas tel quel).
    pub points: f64,
}

fn sub(measure: Measure, spread: f64, per_z: f64) -> Sub {
    // Peu d'événements : l'écart est tiré vers l'attendu. Aucun : c'est l'attendu.
    let smoothed = measure.gap * measure.count / (measure.count + K);
    let z = smoothed / spread;
    Sub { gap: measure.gap, count: measure.count, smoothed, z, score: scale(z).round(), points: per_z * z }
}

/// Une action et ce qu'elle a rapporté à la note. Les points de toutes les
/// actions d'une famille font exactement ceux de la famille.
#[derive(Debug, Clone, PartialEq)]
pub struct Scored {
    /// Son rang dans le résumé (0 conversion … 4 ressources).
    pub family: usize,
    pub t: Option<u64>,
    pub code: &'static str,
    pub points: f64,
    pub value: f64,
    pub expected: f64,
}

#[derive(Debug, serde::Serialize)]
pub struct Rating {
    /// De 0 à 500, bonus bug use compris ; absente si le duel n'est pas noté
    /// (`reason` dit pourquoi).
    pub score: Option<f64>,
    /// La note des cinq familles, avant le bonus (« 312 » dans « 312 + 20 bug use »).
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
    /// mesurées de son côté et jugées avec les mêmes attendus. Affiché,
    /// mais il n'entre plus dans la note (2026-10-14) : on ne le voit jouer
    /// que contre nous, donc il est en grande partie le miroir de notre jeu —
    /// sa conversion est l'envers de notre défense, sa défense l'envers de
    /// notre conversion. Dans la note, il annulait une partie de notre propre
    /// jeu : dominer faisait paraître l'adversaire faible et plafonnait la note.
    pub opponent: Sub,
    /// La force de l'adversaire connue AVANT ce duel ([`Prior`]). Elle est
    /// entrée dans la note comme sixième famille jusqu'au 2026-10-22 ; elle
    /// n'y entre plus (`points` vaut 0) et reste calculée pour le classement.
    pub opponent_known: Sub,
    /// Les quatre familles dont le niveau de l'adversaire est fait, dans
    /// l'ordre : conversion, qualité du smash, défense, contrôle vertical.
    pub opponent_families: [Sub; 4],
    /// Mon jeu seul : mes cinq familles combinées comme la note, sans le
    /// niveau de l'adversaire. À lire à côté de `opponent` — c'est la
    /// comparaison des deux que la note résume.
    pub own: Sub,
    /// Chaque action de mes cinq familles et ses points. Hors du résumé
    /// écrit sur le disque : elle se relit du journal (`score::detail_text`).
    #[serde(skip)]
    pub actions: Vec<Scored>,
}

/// Poids du lissage de la force connue : un adversaire vu dans `K_PRIOR`
/// duels compte pour moitié ce qu'on en a mesuré, pour moitié l'attendu.
/// Un seul duel passé compte pour un tiers.
pub const K_PRIOR: f64 = 2.0;

/// La force d'un adversaire telle que nos duels précédents contre lui la
/// font connaître : la moyenne de son niveau dans ces duels (en écarts de
/// note), tirée vers l'attendu tant qu'on l'a peu vu.
///
/// Elle ne vient jamais du duel qu'on note : c'est ce qui la rend
/// indépendante de notre jeu ce jour-là.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct Prior {
    /// En écarts de note ; 0 pour un inconnu.
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
    let s = scale_of(features.mode);
    let per_z = points_per_z(s);
    let measures = [features.conversion, features.smash_quality, features.exposure, features.vertical, features.resources];
    let subs: Vec<Sub> = measures.iter().zip(s.spread).map(|(m, spread)| sub(*m, spread, per_z)).collect();
    let (opponent, opponent_families) = opponent_level(features, s);
    let reason = if features.engaged_secs < s.min_engaged_secs {
        Some("too_short")
    } else if features.evidence < s.min_evidence {
        Some("too_few_opportunities")
    } else {
        None
    };
    // Mes cinq familles à poids égaux, puis la moyenne ramenée à l'échelle
    // d'un écart. La force connue de l'adversaire est calculée pour le
    // classement, hors de la note.
    let opponent_known = Sub { gap: prior.z, count: prior.duels as f64, smoothed: prior.z, z: prior.z, score: scale(prior.z).round(), points: 0.0 };
    let counted = &subs[..s.families];
    let mean = counted.iter().map(|s| s.z).sum::<f64>() / s.families as f64;
    let z = mean * s.factor();
    let n = features.evidence as f64;
    let own = combined(counted, n, s.factor());
    let base_score = reason.is_none().then(|| scale(z).round());
    // Le bonus s'ajoute à la note des cinq familles, plafonné à +50 (déjà
    // fait à l'extraction), la note restant plafonnée à 500.
    let bug_use = features.bug_use.points;
    // Les points de chaque action : sa part de l'écart de la famille, à la
    // même échelle que la famille.
    let mut actions = Vec::new();
    for (family, list) in features.actions.iter().enumerate() {
        let m = measures[family];
        let per_gap = per_z / s.spread[family] * m.count / (m.count + K);
        actions.extend(list.iter().map(|a| Scored { family, t: a.t, code: a.code, points: per_gap * a.gap(), value: a.value, expected: a.expected }));
    }
    Rating {
        score: base_score.map(|base| (base + bug_use).min(MAX).round()),
        base_score,
        bug_use,
        reason,
        confidence: n / (n + s.k_confidence),
        conversion: subs[0],
        smash_quality: subs[1],
        exposure: subs[2],
        vertical: subs[3],
        resources: subs[4],
        opponent,
        opponent_known,
        opponent_families,
        own,
        actions,
    }
}

/// Des familles combinées comme une note : moyenne des écarts, élargie par
/// l'élargissement du mode, ramenée sur l'échelle de la note.
fn combined(subs: &[Sub], count: f64, factor: f64) -> Sub {
    let z = subs.iter().map(|s| s.z).sum::<f64>() / subs.len() as f64 * factor;
    Sub { gap: z, count, smoothed: z, z, score: scale(z).round(), points: 0.0 }
}

/// Le niveau de l'adversaire, sur l'échelle de la note.
///
/// Ce sont ses quatre familles mesurables, jugées avec les mêmes attendus
/// que les nôtres, puis combinées comme une note. `count` est le nombre
/// d'opportunités sur lequel il repose.
///
/// Il n'est pas indépendant de notre jeu : on ne le voit jouer que contre
/// nous, donc sa conversion est l'envers de notre défense. C'est pourquoi il
/// n'entre pas dans la note de CE duel ; moyenné sur nos duels précédents
/// contre lui, il devient sa force connue ([`Prior`]), gardée pour le classement.
fn opponent_level(features: &Features, s: &Scale) -> (Sub, [Sub; 4]) {
    let foe = &features.foe;
    let measures = [foe.conversion, foe.smash_quality, foe.exposure, foe.vertical];
    let subs = [0, 1, 2, 3].map(|i| sub(measures[i], s.spread[i], 0.0));
    (combined(&subs, features.evidence as f64, s.factor()), subs)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Un adversaire dont on ne sait rien : il pèse comme l'attendu.
    const UNKNOWN_FOE: Measure = Measure { gap: 0.0, count: 0.0 };

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
            mode: Mode::Mace,
            evidence: opps,
            sword: None,
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
            actions: Default::default(),
            foe_actions: Default::default(),
            smashes: Vec::new(),
            detections: Vec::new(),
        }
    }

    /// Contre un adversaire jamais affronté.
    fn unknown(features: &Features) -> Rating {
        rate(features, &Prior::default())
    }

    #[test]
    fn l_elargissement_suit_la_correlation_entre_familles() {
        let with = |correlation: f64| Scale { spread: [1.0; 5], correlation, families: 4, min_engaged_secs: 0.0, min_evidence: 0, k_confidence: 1.0 }.factor();
        // Indépendantes : √4 ; parfaitement liées : la simple moyenne.
        assert!((with(0.0) - 2.0).abs() < 1e-9);
        assert!((with(1.0) - 1.0).abs() < 1e-9);
        // Le duel qui touchait le plafond : familles 456, 252, 442, 447.
        let mean = [2.06, 0.02, 1.92, 1.97].iter().sum::<f64>() / 4.0;
        assert_eq!(scale(mean * with(0.0)), MAX);
        let now = scale(mean * SWORD.factor());
        assert!(now > 440.0 && now < 460.0, "{now}");
    }

    #[test]
    fn le_bonus_bug_use_ne_fait_que_monter_la_note() {
        let mut played = features(20, 120.0, Measure { gap: 0.0, count: 20.0 });
        let without = unknown(&played);
        played.bug_use.points = 20.0;
        let with = unknown(&played);
        assert_eq!(with.base_score, without.score, "la note des familles ne bouge pas");
        assert_eq!(with.score, without.score.map(|s| s + 20.0));
        let mut strong = features(20, 120.0, Measure { gap: 5.0, count: 200.0 });
        strong.bug_use.points = 50.0;
        assert_eq!(unknown(&strong).score, Some(MAX), "plafonnée à 500");
    }

    #[test]
    fn la_note_est_en_ligne_droite() {
        assert_eq!(scale(0.0), 250.0);
        assert_eq!(scale(1.0) - scale(0.5), scale(0.5) - scale(0.0), "un même progrès vaut autant partout");
        assert_eq!(scale(2.5), 500.0);
        assert_eq!(scale(-4.0), 0.0);
    }

    #[test]
    fn peu_d_evenements_ramene_vers_l_attendu() {
        let two = sub(Measure { gap: 1.0, count: 2.0 }, 0.5, 1.0);
        assert!((two.smoothed - 0.5).abs() < 1e-12, "deux événements : à moitié tiré vers l'attendu");
        let nothing = sub(Measure { gap: 0.0, count: 0.0 }, 0.5, 1.0);
        assert_eq!(nothing.z, 0.0, "aucun événement : la famille ne pèse rien");
        assert_eq!(nothing.score, CENTER);
    }

    #[test]
    fn un_duel_sans_evenement_vaut_le_centre_et_un_duel_court_n_est_pas_note() {
        let empty = Measure { gap: 0.0, count: 0.0 };
        assert_eq!(unknown(&features(5, 60.0, empty)).score, Some(CENTER));
        assert_eq!(unknown(&features(5, 60.0, empty)).own.score, CENTER, "mon jeu seul, sans rien mesurer : moyen");
        assert_eq!(unknown(&features(5, 10.0, empty)).reason, Some("too_short"));
        assert_eq!(unknown(&features(5, 10.0, empty)).score, None);
        assert_eq!(unknown(&features(2, 60.0, empty)).reason, Some("too_few_opportunities"));
    }

    #[test]
    fn la_note_monte_avec_les_familles_et_la_confiance_avec_les_opportunites() {
        let strong = unknown(&features(20, 120.0, Measure { gap: 0.6, count: 20.0 }));
        let weak = unknown(&features(20, 120.0, Measure { gap: -0.6, count: 20.0 }));
        assert!(strong.score.unwrap() > 400.0 && weak.score.unwrap() < 100.0, "{:?} {:?}", strong.score, weak.score);
        assert_eq!(strong.score.unwrap() - CENTER, CENTER - weak.score.unwrap(), "symétrique autour du centre");
        assert!(unknown(&features(20, 120.0, Measure { gap: 0.6, count: 20.0 })).confidence > unknown(&features(4, 120.0, Measure { gap: 0.6, count: 4.0 })).confidence);
    }

    #[test]
    fn les_points_des_familles_font_la_note() {
        let r = unknown(&features(20, 120.0, Measure { gap: 0.2, count: 20.0 }));
        let families = [r.conversion, r.smash_quality, r.exposure, r.vertical, r.resources];
        let points: f64 = families.iter().map(|s| s.points).sum::<f64>();
        assert!((CENTER + points - r.base_score.unwrap()).abs() <= 0.5, "{} + {points} ≠ {:?}", CENTER, r.base_score);
    }

    #[test]
    fn le_niveau_mesure_pendant_le_duel_est_affiche_mais_ne_compte_pas() {
        let mine = Measure { gap: 0.0, count: 20.0 };
        let strong = unknown(&against(20, 120.0, mine, Measure { gap: 0.6, count: 20.0 }));
        let weak = unknown(&against(20, 120.0, mine, Measure { gap: -0.6, count: 20.0 }));
        assert!(strong.opponent.score > 400.0 && weak.opponent.score < 100.0, "il est bien mesuré");
        assert_eq!(strong.score, weak.score, "mais c'est le miroir de notre jeu : il ne touche pas la note");
        assert_eq!(strong.opponent_known.score, CENTER, "un inconnu vaut l'attendu");
    }

    #[test]
    fn la_force_connue_avant_le_duel_est_gardee_mais_ne_compte_pas() {
        let features = features(20, 120.0, Measure { gap: 0.0, count: 20.0 });
        let strong = rate(&features, &Prior::from_levels(&[1.5, 1.2, 1.8]));
        let weak = rate(&features, &Prior::from_levels(&[-1.5, -1.2, -1.8]));
        assert_eq!(strong.score, weak.score, "le même jeu vaut la même note, quel que soit l'adversaire");
        assert!(strong.opponent_known.score > weak.opponent_known.score, "elle reste calculée pour le classement");
        assert_eq!(strong.opponent_known.count, 3.0);
        assert_eq!(strong.opponent_known.points, 0.0);
    }

    #[test]
    fn un_adversaire_peu_vu_reste_proche_de_l_attendu() {
        let once = Prior::from_levels(&[1.5]);
        assert!((once.z - 0.5).abs() < 1e-12, "un duel passé compte pour un tiers");
        let often = Prior::from_levels(&[1.5; 8]);
        assert!((often.z - 1.2).abs() < 1e-12, "huit duels passés : quatre cinquièmes");
        assert_eq!(Prior::from_levels(&[]), Prior::default());
    }
}
