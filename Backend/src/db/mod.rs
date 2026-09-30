pub mod instance;
pub mod jvm_profile;
pub mod mc_account;
pub mod prefs;
pub mod schema;
pub mod skin_history;
pub mod stats;
pub mod yuyu_session;

pub use instance::*;
pub use jvm_profile::*;
pub use mc_account::*;
pub use schema::*;
pub use stats::*;
// Pas de `pub use skin_history::*` : ses `list`/`forget`/`remember` sont trop
// génériques pour vivre dans l'espace de noms commun de `db`. On écrit
// `db::skin_history::list(...)`, qui dit de quoi on parle.
pub use yuyu_session::*;
