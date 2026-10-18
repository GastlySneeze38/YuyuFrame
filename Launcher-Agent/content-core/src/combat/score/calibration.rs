//! Le relevé de calibration (docs/scoring/calibration.md) : relit tous les
//! journaux d'un dossier et écrit, en CSV (séparateur `;`, virgule décimale
//! évitée : point), de quoi recalculer chaque valeur à calibrer.
//!
//! ```text
//! YF_MATCHES=<dossier des journaux> YF_CALIB_OUT=<dossier de sortie> \
//!   cargo test --lib releve_de_calibration -- --ignored --nocapture
//! ```
//!
//!   duels.csv        un duel par ligne : résultat, note, familles des deux côtés
//!   parties.csv      chaque partie de chaque famille AVANT recentrage, des deux côtés :
//!                    centres (moyenne) et poids (régression du résultat)
//!   opportunites.csv chaque opportunité des deux joueurs et ses cinq critères :
//!                    régression de la chance de réussite, fenêtre minimale, grosses / petites
//!   smashs.csv       chaque smash réussi des deux joueurs : charge, critique, sprint,
//!                    chute au coup et possible — la référence de la qualité du smash
//!   techniques.csv   chaque technique de bug use et chaque élan de Lunge reconnus,
//!                    avec leur tick : leur fréquence, et à vérifier dans le jeu
//!
//! Chaque duel est noté contre un adversaire inconnu : la force connue de
//! l'adversaire n'entre pas dans ce qu'on calibre ici.

use std::fmt::Write as _;
#[cfg(test)]
use std::io::Read;
#[cfg(test)]
use std::path::Path;

use super::features::{self, Features};
use super::{parse, rating, Prior};

fn number(value: Option<f64>) -> String {
    value.map(|v| format!("{v:.4}")).unwrap_or_default()
}

fn side(by_me: bool) -> &'static str {
    if by_me { "moi" } else { "adversaire" }
}

fn result_code(result: Option<&str>) -> &str {
    result.unwrap_or("")
}

/// Les tableaux, remplis duel par duel.
#[derive(Default)]
struct Tables {
    duels: String,
    parts: String,
    opportunities: String,
    smashes: String,
    techniques: String,
}

impl Tables {
    fn new() -> Self {
        let mut t = Tables::default();
        t.duels.push_str(
            "duel;version;mode;resultat;secondes;note;note_familles;bug_use;bug_use_adverse;\
             conversion;qualite_smash;defense;controle;ressources;conversion_n;qualite_smash_n;defense_n;controle_n;ressources_n;\
             adv_conversion;adv_qualite_smash;adv_defense;adv_controle\n",
        );
        t.parts.push_str("duel;version;resultat;cote;partie;valeur\n");
        t.opportunities.push_str("duel;version;cote;ouverture;ticks;decalage;vitesse_cible;chute;chute_possible;dos_bouclier;convertie;chance;bonus_possible\n");
        t.smashes.push_str("duel;version;cote;tick;chute;chute_possible;charge;critique;sprint;rapport\n");
        t.techniques.push_str("duel;version;cote;tick;technique;points\n");
        t
    }

    fn add(&mut self, name: &str, duel: &parse::Duel, f: &Features) {
        let r = rating::rate(f, &Prior::default());
        let v = duel.version;
        let result = result_code(duel.result.as_deref());
        let families = [&r.conversion, &r.smash_quality, &r.exposure, &r.vertical, &r.resources];
        let _ = writeln!(
            self.duels,
            "{name};{v};{:?};{result};{:.1};{};{};{:.2};{:.2};{};{};{}",
            duel.mode,
            f.engaged_secs,
            number(r.score),
            number(r.base_score),
            r.bug_use,
            f.bug_use.foe_points,
            families.iter().map(|s| format!("{:.4}", s.value)).collect::<Vec<_>>().join(";"),
            families.iter().map(|s| format!("{:.1}", s.count)).collect::<Vec<_>>().join(";"),
            r.opponent_families.iter().map(|s| format!("{:.4}", s.value)).collect::<Vec<_>>().join(";"),
        );
        // Les familles d'un seul nombre sont des parties à elles seules.
        let whole = [("conversion", f.conversion, f.foe.conversion), ("smash", f.smash_quality, f.foe.smash_quality)];
        for (part, mine, theirs) in whole {
            for (by_me, m) in [(true, mine), (false, theirs)] {
                if m.count > 0.0 {
                    let _ = writeln!(self.parts, "{name};{v};{result};{};{part};{:.4}", side(by_me), m.value);
                }
            }
        }
        for (by_me, raw) in [(true, &f.raw_mine), (false, &f.raw_foe)] {
            for (part, value) in raw.0.iter().filter(|(_, value)| value.is_some()) {
                let _ = writeln!(self.parts, "{name};{v};{result};{};{part};{}", side(by_me), number(*value));
            }
        }
        for (by_me, list) in [(true, &f.mine), (false, &f.theirs)] {
            for o in list {
                let _ = writeln!(
                    self.opportunities,
                    "{name};{v};{};{};{};{:.3};{:.4};{:.3};{:.3};{};{};{:.4};{:.2}",
                    side(by_me),
                    o.open,
                    o.close - o.open + 1,
                    o.offset,
                    o.target_speed,
                    o.fall,
                    o.possible_fall,
                    o.behind_shield as u8,
                    o.converted as u8,
                    o.chance,
                    o.expected,
                );
            }
        }
        for s in &f.smashes {
            let flag = |b: Option<bool>| b.map(|b| (b as u8).to_string()).unwrap_or_default();
            let _ = writeln!(
                self.smashes,
                "{name};{v};{};{};{:.3};{:.3};{};{};{};{:.4}",
                side(s.by_me),
                s.t,
                s.hit_fall,
                s.possible_fall,
                number(s.charge),
                flag(s.crit),
                flag(s.sprinting),
                s.ratio,
            );
        }
        for d in &f.detections {
            let _ = writeln!(self.techniques, "{name};{v};{};{};{};{:.2}", side(d.by_me), d.t, d.name, d.points);
        }
    }

    /// Les cinq tableaux, en-têtes compris, sous le nom de leur fichier.
    fn to_json(&self) -> serde_json::Value {
        serde_json::json!({
            "duels": self.duels,
            "parties": self.parts,
            "opportunites": self.opportunities,
            "smashs": self.smashes,
            "techniques": self.techniques,
        })
    }

    #[cfg(test)]
    fn write(&self, dir: &Path) {
        std::fs::create_dir_all(dir).unwrap();
        for (file, text) in [
            ("duels.csv", &self.duels),
            ("parties.csv", &self.parts),
            ("opportunites.csv", &self.opportunities),
            ("smashs.csv", &self.smashes),
            ("techniques.csv", &self.techniques),
        ] {
            std::fs::write(dir.join(file), text).unwrap();
        }
    }
}

/// Les cinq tableaux d'un seul duel, en-têtes compris (une ligne d'en-tête
/// puis ses lignes), et ce qui le résume — ce qu'un joueur envoie pour la
/// calibration (`combat::share`). Mêmes lignes que le relevé : le
/// back-office les met bout à bout, en ne gardant qu'un en-tête par tableau.
pub fn duel_tables(name: &str, duel: &parse::Duel) -> (serde_json::Value, serde_json::Value) {
    let f = features::extract(duel);
    let mut tables = Tables::new();
    tables.add(name, duel, &f);
    let r = rating::rate(&f, &Prior::default());
    let summary = serde_json::json!({
        "mode": format!("{:?}", duel.mode),
        "result": duel.result,
        "seconds": (f.engaged_secs * 10.0).round() / 10.0,
        "score": r.score,
        "base_score": r.base_score,
        "opened": f.mine.len(),
        "converted": f.mine.iter().filter(|o| o.converted).count(),
        "foe_opened": f.theirs.len(),
    });
    (tables.to_json(), summary)
}

/// Le texte d'un journal, compressé (`.zst`) ou non.
#[cfg(test)]
fn read_journal(path: &Path) -> Option<String> {
    let bytes = std::fs::read(path).ok()?;
    if path.extension().is_some_and(|e| e == "zst") {
        let mut text = String::new();
        zstd::stream::read::Decoder::new(bytes.as_slice()).ok()?.read_to_string(&mut text).ok()?;
        Some(text)
    } else {
        String::from_utf8(bytes).ok()
    }
}

/// Décode du base64 standard (avec ou sans remplissage).
#[cfg(test)]
fn base64_decode(text: &str) -> Option<Vec<u8>> {
    let value = |c: u8| -> Option<u32> {
        Some(match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            _ => return None,
        } as u32)
    };
    let bytes: Vec<u8> = text.bytes().filter(|c| !c.is_ascii_whitespace() && *c != b'=').collect();
    let mut out = Vec::with_capacity(bytes.len() * 3 / 4);
    for chunk in bytes.chunks(4) {
        let mut n = 0u32;
        for (i, c) in chunk.iter().enumerate() {
            n |= value(*c)? << (18 - 6 * i);
        }
        out.push((n >> 16) as u8);
        if chunk.len() > 2 {
            out.push((n >> 8) as u8);
        }
        if chunk.len() > 3 {
            out.push(n as u8);
        }
    }
    Some(out)
}

/// Un export du back-office (Calibration PvP → « Journaux ») : un duel par
/// ligne, `{ "duel", "joueur", "journal" }`, le journal compressé en zstd puis
/// en base64. Rend `(nom, texte)` pour chaque duel lisible, `None` si le
/// fichier n'est pas un export (c'est alors un journal en clair).
#[cfg(test)]
fn read_export(path: &Path) -> Option<Vec<(String, Option<String>)>> {
    let text = std::fs::read_to_string(path).ok()?;
    let first: serde_json::Value = serde_json::from_str(text.lines().next()?).ok()?;
    first.get("journal")?;
    Some(
        text.lines()
            .filter(|l| !l.trim().is_empty())
            .map(|line| {
                let value: serde_json::Value = serde_json::from_str(line).unwrap_or_default();
                let name = value["duel"].as_str().unwrap_or("export").to_string();
                let journal = value["journal"].as_str().and_then(base64_decode).and_then(|bytes| {
                    let mut text = String::new();
                    zstd::stream::read::Decoder::new(bytes.as_slice()).ok()?.read_to_string(&mut text).ok()?;
                    Some(text)
                });
                (name, journal)
            })
            .collect(),
    )
}

/// Le relevé. `YF_MATCHES` peut contenir des journaux du jeu (`.zst`, ou
/// `.ndjson` en clair) ET des exports du back-office (`.ndjson`, un duel par
/// ligne) : tout est relu et recalculé avec la version de l'extraction en
/// cours.
#[test]
#[ignore]
fn releve_de_calibration() {
    let matches = std::env::var("YF_MATCHES").expect("YF_MATCHES : le dossier des journaux (yuyuframe/matches) ou des exports");
    let out = std::env::var("YF_CALIB_OUT").expect("YF_CALIB_OUT : le dossier où écrire les tableaux");
    let mut paths: Vec<_> = std::fs::read_dir(&matches)
        .unwrap()
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| p.to_string_lossy().ends_with(".events.ndjson.zst") || p.to_string_lossy().ends_with(".ndjson"))
        .collect();
    paths.sort();
    let mut tables = Tables::new();
    let (mut read, mut skipped) = (0, 0);
    for path in &paths {
        let duels = read_export(path).unwrap_or_else(|| {
            let name = path.file_name().unwrap().to_string_lossy().split('.').next().unwrap_or("").to_string();
            vec![(name, read_journal(path))]
        });
        for (name, text) in duels {
            let Some(duel) = text.and_then(|text| parse::parse(&text)) else {
                skipped += 1;
                continue;
            };
            tables.add(&name, &duel, &features::extract(&duel));
            read += 1;
        }
    }
    tables.write(Path::new(&out));
    println!("{read} journaux relevés, {skipped} illisibles → {out}");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn un_export_du_back_office_se_relit() {
        let journal = include_str!("fixtures/duel_masse_defaite.ndjson");
        let packed = crate::combat::share::base64(&zstd::bulk::compress(journal.as_bytes(), 9).unwrap());
        assert_eq!(base64_decode(&packed).map(|b| zstd::bulk::decompress(&b, 1 << 24).unwrap()), Some(journal.as_bytes().to_vec()));
        let dir = std::env::temp_dir().join(format!("yf-export-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("duels-partages.ndjson");
        let line = serde_json::json!({ "duel": "0123456789abcdef", "joueur": 1, "journal": packed }).to_string();
        std::fs::write(&path, format!("{line}\n{line}\n")).unwrap();
        let duels = read_export(&path).expect("un export");
        assert_eq!(duels.len(), 2);
        assert_eq!(duels[0].0, "0123456789abcdef");
        assert_eq!(duels[0].1.as_deref(), Some(journal));
        // Un journal du jeu en clair n'est pas pris pour un export.
        let plain = dir.join("1.ndjson");
        std::fs::write(&plain, journal).unwrap();
        assert!(read_export(&plain).is_none());
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn un_vrai_duel_remplit_les_cinq_tableaux() {
        let duel = parse::parse(include_str!("fixtures/duel_masse_defaite.ndjson")).unwrap();
        let mut tables = Tables::new();
        tables.add("essai", &duel, &features::extract(&duel));
        let lines = |text: &str| text.lines().count();
        assert_eq!(lines(&tables.duels), 2, "l'en-tête et le duel");
        assert!(lines(&tables.parts) > 5);
        assert!(lines(&tables.opportunities) > 1);
        assert!(lines(&tables.smashes) > 1, "les smashs réussis des deux côtés");
        // Chaque ligne a autant de colonnes que son en-tête.
        for text in [&tables.duels, &tables.parts, &tables.opportunities, &tables.smashes] {
            let columns = text.lines().next().unwrap().split(';').count();
            assert!(text.lines().all(|l| l.split(';').count() == columns), "{}", text.lines().next().unwrap());
        }
    }
}
