//! L'historique des duels d'une instance, pour l'écran du module : la moyenne
//! des notes, puis un duel par ligne, du plus récent au plus ancien.
//!
//! Tout part des JOURNAUX (`<horodatage>.events.ndjson.zst`), seule source :
//! chaque duel est renoté avec la formule courante. Un résumé écrit par une
//! ancienne version ne fausse donc jamais la liste, et le fichier `.json` à
//! côté du journal est remis à jour au passage. Renoter coûte quelques
//! millisecondes par duel ; l'écran ne le demande qu'à son ouverture.
//!
//! Seuls les duels allés jusqu'à un verdict sont listés (victoire, défaite,
//! nul) : un journal coupé en plein combat ou fermé sans résultat n'est pas
//! un match à montrer.

use std::collections::HashMap;
use std::io::Read;
use std::path::Path;

use super::score::{score_with, Journal, Prior, Report};

/// Duels rendus au plus : les plus récents.
const MAX_DUELS: usize = 200;
/// Un journal décompressé plus gros que cela n'est pas lu (un duel d'une
/// heure fait quelques mégaoctets).
const MAX_JOURNAL_BYTES: u64 = 64 * 1024 * 1024;

fn read_journal(path: &Path) -> Option<String> {
    let file = std::fs::File::open(path).ok()?;
    let mut text = String::new();
    zstd::stream::read::Decoder::new(file).ok()?.take(MAX_JOURNAL_BYTES).read_to_string(&mut text).ok()?;
    Some(text)
}

/// Les duels terminés du dossier `matches`, du plus récent au plus ancien.
///
/// Les journaux sont notés dans l'ORDRE OÙ ILS ONT ÉTÉ JOUÉS : la note d'un
/// duel dépend de la force de son adversaire connue avant lui, c'est-à-dire
/// de son niveau dans nos duels précédents contre lui ([`Prior`]). Tous sont
/// donc relus, pas seulement les derniers listés.
fn duels(matches: &Path) -> Vec<Report> {
    let Ok(entries) = std::fs::read_dir(matches) else { return Vec::new() };
    let mut journals: Vec<_> = entries
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.ends_with(".events.ndjson.zst")))
        .collect();
    // Le nom d'un journal est son heure d'ouverture : trié, c'est l'ordre des duels.
    journals.sort();

    let mut known: HashMap<String, Vec<f64>> = HashMap::new();
    let mut out = Vec::new();
    for path in journals {
        let Some(journal) = read_journal(&path).and_then(|text| Journal::read(&text)) else { continue };
        let key = journal.foe_key();
        let prior = key.as_ref().and_then(|k| known.get(k)).map(|levels| Prior::from_levels(levels)).unwrap_or_default();
        let report = score_with(&journal, &prior);
        if let (Some(key), true) = (key, report.informs_prior()) {
            known.entry(key).or_default().push(report.rating.opponent.z);
        }
        if !matches!(report.result.as_deref(), Some("win" | "loss" | "draw")) {
            continue;
        }
        // Le résumé suit la formule courante. Un échec d'écriture n'empêche pas de lister.
        if let Ok(json) = serde_json::to_string_pretty(&report) {
            if let Err(e) = std::fs::write(super::writer::summary_path(&path), json) {
                eprintln!("[YuyuFrame] résumé du duel {} non mis à jour : {e}", path.display());
            }
        }
        out.push(report);
    }
    out.reverse();
    out.truncate(MAX_DUELS);
    out
}

/// La force connue d'un adversaire, pour un duel qui vient de se terminer :
/// lue dans les résumés des duels précédents du même dossier (les fichiers
/// `.json` à côté des journaux, que [`history`] remet à la formule courante).
///
/// `uuid` et `name` : l'adversaire du duel qu'on note ; `before` : son heure
/// d'ouverture — lui-même et les suivants ne comptent pas.
pub fn prior_from_summaries(matches: &Path, uuid: Option<&str>, name: Option<&str>, before: u64) -> Prior {
    let name = name.map(str::to_lowercase);
    if uuid.is_none() && name.is_none() {
        return Prior::default();
    }
    let Ok(entries) = std::fs::read_dir(matches) else { return Prior::default() };
    let mut levels = Vec::new();
    for path in entries.filter_map(|e| e.ok()).map(|e| e.path()) {
        let is_summary = path.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.ends_with(".json"));
        if !is_summary {
            continue;
        }
        let Some(summary) = std::fs::read_to_string(&path).ok().and_then(|t| serde_json::from_str::<serde_json::Value>(&t).ok()) else {
            continue;
        };
        if summary["time"].as_u64().unwrap_or(u64::MAX) >= before || summary["score"].is_null() {
            continue;
        }
        let their_uuid = summary["foe_uuid"].as_str();
        let their_name = summary["foe_name"].as_str().map(|n| n.to_lowercase());
        // L'UUID quand les deux l'ont ; sinon le pseudo (un résumé d'avant le
        // 2026-10-14 n'a pas d'UUID).
        let same = match (uuid, their_uuid) {
            (Some(a), Some(b)) => a == b,
            _ => name.is_some() && their_name == name,
        };
        if same {
            if let Some(z) = summary["opponent"]["z"].as_f64() {
                levels.push(z);
            }
        }
    }
    Prior::from_levels(&levels)
}

/// Le texte rendu à Java : une ligne d'en-tête, puis une ligne par duel,
/// champs séparés par des tabulations.
///
/// ```text
/// avg ⇥ <moyenne des notes, −1 s'il n'y en a pas> ⇥ <duels notés> ⇥ <duels listés>
/// duel ⇥ <heure, ms depuis 1970> ⇥ <pseudo de l'adversaire> ⇥ <les nombres du résumé, séparés par des virgules>
/// ```
///
/// Les nombres d'un duel sont ceux de `Report::numbers`, dans le même ordre
/// que pour le panneau de fin de duel.
pub fn history(instance_dir: &Path) -> String {
    let reports = duels(&instance_dir.join("yuyuframe").join("matches"));
    let scores: Vec<f64> = reports.iter().filter_map(|r| r.rating.score).collect();
    let average = if scores.is_empty() { -1.0 } else { (scores.iter().sum::<f64>() / scores.len() as f64).round() };
    let mut text = format!("avg\t{average}\t{}\t{}\n", scores.len(), reports.len());
    for report in &reports {
        let numbers: Vec<String> = report.numbers().iter().map(|n| n.to_string()).collect();
        // Un pseudo ne contient ni tabulation ni retour à la ligne (le journal
        // n'écrit que des pseudos valides), mais on ne s'y fie pas pour le format.
        let name: String = report.foe_name.as_deref().unwrap_or("").chars().filter(|c| !c.is_control()).collect();
        text.push_str(&format!("duel\t{}\t{}\t{}\n", report.time.unwrap_or(0), name, numbers.join(",")));
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn write_journal(dir: &Path, name: &str, text: &str) {
        let matches = dir.join("yuyuframe").join("matches");
        std::fs::create_dir_all(&matches).unwrap();
        let mut file = std::fs::File::create(matches.join(format!("{name}.events.ndjson.zst"))).unwrap();
        file.write_all(&zstd::bulk::compress(text.as_bytes(), 3).unwrap()).unwrap();
    }

    #[test]
    fn l_historique_liste_les_duels_termines_du_plus_recent_au_plus_ancien() {
        let dir = std::env::temp_dir().join(format!("yf-combat-historique-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let defeat = include_str!("score/fixtures/duel_masse_defaite.ndjson");
        write_journal(&dir, "1000", defeat);
        write_journal(&dir, "2000", defeat);
        // Un duel coupé avant le verdict, et un fichier qui n'est pas un journal : ignorés.
        write_journal(&dir, "3000", include_str!("score/fixtures/duel_masse_abandon.ndjson"));
        write_journal(&dir, "4000", "pas un journal");

        let text = history(&dir);
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(lines.len(), 3, "{text}");
        let head: Vec<&str> = lines[0].split('\t').collect();
        assert_eq!((head[0], head[2], head[3]), ("avg", "2", "2"));
        let average: f64 = head[1].parse().unwrap();
        assert!((0.0..=100.0).contains(&average));

        let duel: Vec<&str> = lines[1].split('\t').collect();
        assert_eq!(duel[0], "duel");
        assert_eq!(duel[2], "Rival", "le pseudo de l'adversaire");
        let numbers: Vec<f64> = duel[3].split(',').map(|n| n.parse().unwrap()).collect();
        assert_eq!(numbers.len(), super::super::score::REPORT_LEN);
        assert_eq!(numbers[2], 2.0, "défaite");
        // Le même adversaire deux fois : le premier duel le découvre, le
        // second le connaît par le premier.
        let older: Vec<f64> = lines[2].split('\t').nth(3).unwrap().split(',').map(|n| n.parse().unwrap()).collect();
        assert_eq!((older[35], older[36]), (50.0, 0.0), "premier duel contre lui : inconnu");
        assert_eq!(numbers[36], 1.0, "second duel : connu par le premier");
        assert_ne!(numbers[35], 50.0);
        assert_eq!(average, ((numbers[0] + older[0]) / 2.0).round(), "la moyenne des deux notes");

        // Le résumé à côté du journal suit la formule courante.
        let summary: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(dir.join("yuyuframe").join("matches").join("2000.json")).unwrap()).unwrap();
        assert_eq!(summary["foe_name"], "Rival");
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn un_dossier_sans_duel_rend_un_historique_vide() {
        let text = history(Path::new("un-dossier-qui-n-existe-pas"));
        assert_eq!(text, "avg\t-1\t0\t0\n");
    }
}
