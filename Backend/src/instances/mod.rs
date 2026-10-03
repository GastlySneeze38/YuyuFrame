//! Les instances : leur création, leur contenu, leurs réglages, leur partage.
//!
//!   crud       créer, lister, modifier, dupliquer, supprimer ; chemins d'une instance
//!   store      table `instances` de la base locale
//!   import     reprendre les instances d'un autre launcher
//!   icon       icône d'instance
//!   versions   versions du jeu et des loaders proposées à la création
//!   mods       mods installés (liste, bascule, installation, conflits)
//!   packs      resource packs et shaders
//!   modrinth   recherche Modrinth
//!   modpack    installation d'un modpack (mrpack, CurseForge)
//!   settings/  Java, configurations JVM, options du jeu et du client intégré
//!   health/    réparer une installation, essai de compatibilité
//!   share/     partage par fichier `.mrpack` ou par lien
//!
//! Règle posée après la perte d'instances du 2026-09-27 : aucun chemin de
//! code ne supprime un dossier d'instance sans geste explicite de l'utilisateur.

pub mod crud;
pub mod health;
pub mod icon;
pub mod import;
pub mod modpack;
pub mod modrinth;
pub mod mods;
pub mod packs;
pub mod settings;
pub mod share;
pub mod store;
pub mod versions;
