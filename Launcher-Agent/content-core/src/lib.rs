//! `content_core.dll` — le code natif de LauncherAgent. Un dossier par
//! domaine ; dans chacun, `bridge.rs` est le seul fichier que Java appelle
//! (JNI ou FFM), le reste ne sait rien de Java.

// `self::` : notre module `image`, pas le crate du même nom.
pub use self::image::fetch_image_rgba;
pub use layout::compute_layout_json;
pub use modrinth::{download_file, get_latest_file, get_project, get_versions, search_modrinth};
/// Le classement vérifié (`combat/verified.rs`) : le même calcul pour le jeu
/// et pour LauncherAPI, qui dépend de ce crate pour le refaire à l'identique.
pub use combat::verified as ranked_verify;

mod combat;
mod image;
mod layout;
mod link;
mod mesh;
mod modrinth;
mod mouse;
mod presence;
mod share;
mod sort;
