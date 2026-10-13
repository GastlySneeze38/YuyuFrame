//! Les fonctions `yf_combat_*` : des fonctions C ordinaires, que Java cherche
//! par leur nom dans la DLL (FFM, `runtime/combat/NativeCombat.java`). Leur
//! contrat est ici ; un changement de signature ou de sens se fait avec
//! `ABI_VERSION`.
//!
//! Toutes sont appelées depuis le fil du jeu et aucune n'attend : elles
//! recopient leurs arguments et déposent un enregistrement dans la file du
//! fil d'écriture (`writer::send`).

use std::path::PathBuf;

use super::record::*;
use super::writer::send;

/// Version du contrat. Java refuse une DLL qui n'annonce pas la version qu'il
/// attend (`NativeCombat.ABI_VERSION`), au lieu de l'appeler de travers.
/// 2 (2026-10-13) : `yf_combat_match` — le journal ne s'écrit plus qu'entre
/// l'ouverture et la fermeture d'un duel.
const ABI_VERSION: u32 = 2;

/// Autres joueurs transmis par tick, au plus.
const MAX_OTHERS: usize = 16;
/// Longueur maximale d'un texte reçu (pseudo, adresse, équipement).
const MAX_TEXT: usize = 2048;

/// # Safety
/// `ptr` doit pointer sur `len` octets lisibles (ou être nul avec `len` ≤ 0).
unsafe fn text(ptr: *const u8, len: i32) -> String {
    if ptr.is_null() || len <= 0 {
        return String::new();
    }
    let bytes = std::slice::from_raw_parts(ptr, (len as usize).min(MAX_TEXT));
    String::from_utf8_lossy(bytes).into_owned()
}

#[no_mangle]
pub extern "C" fn yf_combat_abi() -> u32 {
    ABI_VERSION
}

/// Entrée dans un monde. `dir` : le dossier de l'instance, où le journal
/// s'écrit (`yuyuframe/matches/`). `server` : l'adresse du serveur, vide en
/// solo. `mc` : la version du jeu. Le journal en cours, s'il y en a un, se
/// termine.
///
/// # Safety
/// Chaque pointeur doit désigner autant d'octets lisibles que sa longueur
/// l'annonce (ou être nul avec une longueur de 0).
#[no_mangle]
pub unsafe extern "C" fn yf_combat_context(dir: *const u8, dir_len: i32, server: *const u8, server_len: i32, mc: *const u8, mc_len: i32) {
    let dir = text(dir, dir_len);
    if dir.is_empty() {
        return;
    }
    send(Record::Context { dir: PathBuf::from(dir), server: text(server, server_len), mc: text(mc, mc_len) });
}

/// Changement de monde : le journal en cours se termine.
#[no_mangle]
pub extern "C" fn yf_combat_world() {
    send(Record::World);
}

/// Le joueur ouvre (`start` ≠ 0) ou ferme un duel. Ouvrir crée le fichier du
/// journal ; rien n'est écrit en dehors d'un duel. Ouvrir alors qu'un duel
/// est déjà ouvert ferme d'abord celui-ci.
#[no_mangle]
pub extern "C" fn yf_combat_match(start: i32) {
    send(Record::Match { start: start != 0 });
}

/// Un tick du jeu. `me` : `ME_LEN` nombres (`record.rs`). `others` :
/// `others_count` joueurs de `OTHER_LEN` nombres chacun, à la suite.
///
/// # Safety
/// `me` doit pointer sur `ME_LEN` nombres lisibles, `others` sur
/// `others_count × OTHER_LEN` (ou être nul avec un compte de 0).
#[no_mangle]
pub unsafe extern "C" fn yf_combat_tick(me: *const f64, others: *const f64, others_count: i32) {
    if me.is_null() {
        return;
    }
    let mut mine = [0.0; ME_LEN];
    mine.copy_from_slice(std::slice::from_raw_parts(me, ME_LEN));
    let count = if others.is_null() { 0 } else { (others_count.max(0) as usize).min(MAX_OTHERS) };
    let mut list = Vec::with_capacity(count);
    for i in 0..count {
        let mut one = [0.0; OTHER_LEN];
        one.copy_from_slice(std::slice::from_raw_parts(others.add(i * OTHER_LEN), OTHER_LEN));
        list.push(one);
    }
    send(Record::Tick { me: mine, others: list });
}

/// Qui est derrière un identifiant réseau. À rappeler régulièrement pour les
/// joueurs à portée : seul un changement est écrit.
///
/// # Safety
/// `name` doit pointer sur `name_len` octets lisibles.
#[no_mangle]
pub unsafe extern "C" fn yf_combat_player(id: i32, uuid_hi: i64, uuid_lo: i64, name: *const u8, name_len: i32) {
    let uuid = ((uuid_hi as u64 as u128) << 64) | (uuid_lo as u64 as u128);
    send(Record::Player { id, uuid, name: text(name, name_len) });
}

/// L'équipement d'un joueur : six cases séparées par des points-virgules
/// (tête, torse, jambes, pieds, main principale, main secondaire), chacune
/// vide ou « identifiant,enchantement=niveau,… ». À rappeler régulièrement :
/// seul un changement est écrit.
///
/// # Safety
/// `eq` doit pointer sur `eq_len` octets lisibles.
#[no_mangle]
pub unsafe extern "C" fn yf_combat_equipment(id: i32, eq: *const u8, eq_len: i32) {
    send(Record::Equipment { id, text: text(eq, eq_len) });
}

/// Un événement de combat : `kind` et le sens de `a`, `b`, `c` et du texte
/// sont ceux des constantes `EVENT_*` de `record.rs`.
///
/// # Safety
/// `info` doit pointer sur `info_len` octets lisibles (ou être nul avec une
/// longueur de 0).
#[no_mangle]
pub unsafe extern "C" fn yf_combat_event(kind: i32, a: i32, b: i32, c: i32, info: *const u8, info_len: i32) {
    send(Record::Event { kind, a, b, c, text: text(info, info_len) });
}

/// Le suivi s'arrête : le journal en cours se termine.
#[no_mangle]
pub extern "C" fn yf_combat_close() {
    send(Record::Close);
}
