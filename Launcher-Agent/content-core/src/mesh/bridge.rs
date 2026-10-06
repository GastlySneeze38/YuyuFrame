//! Les fonctions `yf_mesh_*` : des fonctions C ordinaires, que Java cherche
//! par leur nom dans la DLL (FFM). Leur contrat est ici ; un changement de
//! signature ou de sens se fait avec `ABI_VERSION`.

use std::cell::RefCell;
use std::panic::{catch_unwind, AssertUnwindSafe};

use super::emit::{emit_quads, Command, MAX_BUCKETS};
use super::light::{LightReader, SectionData, Workspace as LightWorkspace, WORKSPACE_BYTES};
use super::shape::{Workspace, BLOCKS, STATES};

/// Version du contrat. À incrémenter dès qu'une fonction change de signature
/// ou de sens : Java refuse une DLL qui n'annonce pas la version qu'il attend
/// (`NativeMesh.ABI_VERSION`), au lieu de l'appeler de travers.
///
/// 2 : écriture des quads (`yf_mesh_workspace_bytes`, `yf_mesh_begin`,
/// `yf_mesh_load_section`, `yf_mesh_emit_quads`).
const ABI_VERSION: u32 = 2;

/// Réponse de `yf_mesh_emit_quads` : tout est écrit.
const EMIT_OK: i32 = 0;
/// Réponse de `yf_mesh_emit_quads` : erreur interne ou arguments invalides, les tampons ne sont pas à lire.
const EMIT_FAILED: i32 = -2;

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

// ── Écriture des quads ───────────────────────────────────────────────────────
//
// Une section se maille en trois temps, depuis un même fil :
// `yf_mesh_begin`, puis `yf_mesh_load_section` pour chacune des 27 sections
// copiées, puis `yf_mesh_emit_quads`. Le noyau ne garde rien d'un appel à
// l'autre : tout tient dans l'espace de travail, alloué par Java.

/// Taille, en octets, de l'espace de travail que Java alloue par fil de
/// construction (mémoire à zéro, alignée sur 8) et tend aux appels suivants.
#[no_mangle]
pub extern "C" fn yf_mesh_workspace_bytes() -> i32 {
    WORKSPACE_BYTES as i32
}

/// Commence une section : `base_*` est son coin en blocs du monde,
/// `has_sky_light` vaut 0 dans une dimension sans ciel.
///
/// # Safety
/// `workspace` doit pointer sur `yf_mesh_workspace_bytes()` octets inscriptibles, alignés sur 8.
#[no_mangle]
pub unsafe extern "C" fn yf_mesh_begin(workspace: *mut u8, base_x: i32, base_y: i32, base_z: i32, has_sky_light: i32) {
    if workspace.is_null() {
        return;
    }
    (*(workspace as *mut LightWorkspace)).begin(base_x, base_y, base_z, has_sky_light != 0);
}

/// Tend une des 27 sections copiées. `slot` : son rang dans le tableau de
/// `ChunkRenderContext` (`dx + 3 * (dz + 3 * dy)`, 13 = la section à mailler).
///
/// Les quatre tableaux sont ceux de `SectionSnapshot`, tels quels, et peuvent
/// être nuls : `blocks` (4 096 états) nul = section vide, dont `heights` (256
/// hauteurs de colonnes) dit alors où commence le ciel ; `block_light` et
/// `sky_light` (2 048 octets, 4 bits par bloc).
///
/// # Safety
/// Chaque pointeur non nul doit pointer sur un tableau lisible de la taille dite.
#[no_mangle]
pub unsafe extern "C" fn yf_mesh_load_section(
    workspace: *mut u8,
    slot: i32,
    blocks: *const u16,
    block_light: *const u8,
    sky_light: *const u8,
    heights: *const i32,
) {
    if workspace.is_null() || !(0..27).contains(&slot) {
        return;
    }
    let data = SectionData {
        blocks: (blocks as *const [u16; 4096]).as_ref(),
        block_light: (block_light as *const [u8; 2048]).as_ref(),
        sky_light: (sky_light as *const [u8; 2048]).as_ref(),
        heights: (heights as *const [i32; 256]).as_ref(),
    };
    let slot = slot as usize;
    (*(workspace as *mut LightWorkspace)).load_section(slot % 3, slot / 9, (slot / 3) % 3, &data);
}

/// Calcule la lumière des quads commandés et écrit leurs sommets.
///
/// - `props` : 65 536 mots, un par état de bloc (`PROP_*` dans light.rs) ;
/// - `commands` : `command_count` commandes de quatre mots (`Command` dans emit.rs) ;
/// - `dests` : `bucket_count` adresses, une par tampon de destination. Chaque
///   tampon doit avoir la place pour les quads qui lui reviennent : 4 sommets
///   de 32 octets par quad, écrits à la suite depuis l'adresse donnée ;
/// - `section_index` : recopié dans chaque sommet.
///
/// Rend 0, ou −2 en cas d'erreur (les tampons ne sont alors pas à lire).
///
/// Ne prend aucun verrou et ne rappelle jamais Java (option `critical` de FFM).
///
/// # Safety
/// Les tableaux doivent avoir les tailles dites, chaque commande porter
/// l'adresse d'un quad valide, et `workspace` avoir été préparé par
/// `yf_mesh_begin` et `yf_mesh_load_section`.
#[no_mangle]
pub unsafe extern "C" fn yf_mesh_emit_quads(
    workspace: *mut u8,
    props: *const u32,
    commands: *const u32,
    command_count: i32,
    dests: *const i64,
    bucket_count: i32,
    section_index: i32,
) -> i32 {
    if workspace.is_null() || props.is_null() || commands.is_null() || dests.is_null() {
        return EMIT_FAILED;
    }
    if command_count < 0 || bucket_count < 0 || bucket_count as usize > MAX_BUCKETS {
        return EMIT_FAILED;
    }
    let props = &*(props as *const [u32; super::light::STATES]);
    let commands = std::slice::from_raw_parts(commands as *const Command, command_count as usize);
    let bucket_count = bucket_count as usize;

    let mut cursors = [std::ptr::null_mut::<u8>(); MAX_BUCKETS];
    for (bucket, cursor) in cursors.iter_mut().enumerate().take(bucket_count) {
        *cursor = dests.add(bucket).read_unaligned() as usize as *mut u8;
    }
    // Une commande qui vise un tampon absent écrirait n'importe où.
    let valid = commands.iter().all(|command| {
        let bucket = ((command.packed >> super::emit::CMD_BUCKET_SHIFT) & super::emit::CMD_BUCKET_MASK) as usize;
        bucket < bucket_count && !cursors[bucket].is_null()
    });
    if !valid {
        return EMIT_FAILED;
    }

    let workspace = &mut *(workspace as *mut LightWorkspace);
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut light = LightReader::new(workspace, props);
        emit_quads(&mut light, commands, &mut cursors[..bucket_count], section_index as u32);
        EMIT_OK
    }));
    result.unwrap_or(EMIT_FAILED)
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
