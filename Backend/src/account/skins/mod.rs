//! Skins d'un compte Minecraft.
//!
//!   skin     sources (joueur, URL, fichier, éditeur), application, aperçus
//!   catalog  catalogue Ely.by — désigne un skin, n'en applique jamais
//!   history  historique local (Mojang ne sert que le skin courant)
//!   host     compte hors ligne : faire héberger son skin par Mojang

pub mod catalog;
pub mod history;
pub mod host;
pub mod skin;
