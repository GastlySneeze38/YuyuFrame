//! Les fonctions `yf_presence_*` : des fonctions C ordinaires, que Java
//! cherche par leur nom dans la DLL (FFM). Leur contrat est ici ; un
//! changement de signature ou de sens se fait avec `ABI_VERSION`.
//!
//! Aucune ne touche au réseau ni n'attend : le seul verrou pris est celui de
//! l'annuaire, que le thread de fond ne garde jamais pendant une requête.

use std::sync::{Arc, OnceLock};
use std::time::Instant;

use super::client::Client;
use super::directory::{server_form, Answer, PlayerKey, MAX_SERVER_FORMS};
use super::token;
use super::worker::{self, Shared};

/// Version du contrat. À incrémenter dès qu'une fonction change de signature
/// ou de sens : Java refuse une DLL qui n'annonce pas la version qu'il attend
/// (`NativePresence.ABI_VERSION`), au lieu de l'appeler de travers.
/// 2 (2026-10-08) : `yf_presence_context`, et un UUID version 3 n'est plus
/// « absent » d'office.
const ABI_VERSION: u32 = 2;

/// `None` une fois pour toutes si le jeu n'a pas été lancé par le launcher :
/// l'environnement du processus ne changera pas en cours de partie.
static SHARED: OnceLock<Option<Arc<Shared>>> = OnceLock::new();

fn shared() -> Option<&'static Arc<Shared>> {
    SHARED
        .get_or_init(|| {
            // Sans canal vers le launcher, aucun jeton n'arrivera jamais.
            if !crate::link::available() {
                return None;
            }
            let client = Client::from_env()?;
            worker::start(client, token::store())
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
/// Un faux joueur (UUID ni en version 4 ni en version 3) répond 2 sans rien
/// demander. Un UUID version 3 — joueur d'un serveur en mode hors ligne —
/// aussi, tant que `yf_presence_context` n'a pas donné l'adresse du serveur.
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

/// Où l'on joue, et sous quelle identité : ce qui permet de reconnaître les
/// joueurs d'un serveur en mode hors ligne, et de s'y annoncer. À rappeler
/// régulièrement (une fois par seconde suffit) : seul un changement a un effet.
///
/// `host` / `ip` : les deux formes de l'adresse du serveur de jeu, « hôte:port »
/// en ASCII — le nom tapé par le joueur, l'adresse IP jointe. L'une ou l'autre
/// peut manquer (longueur 0). Les deux à 0 : pas de serveur (solo, menu), ou
/// serveurs hors ligne refusés par réglage — plus rien ne part pour eux et
/// les joueurs en UUID version 3 répondent « absent ».
///
/// `uuid_hi` / `uuid_lo` / `name` : le PROFIL de notre propre joueur tel que
/// ce serveur le donne. Il n'est annoncé que s'il est en version 3 ; un
/// compte Microsoft reconnu comme tel par le serveur est déjà annoncé par le
/// launcher.
///
/// # Safety
/// `host`, `ip` et `name` doivent pointer sur autant d'octets lisibles que
/// leur longueur l'annonce (ou être nuls avec une longueur de 0).
#[no_mangle]
pub unsafe extern "C" fn yf_presence_context(
    host: *const u8,
    host_len: i32,
    ip: *const u8,
    ip_len: i32,
    uuid_hi: i64,
    uuid_lo: i64,
    name: *const u8,
    name_len: i32,
) {
    let Some(shared) = shared() else { return };
    let bytes = |ptr: *const u8, len: i32| -> &[u8] {
        if ptr.is_null() || len <= 0 {
            &[]
        } else {
            std::slice::from_raw_parts(ptr, len as usize)
        }
    };
    let mut server: Vec<Vec<u8>> = Vec::with_capacity(MAX_SERVER_FORMS);
    for raw in [bytes(host, host_len), bytes(ip, ip_len)] {
        if let Some(form) = server_form(raw) {
            if !server.contains(&form) {
                server.push(form);
            }
        }
    }
    let uuid = ((uuid_hi as u64 as u128) << 64) | (uuid_lo as u64 as u128);
    let own = PlayerKey::new(uuid, bytes(name, name_len));
    shared.directory().set_context(server, own, Instant::now());
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
        let host = b"play.example.fr:25565";
        unsafe { yf_presence_context(host.as_ptr(), host.len() as i32, std::ptr::null(), 0, 0, 0, name.as_ptr(), name.len() as i32) };
        unsafe { yf_presence_context(std::ptr::null(), 7, std::ptr::null(), -1, 0, 0, std::ptr::null(), 3) };
        let mut out = [-1i32; 4];
        unsafe { yf_presence_status(out.as_mut_ptr()) };
        assert_eq!(out, [0, 0, 0, 0]);
        unsafe { yf_presence_status(std::ptr::null_mut()) };
    }
}
