//! Les fonctions `yf_presence_*` : des fonctions C ordinaires, que Java
//! cherche par leur nom dans la DLL (FFM). Leur contrat est ici ; un
//! changement de signature ou de sens se fait avec `ABI_VERSION`.
//!
//! Aucune ne touche au réseau ni n'attend : le seul verrou pris est celui de
//! l'annuaire, que le thread de fond ne garde jamais pendant une requête.

use std::sync::{Arc, OnceLock};
use std::time::Instant;

use super::client::Client;
use super::directory::{Answer, PlayerKey};
use super::token::{self, TokenStore};
use super::worker::{self, Shared};

/// Version du contrat. À incrémenter dès qu'une fonction change de signature
/// ou de sens : Java refuse une DLL qui n'annonce pas la version qu'il attend
/// (`NativePresence.ABI_VERSION`), au lieu de l'appeler de travers.
const ABI_VERSION: u32 = 1;

/// `None` une fois pour toutes si le jeu n'a pas été lancé par le launcher :
/// l'environnement du processus ne changera pas en cours de partie.
static SHARED: OnceLock<Option<Arc<Shared>>> = OnceLock::new();

fn shared() -> Option<&'static Arc<Shared>> {
    SHARED
        .get_or_init(|| {
            if !token::channel_open() {
                return None;
            }
            let client = Client::from_env()?;
            let tokens = Arc::new(TokenStore::default());
            if !token::start_reader(tokens.clone()) {
                return None;
            }
            worker::start(client, tokens)
        })
        .as_ref()
}

#[no_mangle]
pub extern "C" fn yf_presence_abi() -> u32 {
    ABI_VERSION
}

/// Démarre, la première fois, la lecture des jetons envoyés par le launcher
/// et le thread de fond. Rend 1 s'ils tournent, 0 sinon (jeu lancé hors
/// launcher). Tourner ne dit pas qu'un jeton est arrivé : voir
/// `yf_presence_status`.
#[no_mangle]
pub extern "C" fn yf_presence_start() -> i32 {
    shared().is_some() as i32
}

/// « Ce joueur utilise-t-il YuyuFrame ? » — 0 : on ne sait pas (encore),
/// 1 : oui, 2 : non. Ne bloque jamais ; un joueur inconnu est demandé en
/// arrière-plan, et la réponse est là quelques secondes plus tard.
///
/// `uuid_hi` / `uuid_lo` : les deux moitiés de l'UUID du PROFIL du joueur
/// (`UUID.getMostSignificantBits` / `getLeastSignificantBits`). `name` : son
/// pseudo de profil en ASCII, jamais le texte affiché au-dessus de sa tête.
///
/// Un compte hors ligne ou un faux joueur (UUID qui n'est pas en version 4)
/// répond 2 sans rien demander.
///
/// # Safety
/// `name` doit pointer sur `name_len` octets lisibles.
#[no_mangle]
pub unsafe extern "C" fn yf_presence_query(uuid_hi: i64, uuid_lo: i64, name: *const u8, name_len: i32) -> i32 {
    let Some(shared) = shared() else { return Answer::Unknown as i32 };
    if name.is_null() || name_len <= 0 {
        return Answer::Absent as i32;
    }
    let name = std::slice::from_raw_parts(name, name_len as usize);
    let uuid = ((uuid_hi as u64 as u128) << 64) | (uuid_lo as u64 as u128);
    let Some(key) = PlayerKey::new(uuid, name) else { return Answer::Absent as i32 };
    shared.directory().query(key, Instant::now()) as i32
}

/// État, pour le journal de l'agent : écrit 4 entiers dans `out` —
/// l'état du thread (0 : éteint, sinon `worker::STATE_*`), le nombre de
/// joueurs en mémoire, de joueurs reconnus, de questions en attente.
///
/// # Safety
/// `out` doit pointer sur 4 entiers de 32 bits inscriptibles.
#[no_mangle]
pub unsafe extern "C" fn yf_presence_status(out: *mut i32) {
    if out.is_null() {
        return;
    }
    let (state, cached, present, pending) = match SHARED.get().and_then(|s| s.as_ref()) {
        Some(shared) => {
            let (cached, present, pending) = shared.directory().counts();
            (shared.state(), cached as i32, present as i32, pending as i32)
        }
        None => (0, 0, 0, 0),
    };
    out.write(state);
    out.add(1).write(cached);
    out.add(2).write(present);
    out.add(3).write(pending);
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Hors launcher (le cas des tests) : rien ne démarre, l'entrée standard
    /// n'est pas lue, tout répond « inconnu », et aucun pointeur douteux ne
    /// fait planter.
    #[test]
    fn hors_launcher_tout_repond_inconnu() {
        assert_eq!(yf_presence_start(), 0);
        let name = b"Steve";
        assert_eq!(unsafe { yf_presence_query(0, 0, name.as_ptr(), name.len() as i32) }, 0);
        assert_eq!(unsafe { yf_presence_query(0, 0, std::ptr::null(), 5) }, 0);
        let mut out = [-1i32; 4];
        unsafe { yf_presence_status(out.as_mut_ptr()) };
        assert_eq!(out, [0, 0, 0, 0]);
        unsafe { yf_presence_status(std::ptr::null_mut()) };
    }
}
