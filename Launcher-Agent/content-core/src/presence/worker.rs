//! Le thread qui vide la file des questions. Il est seul à toucher au réseau,
//! et ne tient jamais le verrou de l'annuaire pendant une requête : le fil du
//! jeu, qui pose ses questions sous ce même verrou, n'attend jamais le réseau.

use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use super::client::{Client, Failure};
use super::directory::Directory;
use super::token::TokenStore;

/// Les questions sont regroupées : un joueur qui entre dans un lobby en
/// voit cent d'un coup, et c'est une requête, pas cent.
const TICK: Duration = Duration::from_secs(2);
/// Après un échec réseau : on attend de plus en plus, sans dépasser ce plafond.
const BACKOFF_START: Duration = Duration::from_secs(10);
const BACKOFF_MAX: Duration = Duration::from_secs(300);
/// Le serveur a dit « trop de questions » : sa fenêtre est d'une heure, mais
/// notre budget est sous le sien — un refus est un accident, pas un régime.
const RATE_LIMIT_PAUSE: Duration = Duration::from_secs(600);
const SWEEP_EVERY: Duration = Duration::from_secs(120);

pub const STATE_RUNNING: i32 = 1;
/// Dernière requête en échec (réseau, serveur) : on réessaie.
pub const STATE_RETRYING: i32 = 2;
pub const STATE_RATE_LIMITED: i32 = 3;
/// Le jeton en main a été refusé : on attend le suivant.
pub const STATE_REFUSED: i32 = 4;
/// Aucun jeton reçu du launcher (joueur sans compte YuyuFrame, launcher fermé).
pub const STATE_NO_TOKEN: i32 = 5;

pub struct Shared {
    directory: Mutex<Directory>,
    state: AtomicI32,
}

impl Shared {
    /// Un thread mort en plein verrou ne doit pas faire planter le jeu à la
    /// question suivante : l'annuaire reste lisible.
    pub fn directory(&self) -> MutexGuard<'_, Directory> {
        self.directory.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    pub fn state(&self) -> i32 {
        self.state.load(Ordering::Relaxed)
    }
}

/// Démarre le thread. `None` si le système refuse de le créer.
pub fn start(client: Client, tokens: Arc<TokenStore>) -> Option<Arc<Shared>> {
    let shared = Arc::new(Shared { directory: Mutex::new(Directory::new(Instant::now())), state: AtomicI32::new(STATE_NO_TOKEN) });
    let for_thread = shared.clone();
    std::thread::Builder::new().name("yf-presence".into()).spawn(move || run(&for_thread, &client, &tokens)).ok()?;
    Some(shared)
}

fn run(shared: &Shared, client: &Client, tokens: &TokenStore) {
    let mut wait = TICK;
    let mut backoff = BACKOFF_START;
    let mut last_sweep = Instant::now();
    // Le jeton que le serveur vient de refuser : inutile de le représenter.
    let mut refused: Option<Arc<str>> = None;
    loop {
        std::thread::sleep(wait);
        wait = TICK;

        let now = Instant::now();
        if now.duration_since(last_sweep) >= SWEEP_EVERY {
            shared.directory().sweep(now);
            last_sweep = now;
        }

        // Pas de jeton utilisable : on ne sort rien de la file, les questions
        // y attendent sans entamer le budget de l'heure.
        let Some(token) = tokens.get() else {
            shared.state.store(STATE_NO_TOKEN, Ordering::Relaxed);
            continue;
        };
        if refused.as_deref() == Some(&*token) {
            continue;
        }

        let batch = shared.directory().take_batch(now);
        if batch.is_empty() {
            continue;
        }

        let fingerprints: Vec<[u8; 32]> = batch.iter().map(|(_, f)| *f).collect();
        match client.lookup(&token, &fingerprints) {
            Ok(present) => {
                shared.directory().apply(&batch, &present, Instant::now());
                shared.state.store(STATE_RUNNING, Ordering::Relaxed);
                backoff = BACKOFF_START;
                refused = None;
            }
            Err(Failure::Refused) => {
                shared.directory().give_back(batch);
                shared.state.store(STATE_REFUSED, Ordering::Relaxed);
                refused = Some(token);
            }
            Err(Failure::RateLimited) => {
                shared.directory().give_back(batch);
                shared.state.store(STATE_RATE_LIMITED, Ordering::Relaxed);
                wait = RATE_LIMIT_PAUSE;
            }
            Err(Failure::Unavailable) => {
                shared.directory().give_back(batch);
                shared.state.store(STATE_RETRYING, Ordering::Relaxed);
                wait = backoff;
                backoff = (backoff * 2).min(BACKOFF_MAX);
            }
        }
    }
}
