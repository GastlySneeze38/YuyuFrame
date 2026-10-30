//! Le classement vérifié, côté jeu : la copie de l'état, et ses échanges avec
//! LauncherAPI (docs/scoring/classement.md, « Classement vérifié »).
//!
//! À la fin d'un duel ([`on_duel`], sur le fil `yf-ranked`) :
//!
//!   1. l'état du compte Minecraft qui a joué est pris dans la copie, ou
//!      demandé au serveur (`GET /v1/ranked/state/{uuid}`) ;
//!   2. le journal est anonymisé sous le sel de l'état, puis appliqué
//!      (`verified::apply`) : c'est ce que le jeu annonce ;
//!   3. journal et annonce partent (`POST /v1/ranked/duels`). Le serveur
//!      refait le calcul : mêmes nombres, le duel compte et le nouvel état
//!      revient ; sinon il ne compte pas, et c'est l'état du serveur qui
//!      revient. La copie suit TOUJOURS le serveur.
//!
//! Sans réponse (réseau, panne, jeton expiré), le duel attend dans
//! `yuyuframe/outbox/ranked/` de l'instance et repart plus tard, DANS
//! L'ORDRE : chaque duel dépend du précédent, donc tant qu'un duel attend,
//! les suivants attendent derrière lui.
//!
//! Le classement n'est vérifiable qu'avec un compte YuyuFrame connecté et un
//! compte Minecraft Microsoft (UUID version 4) : sur un serveur de jeu en
//! mode hors ligne, l'identité n'est pas prouvée. Le panneau le dit
//! ([`Status`]). Tant que l'API ne connaît pas ces routes (déploiement pas
//! encore fait), on garde le classement local d'avant ([`Status::Legacy`]).

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use super::history::RANKED_LEN;
use super::verified::{self, LadderState, Outcome, Rejection};

/// Ce que le panneau dit du classement du dernier duel (contrat
/// `NativeCombat.RANKED_STATUS_*`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub enum Status {
    /// Rien encore.
    None = 0,
    /// Le serveur a fait le même calcul : le duel compte.
    Verified = 1,
    /// Le serveur a trouvé autre chose : le duel ne compte pas.
    Rejected = 2,
    /// Pas de réponse : le duel attend, il partira plus tard.
    Queued = 3,
    /// Pas de compte YuyuFrame connecté : rien n'est vérifiable.
    NoAccount = 4,
    /// Joué en casual : ce qu'il aurait rapporté, rien n'est envoyé.
    Casual = 5,
    /// L'API ne connaît pas encore le classement vérifié : classement local d'avant.
    Legacy = 6,
    /// Serveur de jeu en mode hors ligne : identité non prouvée, pas de classement.
    OfflineServer = 7,
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

static CACHE: Mutex<Option<Cached>> = Mutex::new(None);
static STATUS: Mutex<(i32, i32)> = Mutex::new((0, 0));
static SHOW_RANK: AtomicBool = AtomicBool::new(true);
/// Le réglage a changé et n'est pas encore parti.
static SHOW_RANK_DIRTY: AtomicBool = AtomicBool::new(false);
static OUTBOXES: Mutex<Vec<PathBuf>> = Mutex::new(Vec::new());
/// L'API n'a pas ces routes : inutile d'insister pendant cette partie.
static UNSUPPORTED: AtomicBool = AtomicBool::new(false);

const QUEUE_EVERY: Duration = Duration::from_secs(30);
/// Un journal plus gros n'est pas envoyé (la route le refuserait).
const MAX_PACKET: usize = 4 * 1024 * 1024;

/// Le statut du classement du duel `number` (numéro du résumé).
pub fn status() -> (i32, i32) {
    *STATUS.lock().unwrap_or_else(|p| p.into_inner())
}

fn set_status(number: i32, status: Status) {
    *STATUS.lock().unwrap_or_else(|p| p.into_inner()) = (number, status as i32);
}

/// La copie de l'état, si un compte a déjà été synchronisé.
pub fn cached() -> Option<Cached> {
    CACHE.lock().unwrap_or_else(|p| p.into_inner()).clone()
}

/// Le réglage « Montrer mon rang aux autres ».
pub fn set_show_rank(show: bool) {
    let was = SHOW_RANK.swap(show, Ordering::Relaxed);
    if was != show {
        SHOW_RANK_DIRTY.store(true, Ordering::Relaxed);
        start_worker();
    }
}

/// Le code du logo de notre propre joueur, tel que les autres le voient
/// (voir `presence::directory::badge_code`) : 0 tant qu'on ne sait rien.
pub fn own_badge_code() -> i32 {
    cached().map(|c| crate::presence::badge_code(&c.badge)).unwrap_or(0)
}

// ── La fin d'un duel ─────────────────────────────────────────────────────

/// Le classement du duel qui vient de se fermer. Rend les nombres du panneau
/// et son statut, ou `None` si le duel ne compte pas au classement (pas
/// noté, mode sans classement) — l'appelant retombe alors sur le classement
/// local. Bloque le temps d'un aller-retour avec l'API : à appeler hors du
/// fil du jeu et du fil d'écriture.
pub fn on_duel(instance: &Path, journal_path: &Path, text: &str) -> Option<([f64; RANKED_LEN], Status)> {
    if UNSUPPORTED.load(Ordering::Relaxed) {
        return None;
    }
    let own = verified::own_uuid(text)?;
    if !is_microsoft(&own) {
        return preview_only(text).map(|n| (n, Status::OfflineServer));
    }
    watch(instance);
    let Some(token) = crate::presence::token::store().get() else {
        return preview_only(text).map(|n| (n, Status::NoAccount));
    };
    if !verified::is_competitive(text) {
        let state = ensure_state(&token, &own).ok()?;
        let outcome = verified::preview(&state.state, &verified::anonymize(text, &state.state.salt))?;
        return Some((outcome.numbers(true), Status::Casual));
    }
    // Un duel déjà en attente passe d'abord : celui-ci se range derrière lui.
    let outbox = outbox_of(instance);
    if has_queued(&outbox) {
        enqueue(&outbox, journal_path, &own);
        start_worker();
        return preview_only(text).map(|n| (n, Status::Queued));
    }
    match submit(&token, &own, text) {
        Ok(Submitted::Verified(outcome)) => Some((outcome.numbers(false), Status::Verified)),
        Ok(Submitted::Rejected(outcome)) => outcome.map(|o| (o.numbers(true), Status::Rejected)),
        Ok(Submitted::Skipped) => None,
        Err(Failure::Unsupported) => {
            UNSUPPORTED.store(true, Ordering::Relaxed);
            None
        }
        Err(_) => {
            enqueue(&outbox, journal_path, &own);
            start_worker();
            preview_only(text).map(|n| (n, Status::Queued))
        }
    }
}

/// Le même calcul sur la copie, sans rien envoyer : pour montrer ce que le
/// duel rapporte quand il ne peut pas (encore) être vérifié.
fn preview_only(text: &str) -> Option<[f64; RANKED_LEN]> {
    let cache = cached()?;
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
        let verified = answer["verified"].as_bool().unwrap_or(false);
        let reason = answer["reason"].as_str().unwrap_or("");
        if verified {
            return Ok(Submitted::Verified(applied.outcome));
        }
        if reason == "stale" {
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

/// L'état de `own` : la copie si c'est le bon compte, sinon celui du serveur.
fn ensure_state(token: &str, own: &str) -> Result<Cached, Failure> {
    if let Some(cache) = cached().filter(|c| c.uuid == own) {
        return Ok(cache);
    }
    let answer = api().get(token, &format!("/v1/ranked/state/{own}"))?;
    let fresh = read_cached(own, &answer).ok_or(Failure::Unavailable)?;
    store(fresh.clone());
    Ok(fresh)
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
/// (l'écran du module la relit pour afficher le rang).
fn store(cache: Cached) {
    let json = serde_json::to_string(&cache).ok();
    *CACHE.lock().unwrap_or_else(|p| p.into_inner()) = Some(cache);
    let Some(json) = json else { return };
    for outbox in OUTBOXES.lock().unwrap_or_else(|p| p.into_inner()).iter() {
        let Some(instance) = outbox.parent().and_then(Path::parent).and_then(Path::parent) else { continue };
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

/// La copie de l'état écrite dans cette instance — pour l'écran du module,
/// avant même le premier échange de la partie.
pub fn cached_in(instance: &Path) -> Option<Cached> {
    if let Some(cache) = cached() {
        return Some(cache);
    }
    let text = std::fs::read_to_string(cache_path(instance)).ok()?;
    let cache: Cached = serde_json::from_str(&text).ok()?;
    *CACHE.lock().unwrap_or_else(|p| p.into_inner()) = Some(cache.clone());
    Some(cache)
}

// ── La file d'attente ────────────────────────────────────────────────────

fn outbox_of(instance: &Path) -> PathBuf {
    instance.join("yuyuframe").join("outbox").join("ranked")
}

/// Cette instance sera suivie : sa file repart, sa copie y est écrite.
pub fn watch(instance: &Path) {
    let dir = outbox_of(instance);
    let mut list = OUTBOXES.lock().unwrap_or_else(|p| p.into_inner());
    if !list.contains(&dir) {
        list.push(dir);
    }
}

fn has_queued(outbox: &Path) -> bool {
    std::fs::read_dir(outbox).map(|mut e| e.any(|f| f.is_ok())).unwrap_or(false)
}

/// Une ligne par duel en attente : le chemin de son journal et le compte qui
/// l'a joué. Le nom du fichier est l'heure du duel : trié, c'est l'ordre.
fn enqueue(outbox: &Path, journal_path: &Path, own: &str) {
    let Some(name) = journal_path.file_name().and_then(|n| n.to_str()).and_then(|n| n.split('.').next()) else { return };
    let line = format!("{}\t{own}", journal_path.display());
    let written = std::fs::create_dir_all(outbox).and_then(|_| std::fs::write(outbox.join(format!("{name}.txt")), line));
    if let Err(e) = written {
        eprintln!("[YuyuFrame] classement : duel non mis en attente : {e}");
    }
}

fn start_worker() {
    static STARTED: OnceLock<()> = OnceLock::new();
    STARTED.get_or_init(|| {
        if let Err(e) = std::thread::Builder::new().name("yf-ranked-queue".into()).spawn(run) {
            eprintln!("[YuyuFrame] classement : fil d'attente non démarré : {e}");
        }
    });
}

fn run() {
    loop {
        if !UNSUPPORTED.load(Ordering::Relaxed) {
            if let Some(token) = crate::presence::token::store().get() {
                send_visibility(&token);
                drain(&token);
            }
        }
        std::thread::sleep(QUEUE_EVERY);
    }
}

/// Envoie les duels en attente, du plus ancien au plus récent ; s'arrête au
/// premier qui ne part pas (les suivants dépendent de lui).
fn drain(token: &str) {
    let dirs: Vec<PathBuf> = OUTBOXES.lock().unwrap_or_else(|p| p.into_inner()).clone();
    for dir in dirs {
        let Ok(entries) = std::fs::read_dir(&dir) else { continue };
        let mut files: Vec<PathBuf> = entries.flatten().map(|e| e.path()).filter(|p| p.extension().is_some_and(|e| e == "txt")).collect();
        files.sort();
        for file in files {
            let Ok(line) = std::fs::read_to_string(&file) else { continue };
            let mut fields = line.trim().splitn(2, '\t');
            let (Some(journal), Some(own)) = (fields.next(), fields.next()) else {
                remove(&file);
                continue;
            };
            let Some(text) = super::history::read_journal_text(Path::new(journal)) else {
                remove(&file);
                continue;
            };
            match submit(token, own, &text) {
                Ok(_) => remove(&file),
                Err(Failure::Unsupported) => {
                    UNSUPPORTED.store(true, Ordering::Relaxed);
                    return;
                }
                Err(_) => return,
            }
        }
    }
}

fn remove(file: &Path) {
    if let Err(e) = std::fs::remove_file(file) {
        eprintln!("[YuyuFrame] classement : {} non retiré de la file : {e}", file.display());
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

/// Les nombres d'un duel vérifié ou non, et son statut, pour le panneau.
pub fn publish(number: i32, status: Status) {
    set_status(number, status);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seul_un_compte_microsoft_se_classe() {
        assert!(is_microsoft("0123456789ab4def0123456789abcdef"));
        assert!(!is_microsoft("0123456789ab3def0123456789abcdef"));
        assert!(!is_microsoft("trop-court"));
    }
}
