//! Le canal entre le jeu et le launcher qui l'a lancé : un tube nommé, à
//! double sens. Le launcher le crée avant de lancer le jeu et lui en donne le
//! nom (`Launcher-Client/Backend/src/play/game_link.rs`, où le protocole est
//! décrit) ; le jeu l'ouvre ici.
//!
//! Le jeu pose une question, le launcher répond : une ligne dans chaque sens,
//! des champs séparés par des tabulations. Un seul thread à nous (`yf-link`)
//! tient le tube — une question à la fois, la réponse lue avant la suivante —
//! donc jamais le fil du jeu, qui dépose sa question et vient relever la
//! réponse plus tard (`bridge.rs`).
//!
//! Ce qui y passe :
//! - « le jeu est prêt » (menu principal atteint) ;
//! - le jeton du compte YuyuFrame et le réglage de visibilité, redemandés à
//!   intervalle régulier et rangés dans `presence::token` — eux ne sortent
//!   jamais vers Java ;
//! - la liste des comptes Minecraft et le changement de compte, pour la liste
//!   déroulante du client intégré.
//!
//! Sans tube — jeu lancé à la main, autre launcher, launcher fermé depuis —
//! tout répond « indisponible » et rien d'autre ne casse.

mod bridge;
mod channel;

pub use channel::available;
