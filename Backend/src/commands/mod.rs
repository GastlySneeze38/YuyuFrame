pub mod account;
pub mod analytics;
pub mod backup;
pub mod crash;
pub mod curseforge;
pub mod deep_link;
pub mod fleet;
pub mod instance;
pub mod jvm_profile;
pub mod launch;
pub mod locale;
pub mod modrinth;
pub mod patch_notes;
pub mod pending;
pub mod plan;
pub mod reviews;
pub mod support;
pub mod sync;
pub mod system;
pub mod window;

// Plus aucun helper d'accès serveur ici : la synchronisation était le dernier
// appelant à parler à la LauncherAPI sans passer par `crate::api` (URL montée
// à la main, jeton lu dans le state, erreurs formatées sur place). Sa
// réécriture sur le protocole par morceaux (2026-09-21) l'a fait basculer
// comme le reste, et `api_base` / `network_err` / `bearer_call_error` n'ont
// plus eu d'utilisateur. Les préfixes d'erreur que le frontend reconnaît
// (`Serveur inaccessible : `, `Session expirée : `) sont produits par
// `api::error` — c'est là qu'il faut aller si un jour ils doivent changer.
