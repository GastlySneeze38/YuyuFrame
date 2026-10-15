//! Ce que Java envoie, tel quel. L'ordre des champs des deux tableaux est un
//! contrat avec `runtime/combat/NativeCombat.java` : le changer, c'est monter
//! `ABI_VERSION` dans `bridge.rs`.

use std::path::PathBuf;

/// Notre joueur à un tick. Dans l'ordre : identifiant réseau ; position x, y,
/// z ; vitesse x, y, z (blocs par tick) ; regard horizontal, vertical ; vie,
/// vie maximale ; distance de chute ; charge de l'attaque (0 à 1) ; drapeaux
/// (`FLAG_*`) ; ping en millisecondes.
///
/// Puis (ABI 9, journal version 2 — les mesures des familles,
/// docs/scoring/familles/) : barre de faim, saturation (−1 si inconnues) ;
/// case sélectionnée de la barre d'inventaire (−1 si inconnue) ; hauteur des
/// pieds au-dessus du premier bloc solide en dessous (−1 sans sol à portée) ;
/// niveau des effets de force et de vitesse (0 sans l'effet).
pub const ME_LEN: usize = 21;
pub const ME_ID: usize = 0;
pub const ME_X: usize = 1;
/// Longueur d'un tick de notre joueur dans un journal de version 1, avant les mesures des familles.
pub const ME_LEN_V1: usize = 15;

/// Un autre joueur à un tick : identifiant réseau ; position ; vitesse ;
/// drapeaux (`FLAG_ON_GROUND`, et depuis l'ABI 9 `FLAG_GLIDING`,
/// `FLAG_USING_ITEM`, `FLAG_BLOCKING`) ; rotation horizontale de la tête en
/// degrés ; hauteur au-dessus du sol (−1 hors de portée de combat ou sans sol).
pub const OTHER_LEN: usize = 10;
pub const OTHER_ID: usize = 0;
pub const OTHER_X: usize = 1;
/// Longueur d'un autre joueur dans un journal de version 1.
pub const OTHER_LEN_V1: usize = 8;

/// Coup porté par notre joueur. `a` : identifiant de la cible ; `b` : case
/// sélectionnée ; texte : l'objet en main AU MOMENT du coup (depuis l'ABI 9 —
/// un attribute swap se lit là).
pub const EVENT_ATTACK: i32 = 1;
/// Paquet de dégâts. `a` : entité touchée, `b` : auteur, `c` : source directe
/// (−1 s'il n'y en a pas), texte : type de dégât.
pub const EVENT_DAMAGE: i32 = 2;
/// Événement d'entité. `a` : entité, `b` : code (3 = mort, 35 = totem).
pub const EVENT_ENTITY: i32 = 3;
/// Objet utilisé par notre joueur. `a` : 1 pour la main principale, texte :
/// identifiant de l'objet.
pub const EVENT_ITEM_USE: i32 = 4;
/// Son du bouclier joué par le serveur (ABI 9). `a` : le joueur sur qui il est
/// joué (le plus proche du son), texte : `item.shield.block` (coup arrêté) ou
/// `item.shield.break` (bouclier désactivé par une hache). Un coup arrêté
/// n'envoie aucun paquet de dégâts : c'est sa seule trace. Depuis le
/// 2026-10-15 aussi `entity.player.attack.crit`, joué sur celui qui frappe un
/// coup critique : la seule trace du critique de l'adversaire.
pub const EVENT_SOUND: i32 = 5;
/// Recharge d'un groupe de nos objets (ABI 9). `a` : durée en ticks, texte :
/// le groupe (`shield` quand une hache désactive notre bouclier).
pub const EVENT_COOLDOWN: i32 = 6;

pub enum Record {
    /// Entrée dans un monde : où écrire, et de quoi parle le journal.
    Context { dir: PathBuf, server: String, mc: String },
    /// Changement de monde : le duel en cours se ferme.
    World,
    /// Le joueur ouvre (`start`) ou ferme un duel, d'une touche.
    Match { start: bool },
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
