//! Du journal d'un duel à sa note (docs/scoring/). Aucune classe du jeu,
//! aucune horloge, aucun fichier : du texte en entrée, un rapport en sortie.
//! Tout se teste sur des journaux enregistrés, sans lancer Minecraft.
//!
//!   parse     le texte du journal → un duel en mémoire
//!   features  les opportunités de smash et les cinq familles
//!   rating    lissage, normalisation, note sur 100, confiance
//!
//! Le journal est la seule source : features et note en sont dérivées et
//! portent leur version, pour être recalculées quand une formule change.

mod features;
mod parse;
mod rating;

use serde::Serialize;

/// Version de l'extraction des features. Changer la définition d'une
/// opportunité ou d'une famille l'incrémente.
pub const EXTRACTOR_VERSION: u32 = 1;

/// Le décompte des opportunités d'un joueur.
#[derive(Debug, Serialize)]
pub struct Opportunities {
    pub opened: usize,
    pub converted: usize,
    /// Somme des bonus de smash possibles, et de ceux réellement portés.
    pub expected: f64,
    pub realized: f64,
}

impl Opportunities {
    fn of(list: &[features::Opportunity]) -> Self {
        let round = |v: f64| (v * 10.0).round() / 10.0;
        Self {
            opened: list.len(),
            converted: list.iter().filter(|o| o.converted).count(),
            expected: round(list.iter().map(|o| o.expected).sum()),
            realized: round(list.iter().map(|o| o.realized).sum()),
        }
    }
}

/// Le résumé d'un duel : ce qui est écrit à côté du journal, et ce que
/// l'interface montrera.
#[derive(Debug, Serialize)]
pub struct Report {
    pub extractor_version: u32,
    pub score_version: u32,
    /// `win`, `loss`, `draw`, `unknown` — absent si le journal s'arrête avant le verdict.
    pub result: Option<String>,
    pub engaged_secs: f64,
    pub mine: Opportunities,
    pub theirs: Opportunities,
    /// Charge d'attaque moyenne à mes smashs (relevée, pas notée).
    pub smash_charge: Option<f64>,
    #[serde(flatten)]
    pub rating: rating::Rating,
}

/// Note un journal. `None` si le texte n'en est pas un.
pub fn score_journal(text: &str) -> Option<Report> {
    let duel = parse::parse(text)?;
    let features = features::extract(&duel);
    Some(Report {
        extractor_version: EXTRACTOR_VERSION,
        score_version: rating::SCORE_VERSION,
        result: duel.result.clone(),
        engaged_secs: features.engaged_secs,
        mine: Opportunities::of(&features.mine),
        theirs: Opportunities::of(&features.theirs),
        smash_charge: features.smash_charge,
        rating: rating::rate(&features),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Deux vrais duels à la masse du 2026-10-13, pseudos et UUID remplacés.
    const DEFEAT: &str = include_str!("fixtures/duel_masse_defaite.ndjson");
    const ABANDONED: &str = include_str!("fixtures/duel_masse_abandon.ndjson");

    #[test]
    fn un_vrai_duel_perdu_sort_une_note() {
        let report = score_journal(DEFEAT).expect("un journal");
        println!("{}", serde_json::to_string_pretty(&report).unwrap());
        assert_eq!(report.result.as_deref(), Some("loss"));
        // Trois smashs confirmés de notre joueur dans ce journal, un de l'adversaire.
        assert_eq!(report.mine.converted, 3);
        assert_eq!(report.theirs.converted, 1);
        assert!(report.mine.opened >= report.mine.converted);
        let score = report.rating.score.expect("assez d'opportunités pour une note");
        assert!((0.0..=100.0).contains(&score));
        assert!(report.rating.confidence > 0.0 && report.rating.confidence < 1.0);
    }

    #[test]
    fn un_duel_abandonne_avant_le_verdict_se_lit_quand_meme() {
        let report = score_journal(ABANDONED).expect("un journal");
        println!("{}", serde_json::to_string_pretty(&report).unwrap());
        assert_eq!(report.result, None, "le journal s'arrête avant le verdict");
        assert_eq!(report.theirs.converted, 2, "deux smashs adverses confirmés");
        assert_eq!(report.mine.converted, 0);
    }

    #[test]
    fn un_texte_qui_n_est_pas_un_journal_ne_donne_rien() {
        assert!(score_journal("pas un journal").is_none());
    }

    /// Outil de mise au point : note un journal déjà décompressé.
    /// `YF_JOURNAL_TEXT=<journal.ndjson> cargo test --lib note_un_journal -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn note_un_journal() {
        let path = std::env::var("YF_JOURNAL_TEXT").expect("YF_JOURNAL_TEXT : le journal décompressé");
        let report = score_journal(&std::fs::read_to_string(path).unwrap()).expect("un journal");
        println!("{}", serde_json::to_string_pretty(&report).unwrap());
    }
}
