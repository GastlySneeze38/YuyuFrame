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

pub use parse::Mode;
pub use rating::Prior;

/// Version de l'extraction des features. Changer la définition d'une
/// opportunité ou d'une famille l'incrémente.
// Version 3 (2026-10-14) : les familles de docs/scoring/familles/.
pub const EXTRACTOR_VERSION: u32 = 3;

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
    /// Le pseudo de l'adversaire et l'heure d'ouverture du duel (millisecondes
    /// depuis 1970), pour l'historique.
    pub foe_name: Option<String>,
    /// Son UUID sans tirets : ce qui le reconnaît d'un duel à l'autre.
    pub foe_uuid: Option<String>,
    pub time: Option<u64>,
    /// Reconnu à l'arme en main ; seuls les duels à la masse sont notés.
    pub mode: Mode,
    pub engaged_secs: f64,
    pub mine: Opportunities,
    pub theirs: Opportunities,
    /// Charge d'attaque moyenne à mes smashs, telle que le jeu la compte au coup.
    pub smash_charge: Option<f64>,
    /// Mes grosses et mes petites ouvertures, à part (hors note).
    pub split: features::Split,
    /// Part des dégâts adverses neutralisés : ouvertures ratées et smashs
    /// arrêtés, face à tout ce qu'il pouvait mettre (hors note).
    pub avoided: Option<f64>,
    /// Les techniques du bonus bug use, les miennes et ce qu'on voit des siennes.
    /// Pas `bug_use` : la note (aplatie dans ce résumé) porte déjà ce nom pour le bonus.
    pub techniques: features::BugUse,
    /// Ping moyen pendant le duel, en millisecondes (affiché, pas corrigé).
    pub ping: Option<f64>,
    #[serde(flatten)]
    pub rating: rating::Rating,
}

/// Nombre de valeurs de [`Report::numbers`].
pub const REPORT_LEN: usize = 46;

impl Report {
    /// Ce qui reconnaît l'adversaire d'un duel à l'autre : son UUID, à
    /// défaut son pseudo en minuscules. `None` s'il n'est pas connu.
    pub fn foe_key(&self) -> Option<String> {
        self.foe_uuid.clone().or_else(|| self.foe_name.as_ref().map(|n| n.to_lowercase()))
    }

    /// Ce duel peut-il servir à connaître la force de son adversaire pour
    /// les suivants ? Un duel noté — assez long, assez d'opportunités, à la
    /// masse : sinon son niveau mesuré ne veut rien dire.
    pub fn informs_prior(&self) -> bool {
        self.rating.score.is_some()
    }

    /// Le résumé en nombres, pour l'interface en jeu — contrat avec
    /// `runtime/combat/NativeCombat.java` (constantes `REPORT_*`). Dans
    /// l'ordre : note (−1 si le duel n'est pas noté) ; confiance ; résultat
    /// (0 aucun, 1 victoire, 2 défaite, 3 nul, 4 inconnu) ; raison de
    /// l'absence de note (0 aucune, 1 duel trop court, 2 trop peu
    /// d'opportunités) ; les six familles sur 100 (conversion, qualité du
    /// smash, exposition, contrôle vertical, ressources, niveau de l'adversaire) ;
    /// ping (−1 si inconnu) ; mes opportunités ouvertes puis converties ;
    /// celles de l'adversaire ; secondes au contact.
    ///
    /// Puis, pour le détail (ABI 7) : les quatre familles du niveau de
    /// l'adversaire sur 100 (conversion, qualité du smash, défense, contrôle
    /// vertical) ; le nombre d'événements derrière chacune de mes cinq
    /// familles, puis derrière chacune des quatre siennes ; mes bonus de
    /// smash portés puis possibles, les siens ; la charge d'attaque moyenne à
    /// mes smashs (−1 si aucun) ; mon jeu seul sur 100.
    ///
    /// Puis (ABI 8) : la force de l'adversaire connue avant le duel sur 100,
    /// le nombre de duels passés sur lesquels elle repose, et le mode
    /// ([`Mode::code`]). La raison de l'absence de note peut valoir 3 : le
    /// duel n'est pas à la masse. Et la sixième famille (index 9) reste le
    /// niveau de l'adversaire mesuré PENDANT le duel — affiché, hors note.
    ///
    /// Puis (ABI 10, familles de docs/scoring/familles/) : la note des six
    /// familles avant le bonus (−1 si le duel n'est pas noté) ; le bonus bug
    /// use ; les points de ses techniques à lui (affichés, jamais comptés) ;
    /// mes grosses ouvertures ouvertes puis converties, mes petites ; la part
    /// des dégâts adverses évités sur 100 (−1 si rien à éviter). La note
    /// (index 0) comprend le bonus.
    pub fn numbers(&self) -> [f64; REPORT_LEN] {
        let result = match self.result.as_deref() {
            Some("win") => 1.0,
            Some("loss") => 2.0,
            Some("draw") => 3.0,
            Some(_) => 4.0,
            None => 0.0,
        };
        let reason = match self.rating.reason {
            Some("too_short") => 1.0,
            Some(UNSCORED_MODE) => 3.0,
            Some(_) => 2.0,
            None => 0.0,
        };
        let r = &self.rating;
        let foe = &r.opponent_families;
        [
            self.rating.score.unwrap_or(-1.0),
            self.rating.confidence,
            result,
            reason,
            self.rating.conversion.score,
            self.rating.smash_quality.score,
            self.rating.exposure.score,
            self.rating.vertical.score,
            self.rating.resources.score,
            self.rating.opponent.score,
            self.ping.unwrap_or(-1.0),
            self.mine.opened as f64,
            self.mine.converted as f64,
            self.theirs.opened as f64,
            self.theirs.converted as f64,
            self.engaged_secs,
            foe[0].score,
            foe[1].score,
            foe[2].score,
            foe[3].score,
            r.conversion.count,
            r.smash_quality.count,
            r.exposure.count,
            r.vertical.count,
            r.resources.count,
            foe[0].count,
            foe[1].count,
            foe[2].count,
            foe[3].count,
            self.mine.realized,
            self.mine.expected,
            self.theirs.realized,
            self.theirs.expected,
            self.smash_charge.unwrap_or(-1.0),
            r.own.score,
            r.opponent_known.score,
            r.opponent_known.count,
            self.mode.code(),
            r.base_score.unwrap_or(-1.0),
            r.bug_use,
            self.techniques.foe_points,
            self.split.big_opened as f64,
            self.split.big_converted as f64,
            self.split.small_opened as f64,
            self.split.small_converted as f64,
            self.avoided.map(|a| (100.0 * a).round()).unwrap_or(-1.0),
        ]
    }
}

/// La raison donnée à un duel qui n'est pas à la masse : il n'est pas noté.
const UNSCORED_MODE: &str = "not_mace";

/// Le journal d'un duel déjà lu, avant sa note : de quoi savoir contre qui
/// il a été joué, et donc quelle force connue lui donner ([`score_with`]).
pub struct Journal {
    duel: parse::Duel,
}

impl Journal {
    /// `None` si le texte n'est pas un journal.
    pub fn read(text: &str) -> Option<Self> {
        parse::parse(text).map(|duel| Self { duel })
    }

    /// Voir [`Report::foe_key`].
    pub fn foe_key(&self) -> Option<String> {
        self.duel.foe_uuid.clone().or_else(|| self.duel.foe_name.as_ref().map(|n| n.to_lowercase()))
    }

    pub fn time(&self) -> Option<u64> {
        self.duel.time
    }

    pub fn foe_uuid(&self) -> Option<&str> {
        self.duel.foe_uuid.as_deref()
    }

    pub fn foe_name(&self) -> Option<&str> {
        self.duel.foe_name.as_deref()
    }
}

/// Note un journal contre un adversaire inconnu. `None` si le texte n'en est pas un.
pub fn score_journal(text: &str) -> Option<Report> {
    Journal::read(text).map(|journal| score_with(&journal, &Prior::default()))
}

/// Note un duel, avec la force de son adversaire connue avant lui.
pub fn score_with(journal: &Journal, prior: &Prior) -> Report {
    let duel = &journal.duel;
    let features = features::extract(duel);
    // Le ping est le quinzième nombre de chaque tick ; −1 quand le jeu ne le connaît pas.
    let pings: Vec<f64> = duel.samples.iter().map(|s| s.me[14]).filter(|p| *p >= 0.0).collect();
    let mut rating = rating::rate(&features, prior);
    // La note est faite pour la masse : un duel sans masse (à l'épée) est
    // reconnu et gardé, mais pas noté. Un duel déjà trop court garde cette
    // raison-là, plus parlante.
    if duel.mode != Mode::Mace && rating.score.is_some() {
        rating.score = None;
        rating.base_score = None;
        rating.reason = Some(UNSCORED_MODE);
    }
    Report {
        ping: (!pings.is_empty()).then(|| (pings.iter().sum::<f64>() / pings.len() as f64).round()),
        extractor_version: EXTRACTOR_VERSION,
        score_version: rating::SCORE_VERSION,
        result: duel.result.clone(),
        foe_name: duel.foe_name.clone(),
        foe_uuid: duel.foe_uuid.clone(),
        time: duel.time,
        mode: duel.mode,
        engaged_secs: features.engaged_secs,
        mine: Opportunities::of(&features.mine),
        theirs: Opportunities::of(&features.theirs),
        smash_charge: features.smash_charge,
        split: features.split,
        avoided: features.avoided,
        techniques: features.bug_use,
        rating,
    }
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
        // Le même résumé, en nombres pour l'interface.
        let numbers = report.numbers();
        assert_eq!(numbers[0], score);
        assert_eq!(numbers[2], 2.0, "défaite");
        assert_eq!(numbers[3], 0.0, "duel noté");
        assert!(numbers[4..10].iter().all(|family| (0.0..=100.0).contains(family)));
        assert!(numbers[10] > 0.0, "ping connu");
        assert_eq!((numbers[11], numbers[12]), (report.mine.opened as f64, 3.0));
        assert!(numbers[16..20].iter().all(|family| (0.0..=100.0).contains(family)), "familles de l'adversaire");
        assert_eq!(numbers[29], report.mine.realized);
        assert!((0.0..=100.0).contains(&numbers[34]), "mon jeu seul");
        assert_eq!((numbers[35], numbers[36]), (50.0, 0.0), "un adversaire jamais affronté");
        assert_eq!(numbers[37], 1.0, "un duel à la masse");
        assert_eq!(report.mode, Mode::Mace);
    }

    #[test]
    fn un_adversaire_connu_pour_etre_fort_monte_la_note_du_meme_duel() {
        let journal = Journal::read(DEFEAT).unwrap();
        let first = score_with(&journal, &Prior::default());
        let known = score_with(&journal, &Prior::from_levels(&[1.5, 1.5]));
        assert!(known.rating.score.unwrap() > first.rating.score.unwrap());
        assert_eq!(known.numbers()[36], 2.0, "deux duels passés contre lui");
        assert_eq!(first.foe_key(), journal.foe_key(), "le même adversaire, lu au journal ou au résumé");
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
