//! Les fonctions `yf_mesh_*` : des fonctions C ordinaires, que Java cherche
//! par leur nom dans la DLL (FFM). Leur contrat est ici ; un changement de
//! signature ou de sens se fait avec `ABI_VERSION`.

use std::cell::RefCell;
use std::panic::{catch_unwind, AssertUnwindSafe};

use super::shape::{Workspace, BLOCKS, STATES};

/// Version du contrat. À incrémenter dès qu'une fonction change de signature
/// ou de sens : Java refuse une DLL qui n'annonce pas la version qu'il attend
/// (`NativeMesh.ABI_VERSION`), au lieu de l'appeler de travers.
const ABI_VERSION: u32 = 1;

/// Cases de `out` avant les boîtes : visibilité (deux moitiés), volume à dessiner.
const HEADER: usize = 3;

/// Réponse de `yf_mesh_section_shape` : la section n'a rien à dessiner ni à occulter.
const SHAPE_NO_DATA: i32 = -1;
/// Réponse de `yf_mesh_section_shape` : erreur interne ou arguments invalides, `out` n'est pas à lire.
const SHAPE_FAILED: i32 = -2;

thread_local! {
    // Un espace de travail par fil de construction : gros, donc gardé d'un appel à l'autre.
    static WORKSPACE: RefCell<(Workspace, Vec<i32>)> = RefCell::new((Workspace::new(), Vec::new()));
}

#[no_mangle]
pub extern "C" fn yf_mesh_abi() -> u32 {
    ABI_VERSION
}

/// Calcule la forme d'une section.
///
/// - `blocks` : 4 096 états de blocs (`identifiant << 4 | métadonnée`), à
///   l'indice `y << 8 | z << 4 | x` — le tableau de `SectionSnapshot` tel quel ;
/// - `flags` : 65 536 octets, un par état : bit 0 = se dessine (tout sauf
///   l'air), bit 1 = cube plein opaque ;
/// - `out` : `out_len` entiers. `out[0]` et `out[1]` reçoivent les moitiés
///   basse et haute de la visibilité (`VisibilityEncoding`), `out[2]` le
///   volume à dessiner (`PackedBox`), la suite les boîtes occultantes
///   (`PackedBox`, axes du monde).
///
/// Rend le nombre de boîtes écrites, −1 si la section n'a rien à dessiner ni
/// à occulter (seule la visibilité est alors écrite), −2 en cas d'erreur.
/// Les boîtes qui ne tiennent pas dans `out` sont laissées de côté : cela
/// coûte du culling, pas de l'exactitude.
///
/// Ne prend aucun verrou et ne rappelle jamais Java : Java l'appelle avec
/// l'option `critical` de FFM, sur ses propres tableaux.
///
/// # Safety
/// `blocks` doit pointer sur 4 096 `u16` lisibles, `flags` sur 65 536 octets
/// lisibles, `out` sur `out_len` `i32` inscriptibles.
#[no_mangle]
pub unsafe extern "C" fn yf_mesh_section_shape(blocks: *const u16, flags: *const u8, out: *mut i32, out_len: i32) -> i32 {
    if blocks.is_null() || flags.is_null() || out.is_null() || (out_len as usize) < HEADER || out_len < 0 {
        return SHAPE_FAILED;
    }
    let blocks = &*(blocks as *const [u16; BLOCKS]);
    let flags = &*(flags as *const [u8; STATES]);
    let out = std::slice::from_raw_parts_mut(out, out_len as usize);

    // Une panique ne doit pas traverser la frontière : elle tuerait le jeu.
    let result = catch_unwind(AssertUnwindSafe(|| {
        WORKSPACE.with(|cell| {
            let (workspace, boxes) = &mut *cell.borrow_mut();
            let shape = workspace.section_shape(blocks, flags, boxes);

            out[0] = shape.visibility as u32 as i32;
            out[1] = (shape.visibility >> 32) as u32 as i32;
            out[2] = shape.bounds;
            if !shape.has_data {
                return SHAPE_NO_DATA;
            }
            let count = boxes.len().min(out.len() - HEADER);
            out[HEADER..HEADER + count].copy_from_slice(&boxes[..count]);
            count as i32
        })
    }));
    result.unwrap_or(SHAPE_FAILED)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn contrat_de_sortie() {
        let flags = vec![3u8; STATES];
        let blocks = vec![16u16; BLOCKS];
        let mut out = [0i32; 16];
        let count = unsafe { yf_mesh_section_shape(blocks.as_ptr(), flags.as_ptr(), out.as_mut_ptr(), out.len() as i32) };
        // Section pleine : on ne voit rien à travers, une seule boîte, la section entière.
        assert_eq!(count, 1);
        assert_eq!((out[0], out[1]), (0, 0));
        assert_eq!(out[2], out[3]);

        let empty = vec![0u8; STATES];
        let count = unsafe { yf_mesh_section_shape(blocks.as_ptr(), empty.as_ptr(), out.as_mut_ptr(), out.len() as i32) };
        assert_eq!(count, SHAPE_NO_DATA);
        assert_ne!(out[0], 0);

        let count = unsafe { yf_mesh_section_shape(blocks.as_ptr(), flags.as_ptr(), out.as_mut_ptr(), 2) };
        assert_eq!(count, SHAPE_FAILED);
        let count = unsafe { yf_mesh_section_shape(std::ptr::null(), flags.as_ptr(), out.as_mut_ptr(), 16) };
        assert_eq!(count, SHAPE_FAILED);
    }
}
