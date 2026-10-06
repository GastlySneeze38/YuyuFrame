//! `content_core.dll` — le code natif de LauncherAgent. Un dossier par
//! domaine ; dans chacun, `bridge.rs` est le seul fichier que Java appelle
//! (JNI ou FFM), le reste ne sait rien de Java.

// `self::` : notre module `image`, pas le crate du même nom.
pub use self::image::fetch_image_rgba;
pub use layout::compute_layout_json;
pub use modrinth::{download_file, get_latest_file, get_project, get_versions, search_modrinth};

mod image;
mod layout;
mod mesh;
mod modrinth;
mod mouse;
mod sort;
