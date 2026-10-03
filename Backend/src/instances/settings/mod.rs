//! Réglages d'une instance.
//!
//!   java               le Java d'une instance : détecter, installer, analyser
//!   jvm_profiles       configurations JVM réutilisables, reliables aux instances
//!   jvm_profile_store  leur table en base locale
//!   options            `options.txt` et le modèle partagé entre instances
//!   options_archive    emporter et reprendre toutes les options (archive)
//!   options_share      options du client intégré en fichier, options par lien
//!   agent_options      options du client intégré (`.properties` de l'agent)

pub mod agent_options;
pub mod java;
pub mod jvm_profile_store;
pub mod jvm_profiles;
pub mod options;
pub mod options_archive;
pub mod options_share;
