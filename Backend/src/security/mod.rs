//! Sécurité du launcher : ce qui protège le compte et ce qui est payé, réuni
//! ici pour se lire d'une traite.
//!
//!   vault       refresh token dans le coffre du système
//!   hardware    empreinte matérielle envoyée à la connexion (anti-alt)
//!   license     licence signée, vérifiée hors ligne
//!   plan_guard  décision d'ouvrir une page payante
//!   safe_paths  chemins venus d'une archive ou d'un lien, jamais crus sur parole
//!
//! Les jetons eux-mêmes ne sortent pas de `crate::server` (rotation) et de
//! `crate::account::yuyu::store` (persistance) : aucune commande n'en manipule.

pub mod hardware;
pub mod license;
pub mod plan_guard;
pub mod safe_paths;
pub mod vault;
