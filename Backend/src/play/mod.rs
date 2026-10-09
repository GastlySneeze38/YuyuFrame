//! Jouer : lancer une partie, la suivre, et ce qu'il en reste ensuite.
//!
//!   launch    lancement d'une instance, annulation, serveurs enregistrés
//!   presence  signal « je suis en jeu », pour être reconnu des autres joueurs
//!   recovery  parties qu'un launcher fermé n'a pas vues finir
//!   stats/    temps de jeu — calcul, table locale, commandes
//!
//! Le moteur lui-même (JVM, loaders, classpath) est dans `crate::minecraft`.

pub mod game_link;
pub mod launch;
pub mod presence;
pub mod recovery;
pub mod stats;
