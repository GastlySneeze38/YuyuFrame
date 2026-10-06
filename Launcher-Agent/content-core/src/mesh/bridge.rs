//! Les fonctions `yf_mesh_*` : des fonctions C ordinaires, que Java cherche
//! par leur nom dans la DLL (FFM). Leur contrat est ici ; un changement de
//! signature ou de sens se fait avec `ABI_VERSION`.

use std::cell::RefCell;
use std::panic::{catch_unwind, AssertUnwindSafe};

use super::emit::{emit_quads, prepare, Command, FluidBatch, MAX_BUCKETS};
use super::expose::{exposed_blocks, MASK_WORDS};
use super::fluid::{FluidData, FLUID_DATA_LEN};
use super::light::{LightReader, SectionData, Workspace as LightWorkspace, WORKSPACE_BYTES};
use super::shape::{Workspace, BLOCKS, STATES};

/// Version du contrat. À incrémenter dès qu'une fonction change de signature
/// ou de sens : Java refuse une DLL qui n'annonce pas la version qu'il attend
/// (`NativeMesh.ABI_VERSION`), au lieu de l'appeler de travers.
///
/// 2 : écriture des quads (`yf_mesh_workspace_bytes`, `yf_mesh_begin`,
/// `yf_mesh_load_section`, `yf_mesh_emit_quads`).
/// 3 : fluides et passes triées (`yf_mesh_prepare`, `yf_mesh_fluid_data_len`) ;
/// `yf_mesh_emit_quads` attend désormais un `yf_mesh_prepare` juste avant.
/// 4 : blocs exposés (`yf_mesh_exposed`) ; deux bits de plus dans la table des
/// propriétés (tout sauf l'air, block entity), l'occlusion ambiante sur 13 bits.
/// 5 : préparation en deux temps — `yf_mesh_load_section` remplacé par
/// `yf_mesh_load_states`, `yf_mesh_begin_light` et `yf_mesh_load_light`.
const ABI_VERSION: u32 = 5;

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
    // Les quads de fluides de la section en cours, entre son comptage et son écriture.
    static FLUIDS: RefCell<FluidBatch> = RefCell::new(FluidBatch::default());
}

/// Relève les blocs de la section qui peuvent avoir quelque chose à dessiner
/// (règle dans expose.rs) : Java ne parcourt que ceux-là.
///
/// - `workspace` : préparé par `yf_mesh_begin` et `yf_mesh_load_states` ;
/// - `props` : la table des propriétés par état ;
/// - `mask` : 64 mots de 64 bits, un bit par bloc à l'indice `y << 8 | z << 4 | x`.
///
/// Rend le nombre de blocs exposés, ou −2 en cas d'erreur.
///
/// # Safety
/// `workspace` et `props` comme pour `yf_mesh_emit_quads` ; `mask` doit pointer sur 64 `u64` inscriptibles.
#[no_mangle]
pub unsafe extern "C" fn yf_mesh_exposed(workspace: *mut u8, props: *const u32, mask: *mut u64) -> i32 {
    if workspace.is_null() || props.is_null() || mask.is_null() {
        return EMIT_FAILED;
    }
    let props = &*(props as *const [u32; super::light::STATES]);
    let mask = &mut *(mask as *mut [u64; MASK_WORDS]);
    let workspace = &mut *(workspace as *mut LightWorkspace);
    let result = catch_unwind(AssertUnwindSafe(|| exposed_blocks(&LightReader::new(workspace, props), mask) as i32));
    result.unwrap_or(EMIT_FAILED)
}

/// Longueur du tableau `fluid_data` de `yf_mesh_prepare`, pour que Java vérifie la sienne.
#[no_mangle]
pub extern "C" fn yf_mesh_fluid_data_len() -> i32 {
    FLUID_DATA_LEN as i32
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
// Une section se maille depuis un même fil, dans cet ordre :
//   1. `yf_mesh_begin`, puis `yf_mesh_load_states` pour chacune des 27 sections ;
//   2. `yf_mesh_exposed` : les blocs qui peuvent avoir quelque chose à dessiner ;
//   3. s'il y a des quads à écrire : `yf_mesh_begin_light`, `yf_mesh_load_light`
//      pour chacune des 27, `yf_mesh_prepare`, `yf_mesh_emit_quads`.
// La plupart des sections s'arrêtent au 2. Tout tient dans l'espace de
// travail, alloué par Java ; seuls les quads de fluides sont gardés ici entre
// `yf_mesh_prepare` et `yf_mesh_emit_quads`.

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

/// Premier temps : tend les blocs d'une des 27 sections copiées. À appeler
/// pour **chacune** des 27, absentes comprises (rien n'est remis à zéro
/// d'avance). `slot` : son rang dans le tableau de `ChunkRenderContext`
/// (`dx + 3 * (dz + 3 * dy)`, 13 = la section à mailler). `blocks` : les
/// 4 096 états de `SectionSnapshot`, ou nul pour une section vide ou absente.
///
/// Après les 27, `yf_mesh_exposed` peut être appelé : il ne lit que les blocs.
///
/// # Safety
/// `blocks`, s'il n'est pas nul, doit pointer sur 4 096 `u16` lisibles.
#[no_mangle]
pub unsafe extern "C" fn yf_mesh_load_states(workspace: *mut u8, slot: i32, blocks: *const u16) {
    if workspace.is_null() || !(0..27).contains(&slot) {
        return;
    }
    let slot = slot as usize;
    (*(workspace as *mut LightWorkspace)).load_states(slot % 3, slot / 9, (slot / 3) % 3, (blocks as *const [u16; 4096]).as_ref());
}

/// Second temps, seulement s'il y a des quads à écrire : commence la lumière
/// de la section. À faire suivre de `yf_mesh_load_light` pour chacune des 27
/// sections, puis de `yf_mesh_prepare` et `yf_mesh_emit_quads`.
///
/// # Safety
/// `workspace` comme pour `yf_mesh_begin`.
#[no_mangle]
pub unsafe extern "C" fn yf_mesh_begin_light(workspace: *mut u8) {
    if workspace.is_null() {
        return;
    }
    (*(workspace as *mut LightWorkspace)).begin_light();
}

/// Tend la lumière d'une des 27 sections copiées — pour chacune, absentes
/// comprises. Les tableaux sont ceux de `SectionSnapshot`, tels quels, et
/// peuvent être nuls : `blocks` nul = section vide, dont `heights` (256
/// hauteurs de colonnes) dit alors où commence le ciel ; `block_light` et
/// `sky_light` (2 048 octets, 4 bits par bloc).
///
/// # Safety
/// Chaque pointeur non nul doit pointer sur un tableau lisible de la taille dite.
#[no_mangle]
pub unsafe extern "C" fn yf_mesh_load_light(
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
    (*(workspace as *mut LightWorkspace)).load_light(slot % 3, slot / 9, (slot / 3) % 3, &data);
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
/// `yf_mesh_begin`, `yf_mesh_load_states` et `yf_mesh_load_light`.
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

    let workspace = &mut *(workspace as *mut LightWorkspace);
    let result = catch_unwind(AssertUnwindSafe(|| {
        FLUIDS.with(|cell| {
            let fluids = cell.borrow();
            let mut light = LightReader::new(workspace, props);
            // Une commande qui vise un tampon absent est refusée plutôt qu'écrite n'importe où.
            if emit_quads(&mut light, commands, &mut cursors[..bucket_count], section_index as u32, &fluids) {
                EMIT_OK
            } else {
                EMIT_FAILED
            }
        })
    }));
    result.unwrap_or(EMIT_FAILED)
}

/// Compte les quads que `yf_mesh_emit_quads` écrira dans chaque tampon, pour
/// que Java y réserve la place. À appeler juste avant, depuis le même fil,
/// avec les mêmes commandes : les quads des fluides sont calculés ici (leur
/// nombre ne se connaît qu'en les faisant) et gardés pour l'écriture.
///
/// - `fluid_data` : les textures des fluides et la table du courant
///   (`FLUID_DATA_LEN` flottants, voir fluid.rs) ; peut être nul s'il n'y a
///   aucune commande de fluide ;
/// - `counts` : `bucket_count` entiers, remplis du nombre de quads de chaque tampon.
///
/// Rend 0, ou −2 en cas d'erreur.
///
/// # Safety
/// Comme `yf_mesh_emit_quads` ; `counts` doit pointer sur `bucket_count` `i32` inscriptibles.
#[no_mangle]
pub unsafe extern "C" fn yf_mesh_prepare(
    workspace: *mut u8,
    props: *const u32,
    fluid_data: *const f32,
    commands: *const u32,
    command_count: i32,
    counts: *mut i32,
    bucket_count: i32,
    section_index: i32,
) -> i32 {
    if workspace.is_null() || props.is_null() || commands.is_null() || counts.is_null() {
        return EMIT_FAILED;
    }
    if command_count < 0 || bucket_count < 0 || bucket_count as usize > MAX_BUCKETS {
        return EMIT_FAILED;
    }
    let props = &*(props as *const [u32; super::light::STATES]);
    let commands = std::slice::from_raw_parts(commands as *const Command, command_count as usize);
    let counts = std::slice::from_raw_parts_mut(counts, bucket_count as usize);
    let fluid_data = (fluid_data as *const [f32; FLUID_DATA_LEN]).as_ref().map(|values| FluidData { values });

    let workspace = &mut *(workspace as *mut LightWorkspace);
    let result = catch_unwind(AssertUnwindSafe(|| {
        FLUIDS.with(|cell| {
            let light = LightReader::new(workspace, props);
            if prepare(&light, fluid_data.as_ref(), commands, counts, section_index as u32, &mut cell.borrow_mut()) {
                EMIT_OK
            } else {
                EMIT_FAILED
            }
        })
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
