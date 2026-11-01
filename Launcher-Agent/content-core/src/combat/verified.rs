//! Le classement VÉRIFIÉ (docs/scoring/classement.md, « Classement vérifié ») :
//! le même calcul, fait par le jeu et par le serveur, sur le même état.
//!
//! Un duel ne se classe pas seul : sa note dépend de la force connue de
//! l'adversaire (ses duels précédents contre nous), et ses points de toute la
//! position d'avant. Le serveur garde donc l'ÉTAT du classement de chaque
//! compte Minecraft ([`LadderState`] : positions, placement, adversaires déjà
//! vus, en pseudonymes), et le jeu en garde une copie. À la fin d'un duel
//! compétitif :
//!
//!   1. le jeu anonymise le journal ([`anonymize`], avec le sel de l'état)
//!      et l'applique à sa copie ([`apply`]) : c'est ce qu'il annonce ;
//!   2. le serveur applique le même journal à SON état, avec ce même code ;
//!   3. mêmes nombres ([`Outcome::agrees`]) : le nouvel état est retenu des
//!      deux côtés ; sinon le duel ne compte nulle part.
//!
//! Ce module ne touche ni au disque ni au réseau : le serveur (LauncherAPI)
//! l'utilise tel quel, c'est ce qui garantit le même calcul.
//!
//! Ce que la vérification attrape : un bug, deux versions différentes, un
//! résultat modifié à la main. Ce qu'elle n'attrape pas : un journal entier
//! fabriqué par un client modifié — il serait cohérent avec lui-même.

use std::collections::BTreeMap;

use ring::digest::{Context, SHA256};
use serde::{Deserialize, Serialize};

use super::ranked::{self, Ladder, SavedLadder, CHAMPION, TIERS};
use super::score::{score_with, Journal, Prior};

/// Forme de [`LadderState`] : à incrémenter si elle change.
pub const STATE_FORMAT: u32 = 1;
/// Adversaires gardés au plus : les plus anciennement vus partent d'abord.
const MAX_FOES: usize = 2000;
/// Niveaux gardés par adversaire : les plus récents.
const MAX_LEVELS: usize = 20;
const DAY_MS: u64 = 86_400_000;
/// Sépare les pseudonymes du classement de tout autre SHA-256.
const PSEUDONYM_DOMAIN: &[u8] = b"yuyuframe-ranked-pseudonym-v1";
const SERVER_DOMAIN: &[u8] = b"yuyuframe-ranked-server-v1";

/// La force connue d'un adversaire : son niveau dans nos duels contre lui.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FoeLevels {
    /// `Rating::opponent.z` de chaque duel noté contre lui, du plus ancien au plus récent.
    pub levels: Vec<f64>,
    /// Heure du dernier duel contre lui : les plus anciens partent d'abord.
    pub last_seen: u64,
}

/// L'état du classement vérifié d'un compte Minecraft.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct LadderState {
    pub format: u32,
    /// Sel des pseudonymes (64 hexa), tiré par le serveur à la création :
    /// un adversaire a le même pseudonyme d'une installation à l'autre du
    /// même joueur, et un autre chez chaque joueur.
    pub salt: String,
    /// Heure d'ouverture du dernier duel appliqué : un duel plus ancien ou
    /// déjà appliqué est refusé.
    pub last_time: u64,
    pub ladder: SavedLadder,
    /// Pseudonyme de l'adversaire → sa force connue.
    pub foes: BTreeMap<String, FoeLevels>,
    /// Numéro de la saison où vit cet état (0 : d'avant les saisons). Le
    /// serveur le fait avancer par [`season_reset`] ; le jeu ne fait que suivre.
    #[serde(default)]
    pub season: u32,
}

impl LadderState {
    /// Un état neuf : tout à faire, le placement compris.
    pub fn new(salt: String) -> Self {
        Self { format: STATE_FORMAT, salt, last_time: 0, ladder: SavedLadder::default(), foes: BTreeMap::new(), season: 0 }
    }
}

/// Divisions perdues à chaque nouvelle saison : un rang entier.
pub const SEASON_DROP: usize = 3;

/// L'état au début de la saison `season` : chaque mode PLACÉ redescend d'un
/// rang ([`SEASON_DROP`] divisions, jamais sous Bois 1) et repart à 0 point,
/// sans bouclier ; il reste placé — pas de nouveau placement. Un mode en cours
/// de placement continue où il en était. Les adversaires connus restent (leur
/// force ne change pas avec la saison). Sans effet si l'état est déjà à cette
/// saison ou plus loin.
pub fn season_reset(state: &LadderState, season: u32) -> LadderState {
    let mut next = state.clone();
    if season <= state.season {
        return next;
    }
    for standing in &mut next.ladder.standings {
        if standing.duels >= super::ranked::PLACEMENT_DUELS {
            standing.division = standing.division.saturating_sub(SEASON_DROP);
            standing.points = 0;
            standing.shield = false;
        }
    }
    next.season = season;
    next
}

/// Ce qu'un duel a rapporté, en nombres. Les entiers sont comparés entre le
/// jeu et le serveur ([`Outcome::agrees`]) ; les détails en virgule flottante
/// ne servent qu'à l'affichage.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct Outcome {
    /// 1 masse, 2 épée.
    pub mode: u8,
    pub total: i32,
    pub base: f64,
    pub play: f64,
    pub foe_factor: f64,
    pub repeat_factor: f64,
    pub before_division: usize,
    pub before_points: i32,
    pub before_duels: usize,
    pub after_division: usize,
    pub after_points: i32,
    pub after_duels: usize,
    /// Placement terminé après ce duel.
    pub placed: bool,
    /// 0 après le placement, 1 duel de placement, 2 joué pendant le placement sans y compter.
    pub placement: u8,
}

impl Outcome {
    /// Le jeu et le serveur ont-ils classé ce duel de la même façon ? Seuls
    /// les entiers comptent : un dernier chiffre de virgule flottante peut
    /// différer d'une machine à l'autre, le total arrondi et la position non.
    pub fn agrees(&self, other: &Outcome) -> bool {
        self.mode == other.mode
            && self.total == other.total
            && self.before_division == other.before_division
            && self.before_points == other.before_points
            && self.before_duels == other.before_duels
            && self.after_division == other.after_division
            && self.after_points == other.after_points
            && self.after_duels == other.after_duels
            && self.placed == other.placed
            && self.placement == other.placement
    }

    /// Les nombres du panneau de fin de duel (contrat `NativeCombat.RANKED_*`) :
    /// total, base, jeu, adversaire, répétition, position avant puis après,
    /// placement terminé, casual.
    pub fn numbers(&self, casual: bool) -> [f64; super::history::RANKED_LEN] {
        [
            self.total as f64,
            self.base,
            self.play,
            self.foe_factor,
            self.repeat_factor,
            self.before_division as f64,
            self.before_points as f64,
            self.before_duels as f64,
            self.after_division as f64,
            self.after_points as f64,
            self.after_duels as f64,
            self.placed as u8 as f64,
            casual as u8 as f64,
        ]
    }
}

/// Pourquoi un journal n'a pas été classé.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Rejection {
    /// Pas un journal lisible.
    NotAJournal,
    /// Joué en casual : il ne compte pas, il ne s'envoie pas.
    Casual,
    /// Pas noté, pas de verdict, ou mode sans classement.
    NotRanked,
    /// Plus ancien que le dernier duel appliqué, ou déjà appliqué.
    OutOfOrder,
}

impl Rejection {
    /// Le code stable, pour l'API et le journal.
    pub fn code(self) -> &'static str {
        match self {
            Rejection::NotAJournal => "not_a_journal",
            Rejection::Casual => "casual",
            Rejection::NotRanked => "not_ranked",
            Rejection::OutOfOrder => "out_of_order",
        }
    }
}

/// Un duel appliqué : ce qu'il a rapporté, et l'état d'après.
#[derive(Debug, Clone)]
pub struct Applied {
    pub outcome: Outcome,
    pub state: LadderState,
    /// Heure d'ouverture du duel.
    pub time: u64,
    /// Ce que l'historique montre du duel, tiré du même calcul.
    pub details: Details,
}

/// Le détail d'un duel pour l'historique : calculé depuis le journal par le
/// serveur lui-même, donc rien à croire du jeu. Hors de [`Outcome::agrees`].
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Details {
    /// `win`, `loss`, `draw`, `unknown` ; absent si le journal s'arrête avant le verdict.
    pub result: Option<String>,
    /// La note du duel, sur 500, bonus compris ; absente si le duel n'est pas noté.
    pub score: Option<f64>,
    pub duration_secs: f64,
    /// Ping moyen, en millisecondes.
    pub ping: Option<f64>,
}

/// Ce que le jeu déclare en clair à côté du journal anonymisé : l'adversaire
/// et le serveur, que l'historique affiche. Le serveur ne les croit pas sur
/// parole : il les recoupe avec les empreintes du journal ([`check_declared`]).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Declared {
    /// UUID de l'adversaire, sans tirets.
    pub foe_uuid: Option<String>,
    pub foe_name: Option<String>,
    /// Adresse du serveur de jeu, telle que le joueur l'a tapée.
    pub server: Option<String>,
}

/// Ce qu'il faut déclarer d'un journal EN CLAIR (avant [`anonymize`]).
pub fn declared(text: &str) -> Declared {
    let journal = Journal::read(text);
    let server = text
        .lines()
        .filter_map(|l| serde_json::from_str::<serde_json::Value>(l).ok())
        .find(|l| l["k"] == "h")
        .and_then(|h| h["server"].as_str().map(|s| s.trim().to_string()))
        .filter(|s| !s.is_empty());
    Declared {
        foe_uuid: journal.as_ref().and_then(|j| j.foe_uuid()).map(normalize_uuid),
        foe_name: journal.as_ref().and_then(|j| j.foe_name()).map(str::to_string),
        server,
    }
}

/// Ce que le jeu déclare correspond-il au journal anonymisé ? L'UUID de
/// l'adversaire doit donner son pseudonyme sous le sel de l'état, l'adresse
/// l'empreinte du serveur. Le pseudo, lui, ne se vérifie pas : il doit
/// seulement avoir la forme d'un pseudo Minecraft.
pub fn check_declared(anonymized: &str, salt: &str, declared: &Declared) -> bool {
    let Some(journal) = Journal::read(anonymized) else { return false };
    let foe_ok = match (journal.foe_uuid(), declared.foe_uuid.as_deref()) {
        (_, None) => true,
        (Some(hidden), Some(clear)) => normalize_uuid(hidden) == pseudonym(salt, &normalize_uuid(clear)),
        (None, Some(_)) => false,
    };
    let print = anonymized
        .lines()
        .filter_map(|l| serde_json::from_str::<serde_json::Value>(l).ok())
        .find(|l| l["k"] == "h")
        .and_then(|h| h["server"].as_str().map(str::to_string))
        .unwrap_or_default();
    let server_ok = match declared.server.as_deref() {
        None => true,
        Some(address) => server_print(address) == print,
    };
    let name_ok = declared.foe_name.as_deref().map(is_minecraft_name).unwrap_or(true);
    foe_ok && server_ok && name_ok
}

/// 3 à 16 lettres, chiffres ou `_` : la forme d'un pseudo Minecraft.
pub fn is_minecraft_name(name: &str) -> bool {
    (3..=16).contains(&name.len()) && name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
}

fn normalize_uuid(uuid: &str) -> String {
    uuid.replace('-', "").to_ascii_lowercase()
}

/// L'empreinte d'une adresse de serveur dans un journal anonymisé (vide sans adresse).
pub fn server_print(address: &str) -> String {
    let server = address.trim().to_ascii_lowercase();
    if server.is_empty() {
        String::new()
    } else {
        format!("srv-{}", &hex(&sha256(&[SERVER_DOMAIN, server.as_bytes()]))[..16])
    }
}

/// Applique un journal (anonymisé par [`anonymize`]) à `state`, sans le
/// modifier : rend ce que le duel a rapporté et l'état d'après. Exactement ce
/// que fait le serveur ; le jeu fait la même chose pour l'annoncer.
pub fn apply(state: &LadderState, journal: &str) -> Result<Applied, Rejection> {
    let journal = Journal::read(journal).ok_or(Rejection::NotAJournal)?;
    let time = journal.time().ok_or(Rejection::NotAJournal)?;
    let key = journal.foe_key();
    let known = key.as_ref().and_then(|k| state.foes.get(k)).map(|f| Prior::from_levels(&f.levels)).unwrap_or_default();
    let report = score_with(&journal, &known);
    if report.casual {
        return Err(Rejection::Casual);
    }
    if time <= state.last_time {
        return Err(Rejection::OutOfOrder);
    }
    let mut next = state.clone();
    // La force de l'adversaire pour le classement : ses duels passés et
    // celui-ci (comme `history::duels`).
    let foe = match (key, report.informs_prior()) {
        (Some(key), true) => {
            let entry = next.foes.entry(key).or_insert_with(|| FoeLevels { levels: Vec::new(), last_seen: time });
            entry.levels.push(report.rating.opponent.z);
            if entry.levels.len() > MAX_LEVELS {
                let extra = entry.levels.len() - MAX_LEVELS;
                entry.levels.drain(..extra);
            }
            entry.last_seen = time;
            Prior::from_levels(&entry.levels)
        }
        _ => Prior::from_levels(&[report.rating.opponent.z]),
    };
    let played = ranked::played_of(&report, &foe).ok_or(Rejection::NotRanked)?;
    let mut ladder = Ladder::restore(&state.ladder);
    let change = ladder.play(&played).ok_or(Rejection::NotRanked)?;
    prune_foes(&mut next.foes);
    next.ladder = ladder.save(time / DAY_MS);
    next.last_time = time;
    let outcome = Outcome {
        mode: ranked::mode_code(played.mode),
        total: change.total,
        base: change.base,
        play: change.play,
        foe_factor: change.foe_factor,
        repeat_factor: change.repeat_factor,
        before_division: change.before.division,
        before_points: change.before.points,
        before_duels: change.before.duels,
        after_division: change.after.division,
        after_points: change.after.points,
        after_duels: change.after.duels,
        placed: change.after.placed(),
        placement: change.placement.code(),
    };
    let details = Details {
        result: report.result.clone(),
        score: report.rating.score.map(|s| (s * 10.0).round() / 10.0),
        duration_secs: report.duration_secs,
        ping: report.ping,
    };
    Ok(Applied { outcome, state: next, time, details })
}

/// Ce qu'un duel joué en casual AURAIT rapporté, sans rien changer : le
/// panneau le montre (« tu aurais gagné »). `None` s'il n'aurait pas compté.
pub fn preview(state: &LadderState, journal: &str) -> Option<Outcome> {
    // Le même calcul, sur un journal relu comme compétitif.
    let mut lines: Vec<serde_json::Value> = journal.lines().filter_map(|l| serde_json::from_str(l).ok()).collect();
    for line in &mut lines {
        if line["k"] == "h" {
            line["ranked"] = serde_json::Value::Bool(true);
        }
    }
    let text: String = lines.iter().map(|l| format!("{l}\n")).collect();
    let mut probe = state.clone();
    probe.last_time = 0;
    apply(&probe, &text).ok().map(|a| a.outcome)
}

/// Oublie les adversaires les plus anciennement vus au-delà de `MAX_FOES`.
/// Déterministe (heure, puis pseudonyme) : le jeu et le serveur oublient les mêmes.
fn prune_foes(foes: &mut BTreeMap<String, FoeLevels>) {
    if foes.len() <= MAX_FOES {
        return;
    }
    let mut by_age: Vec<(u64, String)> = foes.iter().map(|(k, f)| (f.last_seen, k.clone())).collect();
    by_age.sort();
    for (_, key) in by_age.into_iter().take(foes.len() - MAX_FOES) {
        foes.remove(&key);
    }
}

// ── Anonymisation ────────────────────────────────────────────────────────

/// Le journal sans ce qui désigne quelqu'un ou quelque part : l'adresse du
/// serveur devient une empreinte, l'UUID et le pseudo de chaque joueur un
/// pseudonyme sous le sel de l'état ([`pseudonym`]). Le reste est intact, la
/// note n'en dépend pas.
pub fn anonymize(text: &str, salt: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for line in text.lines() {
        let Ok(mut value) = serde_json::from_str::<serde_json::Value>(line) else { continue };
        match value["k"].as_str() {
            Some("h") => {
                let print = server_print(value["server"].as_str().unwrap_or(""));
                value["server"] = serde_json::Value::String(print);
            }
            Some("p") => {
                let uuid = value["uuid"].as_str().unwrap_or("").replace('-', "").to_ascii_lowercase();
                let name = pseudonym(salt, &uuid);
                value["name"] = serde_json::Value::String(format!("joueur-{}", &name[..6]));
                value["uuid"] = serde_json::Value::String(name);
            }
            _ => {}
        }
        out.push_str(&value.to_string());
        out.push('\n');
    }
    out
}

/// Le pseudonyme d'un joueur sous le sel d'un état : 32 hexa. `uuid` sans
/// tirets, en minuscules.
pub fn pseudonym(salt: &str, uuid: &str) -> String {
    hex(&sha256(&[PSEUDONYM_DOMAIN, salt.as_bytes(), uuid.as_bytes()]))[..32].to_string()
}

/// L'UUID (sans tirets) du joueur qui a enregistré ce journal : celui du
/// joueur `me` de l'en-tête. Dans un journal anonymisé, c'est son pseudonyme.
pub fn own_uuid(text: &str) -> Option<String> {
    let lines: Vec<serde_json::Value> = text.lines().filter_map(|l| serde_json::from_str(l).ok()).collect();
    let me = lines.iter().find(|l| l["k"] == "h")?.get("me")?.as_i64()?;
    lines
        .iter()
        .find(|l| l["k"] == "p" && l["id"].as_i64() == Some(me))
        .and_then(|l| l["uuid"].as_str())
        .map(|u| u.replace('-', "").to_ascii_lowercase())
}

/// Le journal a-t-il été joué en compétitif ? (Un journal sans le champ l'est.)
pub fn is_competitive(text: &str) -> bool {
    text.lines()
        .filter_map(|l| serde_json::from_str::<serde_json::Value>(l).ok())
        .find(|l| l["k"] == "h")
        .map(|h| h["ranked"].as_bool() != Some(false))
        .unwrap_or(false)
}

// ── Lecture de l'état ────────────────────────────────────────────────────

/// La position d'un mode dans l'état : `(code du mode, division, points, duels, placement terminé)`.
pub fn standings(state: &LadderState) -> Vec<(u8, usize, i32, usize, bool)> {
    let ladder = Ladder::restore(&state.ladder);
    [1u8, 2]
        .iter()
        .filter_map(|&code| ranked::mode_of(code).map(|mode| (code, ladder.standing(mode))))
        .map(|(code, s)| (code, s.division, s.points, s.duels, s.placed()))
        .collect()
}

/// Le meilleur rang classé de l'état, tous modes confondus (0 = Bois…
/// 6 = Champion) — ce que montre le logo. `None` tant qu'aucun placement
/// n'est terminé.
pub fn best_tier(state: &LadderState) -> Option<usize> {
    standings(state)
        .into_iter()
        .filter(|s| s.4)
        .map(|s| s.1.min(CHAMPION))
        .max()
        .map(|division| (division / 3).min(TIERS.len() - 1))
}

/// Le nom d'un rang pour le code de logo, en ASCII : `bois` … `champion`.
pub fn tier_code(tier: usize) -> &'static str {
    const CODES: [&str; 7] = ["bois", "pierre", "fer", "or", "diamant", "netherite", "champion"];
    CODES[tier.min(CODES.len() - 1)]
}

fn sha256(parts: &[&[u8]]) -> [u8; 32] {
    let mut ctx = Context::new(&SHA256);
    for part in parts {
        ctx.update(part);
    }
    let mut out = [0u8; 32];
    out.copy_from_slice(ctx.finish().as_ref());
    out
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const SALT: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    fn defeat() -> &'static str {
        include_str!("score/fixtures/duel_masse_defaite.ndjson")
    }

    /// Le même journal, ouvert `ms` plus tard.
    fn later(text: &str, ms: u64) -> String {
        text.lines()
            .filter_map(|l| serde_json::from_str::<serde_json::Value>(l).ok())
            .map(|mut l| {
                if l["k"] == "h" {
                    let t = l["time"].as_u64().unwrap();
                    l["time"] = serde_json::json!(t + ms);
                }
                format!("{l}\n")
            })
            .collect()
    }

    #[test]
    fn l_anonymat_ne_change_pas_le_classement() {
        let state = LadderState::new(SALT.into());
        let plain = apply(&state, defeat()).expect("un duel classé");
        let hidden = apply(&state, &anonymize(defeat(), SALT)).expect("un duel classé");
        assert!(plain.outcome.agrees(&hidden.outcome), "{:?} / {:?}", plain.outcome, hidden.outcome);
        assert_eq!(plain.outcome.placement, 1, "un duel de placement");
    }

    #[test]
    fn le_journal_anonymise_ne_nomme_plus_personne() {
        let hidden = anonymize(defeat(), SALT);
        assert!(!hidden.contains("Rival"));
        let own = own_uuid(defeat()).expect("notre joueur");
        assert_eq!(own_uuid(&hidden).unwrap(), pseudonym(SALT, &own), "le serveur retrouve notre pseudonyme");
        // Un autre sel, un autre pseudonyme.
        assert_ne!(pseudonym(SALT, &own), pseudonym(&SALT.replace('0', "f"), &own));
    }

    #[test]
    fn un_duel_ne_s_applique_qu_une_fois_et_dans_l_ordre() {
        let state = LadderState::new(SALT.into());
        let first = apply(&state, defeat()).unwrap();
        assert_eq!(apply(&first.state, defeat()).unwrap_err(), Rejection::OutOfOrder);
        let second = apply(&first.state, &later(defeat(), 1000)).unwrap();
        assert_eq!(second.outcome.before_duels, 1);
        assert_eq!(second.outcome.repeat_factor, 0.5, "le même adversaire le même jour");
    }

    #[test]
    fn l_etat_se_sauve_et_se_relit_a_l_identique() {
        let state = LadderState::new(SALT.into());
        let applied = apply(&state, defeat()).unwrap();
        let json = serde_json::to_string(&applied.state).unwrap();
        let back: LadderState = serde_json::from_str(&json).unwrap();
        assert_eq!(back, applied.state);
        // Le duel suivant se classe pareil depuis l'état relu.
        let a = apply(&applied.state, &later(defeat(), 1000)).unwrap();
        let b = apply(&back, &later(defeat(), 1000)).unwrap();
        assert!(a.outcome.agrees(&b.outcome));
    }

    #[test]
    fn un_resultat_retouche_ne_s_accorde_pas() {
        let state = LadderState::new(SALT.into());
        let honest = apply(&state, defeat()).unwrap().outcome;
        let mut forged = honest;
        forged.total += 10;
        assert!(!honest.agrees(&forged));
    }

    #[test]
    fn un_duel_casual_n_est_pas_classe_mais_se_previsualise() {
        let casual: String = defeat()
            .lines()
            .filter_map(|l| serde_json::from_str::<serde_json::Value>(l).ok())
            .map(|mut l| {
                if l["k"] == "h" {
                    l["ranked"] = serde_json::json!(false);
                }
                format!("{l}\n")
            })
            .collect();
        assert!(!is_competitive(&casual));
        let state = LadderState::new(SALT.into());
        assert_eq!(apply(&state, &casual).unwrap_err(), Rejection::Casual);
        let preview = preview(&state, &casual).expect("ce qu'il aurait rapporté");
        assert!(preview.agrees(&apply(&state, defeat()).unwrap().outcome));
    }

    #[test]
    fn une_nouvelle_saison_fait_redescendre_d_un_rang() {
        use super::super::ranked::SavedStanding;
        let mut state = LadderState::new(SALT.into());
        state.ladder.standings = vec![
            SavedStanding { mode: 1, division: 10, points: 340, duels: 50, shield: true, placement: 0 },
            SavedStanding { mode: 2, division: 1, points: 120, duels: 30, shield: false, placement: 0 },
        ];
        let next = season_reset(&state, 2);
        assert_eq!((next.ladder.standings[0].division, next.ladder.standings[0].points), (7, 0));
        assert!(!next.ladder.standings[0].shield);
        assert_eq!(next.ladder.standings[1].division, 0, "jamais sous Bois 1");
        assert_eq!(next.season, 2);
        // Déjà à cette saison : rien ne bouge.
        assert_eq!(season_reset(&next, 2), next);
        // En placement : on continue.
        state.ladder.standings[0].duels = 4;
        assert_eq!(season_reset(&state, 2).ladder.standings[0].division, 10);
    }

    #[test]
    fn le_detail_vient_du_journal() {
        let state = LadderState::new(SALT.into());
        let applied = apply(&state, &anonymize(defeat(), SALT)).unwrap();
        assert_eq!(applied.details.result.as_deref(), Some("loss"));
        assert!(applied.details.duration_secs > 0.0);
    }

    #[test]
    fn la_declaration_se_recoupe_avec_le_journal() {
        let hidden = anonymize(defeat(), SALT);
        let honest = declared(defeat());
        assert!(honest.foe_uuid.is_some(), "l'adversaire du duel d'essai est connu");
        assert!(check_declared(&hidden, SALT, &honest));
        // Un autre adversaire, un autre serveur : refusé.
        let other_foe = Declared { foe_uuid: Some("0123456789ab4def8123456789abffff".into()), ..honest.clone() };
        assert!(!check_declared(&hidden, SALT, &other_foe));
        let other_server = Declared { server: Some("ailleurs.example".into()), ..honest.clone() };
        assert!(!check_declared(&hidden, SALT, &other_server) || honest.server.is_none());
        let bad_name = Declared { foe_name: Some("pas un pseudo !".into()), ..honest };
        assert!(!check_declared(&hidden, SALT, &bad_name));
        // Rien de déclaré (ancien jeu) : rien à refuser.
        assert!(check_declared(&hidden, SALT, &Declared::default()));
    }

    #[test]
    fn pas_de_logo_de_rang_avant_le_placement() {
        let state = LadderState::new(SALT.into());
        assert_eq!(best_tier(&state), None);
        assert_eq!(tier_code(6), "champion");
    }
}
