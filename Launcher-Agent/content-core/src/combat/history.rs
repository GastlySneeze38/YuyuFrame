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

use std::io::Read;
use std::path::Path;

use super::score::{score_journal, Report};

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

/// Les duels notés du dossier `matches`, du plus récent au plus ancien.
fn duels(matches: &Path) -> Vec<Report> {
    let Ok(entries) = std::fs::read_dir(matches) else { return Vec::new() };
    let mut journals: Vec<_> = entries
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.ends_with(".events.ndjson.zst")))
        .collect();
    // Le nom d'un journal est son heure d'ouverture : trié, c'est l'ordre des duels.
    journals.sort();
    journals.reverse();

    let mut out = Vec::new();
    for journal in journals {
        if out.len() == MAX_DUELS {
            break;
        }
        let Some(report) = read_journal(&journal).and_then(|text| score_journal(&text)) else { continue };
        if !matches!(report.result.as_deref(), Some("win" | "loss" | "draw")) {
            continue;
        }
        // Le résumé suit la formule courante. Un échec d'écriture n'empêche pas de lister.
        if let Ok(json) = serde_json::to_string_pretty(&report) {
            if let Err(e) = std::fs::write(super::writer::summary_path(&journal), json) {
                eprintln!("[YuyuFrame] résumé du duel {} non mis à jour : {e}", journal.display());
            }
        }
        out.push(report);
    }
    out
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
        assert_eq!(numbers[0], average, "deux fois le même duel : la moyenne est sa note");
        assert_eq!(numbers[2], 2.0, "défaite");

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
