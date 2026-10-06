//! Les fonctions `yf_sort_*` : des fonctions C ordinaires, que Java cherche
//! par leur nom dans la DLL (FFM). Leur contrat est ici ; un changement de
//! signature ou de sens se fait avec `ABI_VERSION`.
//!
//! Aucune ne prend de verrou ni ne rappelle Java : Java les appelle avec
//! l'option `critical` de FFM, sur ses propres tableaux.

use std::cell::RefCell;
use std::panic::{catch_unwind, AssertUnwindSafe};

use super::quads::{descending, QuadSet};
use super::tree::{analyze, flatten, read, SortResult};

/// Version du contrat (`NativeSort.ABI_VERSION` côté Java).
const ABI_VERSION: u32 = 1;

const FAILED: i32 = -2;

/// Les genres de tri, dans l'ordre de `SortState.DEBUG_NAMES` côté Java.
const KIND_NONE: i32 = 0;
const KIND_STATIC: i32 = 1;
const KIND_TREE: i32 = 2;
const KIND_DYNAMIC: i32 = 3;

/// Cases de l'en-tête rempli par `yf_sort_analyze`.
const HEADER: usize = 6;

/// Le résultat d'une analyse, entre `yf_sort_analyze` et `yf_sort_take`.
#[derive(Default)]
struct Pending {
    quads: QuadSet,
    ints: Vec<u32>,
    floats: Vec<f32>,
}

thread_local! {
    static PENDING: RefCell<Pending> = RefCell::new(Pending::default());
}

#[no_mangle]
pub extern "C" fn yf_sort_abi() -> u32 {
    ABI_VERSION
}

/// Analyse les quads translucides d'une section et choisit leur tri.
///
/// - `vertices` : les sommets, quatre par quad, la position en trois
///   flottants au début de chacun ; `stride` octets d'un sommet au suivant ;
/// - `header` : six entiers, remplis ainsi : le genre de tri, le nombre
///   d'entiers et le nombre de flottants à venir chercher par `yf_sort_take`,
///   puis le nombre de plans de coupe sur chacun des trois axes.
///
/// Ce qu'il y a à venir chercher, selon le genre :
/// - 0 (rien à trier) : rien ;
/// - 1 (ordre fixe) : entiers = les quads dans l'ordre où les dessiner ;
/// - 2 (arbre) : entiers = l'arbre à plat (voir tree.rs), flottants = les
///   plans de coupe des axes x, y, z à la suite, triés ;
/// - 3 (tri par distance) : flottants = les centres des quads, trois par quad.
///
/// Rend le genre, ou −2 en cas d'erreur.
///
/// # Safety
/// `vertices` doit pointer sur `quad_count * 4` sommets de `stride` octets, `header` sur six `i32`.
#[no_mangle]
pub unsafe extern "C" fn yf_sort_analyze(vertices: *const u8, quad_count: i32, stride: i32, header: *mut i32) -> i32 {
    if vertices.is_null() || header.is_null() || quad_count < 0 || stride < 12 {
        return FAILED;
    }
    let header = std::slice::from_raw_parts_mut(header, HEADER);
    let (quad_count, stride) = (quad_count as usize, stride as usize);

    let result = catch_unwind(AssertUnwindSafe(|| {
        PENDING.with(|cell| {
            let pending = &mut *cell.borrow_mut();
            pending.quads.clear();
            pending.ints.clear();
            pending.floats.clear();

            for quad in 0..quad_count {
                let mut positions = [[0.0f32; 3]; 4];
                for (vertex, position) in positions.iter_mut().enumerate() {
                    let at = vertices.add((quad * 4 + vertex) * stride) as *const f32;
                    *position = [at.read_unaligned(), at.add(1).read_unaligned(), at.add(2).read_unaligned()];
                }
                pending.quads.push(&positions);
            }

            let mut cuts = [0i32; 3];
            let kind = match analyze(&pending.quads) {
                SortResult::None => KIND_NONE,
                SortResult::Static(order) => {
                    pending.ints = order;
                    KIND_STATIC
                }
                SortResult::Tree(tree) => {
                    flatten(&tree.root, &mut pending.ints);
                    for (axis, planes) in tree.cut_planes.iter().enumerate() {
                        cuts[axis] = planes.len() as i32;
                        pending.floats.extend_from_slice(planes);
                    }
                    KIND_TREE
                }
                SortResult::Dynamic => {
                    pending.floats.extend_from_slice(&pending.quads.centers);
                    KIND_DYNAMIC
                }
            };
            header.copy_from_slice(&[kind, pending.ints.len() as i32, pending.floats.len() as i32, cuts[0], cuts[1], cuts[2]]);
            kind
        })
    }));
    result.unwrap_or(FAILED)
}

/// Recopie le résultat de la dernière analyse faite sur ce fil. Les deux
/// tableaux doivent avoir les longueurs annoncées dans l'en-tête ; un tableau
/// de longueur nulle peut être nul.
///
/// # Safety
/// `ints` et `floats` doivent pointer sur autant de cases que l'en-tête en a annoncé.
#[no_mangle]
pub unsafe extern "C" fn yf_sort_take(ints: *mut u32, floats: *mut f32) {
    PENDING.with(|cell| {
        let pending = cell.borrow();
        if !ints.is_null() {
            std::ptr::copy_nonoverlapping(pending.ints.as_ptr(), ints, pending.ints.len());
        }
        if !floats.is_null() {
            std::ptr::copy_nonoverlapping(pending.floats.as_ptr(), floats, pending.floats.len());
        }
    });
}

/// Écrit un quad à la suite : ses indices de sommets, ou son numéro si `elements` vaut 1.
unsafe fn write_quad(out: *mut u32, position: usize, quad: u32, elements: usize) {
    let at = out.add(position * elements);
    let vertex = quad * 4;
    match elements {
        1 => at.write_unaligned(quad),
        4 => {
            for i in 0..4 {
                at.add(i).write_unaligned(vertex + i as u32);
            }
        }
        _ => {
            for (i, offset) in [0u32, 1, 2, 2, 3, 0].into_iter().enumerate() {
                at.add(i).write_unaligned(vertex + offset);
            }
        }
    }
}

/// Relit un arbre pour une position de caméra et écrit l'ordre de dessin.
///
/// - `tree` : `tree_len` entiers, l'arbre rendu par une analyse ;
/// - `cam_*` : la caméra, dans le repère de la section ;
/// - `out` : la place de `quad_count` quads, de `elements` entiers chacun ;
/// - `elements` : 6 = deux triangles par quad (indices de sommets), 4 = le
///   quad tel quel, 1 = seulement le numéro du quad.
///
/// Rend le nombre de quads écrits (il doit valoir `quad_count`), ou −2 si
/// l'arbre est invalide ou en contient davantage.
///
/// # Safety
/// `tree` doit pointer sur `tree_len` entiers, `out` sur `quad_count * elements` entiers.
#[no_mangle]
pub unsafe extern "C" fn yf_sort_tree_order(
    tree: *const u32,
    tree_len: i32,
    cam_x: f32,
    cam_y: f32,
    cam_z: f32,
    out: *mut u32,
    quad_count: i32,
    elements: i32,
) -> i32 {
    if tree.is_null() || out.is_null() || tree_len <= 0 || quad_count < 0 || !matches!(elements, 1 | 4 | 6) {
        return FAILED;
    }
    let flat = std::slice::from_raw_parts(tree, tree_len as usize);
    let (quad_count, elements) = (quad_count as usize, elements as usize);

    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut written = 0usize;
        let mut overflow = false;
        let mut sink = |quads: &[u32]| {
            for &quad in quads {
                // Un quad de trop, ou hors de la section : on n'écrit pas hors de la place donnée.
                if written >= quad_count || quad as usize >= quad_count {
                    overflow = true;
                    return;
                }
                write_quad(out, written, quad, elements);
                written += 1;
            }
        };
        if !read(flat, 0, [cam_x, cam_y, cam_z], &mut sink, 0) || overflow {
            return FAILED;
        }
        written as i32
    }));
    result.unwrap_or(FAILED)
}

/// Trie des quads par distance à la caméra, le plus loin d'abord, et écrit l'ordre de dessin.
///
/// `centers` : trois flottants par quad. `out` et `elements` : comme `yf_sort_tree_order`.
/// Rend 0, ou −2 en cas d'erreur.
///
/// # Safety
/// `centers` doit pointer sur `quad_count * 3` flottants, `out` sur `quad_count * elements` entiers.
#[no_mangle]
pub unsafe extern "C" fn yf_sort_distance_order(
    centers: *const f32,
    quad_count: i32,
    cam_x: f32,
    cam_y: f32,
    cam_z: f32,
    out: *mut u32,
    elements: i32,
) -> i32 {
    if centers.is_null() || out.is_null() || quad_count < 0 || !matches!(elements, 1 | 4 | 6) {
        return FAILED;
    }
    let centers = std::slice::from_raw_parts(centers, quad_count as usize * 3);
    let elements = elements as usize;

    let result = catch_unwind(AssertUnwindSafe(|| {
        let distances: Vec<f32> = centers
            .chunks_exact(3)
            .map(|c| {
                let (x, y, z) = (c[0] - cam_x, c[1] - cam_y, c[2] - cam_z);
                x * x + y * y + z * z
            })
            .collect();
        for (position, quad) in descending(&distances).into_iter().enumerate() {
            write_quad(out, position, quad, elements);
        }
        0
    }));
    result.unwrap_or(FAILED)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Trois carrés horizontaux empilés, sommets de 32 octets.
    fn stacked(heights: &[f32]) -> Vec<u8> {
        let mut bytes = Vec::new();
        for &y in heights {
            for (x, z) in [(0.0f32, 0.0f32), (0.0, 1.0), (1.0, 1.0), (1.0, 0.0)] {
                for value in [x, y, z] {
                    bytes.extend_from_slice(&value.to_le_bytes());
                }
                bytes.extend_from_slice(&[0u8; 20]);
            }
        }
        bytes
    }

    #[test]
    fn analyse_puis_recuperation() {
        let vertices = stacked(&[2.0, 9.0, 5.0]);
        let mut header = [0i32; HEADER];
        let kind = unsafe { yf_sort_analyze(vertices.as_ptr(), 3, 32, header.as_mut_ptr()) };
        assert_eq!(kind, KIND_STATIC);
        assert_eq!(header, [KIND_STATIC, 3, 0, 0, 0, 0]);

        let mut order = [0u32; 3];
        unsafe { yf_sort_take(order.as_mut_ptr(), std::ptr::null_mut()) };
        assert_eq!(order, [0, 2, 1]);

        // Tout dans un plan : rien à trier, rien à venir chercher.
        let flat = stacked(&[4.0, 4.0]);
        let kind = unsafe { yf_sort_analyze(flat.as_ptr(), 2, 32, header.as_mut_ptr()) };
        assert_eq!((kind, header[1], header[2]), (KIND_NONE, 0, 0));

        assert_eq!(unsafe { yf_sort_analyze(std::ptr::null(), 2, 32, header.as_mut_ptr()) }, FAILED);
        assert_eq!(unsafe { yf_sort_analyze(flat.as_ptr(), 2, 8, header.as_mut_ptr()) }, FAILED);
    }

    #[test]
    fn tri_par_distance() {
        // Trois quads sur l'axe x, la caméra près du dernier.
        let centers = [0.0f32, 0.0, 0.0, 5.0, 0.0, 0.0, 10.0, 0.0, 0.0];
        let mut order = [0u32; 3];
        assert_eq!(unsafe { yf_sort_distance_order(centers.as_ptr(), 3, 9.0, 0.0, 0.0, order.as_mut_ptr(), 1) }, 0);
        assert_eq!(order, [0, 1, 2]);

        // Deux triangles par quad : 0-1-2 puis 2-3-0, sur les sommets du quad.
        let mut indices = [0u32; 18];
        assert_eq!(unsafe { yf_sort_distance_order(centers.as_ptr(), 3, 1.0, 0.0, 0.0, indices.as_mut_ptr(), 6) }, 0);
        assert_eq!(indices[..6], [8, 9, 10, 10, 11, 8]);
    }

    #[test]
    fn relecture_d_un_arbre() {
        // Une feuille de deux quads.
        let tree = [0u32, 2, 1, 0];
        let mut out = [9u32; 2];
        assert_eq!(unsafe { yf_sort_tree_order(tree.as_ptr(), 4, 0.0, 0.0, 0.0, out.as_mut_ptr(), 2, 1) }, 2);
        assert_eq!(out, [1, 0]);
        // La même, annoncée pour un seul quad : refusée plutôt qu'écrite hors de la place.
        assert_eq!(unsafe { yf_sort_tree_order(tree.as_ptr(), 4, 0.0, 0.0, 0.0, out.as_mut_ptr(), 1, 1) }, FAILED);
    }
}
