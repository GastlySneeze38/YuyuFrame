//! Base locale (SQLite) : le socle seulement.
//!
//!   schema  ouverture, création des tables, migrations
//!   prefs   préférences clé/valeur
//!
//! Chaque table vit à côté du domaine qui s'en sert (`instances::store`,
//! `account::yuyu::store`, `play::stats::store`…). Elles sont réexportées ici
//! pour qu'un appelant écrive `db::instance_list(...)` sans avoir à savoir où
//! la table est rangée.

pub mod prefs;
pub mod schema;

pub use schema::*;

pub use crate::account::minecraft::store::*;
pub use crate::account::yuyu::store::*;
pub use crate::instances::settings::jvm_profile_store::*;
pub use crate::instances::store::*;
pub use crate::play::stats::store::*;
// Pas de réexport pour l'historique des skins : ses `list`/`forget`/`remember`
// sont trop génériques pour l'espace de noms commun. On écrit
// `account::skins::history::list(...)`, qui dit de quoi on parle.
