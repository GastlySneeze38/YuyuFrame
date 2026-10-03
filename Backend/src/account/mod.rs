//! Gestion de compte. Trois choses distinctes, que rien ne relie sinon la
//! personne devant l'écran :
//!
//!   yuyu       le compte YuyuFrame (connexion, second facteur, e-mail, plan)
//!   minecraft  les comptes Minecraft du PC (Microsoft ou hors ligne)
//!   skins      l'apparence d'un compte Minecraft
//!
//! Les comptes Minecraft appartiennent au PC et ne dépendent jamais d'une
//! session YuyuFrame : se déconnecter de l'un ne touche pas aux autres.

pub mod minecraft;
pub mod skins;
pub mod yuyu;
