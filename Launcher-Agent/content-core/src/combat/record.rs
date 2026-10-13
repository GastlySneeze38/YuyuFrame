//! Ce que Java envoie, tel quel. L'ordre des champs des deux tableaux est un
//! contrat avec `runtime/combat/NativeCombat.java` : le changer, c'est monter
//! `ABI_VERSION` dans `bridge.rs`.

use std::path::PathBuf;

/// Notre joueur à un tick. Dans l'ordre : identifiant réseau ; position x, y,
/// z ; vitesse x, y, z (blocs par tick) ; regard horizontal, vertical ; vie,
/// vie maximale ; distance de chute ; charge de l'attaque (0 à 1) ; drapeaux
/// (`FLAG_*`) ; ping en millisecondes.
pub const ME_LEN: usize = 15;
pub const ME_ID: usize = 0;
pub const ME_X: usize = 1;

/// Un autre joueur à un tick : identifiant réseau ; position ; vitesse ;
/// drapeaux (seul `FLAG_ON_GROUND` est renseigné).
pub const OTHER_LEN: usize = 8;
pub const OTHER_ID: usize = 0;
pub const OTHER_X: usize = 1;

/// Coup porté par notre joueur. `a` : identifiant de la cible.
pub const EVENT_ATTACK: i32 = 1;
/// Paquet de dégâts. `a` : entité touchée, `b` : auteur, `c` : source directe
/// (−1 s'il n'y en a pas), texte : type de dégât.
pub const EVENT_DAMAGE: i32 = 2;
/// Événement d'entité. `a` : entité, `b` : code (3 = mort, 35 = totem).
pub const EVENT_ENTITY: i32 = 3;
/// Objet utilisé par notre joueur. `a` : 1 pour la main principale, texte :
/// identifiant de l'objet.
pub const EVENT_ITEM_USE: i32 = 4;

pub enum Record {
    /// Entrée dans un monde : où écrire, et de quoi parle le journal.
    Context { dir: PathBuf, server: String, mc: String },
    /// Changement de monde : le journal en cours se termine.
    World,
    Tick { me: [f64; ME_LEN], others: Vec<[f64; OTHER_LEN]> },
    /// Qui est derrière un identifiant réseau.
    Player { id: i32, uuid: u128, name: String },
    /// L'équipement d'un joueur, sous la forme que Java lui donne (une case
    /// par emplacement, séparées par des points-virgules).
    Equipment { id: i32, text: String },
    Event { kind: i32, a: i32, b: i32, c: i32, text: String },
    /// Le suivi s'arrête (module éteint) : le journal en cours se termine.
    Close,
}
