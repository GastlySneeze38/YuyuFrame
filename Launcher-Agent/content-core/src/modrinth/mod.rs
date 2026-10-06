//! Catalogue Modrinth pour l'écran en jeu : recherche, fiche d'un projet,
//! téléchargement. Java y entre par `bridge.rs` (JNI, `ContentBridge`).

mod bridge;
mod download;
mod project;
mod search;

pub use download::download_file;
pub use project::{get_latest_file, get_project, get_versions};
pub use search::search_modrinth;

/// Encodage de requête minimal — évite une dépendance supplémentaire pour
/// quelques caractères réservés dans query/facets.
fn urlencoding(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char);
            }
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}
