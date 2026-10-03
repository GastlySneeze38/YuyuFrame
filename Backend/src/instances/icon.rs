//! L'icône d'une instance.
//!
//! Une image choisie sur le disque, relue ici et rangée **en base** sous forme
//! de data URI (voir la migration `icon` dans `db/schema.rs`). Trois raisons de
//! ne pas garder un chemin :
//!
//!   - la liste des instances afficherait N images, donc N lectures de fichier
//!     à chaque affichage ; en base, elles arrivent avec la liste ;
//!   - un chemin pointe vers un fichier que l'utilisateur peut déplacer,
//!     renommer ou supprimer — et l'icône disparaîtrait sans explication ;
//!   - la copie d'une instance emporte son icône sans rien copier sur le
//!     disque.
//!
//! La contrepartie est qu'une image lourde pèserait sur la base : d'où une
//! taille bornée, annoncée à l'utilisateur quand elle est dépassée plutôt que
//! redimensionnée en silence — réduire une image demanderait un décodeur
//! complet, et le launcher n'en embarque pas.

use base64::Engine as _;
use std::path::Path;

use crate::db;
use crate::state::SharedState;

use super::crud::{user_id, Instance};

/// 256 Kio de fichier source. Une icône de 256×256 en PNG tourne autour de
/// 60 Kio, donc la limite laisse largement la place à une image nette sans
/// qu'une photo d'appareil photo passe par là.
const MAX_ICON_BYTES: usize = 256 * 1024;

/// Type d'image reconnu à partir de ses premiers octets.
///
/// La signature et non l'extension : un `.png` renommé depuis un `.bmp` ne
/// s'afficherait pas, et la data URI annoncerait un type qui n'est pas le bon.
fn sniff(bytes: &[u8]) -> Option<&'static str> {
    if bytes.starts_with(&[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A]) {
        return Some("image/png");
    }
    if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        return Some("image/jpeg");
    }
    if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") {
        return Some("image/gif");
    }
    // WebP : "RIFF" .... "WEBP"
    if bytes.len() >= 12 && bytes.starts_with(b"RIFF") && &bytes[8..12] == b"WEBP" {
        return Some("image/webp");
    }
    None
}

/// Les octets d'un fichier image → la data URI à ranger, ou l'explication de
/// ce qui cloche. Séparé de la commande pour être testable.
pub(super) fn to_data_uri(bytes: &[u8]) -> Result<String, String> {
    if bytes.len() > MAX_ICON_BYTES {
        return Err(format!(
            "Image trop lourde ({} Kio) — maximum {} Kio. Réduis-la avant de la choisir.",
            bytes.len() / 1024,
            MAX_ICON_BYTES / 1024
        ));
    }
    let mime = sniff(bytes).ok_or("Format d'image non reconnu — PNG, JPEG, GIF ou WebP attendu")?;
    Ok(format!(
        "data:{mime};base64,{}",
        base64::engine::general_purpose::STANDARD.encode(bytes)
    ))
}

/// Pose l'icône d'une instance, ou la retire.
///
/// `path` absent = retour à l'icône par défaut. Le chemin vient du sélecteur
/// de fichiers, donc d'un geste de l'utilisateur, jamais d'un contenu lu
/// ailleurs — et il n'est pas conservé, seuls les octets le sont.
#[tauri::command]
pub async fn instance_set_icon(
    state: tauri::State<'_, SharedState>,
    instance_id: String,
    path: Option<String>,
) -> Result<Instance, String> {
    let icon = match path {
        Some(path) => {
            let bytes = tokio::fs::read(Path::new(&path))
                .await
                .map_err(|e| format!("Lecture de l'image impossible : {e}"))?;
            to_data_uri(&bytes)?
        }
        None => String::new(),
    };

    let s = state.read().await;
    let uid = user_id(&s);
    let db = s.db.lock().await;
    db::instance_set_icon(&db, &instance_id, uid, &icon).map_err(|e| e.to_string())?;
    db::instance_get(&db, &instance_id, uid)
        .map_err(|e| e.to_string())?
        .map(super::crud::row_to_instance)
        .ok_or_else(|| "Instance introuvable".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    const PNG: [u8; 8] = [0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];

    #[test]
    fn reconnait_les_quatre_formats() {
        assert_eq!(sniff(&PNG), Some("image/png"));
        assert_eq!(sniff(&[0xFF, 0xD8, 0xFF, 0xE0]), Some("image/jpeg"));
        assert_eq!(sniff(b"GIF89a....."), Some("image/gif"));
        assert_eq!(sniff(b"RIFF\0\0\0\0WEBPVP8 "), Some("image/webp"));
    }

    /// Un fichier renommé en `.png` ne doit pas passer pour un PNG : c'est la
    /// signature qui décide, pas l'extension.
    #[test]
    fn refuse_ce_qui_n_est_pas_une_image() {
        assert_eq!(sniff(b"BM......"), None);
        assert_eq!(sniff(b"<html>"), None);
        assert_eq!(sniff(&[]), None);
        assert!(to_data_uri(b"pas une image").is_err());
    }

    #[test]
    fn annonce_le_bon_type_dans_le_data_uri() {
        let uri = to_data_uri(&PNG).unwrap();
        assert!(uri.starts_with("data:image/png;base64,"), "{uri}");
    }

    /// La limite protège la base : elle est franche, et le message dit quoi
    /// faire plutôt que de laisser l'échec sans suite.
    #[test]
    fn refuse_au_dela_de_la_limite() {
        let mut big = PNG.to_vec();
        big.resize(MAX_ICON_BYTES + 1, 0);
        let err = to_data_uri(&big).unwrap_err();
        assert!(err.contains("256"), "{err}");

        let mut ok = PNG.to_vec();
        ok.resize(MAX_ICON_BYTES, 0);
        assert!(to_data_uri(&ok).is_ok());
    }
}
