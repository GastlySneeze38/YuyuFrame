//! Le noyau natif du maillage des chunks de la 1.8.9 (renderer « Celeritas »).
//!
//! Java garde ce qui dépend du code du jeu (modèles, teintes, faces cachées) ;
//! ici vient ce qui ne dépend que de tableaux de nombres :
//! - la forme d'une section (`shape.rs`) — visibilité à travers elle, volume
//!   à dessiner, boîtes occultantes (`boxes.rs`) — à partir des seuls
//!   identifiants de blocs de la copie figée (`SectionSnapshot.blocks`) ;
//! - la lumière et l'occlusion ambiante des quads (`light.rs`) et l'écriture
//!   de leurs sommets au format du moteur (`emit.rs`) ;
//! - l'eau et la lave, de bout en bout (`fluid.rs`).
//!
//! Java y entre par `bridge.rs`, appelé par FFM depuis les fils de
//! construction. Côté Java : `celeritas/v1_8_9/nativecore/NativeMesh.java`.

mod boxes;
mod bridge;
mod emit;
mod expose;
mod fluid;
mod light;
mod shape;
