//! Le classement vérifié, côté jeu : la copie de l'état, et ses échanges avec
//! LauncherAPI (docs/scoring/classement.md, « Classement vérifié »).
//!
//! **Le serveur est la seule source du classement** (2026-11-01). Il n'y a
//! plus de classement local ni de file d'attente :
//!
//! - au lancement, puis toutes les deux minutes, et dès que le launcher
//!   change de compte ([`poke`]), l'état du compte Minecraft de la partie est
//!   relu (`GET /v1/ranked/state/{uuid}`) : le rang affiché est toujours
//!   celui du serveur ;
//! - un duel ne s'ouvre en compétitif que si cette lecture a réussi
//!   ([`can_rank`], lu par le fil d'écriture à l'ouverture de chaque duel) ;
//!   sinon il s'ouvre en casual, quel que soit le choix du joueur ;
//! - à la fin d'un duel compétitif ([`on_duel`]), le journal est anonymisé,
//!   appliqué à la copie, puis envoyé (`POST /v1/ranked/duels`). Le serveur
//!   refait le calcul : mêmes nombres, le duel compte ; sinon non. Sans
//!   réponse après trois essais, le duel ne compte pas.
//!
//! Les duels de l'ancien classement local sont effacés à la première
//! synchronisation de chaque compte dans chaque instance ([`purge`]) : tout
//! journal compétitif de ce compte que le serveur n'a jamais vérifié, et tout
//! journal compétitif joué sous un UUID qui ne prouve rien (serveur de jeu en
//! mode hors ligne). Les duels casual restent.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::{Condvar, Mutex, OnceLock};
use std::time::{Duration, Instant};

use super::history::RANKED_LEN;
use super::verified::{self, LadderState, Outcome, Rejection};

/// Ce que le panneau dit du classement du dernier duel (contrat
/// `NativeCombat.RANKED_STATUS_*`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub enum Status {
    /// Rien encore (la valeur que lit Java avant le premier duel).
    #[allow(dead_code)]
    None = 0,
    /// Le serveur a fait le même calcul : le duel compte.
    Verified = 1,
    /// Le serveur a trouvé autre chose : le duel ne compte pas.
    Rejected = 2,
    /// Le serveur n'a pas répondu : le duel ne compte pas.
    Unreachable = 3,
    /// Pas de compte YuyuFrame connecté : rien n'est vérifiable.
    NoAccount = 4,
    /// Joué en casual : ce qu'il aurait rapporté, rien n'est envoyé.
    Casual = 5,
    // 6 : l'ancien classement local, retiré le 2026-11-01.
    /// Serveur de jeu en mode hors ligne : identité non prouvée, pas de classement.
    OfflineServer = 7,
}

/// Le classement est-il possible en ce moment ? (Contrat
/// `NativeCombat.RANKED_READY_*`.)
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub enum Readiness {
    /// Première lecture en cours.
    Checking = 0,
    /// L'état du compte vient du serveur : un duel peut être compétitif.
    Ready = 1,
    /// Pas de compte YuyuFrame connecté dans le launcher (ou pas de launcher).
    NoAccount = 2,
    /// Le compte Minecraft de la partie n'est pas un compte Microsoft.
    NotMicrosoft = 3,
    /// Le serveur ne répond pas.
    Unreachable = 4,
    /// L'API ne connaît pas le classement.
    Unsupported = 5,
}

/// Le dernier état connu d'un compte Minecraft, tel que le serveur l'a rendu.
#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct Cached {
    pub uuid: String,
    pub version: i64,
    pub state: LadderState,
    /// Le code du logo que les autres voient (`base`, `rank:or`, `title:choc`…).
    pub badge: String,
    pub show_rank: bool,
}

/// Un duel vérifié, tel que l'historique du serveur le garde : ce que la liste
/// des duels montre de son classement.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ServerDuel {
    /// Points gagnés ou perdus (0 pendant le placement : ils sont cachés).
    pub rr: i32,
    pub placement: bool,
    pub after_division: i64,
    pub after_points: i64,
}

static CACHE: Mutex<Option<Cached>> = Mutex::new(None);
static STATUS: Mutex<(i32, i32)> = Mutex::new((0, 0));
static READY: AtomicI32 = AtomicI32::new(Readiness::Checking as i32);
static SHOW_RANK: AtomicBool = AtomicBool::new(true);
/// Le réglage a changé et n'est pas encore parti.
static SHOW_RANK_DIRTY: AtomicBool = AtomicBool::new(false);
/// L'API n'a pas ces routes : inutile d'insister pendant cette partie.
static UNSUPPORTED: AtomicBool = AtomicBool::new(false);
/// Les instances où l'on a joué ou regardé l'historique pendant cette partie.
static INSTANCES: Mutex<Vec<PathBuf>> = Mutex::new(Vec::new());
/// Le compte, et l'heure de la dernière lecture réussie de son état.
static SYNCED: Mutex<Option<(String, Instant)>> = Mutex::new(None);
/// Les duels vérifiés du compte, par heure d'ouverture du journal.
static SERVER_DUELS: Mutex<Option<(String, HashMap<u64, ServerDuel>)>> = Mutex::new(None);
/// Les couples (instance, compte) déjà nettoyés pendant cette partie.
static PURGED: Mutex<Vec<(PathBuf, String)>> = Mutex::new(Vec::new());
/// Les journaux ouverts pendant cette partie : jamais effacés par [`purge`]
/// (un duel peut être en cours de vérification).
static OPENED: Mutex<Vec<PathBuf>> = Mutex::new(Vec::new());

/// Une lecture de l'état plus vieille que cela est refaite.
const REFRESH: Duration = Duration::from_secs(120);
/// Le fil se réveille au moins à cette cadence.
const TICK: Duration = Duration::from_secs(30);
/// Les essais d'envoi d'un duel, et l'attente avant chacun.
const SUBMIT_WAITS: [Duration; 3] = [Duration::ZERO, Duration::from_secs(3), Duration::from_secs(8)];
/// Un journal plus gros n'est pas envoyé (la route le refuserait).
const MAX_PACKET: usize = 4 * 1024 * 1024;
/// Pages d'historique lues au plus (100 duels chacune).
const MAX_HISTORY_PAGES: usize = 500;

// ── Ce que lisent Java et le fil d'écriture ──────────────────────────────

/// Le statut du classement du duel `number` (numéro du résumé).
pub fn status() -> (i32, i32) {
    *STATUS.lock().unwrap_or_else(|p| p.into_inner())
}

fn set_status(number: i32, status: Status) {
    *STATUS.lock().unwrap_or_else(|p| p.into_inner()) = (number, status as i32);
}

/// Les nombres d'un duel vérifié ou non, et son statut, pour le panneau.
pub fn publish(number: i32, status: Status) {
    set_status(number, status);
}

/// Le classement est-il possible en ce moment, et sinon pourquoi.
pub fn readiness() -> Readiness {
    if !crate::link::available() {
        return Readiness::NoAccount;
    }
    start_worker();
    match READY.load(Ordering::Relaxed) {
        1 => Readiness::Ready,
        2 => Readiness::NoAccount,
        3 => Readiness::NotMicrosoft,
        4 => Readiness::Unreachable,
        5 => Readiness::Unsupported,
        _ => Readiness::Checking,
    }
}

/// Un duel ouvert maintenant peut-il être compétitif ?
pub fn can_rank() -> bool {
    READY.load(Ordering::Relaxed) == Readiness::Ready as i32
}

fn set_ready(ready: Readiness) {
    READY.store(ready as i32, Ordering::Relaxed);
}

/// La copie de l'état, quel que soit le compte.
pub fn cached() -> Option<Cached> {
    CACHE.lock().unwrap_or_else(|p| p.into_inner()).clone()
}

/// La copie de l'état du compte `uuid`, si c'est celle qu'on a.
fn cached_for(uuid: &str) -> Option<Cached> {
    cached().filter(|c| c.uuid == uuid)
}

/// L'état à afficher pour le compte de la partie : la copie lue du serveur,
/// sinon celle écrite dans l'instance par une partie précédente — jamais
/// celle d'un autre compte. `None` si le compte n'est pas connu.
pub fn displayed(instance: &Path) -> Option<Cached> {
    let account = crate::presence::token::store().account()?;
    if let Some(cache) = cached_for(&account) {
        return Some(cache);
    }
    let text = std::fs::read_to_string(cache_path(instance)).ok()?;
    serde_json::from_str::<Cached>(&text).ok().filter(|c| c.uuid == account)
}

/// Le classement d'un duel de la liste, tel que le serveur l'a vérifié.
pub fn server_duel(time: u64) -> Option<ServerDuel> {
    let account = crate::presence::token::store().account()?;
    let guard = SERVER_DUELS.lock().unwrap_or_else(|p| p.into_inner());
    guard.as_ref().filter(|(a, _)| *a == account).and_then(|(_, duels)| duels.get(&time).copied())
}

/// Le réglage « Montrer mon rang aux autres ».
pub fn set_show_rank(show: bool) {
    let was = SHOW_RANK.swap(show, Ordering::Relaxed);
    if was != show {
        SHOW_RANK_DIRTY.store(true, Ordering::Relaxed);
        poke();
    }
}

/// Le code du logo de notre propre joueur, tel que les autres le voient
/// (voir `presence::directory::badge_code`) : 0 tant qu'on ne sait rien.
pub fn own_badge_code() -> i32 {
    cached().map(|c| crate::presence::badge_code(&c.badge)).unwrap_or(0)
}

/// Cette instance sera suivie : sa copie y est écrite, ses vieux duels nettoyés.
pub fn watch(instance: &Path) {
    let mut list = INSTANCES.lock().unwrap_or_else(|p| p.into_inner());
    if !list.iter().any(|i| i == instance) {
        list.push(instance.to_path_buf());
        drop(list);
        poke();
    }
}

/// Un journal vient d'être ouvert : il n'est pas de l'ancien classement.
pub fn opened(journal: &Path) {
    OPENED.lock().unwrap_or_else(|p| p.into_inner()).push(journal.to_path_buf());
}

// ── La fin d'un duel ─────────────────────────────────────────────────────

/// Le classement du duel qui vient de se fermer : les nombres du panneau et
/// son statut, ou `None` s'il n'y a rien à montrer (pas noté, mode sans
/// classement). Bloque le temps des échanges avec l'API : à appeler hors du
/// fil du jeu et du fil d'écriture.
pub fn on_duel(instance: &Path, text: &str) -> Option<([f64; RANKED_LEN], Status)> {
    watch(instance);
    let own = verified::own_uuid(text)?;
    if !is_microsoft(&own) {
        return preview_only(&own, text).map(|n| (n, Status::OfflineServer));
    }
    let Some(token) = crate::presence::token::store().get() else {
        return preview_only(&own, text).map(|n| (n, Status::NoAccount));
    };
    if !verified::is_competitive(text) {
        let state = cached_for(&own).or_else(|| ensure_state(&token, &own).ok())?;
        let outcome = verified::preview(&state.state, &verified::anonymize(text, &state.state.salt))?;
        return Some((outcome.numbers(true), Status::Casual));
    }
    for wait in SUBMIT_WAITS {
        std::thread::sleep(wait);
        // Le jeton a pu être renouvelé pendant l'attente.
        let token = crate::presence::token::store().get().unwrap_or_else(|| token.clone());
        match submit(&token, &own, text) {
            Ok(Submitted::Verified(outcome)) => return Some((outcome.numbers(false), Status::Verified)),
            Ok(Submitted::Rejected(outcome)) => return outcome.map(|o| (o.numbers(true), Status::Rejected)),
            Ok(Submitted::Skipped) => return None,
            Err(Failure::Unsupported) => {
                UNSUPPORTED.store(true, Ordering::Relaxed);
                set_ready(Readiness::Unsupported);
                break;
            }
            Err(Failure::Unavailable) => {}
        }
    }
    if READY.load(Ordering::Relaxed) == Readiness::Ready as i32 {
        set_ready(Readiness::Unreachable);
    }
    poke();
    preview_only(&own, text).map(|n| (n, Status::Unreachable))
}

/// Le même calcul sur la copie, sans rien envoyer : pour montrer ce que le
/// duel aurait rapporté quand il ne compte pas.
fn preview_only(own: &str, text: &str) -> Option<[f64; RANKED_LEN]> {
    let cache = cached_for(own).or_else(cached)?;
    let outcome = verified::preview(&cache.state, &verified::anonymize(text, &cache.state.salt))?;
    Some(outcome.numbers(true))
}

/// Un compte Microsoft : UUID version 4. Un serveur en mode hors ligne donne
/// un UUID version 3, dérivé du pseudo, qui ne prouve rien.
fn is_microsoft(uuid: &str) -> bool {
    uuid.len() == 32 && uuid.as_bytes()[12] == b'4'
}

enum Submitted {
    Verified(Outcome),
    /// Le serveur a trouvé autre chose ; ce que le jeu avait calculé, pour le montrer.
    Rejected(Option<Outcome>),
    /// Pas un duel classé (pas noté, déjà appliqué) : rien à dire.
    Skipped,
}

/// Applique le journal à la copie et l'envoie. Un état périmé (un autre poste
/// a joué entre-temps) est repris du serveur et le calcul refait, une fois.
fn submit(token: &str, own: &str, text: &str) -> Result<Submitted, Failure> {
    for _ in 0..2 {
        let cache = ensure_state(token, own)?;
        let anonymous = verified::anonymize(text, &cache.state.salt);
        let applied = match verified::apply(&cache.state, &anonymous) {
            Ok(applied) => applied,
            Err(Rejection::NotRanked | Rejection::OutOfOrder | Rejection::Casual | Rejection::NotAJournal) => return Ok(Submitted::Skipped),
        };
        let compressed = zstd::bulk::compress(anonymous.as_bytes(), 9).map_err(|_| Failure::Unavailable)?;
        if compressed.len() > MAX_PACKET {
            return Ok(Submitted::Skipped);
        }
        let body = serde_json::json!({
            "uuid": own,
            "version": cache.version,
            "journal": super::share::base64(&compressed),
            "claim": applied.outcome,
            // L'adversaire et le serveur en clair, pour l'historique : le
            // serveur les recoupe avec les empreintes du journal.
            "declared": verified::declared(text),
        });
        let answer = api().post(token, "/v1/ranked/duels", body)?;
        let fresh = read_cached(own, &answer).ok_or(Failure::Unavailable)?;
        store(fresh);
        mark_synced(own);
        let verified = answer["verified"].as_bool().unwrap_or(false);
        let reason = answer["reason"].as_str().unwrap_or("");
        if verified {
            remember_verified(own, applied.time, &applied.outcome);
            return Ok(Submitted::Verified(applied.outcome));
        }
        if reason == "stale" {
            // Relu au tour suivant.
            CACHE.lock().unwrap_or_else(|p| p.into_inner()).take();
            continue;
        }
        if reason == "out_of_order" {
            return Ok(Submitted::Skipped);
        }
        eprintln!("[YuyuFrame] classement : duel non vérifié par le serveur ({reason})");
        return Ok(Submitted::Rejected(Some(applied.outcome)));
    }
    Ok(Submitted::Rejected(None))
}

/// Un duel que le serveur vient de vérifier entre dans la liste du compte.
fn remember_verified(own: &str, time: u64, outcome: &Outcome) {
    let mut guard = SERVER_DUELS.lock().unwrap_or_else(|p| p.into_inner());
    if let Some((_, duels)) = guard.as_mut().filter(|(a, _)| a == own) {
        duels.insert(
            time,
            ServerDuel {
                rr: if outcome.placement == 0 { outcome.total } else { 0 },
                placement: outcome.placement != 0,
                after_division: outcome.after_division as i64,
                after_points: outcome.after_points as i64,
            },
        );
    }
}

/// L'état de `own` : la copie si c'est le bon compte, sinon celui du serveur.
fn ensure_state(token: &str, own: &str) -> Result<Cached, Failure> {
    if let Some(cache) = cached_for(own) {
        return Ok(cache);
    }
    fetch_state(token, own)
}

/// L'état de `own`, relu du serveur.
fn fetch_state(token: &str, own: &str) -> Result<Cached, Failure> {
    let answer = api().get(token, &format!("/v1/ranked/state/{own}"))?;
    let fresh = read_cached(own, &answer).ok_or(Failure::Unavailable)?;
    store(fresh.clone());
    mark_synced(own);
    Ok(fresh)
}

fn mark_synced(own: &str) {
    *SYNCED.lock().unwrap_or_else(|p| p.into_inner()) = Some((own.to_string(), Instant::now()));
}

/// `{ version, state, badge, show_rank }` → la copie.
fn read_cached(own: &str, answer: &serde_json::Value) -> Option<Cached> {
    Some(Cached {
        uuid: own.to_string(),
        version: answer["version"].as_i64()?,
        state: serde_json::from_value(answer["state"].clone()).ok()?,
        badge: answer["badge"].as_str().unwrap_or("base").to_string(),
        show_rank: answer["show_rank"].as_bool().unwrap_or(true),
    })
}

/// Retient la copie, en mémoire et sur le disque de chaque instance suivie
/// (l'écran du module la relit avant le premier échange de la partie).
fn store(cache: Cached) {
    let json = serde_json::to_string(&cache).ok();
    *CACHE.lock().unwrap_or_else(|p| p.into_inner()) = Some(cache);
    let Some(json) = json else { return };
    for instance in INSTANCES.lock().unwrap_or_else(|p| p.into_inner()).iter() {
        let path = cache_path(instance);
        let written = path.parent().map(std::fs::create_dir_all).unwrap_or(Ok(())).and_then(|_| std::fs::write(&path, &json));
        if let Err(e) = written {
            eprintln!("[YuyuFrame] classement : copie de l'état non écrite ({}) : {e}", path.display());
        }
    }
}

fn cache_path(instance: &Path) -> PathBuf {
    instance.join("yuyuframe").join("ranked").join("state.json")
}

// ── La synchronisation ───────────────────────────────────────────────────

fn wake() -> &'static (Mutex<bool>, Condvar) {
    static WAKE: OnceLock<(Mutex<bool>, Condvar)> = OnceLock::new();
    WAKE.get_or_init(|| (Mutex::new(false), Condvar::new()))
}

/// Relit l'état tout de suite (compte changé, jeton arrivé, instance nouvelle).
pub fn poke() {
    start_worker();
    let (flag, cond) = wake();
    *flag.lock().unwrap_or_else(|p| p.into_inner()) = true;
    cond.notify_one();
}

fn start_worker() {
    static STARTED: OnceLock<()> = OnceLock::new();
    STARTED.get_or_init(|| {
        if let Err(e) = std::thread::Builder::new().name("yf-ranked-sync".into()).spawn(run) {
            eprintln!("[YuyuFrame] classement : fil de synchronisation non démarré : {e}");
        }
    });
}

fn run() {
    // Le jeu tourne dans le dossier de son instance : ses vieux duels sont
    // nettoyés dès la première synchronisation, sans attendre un monde.
    if let Ok(dir) = std::env::current_dir() {
        if dir.join("yuyuframe").join("matches").is_dir() {
            let mut list = INSTANCES.lock().unwrap_or_else(|p| p.into_inner());
            if !list.contains(&dir) {
                list.push(dir);
            }
        }
    }
    loop {
        sync();
        let (flag, cond) = wake();
        let mut poked = flag.lock().unwrap_or_else(|p| p.into_inner());
        if !*poked {
            poked = cond.wait_timeout(poked, TICK).unwrap_or_else(|p| p.into_inner()).0;
        }
        *poked = false;
    }
}

/// Un tour de synchronisation : l'état du compte de la partie, puis la liste
/// de ses duels vérifiés et le nettoyage des instances suivies.
fn sync() {
    let tokens = crate::presence::token::store();
    let Some(token) = tokens.get() else {
        set_ready(Readiness::NoAccount);
        return;
    };
    let Some(account) = tokens.account() else {
        set_ready(Readiness::NoAccount);
        return;
    };
    if !is_microsoft(&account) {
        set_ready(Readiness::NotMicrosoft);
        return;
    }
    if UNSUPPORTED.load(Ordering::Relaxed) {
        set_ready(Readiness::Unsupported);
        return;
    }
    let fresh = SYNCED.lock().unwrap_or_else(|p| p.into_inner()).as_ref().is_some_and(|(a, at)| *a == account && at.elapsed() < REFRESH);
    if !fresh || READY.load(Ordering::Relaxed) != Readiness::Ready as i32 {
        if cached().is_some_and(|c| c.uuid != account) {
            // Un autre compte : on ne joue plus avec son rang.
            set_ready(Readiness::Checking);
        }
        match fetch_state(&token, &account) {
            Ok(_) => set_ready(Readiness::Ready),
            Err(Failure::Unsupported) => {
                UNSUPPORTED.store(true, Ordering::Relaxed);
                set_ready(Readiness::Unsupported);
                return;
            }
            Err(Failure::Unavailable) => {
                set_ready(Readiness::Unreachable);
                return;
            }
        }
    }
    send_visibility(&token);
    if let Some(verified) = verified_times(&token, &account) {
        let instances: Vec<PathBuf> = INSTANCES.lock().unwrap_or_else(|p| p.into_inner()).clone();
        for instance in instances {
            let key = (instance.clone(), account.clone());
            let mut purged = PURGED.lock().unwrap_or_else(|p| p.into_inner());
            if purged.contains(&key) {
                continue;
            }
            purged.push(key);
            drop(purged);
            purge(&instance, &account, &verified);
        }
    }
}

/// Les heures des duels vérifiés du compte : lues une fois par partie et par
/// compte (toutes les pages), puis tenues à jour par chaque duel vérifié.
/// `None` si la liste n'a pas pu être lue en entier — rien n'est alors effacé.
fn verified_times(token: &str, account: &str) -> Option<HashSet<u64>> {
    {
        let guard = SERVER_DUELS.lock().unwrap_or_else(|p| p.into_inner());
        if let Some((a, duels)) = guard.as_ref() {
            if a == account {
                return Some(duels.keys().copied().collect());
            }
        }
    }
    let mut duels = HashMap::new();
    let mut before: Option<i64> = None;
    for _ in 0..MAX_HISTORY_PAGES {
        let path = match before {
            Some(id) => format!("/v1/ranked/history/{account}?limit=100&before={id}"),
            None => format!("/v1/ranked/history/{account}?limit=100"),
        };
        let page = api().get(token, &path).ok()?;
        for duel in page["duels"].as_array()? {
            let Some(at) = duel["at"].as_i64().filter(|t| *t > 0) else { continue };
            duels.insert(
                at as u64,
                ServerDuel {
                    rr: duel["rr"].as_i64().unwrap_or(0) as i32,
                    placement: duel["placement"].as_bool().unwrap_or(false),
                    after_division: duel["after_division"].as_i64().unwrap_or(0),
                    after_points: duel["after_points"].as_i64().unwrap_or(0),
                },
            );
        }
        match page["next"].as_i64() {
            Some(next) => before = Some(next),
            None => {
                let times = duels.keys().copied().collect();
                *SERVER_DUELS.lock().unwrap_or_else(|p| p.into_inner()) = Some((account.to_string(), duels));
                return Some(times);
            }
        }
    }
    None
}

/// Efface de l'instance les duels de l'ancien classement local : les
/// journaux compétitifs de `account` que le serveur n'a pas vérifiés, et les
/// journaux compétitifs joués sous un UUID qui ne prouve rien. Les duels
/// casual, ceux d'un autre compte Microsoft et ceux de cette partie restent.
fn purge(instance: &Path, account: &str, verified: &HashSet<u64>) {
    let matches = instance.join("yuyuframe").join("matches");
    let Ok(entries) = std::fs::read_dir(&matches) else { return };
    let opened: Vec<PathBuf> = OPENED.lock().unwrap_or_else(|p| p.into_inner()).clone();
    let mut removed = 0;
    for path in entries.flatten().map(|e| e.path()) {
        let Some(name) = path.file_name().and_then(|n| n.to_str()) else { continue };
        let Some(time) = name.strip_suffix(".events.ndjson.zst").and_then(|t| t.parse::<u64>().ok()) else { continue };
        if verified.contains(&time) || opened.contains(&path) {
            continue;
        }
        let Some(text) = super::history::read_journal_text(&path) else { continue };
        if !old_ranked_duel(&text, account) {
            continue;
        }
        match std::fs::remove_file(&path) {
            Ok(()) => {
                removed += 1;
                let summary = super::writer::summary_path(&path);
                if summary.exists() {
                    if let Err(e) = std::fs::remove_file(&summary) {
                        eprintln!("[YuyuFrame] classement : résumé {} non effacé : {e}", summary.display());
                    }
                }
            }
            Err(e) => eprintln!("[YuyuFrame] classement : {} non effacé : {e}", path.display()),
        }
    }
    // L'ancienne file d'attente : elle ne repartira plus.
    if let Ok(entries) = std::fs::read_dir(instance.join("yuyuframe").join("outbox").join("ranked")) {
        for file in entries.flatten().map(|e| e.path()).filter(|p| p.extension().is_some_and(|e| e == "txt")) {
            let _ = std::fs::remove_file(file);
        }
    }
    if removed > 0 {
        eprintln!("[YuyuFrame] classement : {removed} duel(s) de l'ancien classement effacé(s) dans {}", instance.display());
    }
}

/// Un journal de l'ancien classement, à effacer pour `account` : compétitif
/// (un journal sans le champ l'est), joué par ce compte ou sous un UUID qui
/// ne prouve rien. L'appelant a déjà écarté ceux que le serveur a vérifiés.
fn old_ranked_duel(text: &str, account: &str) -> bool {
    if !verified::is_competitive(text) {
        return false;
    }
    match verified::own_uuid(text) {
        Some(own) => own == account || !is_microsoft(&own),
        None => false,
    }
}

/// Le réglage « Montrer mon rang aux autres », pour le compte de la copie.
fn send_visibility(token: &str) {
    if !SHOW_RANK_DIRTY.load(Ordering::Relaxed) {
        return;
    }
    let Some(cache) = cached() else { return };
    let show = SHOW_RANK.load(Ordering::Relaxed);
    let body = serde_json::json!({ "uuid": cache.uuid, "show_rank": show });
    if let Ok(answer) = api().put(token, "/v1/ranked/visibility", body) {
        SHOW_RANK_DIRTY.store(false, Ordering::Relaxed);
        let mut guard = CACHE.lock().unwrap_or_else(|p| p.into_inner());
        if let Some(c) = guard.as_mut() {
            c.show_rank = show;
            if let Some(badge) = answer["badge"].as_str() {
                c.badge = badge.to_string();
            }
        }
    }
}

// ── L'API ────────────────────────────────────────────────────────────────

#[derive(Debug)]
enum Failure {
    Unavailable,
    Unsupported,
}

struct Api {
    base: Option<String>,
    agent: ureq::Agent,
}

fn api() -> &'static Api {
    static API: OnceLock<Api> = OnceLock::new();
    API.get_or_init(|| Api {
        base: crate::presence::client::api_base(),
        agent: ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(5)).timeout(Duration::from_secs(15)).build(),
    })
}

impl Api {
    fn call(&self, request: ureq::Request, token: &str, body: Option<serde_json::Value>) -> Result<serde_json::Value, Failure> {
        let request = request.set("Authorization", &format!("Bearer {token}")).set("User-Agent", "YuyuFrame-LauncherAgent/0.1");
        let response = match body {
            Some(body) => request.send_json(body),
            None => request.call(),
        };
        let response = response.map_err(|e| match e {
            ureq::Error::Status(404 | 405, _) => Failure::Unsupported,
            ureq::Error::Status(code, _) => {
                eprintln!("[YuyuFrame] classement : l'API répond {code}");
                Failure::Unavailable
            }
            _ => Failure::Unavailable,
        })?;
        response.into_json().map_err(|_| Failure::Unavailable)
    }

    fn url(&self, path: &str) -> Result<String, Failure> {
        self.base.as_ref().map(|b| format!("{b}{path}")).ok_or(Failure::Unavailable)
    }

    fn get(&self, token: &str, path: &str) -> Result<serde_json::Value, Failure> {
        self.call(self.agent.get(&self.url(path)?), token, None)
    }

    fn post(&self, token: &str, path: &str, body: serde_json::Value) -> Result<serde_json::Value, Failure> {
        self.call(self.agent.post(&self.url(path)?), token, Some(body))
    }

    fn put(&self, token: &str, path: &str, body: serde_json::Value) -> Result<serde_json::Value, Failure> {
        self.call(self.agent.put(&self.url(path)?), token, Some(body))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn defeat() -> &'static str {
        include_str!("score/fixtures/duel_masse_defaite.ndjson")
    }

    fn with_header(text: &str, ranked: Option<bool>) -> String {
        text.lines()
            .filter_map(|l| serde_json::from_str::<serde_json::Value>(l).ok())
            .map(|mut l| {
                if l["k"] == "h" {
                    match ranked {
                        Some(r) => l["ranked"] = serde_json::json!(r),
                        None => {
                            l.as_object_mut().unwrap().remove("ranked");
                        }
                    }
                }
                format!("{l}\n")
            })
            .collect()
    }

    #[test]
    fn seul_un_compte_microsoft_se_classe() {
        assert!(is_microsoft("0123456789ab4def0123456789abcdef"));
        assert!(!is_microsoft("0123456789ab3def0123456789abcdef"));
        assert!(!is_microsoft("trop-court"));
    }

    #[test]
    fn seuls_les_duels_competitifs_du_compte_sont_de_l_ancien_classement() {
        let own = verified::own_uuid(defeat()).expect("notre joueur");
        let other = "0123456789ab4def0123456789abffff";
        // Compétitif, sans champ (avant le 2026-10-23) ou explicite : effacé.
        assert!(old_ranked_duel(&with_header(defeat(), Some(true)), &own));
        assert!(old_ranked_duel(&with_header(defeat(), None), &own));
        // Casual : gardé.
        assert!(!old_ranked_duel(&with_header(defeat(), Some(false)), &own));
        // Un autre compte Microsoft : gardé (il sera jugé à sa propre synchronisation).
        if is_microsoft(&own) {
            assert!(!old_ranked_duel(&with_header(defeat(), Some(true)), other));
        } else {
            // Joué sous un UUID qui ne prouve rien : jamais vérifiable, effacé.
            assert!(old_ranked_duel(&with_header(defeat(), Some(true)), other));
        }
    }
}
