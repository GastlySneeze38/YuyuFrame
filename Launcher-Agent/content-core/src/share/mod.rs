//! Partager les réglages du client par un lien, depuis le jeu.
//!
//! Le même lien que celui du launcher (`yuyuframe://options/…`) : rien n'est
//! hébergé, tout voyage dans le lien, qu'on copie dans le presse-papiers et
//! qu'un autre joueur colle dans son jeu — ou dans son launcher.
//!
//! - `codec`     la compression et l'écriture du lien (copie de celle du launcher) ;
//! - `options`   ce qui a le droit d'y entrer et d'en sortir ;
//! - `clipboard` le presse-papiers du système ;
//! - `bridge`    les fonctions appelées par Java (`runtime/share/NativeShare`).

mod bridge;
mod clipboard;
mod codec;
mod options;
