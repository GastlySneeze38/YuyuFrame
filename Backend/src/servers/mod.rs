//! Les serveurs multijoueur, hors du lancement lui-même :
//! - `catalog` : le catalogue de Modrinth (recherche, fiche, modpack à installer) ;
//! - `mine` : « mes serveurs », les listes `servers.dat` des instances ;
//! - `probe` : les versions qu'un serveur accepte, une à une (fenêtre « Rejoindre »).
//!
//! Les grands réseaux PvP (Hypixel…) ne sont pas dans le catalogue de
//! Modrinth ; une liste à nous pour les ajouter est remise à plus tard.
//!
//! Rejoindre un serveur reste dans `play::launch` (arguments de connexion
//! directe) et le ping dans `minecraft::server_ping`.

pub mod catalog;
pub mod mine;
pub mod probe;
