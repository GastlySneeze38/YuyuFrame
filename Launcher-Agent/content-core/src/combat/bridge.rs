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
/// 3 (2026-10-13) : le noyau reconnaît les duels seul ; `yf_combat_match`
/// devient `yf_combat_key` (la touche bascule), plus `yf_combat_recording`.
/// 4 (2026-10-13) : `yf_combat_report`, le résumé du dernier duel.
/// 5 (2026-10-13) : six familles dans le résumé au lieu de cinq.
/// 6 (2026-10-13) : `yf_combat_history`, l'historique des duels.
/// 10 (2026-10-14) : le résumé passe à 46 nombres (note avant bonus, bug
/// use, grosses et petites ouvertures, dégâts évités).
/// 11 (2026-10-15) : `yf_combat_detail`, le détail des familles d'un duel.
/// 12 (2026-10-15) : le résumé passe à 50 nombres (coups et totems échangés).
/// 13 (2026-10-18) : `yf_combat_share`, le partage des duels pour la calibration.
/// 14 (2026-10-22) : notes sur 500, et lignes `action` dans le détail d'un duel.
/// 15 (2026-10-22) : le mode épée — neuf chiffres de plus dans le résumé.
/// 16 (2026-10-22) : durée du duel au bout du résumé, `yf_combat_ranked`.
/// 17 (2026-10-23) : compétitif ou casual — `yf_combat_competitive`,
/// `yf_combat_live_mode`, « casual » au bout du résumé et du classement.
/// 18 (2026-10-23) : `yf_combat_kits`, l'équipement d'un nouveau duel.
/// 19 (2026-10-23) : `yf_combat_callouts`, les mots des bons coups ;
/// `yf_combat_live_mode` répond aussi hors duel.
/// 20 (2026-10-23) : le classement vérifié par le serveur —
/// `yf_combat_ranked_status`, `yf_combat_own_badge`, `yf_combat_show_rank`.
/// 21 (2026-11-01) : le serveur est la seule source du classement —
/// `yf_combat_ranked_ready` ; statut 3 = serveur muet (le duel ne compte
/// pas), 6 n'existe plus ; ligne `ready` de l'historique.
const ABI_VERSION: u32 = 21;

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
    // La file des duels partagés de cette instance : surveillée dès maintenant.
    super::share::watch(std::path::Path::new(&dir));
    // Son classement aussi : copie de l'état, nettoyage de l'ancien classement.
    super::ranked_sync::watch(std::path::Path::new(&dir));
    send(Record::Context { dir: PathBuf::from(dir), server: text(server, server_len), mc: text(mc, mc_len) });
}

/// Le réglage « Partager mes duels » du module Score PvP, et la version de
/// l'agent à joindre aux paquets. Appelé à chaque changement du réglage et au
/// démarrage ; le passage à 0 vide les paquets en attente. Voir `share.rs`.
///
/// # Safety
/// `agent` doit désigner `agent_len` octets lisibles (ou être nul avec 0).
#[no_mangle]
pub unsafe extern "C" fn yf_combat_share(enabled: i32, agent: *const u8, agent_len: i32) {
    super::share::set_enabled(enabled != 0, &text(agent, agent_len));
}

/// Changement de monde : le journal en cours se termine.
#[no_mangle]
pub extern "C" fn yf_combat_world() {
    send(Record::World);
}

/// La touche du duel : ouvre un duel s'il n'y en a pas, ferme celui qui est
/// ouvert sinon (résultat « inconnu »). Rend le nouvel état, 1 = ouvert.
///
/// C'est le noyau qui sait si un duel est ouvert — il les ouvre et les ferme
/// aussi de lui-même (`journal.rs`) ; Java ne tient aucun état.
#[no_mangle]
pub extern "C" fn yf_combat_key() -> i32 {
    let start = !super::writer::recording();
    send(Record::Match { start });
    start as i32
}

/// Le résumé du dernier duel fermé. Rend son numéro — 0 tant qu'aucun duel
/// ne s'est fermé depuis le lancement, puis 1, 2… — et, si `out` a la place
/// (`REPORT_LEN` nombres), y écrit le résumé dans l'ordre de
/// `score::Report::numbers`. Java le relit à chaque tick et n'affiche que
/// lorsque le numéro change.
///
/// # Safety
/// `out` doit pointer sur `len` nombres inscriptibles (ou être nul).
#[no_mangle]
pub unsafe extern "C" fn yf_combat_report(out: *mut f64, len: i32) -> i32 {
    let (number, values) = super::writer::last_report();
    if !out.is_null() && len as usize >= values.len() {
        std::slice::from_raw_parts_mut(out, values.len()).copy_from_slice(&values);
    }
    number
}

/// Ce que le dernier duel fermé a rapporté au classement
/// (`verified::Outcome::numbers`, `RANKED_LEN` nombres). Rend le numéro du
/// résumé auquel il se rapporte — celui de [`yf_combat_report`] — ou 0 tant
/// que rien n'est calculé : il arrive un peu après le résumé, le temps d'un
/// aller-retour avec le serveur sur un fil à part. Un duel qui ne compte pas au
/// classement ne publie rien : Java garde alors l'ancien numéro.
///
/// # Safety
/// `out` doit pointer sur `len` nombres inscriptibles (ou être nul).
#[no_mangle]
pub unsafe extern "C" fn yf_combat_ranked(out: *mut f64, len: i32) -> i32 {
    let (number, values) = super::writer::last_ranked();
    if !out.is_null() && len as usize >= values.len() {
        std::slice::from_raw_parts_mut(out, values.len()).copy_from_slice(&values);
    }
    number
}

/// Le statut du classement du dernier duel (`ranked_sync::Status` : 1 vérifié,
/// 2 refusé par le serveur, 3 serveur muet, 4 sans compte, 5 casual, 7
/// serveur de jeu hors ligne), pour le résumé numéro `number` — 0 si ce
/// n'est pas le sien.
#[no_mangle]
pub extern "C" fn yf_combat_ranked_status(number: i32) -> i32 {
    let (of, status) = super::ranked_sync::status();
    if of == number { status } else { 0 }
}

/// Le compétitif est-il possible en ce moment (`ranked_sync::Readiness`) :
/// 0 vérification en cours, 1 oui, 2 pas de compte YuyuFrame, 3 compte
/// Minecraft hors ligne, 4 serveur injoignable, 5 classement inconnu de
/// l'API. Hors de 1, un duel s'ouvre en casual.
#[no_mangle]
pub extern "C" fn yf_combat_ranked_ready() -> i32 {
    super::ranked_sync::readiness() as i32
}

/// Le code du logo de notre propre joueur, tel que les autres le voient
/// (0 logo de base, 1 à 7 un rang, 16 Champion du Choc) — le même que
/// `yf_presence_query` donne pour les autres.
#[no_mangle]
pub extern "C" fn yf_combat_own_badge() -> i32 {
    super::ranked_sync::own_badge_code()
}

/// Le réglage « Montrer mon rang aux autres » : envoyé au serveur, qui
/// donne alors le logo de base à la place du rang.
#[no_mangle]
pub extern "C" fn yf_combat_show_rank(on: i32) {
    super::ranked_sync::set_show_rank(on != 0);
}

/// L'historique des duels de l'instance, pour l'écran du module : la moyenne
/// des notes puis un duel par ligne (format dans `history.rs`). `dir` : le
/// dossier de l'instance. Rend le nombre d'octets écrits dans `out`, ou
/// l'opposé de la place qu'il aurait fallu si `out` est trop petit.
///
/// Relit et renote les journaux : quelques millisecondes par duel. À appeler
/// à l'ouverture de l'écran, pas à chaque image.
///
/// # Safety
/// `dir` doit pointer sur `dir_len` octets lisibles, `out` sur `out_len`
/// octets inscriptibles.
#[no_mangle]
pub unsafe extern "C" fn yf_combat_history(dir: *const u8, dir_len: i32, out: *mut u8, out_len: i32) -> i32 {
    let dir = text(dir, dir_len);
    if dir.is_empty() || out.is_null() || out_len <= 0 {
        return 0;
    }
    let history = super::history::history(std::path::Path::new(&dir));
    let bytes = history.as_bytes();
    if bytes.len() > out_len as usize {
        return -(bytes.len() as i32);
    }
    std::slice::from_raw_parts_mut(out, bytes.len()).copy_from_slice(bytes);
    bytes.len() as i32
}

/// Le détail des familles d'un duel (format dans `score::detail_text`),
/// pour la fenêtre qui s'ouvre au clic sur une famille. `dir` : le dossier de
/// l'instance ; `time` : l'heure d'ouverture du duel. Rend le nombre d'octets
/// écrits, ou l'opposé de la place qu'il aurait fallu. Relit un journal : à
/// appeler à l'ouverture de la page du duel, pas à chaque image.
///
/// # Safety
/// `dir` doit pointer sur `dir_len` octets lisibles, `out` sur `out_len`
/// octets inscriptibles.
#[no_mangle]
pub unsafe extern "C" fn yf_combat_detail(dir: *const u8, dir_len: i32, time: i64, out: *mut u8, out_len: i32) -> i32 {
    let dir = text(dir, dir_len);
    if dir.is_empty() || out.is_null() || out_len <= 0 || time <= 0 {
        return 0;
    }
    let detail = super::history::detail(std::path::Path::new(&dir), time as u64);
    let bytes = detail.as_bytes();
    if bytes.len() > out_len as usize {
        return -(bytes.len() as i32);
    }
    std::slice::from_raw_parts_mut(out, bytes.len()).copy_from_slice(bytes);
    bytes.len() as i32
}

/// Un duel est-il ouvert ? 1 = oui.
#[no_mangle]
pub extern "C" fn yf_combat_recording() -> i32 {
    super::writer::recording() as i32
}

/// Le mode du duel ouvert, deviné à ce que le joueur tient : 1 masse, 2 épée,
/// 3 autre, 0 sans duel. Pour l'annonce de début de duel seulement.
#[no_mangle]
pub extern "C" fn yf_combat_live_mode() -> i32 {
    super::writer::live_mode() as i32
}

/// Combien de fois le serveur a équipé le joueur pour un nouveau duel (son
/// armure arrivée hors duel) : un compteur, dont seul le changement compte.
/// L'interface y efface le panneau du duel précédent, avant le premier coup.
#[no_mangle]
pub extern "C" fn yf_combat_kits() -> i32 {
    super::writer::kits() as i32
}

/// Les mots des bons coups (`live.rs`) pas encore lus, retirés de la file :
/// trois entiers chacun — sorte, rareté (1 à 4), valeur (longueur du combo,
/// chute du smash en dixièmes de bloc…). Rend le nombre de mots écrits dans
/// `out` (au plus `len / 3`).
///
/// # Safety
/// `out` doit pointer sur `len` entiers inscriptibles (ou être nul).
#[no_mangle]
pub unsafe extern "C" fn yf_combat_callouts(out: *mut i32, len: i32) -> i32 {
    if out.is_null() || len < 3 {
        return 0;
    }
    let words = super::live::take(len as usize / 3);
    let slice = std::slice::from_raw_parts_mut(out, words.len() * 3);
    for (i, word) in words.iter().enumerate() {
        slice[i * 3..i * 3 + 3].copy_from_slice(word);
    }
    words.len() as i32
}

/// Le choix du joueur : 1 compétitif (ses duels comptent pour son rang), 0
/// casual. Vaut pour les duels ouverts ensuite ; appelé au démarrage et à
/// chaque changement.
#[no_mangle]
pub extern "C" fn yf_combat_competitive(on: i32) {
    send(Record::Competitive { on: on != 0 });
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
