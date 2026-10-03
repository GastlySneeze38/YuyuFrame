//! Compte YuyuFrame — LauncherAPI /v1, un fichier par geste.
//!
//!   session     inscription, connexion, second facteur, déconnexion
//!   two_factor  application d'authentification, codes de secours
//!   email       changer et confirmer l'e-mail
//!   password    changer le mot de passe, mot de passe oublié
//!   profile     plan, paiement, comptes Minecraft transmis au serveur
//!   devices     appareils connectés
//!   store       ce qui reste de la session en base locale
//!
//! Les jetons ne sont pas manipulés ici : `crate::server` garde la session,
//! rafraîchit le jeton d'accès quand il expire et remonte des erreurs à
//! `code` stable, que le frontend traduit en écran (mise à jour obligatoire,
//! compte suspendu, mot de passe à changer…).

pub mod devices;
pub mod email;
pub mod password;
pub mod profile;
pub mod session;
pub mod store;
pub mod two_factor;

fn text(value: &serde_json::Value, key: &str) -> Option<String> {
    value.get(key).and_then(|v| v.as_str()).filter(|s| !s.is_empty()).map(str::to_string)
}

fn flag(value: &serde_json::Value, key: &str) -> bool {
    value.get(key).and_then(|v| v.as_bool()).unwrap_or(false)
}
