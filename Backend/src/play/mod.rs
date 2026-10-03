//! Jouer : lancer une partie, la suivre, et ce qu'il en reste ensuite.
//!
//!   launch    lancement d'une instance, annulation, serveurs enregistrés
//!   recovery  parties qu'un launcher fermé n'a pas vues finir
//!   stats/    temps de jeu — calcul, table locale, commandes
//!
//! Le moteur lui-même (JVM, loaders, classpath) est dans `crate::minecraft`.

pub mod launch;
pub mod recovery;
pub mod stats;
