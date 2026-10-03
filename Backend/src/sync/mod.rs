//! Transferts par morceaux vers la LauncherAPI.
//!
//! Deux usages, un seul moteur (`chunks`) :
//!
//! - la **synchronisation** d'une instance entre plusieurs PC — mods,
//!   configurations, réglages ; jamais les mondes ;
//! - la **sauvegarde** des mondes, qui est une fonctionnalité à part entière
//!   et pas un effet de bord de la sync.
//!
//! La séparation est volontaire. Synchroniser un monde entre deux PC pose des
//! questions insolubles — lequel des deux gagne si on a joué des deux côtés ?
//! — alors qu'une sauvegarde est datée, empilée, et ne prétend jamais
//! fusionner quoi que ce soit.

pub mod archive;
pub mod chunks;
pub mod mods;
pub mod push_pull;
