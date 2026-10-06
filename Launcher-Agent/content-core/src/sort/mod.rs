//! Le tri du translucide du renderer « Celeritas » : dans quel ordre dessiner
//! les quads d'eau, de verre ou de glace d'une section pour que ce qui est
//! derrière se voie à travers ce qui est devant.
//!
//! À la construction d'une section, `tree.rs` choisit le tri le moins cher
//! qui reste juste ; quand l'ordre dépend de la caméra, il rend un arbre que
//! Java garde et fait relire à chaque déplacement.
//!
//! Java y entre par `bridge.rs`, appelé par FFM. Côté Java :
//! `celeritas/nativecore/NativeSort.java`.

mod bridge;
mod quads;
mod tree;
