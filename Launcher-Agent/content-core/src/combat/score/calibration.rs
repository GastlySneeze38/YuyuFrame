//! Le relevé de calibration (docs/scoring/calibration.md) : relit tous les
//! journaux d'un dossier et écrit, en CSV (séparateur `;`, virgule décimale
//! évitée : point), de quoi recalculer chaque valeur à calibrer.
//!
//! ```text
//! YF_MATCHES=<dossier des journaux> YF_CALIB_OUT=<dossier de sortie> \
//!   cargo test --lib releve_de_calibration -- --ignored --nocapture
//! ```
//!
//!   duels.csv        un duel par ligne : résultat, note sur 500, familles des
//!                    deux côtés (en écarts de note)
//!   parties.csv      chaque action de chaque famille, des deux côtés (2026-10-22) :
//!                    ce qu'elle a donné, ce qu'on en attendait, sa part dans la
//!                    famille et sa situation — de quoi recalculer chaque attendu
//!   opportunites.csv chaque opportunité des deux joueurs et ses cinq critères :
//!                    régression de la chance de réussite, fenêtre minimale, grosses / petites
//!   smashs.csv       chaque smash réussi des deux joueurs : charge, critique, sprint,
//!                    chute au coup et possible — la référence de la qualité du smash
//!   techniques.csv   chaque technique de bug use et chaque élan de Lunge reconnus,
//!                    avec leur tick : leur fréquence, et à vérifier dans le jeu
//!
//! Chaque duel est noté contre un adversaire inconnu : la force connue de
//! l'adversaire n'entre pas dans ce qu'on calibre ici.
//!
//! Le test `proposition_de_calibrage` relit les mêmes journaux et propose
//! les valeurs à poser : l'attendu et l'écart habituel de chaque sorte
//! d'action, les modèles de chance, l'écart de chaque famille, et la note
//! qui en sortirait.

use std::fmt::Write as _;
#[cfg(test)]
use std::io::Read;
#[cfg(test)]
use std::path::Path;

use super::features::{self, Action, Features};
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

/// Une action dans `parties.csv`, à partir de la colonne `partie`.
fn action_row(a: &Action) -> String {
    let tick = a.t.map(|t| t.to_string()).unwrap_or_default();
    let context = a.context.iter().map(|c| format!("{c:.4}")).collect::<Vec<_>>().join(";");
    format!("{};{tick};{:.4};{:.4};{:.6};{:.4};{context}", a.code, a.value, a.expected, a.share, a.spread)
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
        t.parts.push_str("duel;version;resultat;cote;famille;partie;tick;valeur;attendu;part;ecart_habituel;contexte_1;contexte_2;contexte_3;contexte_4;contexte_5\n");
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
            families.iter().map(|s| format!("{:.4}", s.z)).collect::<Vec<_>>().join(";"),
            families.iter().map(|s| format!("{:.1}", s.count)).collect::<Vec<_>>().join(";"),
            r.opponent_families.iter().map(|s| format!("{:.4}", s.z)).collect::<Vec<_>>().join(";"),
        );
        let sides = [(true, f.actions.iter().collect::<Vec<_>>()), (false, f.foe_actions.iter().collect())];
        for (by_me, families) in sides {
            for (family, actions) in families.into_iter().enumerate() {
                for a in actions {
                    let _ = writeln!(self.parts, "{name};{v};{result};{};{family};{}", side(by_me), action_row(a));
                }
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

/// Tous les duels d'un dossier, sous leur nom, et le nombre de journaux
/// illisibles. Le dossier peut contenir des journaux du jeu (`.zst`, ou
/// `.ndjson` en clair) ET des exports du back-office (`.ndjson`, un duel par
/// ligne) : tout est relu avec la version de l'extraction en cours.
#[cfg(test)]
fn load_duels(matches: &str) -> (Vec<(String, parse::Duel)>, usize) {
    let mut paths: Vec<_> = std::fs::read_dir(matches)
        .unwrap()
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| p.to_string_lossy().ends_with(".events.ndjson.zst") || p.to_string_lossy().ends_with(".ndjson"))
        .collect();
    paths.sort();
    let (mut out, mut skipped) = (Vec::new(), 0);
    for path in &paths {
        let duels = read_export(path).unwrap_or_else(|| {
            let name = path.file_name().unwrap().to_string_lossy().split('.').next().unwrap_or("").to_string();
            vec![(name, read_journal(path))]
        });
        for (name, text) in duels {
            match text.and_then(|text| parse::parse(&text)) {
                Some(duel) => out.push((name, duel)),
                None => skipped += 1,
            }
        }
    }
    (out, skipped)
}

/// Le relevé.
#[test]
#[ignore]
fn releve_de_calibration() {
    let matches = std::env::var("YF_MATCHES").expect("YF_MATCHES : le dossier des journaux (yuyuframe/matches) ou des exports");
    let out = std::env::var("YF_CALIB_OUT").expect("YF_CALIB_OUT : le dossier où écrire les tableaux");
    let (duels, skipped) = load_duels(&matches);
    let mut tables = Tables::new();
    for (name, duel) in &duels {
        tables.add(name, duel, &features::extract(duel));
    }
    tables.write(Path::new(&out));
    println!("{} journaux relevés, {skipped} illisibles → {out}", duels.len());
}

// --- La proposition de calibrage ------------------------------------------------

/// Les sortes d'action dont l'attendu vient d'un modèle de chance, et le
/// nombre de critères de leur modèle. Les ouvertures laissées de la défense
/// sont l'envers des ouvertures : elles suivent leur modèle.
#[cfg(test)]
const MODELS: [(&str, usize); 3] = [("conversion.opening", 5), ("defense.dodge", 2), ("control.reached", 2)];

/// Les critères d'une ouverture que le modèle n'apprend pas (2026-10-22) :
/// la durée de la fenêtre et sa chute. La fenêtre se ferme au coup, donc
/// une ouverture convertie paraît toujours plus courte et moins haute
/// qu'une ratée — appris, ces deux critères diraient « longue = ratée »,
/// ce qui est le résultat et non la difficulté. Restent le décalage, la
/// vitesse de la cible et le dos du bouclier, lus à l'ouverture.
#[cfg(test)]
const LEAKING_CRITERIA: [usize; 2] = [2, 3];

/// L'écart habituel le plus petit qu'on pose : une sorte d'action qui vaut
/// presque toujours la même chose (vitesse de montée 0,98 ± 0,05) ferait
/// d'une seule action ordinaire un écart de dix.
#[cfg(test)]
const MIN_SPREAD: f64 = 0.15;

/// Les sortes d'action faussées quand un serveur ne laisse voir aucun smash
/// (2026-10-22) : tout ce qui demande si une ouverture a été convertie, si
/// un smash est passé, ou qui en découle (une descente « esquivée » faute de
/// smash vu, une chute « après un raté » qui suivait en fait un coup). Les
/// duels d'un tel serveur apprennent toutes les autres.
#[cfg(test)]
const SMASH_DEPENDENT: [&str; 11] = [
    "conversion.opening",
    "defense.openings",
    "defense.blocked",
    "defense.dodge",
    "defense.landing",
    "control.first_time",
    "control.follow_up",
    "control.chain_length",
    "resources.effective",
    "smash.hit",
    "smash.none",
];

/// Les sortes dont l'attendu est fixé par construction, pas par la moyenne :
/// « aucun smash » vaut 0 par définition et se compare à un smash réussi
/// moyen ; une chute après un raté n'existe que quand elle a fait mal et se
/// compare à ne rien perdre.
#[cfg(test)]
fn fixed_mean(code: &str, means: &std::collections::HashMap<&'static str, f64>) -> Option<f64> {
    match code {
        "smash.none" => means.get("smash.hit").copied(),
        "defense.landing" => Some(1.0),
        _ => None,
    }
}

/// Une régression logistique par Newton-Raphson, avec un léger rappel vers
/// zéro des coefficients (pas de la constante) pour qu'un critère qui ne
/// varie jamais ne parte pas à l'infini. Rend [constante, coefficients…].
#[cfg(test)]
fn fit_logistic(rows: &[(Vec<f64>, f64)], k: usize) -> Option<Vec<f64>> {
    const RIDGE: f64 = 1e-2;
    let d = k + 1;
    let mut beta = vec![0.0; d];
    for _ in 0..60 {
        let mut h = vec![vec![0.0; d]; d];
        let mut g = vec![0.0; d];
        for j in 1..d {
            h[j][j] += RIDGE;
            g[j] -= RIDGE * beta[j];
        }
        for (x, y) in rows {
            let xt: Vec<f64> = std::iter::once(1.0).chain(x.iter().take(k).copied()).collect();
            let p = 1.0 / (1.0 + (-xt.iter().zip(&beta).map(|(a, b)| a * b).sum::<f64>()).exp());
            let w = p * (1.0 - p);
            for i in 0..d {
                g[i] += (y - p) * xt[i];
                for j in 0..d {
                    h[i][j] += w * xt[i] * xt[j];
                }
            }
        }
        let step = solve(h, g)?;
        let size: f64 = step.iter().map(|s| s.abs()).sum();
        for (b, s) in beta.iter_mut().zip(&step) {
            *b += s;
        }
        if size < 1e-9 {
            break;
        }
    }
    Some(beta)
}

/// Résout `a × x = b` (pivot de Gauss partiel) ; `None` si le système est singulier.
#[cfg(test)]
fn solve(mut a: Vec<Vec<f64>>, mut b: Vec<f64>) -> Option<Vec<f64>> {
    let n = b.len();
    for col in 0..n {
        let pivot = (col..n).max_by(|i, j| a[*i][col].abs().total_cmp(&a[*j][col].abs()))?;
        if a[pivot][col].abs() < 1e-12 {
            return None;
        }
        a.swap(col, pivot);
        b.swap(col, pivot);
        for row in col + 1..n {
            let f = a[row][col] / a[col][col];
            for k in col..n {
                a[row][k] -= f * a[col][k];
            }
            b[row] -= f * b[col];
        }
    }
    let mut x = vec![0.0; n];
    for row in (0..n).rev() {
        let rest: f64 = (row + 1..n).map(|k| a[row][k] * x[k]).sum();
        x[row] = (b[row] - rest) / a[row][row];
    }
    Some(x)
}

/// Ce que la proposition retient d'une sorte d'action.
#[cfg(test)]
#[derive(Default)]
struct Pool {
    /// (valeur, part dans la famille) : la part pèse chaque duel également.
    values: Vec<(f64, f64)>,
    /// (critères, valeur) pour les sortes à modèle.
    rows: Vec<(Vec<f64>, f64)>,
}

/// L'attendu d'une action sous la proposition.
#[cfg(test)]
fn proposed_expected(a: &Action, means: &std::collections::HashMap<&'static str, f64>, models: &std::collections::HashMap<&'static str, Vec<f64>>) -> f64 {
    let model = |code: &str, k: usize| models.get(code).map(|beta| features::logistic(beta[0], &beta[1..], &a.context[..k]));
    match a.code {
        "conversion.opening" => model("conversion.opening", 5).unwrap_or(a.expected),
        "defense.openings" => model("conversion.opening", 5).map(|p| 1.0 - p).unwrap_or(a.expected),
        "defense.dodge" => model("defense.dodge", 2).unwrap_or(a.expected),
        "control.reached" => model("control.reached", 2).unwrap_or(a.expected),
        code => means.get(code).copied().unwrap_or(a.expected),
    }
}

#[cfg(test)]
fn quantile(sorted: &[f64], q: f64) -> f64 {
    if sorted.is_empty() {
        return f64::NAN;
    }
    sorted[((sorted.len() - 1) as f64 * q).round() as usize]
}

/// Propose les valeurs à poser, d'après les duels de `YF_MATCHES` (écrit
/// aussi dans `YF_CALIB_OUT/proposition.txt` s'il est donné) :
///
/// ```text
/// YF_MATCHES=<dossier> cargo test --lib proposition_de_calibrage -- --ignored --nocapture
/// ```
///
/// 1. pour chaque sorte d'action, ce qu'en tirent en moyenne les joueurs
///    (`Norm::mean`) et l'écart habituel d'une action à l'autre
///    (`Norm::spread`) — chaque duel pesant autant ;
/// 2. les modèles de chance (ouvertures, esquives, descentes à portée),
///    appris par régression logistique sur les actions des deux joueurs ;
/// 3. l'écart habituel de chaque famille d'un duel à l'autre, une fois les
///    attendus posés (`FAMILY_SPREAD`), et l'élargissement qui donne à la
///    note un écart de 100 (`SPREAD_FACTOR`) ;
/// 4. la note qui sortirait de tout cela : ses quantiles et la part des
///    duels au-dessus de 350, 400, 450 et 500.
#[test]
#[ignore]
fn proposition_de_calibrage() {
    use std::collections::HashMap;
    let matches = std::env::var("YF_MATCHES").expect("YF_MATCHES : le dossier des journaux ou des exports");
    let (duels, skipped) = load_duels(&matches);
    // Seuls les duels notés : à la masse, assez longs, assez d'opportunités.
    // Un duel à l'épée ou de dix secondes apprendrait des attendus à des
    // situations que la note ne juge jamais.
    let scored: Vec<Features> = duels
        .iter()
        .filter(|(_, d)| d.mode == parse::Mode::Mace)
        .map(|(_, d)| features::extract(d))
        .filter(|f| rating::rate(f, &Prior::default()).reason.is_none())
        .collect();
    // Un duel noté sans un seul smash, ni d'un côté ni de l'autre, n'est pas
    // un duel où personne ne sait frapper : c'est un serveur dont les coups
    // de masse n'arrivent pas comme smashs (vu le 2026-10-22 sur un serveur
    // entier). Il apprendrait que « aucun smash » est la norme : il ne sert
    // qu'aux sortes d'action qui n'en dépendent pas (`SMASH_DEPENDENT`), et
    // jamais aux écarts des familles ni à la forme de la note.
    let (extracted, blind): (Vec<Features>, Vec<Features>) = scored.into_iter().partition(|f| f.mine.iter().chain(&f.theirs).any(|o| o.converted));
    let mut text = format!(
        "{} duels relus, {skipped} illisibles, {} notés complets, {} sans aucun smash relevé (actions hors smashs seulement).\n",
        duels.len(),
        extracted.len(),
        blind.len()
    );

    // Chaque famille des deux côtés : (famille, ses actions, son nombre d'événements).
    let sides = |f: &Features| -> Vec<(usize, Vec<Action>, f64, bool)> {
        let mine = [f.conversion, f.smash_quality, f.exposure, f.vertical, f.resources];
        let foe = [f.foe.conversion, f.foe.smash_quality, f.foe.exposure, f.foe.vertical];
        let mut out: Vec<(usize, Vec<Action>, f64, bool)> = f.actions.iter().cloned().enumerate().map(|(i, a)| (i, a, mine[i].count, true)).collect();
        out.extend(f.foe_actions.iter().cloned().enumerate().map(|(i, a)| (i, a, foe[i].count, false)));
        out
    };

    // 1 et 2. Les sortes d'action.
    let mut pools: HashMap<&'static str, Pool> = HashMap::new();
    let complete = extracted.iter().map(|f| (f, true));
    for (f, complete) in complete.chain(blind.iter().map(|f| (f, false))) {
        for (_, actions, _, _) in sides(f) {
            for a in actions.into_iter().filter(|a| complete || !SMASH_DEPENDENT.contains(&a.code)) {
                let pool = pools.entry(a.code).or_default();
                pool.values.push((a.value, a.share));
                let mut x = a.context.to_vec();
                if a.code == "conversion.opening" {
                    for i in LEAKING_CRITERIA {
                        x[i] = 0.0;
                    }
                }
                pool.rows.push((x, a.value));
            }
        }
    }
    let mut models: HashMap<&'static str, Vec<f64>> = HashMap::new();
    let _ = writeln!(text, "\n## Modèles de chance (constante, puis coefficients dans l'ordre des critères)");
    for (code, k) in MODELS {
        let Some(pool) = pools.get(code) else { continue };
        match fit_logistic(&pool.rows, k) {
            Some(beta) => {
                let _ = writeln!(text, "{code} : {} actions → {:?}", pool.rows.len(), beta.iter().map(|b| (b * 1000.0).round() / 1000.0).collect::<Vec<_>>());
                models.insert(code, beta);
            }
            None => {
                let _ = writeln!(text, "{code} : {} actions, régression impossible (critères sans variation)", pool.rows.len());
            }
        }
    }
    let mut means: HashMap<&'static str, f64> = HashMap::new();
    let mut spreads: HashMap<&'static str, f64> = HashMap::new();
    let _ = writeln!(text, "\n## Sortes d'action : Norm::new(code, moyenne, écart) — sur combien d'actions");
    let mut codes: Vec<&&'static str> = pools.keys().collect();
    codes.sort();
    for code in codes {
        let pool = &pools[*code];
        let weight: f64 = pool.values.iter().map(|(_, w)| w).sum();
        if weight <= 0.0 {
            continue;
        }
        let raw_mean = pool.values.iter().map(|(v, w)| v * w).sum::<f64>() / weight;
        means.insert(*code, raw_mean);
    }
    for code in pools.keys() {
        if let Some(fixed) = fixed_mean(code, &means) {
            means.insert(code, fixed);
        }
    }
    let mut codes: Vec<&&'static str> = pools.keys().collect();
    codes.sort();
    for code in codes {
        let pool = &pools[*code];
        let Some(&mean) = means.get(*code) else { continue };
        // L'écart d'une action à son attendu : la moyenne pour une sorte
        // simple, ce qu'en dit son modèle pour une sorte à chance.
        let model = MODELS.iter().find(|(c, _)| c == code || (*code == "defense.openings" && *c == "conversion.opening"));
        let residual = |(x, y): &(Vec<f64>, f64)| -> f64 {
            match model.and_then(|(c, k)| models.get(c).map(|beta| (*c, *k, beta))) {
                Some((c, k, beta)) => {
                    let p = features::logistic(beta[0], &beta[1..], &x[..k]);
                    let p = if *code == "defense.openings" && c == "conversion.opening" { 1.0 - p } else { p };
                    y - p
                }
                None => y - mean,
            }
        };
        let measured = (pool.rows.iter().map(|r| residual(r).powi(2)).sum::<f64>() / pool.rows.len() as f64).sqrt();
        // « Aucun smash » se juge comme un smash réussi : même écart.
        let spread = if **code == *"smash.none" { spreads.get("smash.hit").copied().unwrap_or(measured) } else { measured.max(MIN_SPREAD) };
        spreads.insert(*code, spread);
        let note = if fixed_mean(code, &means).is_some() { " (attendu fixé par construction)" } else if measured < MIN_SPREAD { " (écart relevé au plancher)" } else { "" };
        let _ = writeln!(text, "Norm::new(\"{code}\", {mean:.3}, {spread:.3}) — {} actions{note}", pool.values.len());
    }

    // 3. Les familles, sous ces attendus.
    let mut family_gaps: [Vec<f64>; 5] = Default::default();
    let mut per_duel: Vec<[f64; 5]> = Vec::new();
    for f in &extracted {
        let mut mine = [0.0; 5];
        for (family, actions, count, is_mine) in sides(f) {
            let gap: f64 = actions
                .iter()
                .map(|a| a.share * (a.value - proposed_expected(a, &means, &models)) / spreads.get(a.code).copied().unwrap_or(a.spread))
                .sum();
            let smoothed = gap * count / (count + rating::K);
            family_gaps[family].push(smoothed);
            if is_mine {
                mine[family] = smoothed;
            }
        }
        per_duel.push(mine);
    }
    let sd = |list: &[f64]| -> (f64, f64) {
        let n = list.len().max(1) as f64;
        let mean = list.iter().sum::<f64>() / n;
        (mean, (list.iter().map(|v| (v - mean).powi(2)).sum::<f64>() / n).sqrt().max(0.05))
    };
    let names = ["conversion", "qualité du smash", "défense", "contrôle", "ressources"];
    let mut family_spread = [0.0; 5];
    let _ = writeln!(text, "\n## Familles (écart après lissage) : FAMILY_SPREAD");
    for (i, gaps) in family_gaps.iter().enumerate() {
        let (mean, spread) = sd(gaps);
        family_spread[i] = spread;
        let _ = writeln!(text, "{} : écart {spread:.3}, moyenne {mean:+.3} sur {} relevés", names[i], gaps.len());
    }
    let _ = writeln!(text, "FAMILY_SPREAD = {:?}", family_spread.map(|s| (s * 1000.0).round() / 1000.0));

    // 4. La note qui en sortirait : la moyenne de mes cinq familles.
    let means_z: Vec<f64> = per_duel.iter().map(|g| g.iter().zip(&family_spread).map(|(g, s)| g / s).sum::<f64>() / rating::FAMILIES).collect();
    let (_, spread_of_mean) = sd(&means_z);
    let factor = 1.0 / spread_of_mean;
    let _ = writeln!(text, "\n## Élargissement : SPREAD_FACTOR = {factor:.3} (pour un écart de note de 100 ; actuel {})", rating::SPREAD_FACTOR);
    let mut notes: Vec<f64> = means_z.iter().map(|m| (rating::CENTER + 100.0 * m * factor).clamp(0.0, rating::MAX)).collect();
    notes.sort_by(f64::total_cmp);
    let above = |limit: f64| 100.0 * notes.iter().filter(|n| **n >= limit).count() as f64 / notes.len().max(1) as f64;
    let _ = writeln!(
        text,
        "Notes (sans bug use) : 10 % {:.0}, 25 % {:.0}, médiane {:.0}, 75 % {:.0}, 90 % {:.0} ; au-dessus de 350 : {:.1} %, 400 : {:.1} %, 450 : {:.1} %, 500 : {:.1} %",
        quantile(&notes, 0.1),
        quantile(&notes, 0.25),
        quantile(&notes, 0.5),
        quantile(&notes, 0.75),
        quantile(&notes, 0.9),
        above(350.0),
        above(400.0),
        above(450.0),
        above(500.0),
    );
    println!("{text}");
    if let Ok(out) = std::env::var("YF_CALIB_OUT") {
        std::fs::create_dir_all(&out).unwrap();
        std::fs::write(Path::new(&out).join("proposition.txt"), &text).unwrap();
    }
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
