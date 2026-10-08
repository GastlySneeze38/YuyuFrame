//! Le thread qui vide les files de questions — et qui, sur un serveur de jeu
//! en mode hors ligne, annonce notre joueur dans la même requête. Il est seul à toucher au réseau,
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

/// Ce que la boucle retient d'un tour à l'autre.
struct Pace {
    wait: Duration,
    backoff: Duration,
    /// Le jeton que le serveur vient de refuser : inutile de le représenter.
    refused: Option<Arc<str>>,
}

impl Pace {
    fn succeeded(&mut self, shared: &Shared) {
        shared.state.store(STATE_RUNNING, Ordering::Relaxed);
        self.backoff = BACKOFF_START;
        self.refused = None;
    }

    fn failed(&mut self, shared: &Shared, failure: Failure, token: &Arc<str>) {
        match failure {
            Failure::Refused => {
                shared.state.store(STATE_REFUSED, Ordering::Relaxed);
                self.refused = Some(token.clone());
            }
            Failure::RateLimited => {
                shared.state.store(STATE_RATE_LIMITED, Ordering::Relaxed);
                self.wait = RATE_LIMIT_PAUSE;
            }
            Failure::Unavailable | Failure::Unsupported => {
                shared.state.store(STATE_RETRYING, Ordering::Relaxed);
                self.wait = self.backoff;
                self.backoff = (self.backoff * 2).min(BACKOFF_MAX);
            }
        }
    }
}

fn run(shared: &Shared, client: &Client, tokens: &TokenStore) {
    let mut pace = Pace { wait: TICK, backoff: BACKOFF_START, refused: None };
    let mut last_sweep = Instant::now();
    // L'API ne connaît pas la route des serveurs hors ligne (plus ancienne que
    // le jeu) : on n'y revient pas de la partie, et la route des comptes
    // Microsoft continue comme avant.
    let mut offline_supported = true;
    loop {
        std::thread::sleep(pace.wait);
        pace.wait = TICK;

        let now = Instant::now();
        if now.duration_since(last_sweep) >= SWEEP_EVERY {
            shared.directory().sweep(now);
            last_sweep = now;
        }

        // Pas de jeton utilisable : on ne sort rien des files, les questions
        // y attendent sans entamer le budget de l'heure.
        let Some(token) = tokens.get() else {
            shared.state.store(STATE_NO_TOKEN, Ordering::Relaxed);
            continue;
        };
        if pace.refused.as_deref() == Some(&*token) {
            continue;
        }

        // Les comptes Microsoft croisés.
        let batch = shared.directory().take_batch(now);
        if !batch.is_empty() {
            let fingerprints: Vec<[u8; 32]> = batch.iter().map(|(_, f)| *f).collect();
            match client.lookup(&token, &fingerprints) {
                Ok(present) => {
                    shared.directory().apply(&batch, &present, Instant::now());
                    pace.succeeded(shared);
                }
                Err(failure) => {
                    shared.directory().give_back(batch);
                    pace.failed(shared, failure, &token);
                    continue;
                }
            }
        }

        // Le serveur de jeu hors ligne où l'on est : notre annonce quand elle
        // est due, et les joueurs de sa liste, dans la même requête.
        if !offline_supported {
            continue;
        }
        let Some(round) = shared.directory().take_offline(now, tokens.visible()) else { continue };
        let players: Vec<[u8; 32]> = round.batch.iter().map(|(_, f)| *f).collect();
        match client.sync_offline(&token, &round.own, &players) {
            Ok((present, renew_in)) => {
                shared.directory().apply_offline(&round, &present, renew_in, Instant::now());
                pace.succeeded(shared);
            }
            Err(Failure::Unsupported) => offline_supported = false,
            Err(failure) => {
                shared.directory().give_back_offline(round, Instant::now());
                pace.failed(shared, failure, &token);
            }
        }
    }
}
