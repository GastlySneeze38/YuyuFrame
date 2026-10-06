//! Quels blocs d'une section peuvent avoir quelque chose à dessiner.
//!
//! La plupart des blocs d'un monde sont enterrés : un cube plein opaque entre
//! six cubes pleins opaques, ou de l'eau au milieu de l'eau. Vanilla — et
//! notre rendu avant le 2026-10-06 — demandait quand même au jeu le modèle de
//! chacun, puis ses six faces, pour conclure qu'il n'y a rien à dessiner :
//! les trois quarts des sections maillées ne produisaient aucun quad.
//!
//! Ici, à partir des seuls identifiants de blocs, on rend la liste des blocs
//! « exposés ». Java ne parcourt plus que ceux-là.
//!
//! **Règle** — un bloc n'est écarté que dans deux cas, où il est certain que
//! rien de lui ne peut se voir :
//! - c'est un cube plein opaque, sans block entity, et ses six voisins sont
//!   des cubes pleins opaques (toutes ses faces sont cachées, et ce qu'un
//!   modèle aurait à l'intérieur du cube est derrière elles) ;
//! - c'est un fluide, et ses six voisins sont le même fluide (le premier test
//!   du rendu de fluides de vanilla).
//! Tout le reste est exposé, y compris ce qui se révélera vide.

use super::light::{LightReader, PROP_BLOCK_ENTITY, PROP_FLUID_SHIFT, PROP_FULL_OPAQUE, PROP_NON_AIR};

/// Un bit par bloc de la section, à l'indice `y << 8 | z << 4 | x`.
pub const MASK_WORDS: usize = 64;

/// La section et un bloc autour.
const SIDE: usize = 18;

/// Remplit `mask` et rend le nombre de blocs exposés.
pub fn exposed_blocks(light: &LightReader, mask: &mut [u64; MASK_WORDS]) -> u32 {
    mask.fill(0);

    // Les propriétés de chaque bloc, lues une fois : six voisins par bloc les reliraient sinon.
    let mut props = [0u32; SIDE * SIDE * SIDE];
    let at = |x: i32, y: i32, z: i32| ((y + 1) as usize * SIDE + (z + 1) as usize) * SIDE + (x + 1) as usize;
    for y in -1..17 {
        for z in -1..17 {
            for x in -1..17 {
                props[at(x, y, z)] = light.props_at(x, y, z);
            }
        }
    }

    let mut count = 0;
    for y in 0..16 {
        for z in 0..16 {
            for x in 0..16 {
                let center = at(x, y, z);
                let own = props[center];
                if own & PROP_NON_AIR == 0 {
                    continue;
                }
                let neighbours = [
                    props[center - 1],
                    props[center + 1],
                    props[center - SIDE],
                    props[center + SIDE],
                    props[center - SIDE * SIDE],
                    props[center + SIDE * SIDE],
                ];

                let fluid = (own >> PROP_FLUID_SHIFT) & 3;
                let buried = if fluid != 0 {
                    neighbours.iter().all(|&n| (n >> PROP_FLUID_SHIFT) & 3 == fluid)
                } else if own & PROP_FULL_OPAQUE != 0 && own & PROP_BLOCK_ENTITY == 0 {
                    neighbours.iter().all(|&n| n & PROP_FULL_OPAQUE != 0)
                } else {
                    false
                };
                if !buried {
                    let index = (y << 8 | z << 4 | x) as usize;
                    mask[index >> 6] |= 1 << (index & 63);
                    count += 1;
                }
            }
        }
    }
    count
}

#[cfg(test)]
mod tests {
    use super::super::light::tests::{props, set, workspace, STONE};
    use super::super::light::{FLUID_WATER, PROP_AO_SHIFT};
    use super::*;

    const WATER: u16 = 9 << 4;
    const GLASS: u16 = 20 << 4;
    const FURNACE: u16 = 61 << 4;

    fn table() -> Box<[u32; super::super::light::STATES]> {
        let mut props = props();
        let air_like = 4096 << PROP_AO_SHIFT;
        props[STONE as usize] |= PROP_NON_AIR;
        props[WATER as usize] = PROP_NON_AIR | FLUID_WATER << PROP_FLUID_SHIFT | air_like;
        props[GLASS as usize] = PROP_NON_AIR | air_like;
        props[FURNACE as usize] = props[STONE as usize] | PROP_BLOCK_ENTITY;
        props
    }

    fn is_set(mask: &[u64; MASK_WORDS], x: usize, y: usize, z: usize) -> bool {
        let index = y << 8 | z << 4 | x;
        mask[index >> 6] >> (index & 63) & 1 != 0
    }

    /// Un cube de pierre de 5 de côté : seul le bloc du milieu... non, les 27 du cœur sont enterrés.
    #[test]
    fn coeur_d_un_cube_de_pierre() {
        let props = table();
        let mut ws = workspace();
        for x in 4..9 {
            for y in 4..9 {
                for z in 4..9 {
                    set(&mut ws, x, y, z, STONE, 0, 0);
                }
            }
        }
        let light = LightReader::new(&mut ws, &props);
        let mut mask = [0u64; MASK_WORDS];
        // 125 blocs, dont les 27 du cœur ne touchent que de la pierre.
        assert_eq!(exposed_blocks(&light, &mut mask), 125 - 27);
        assert!(is_set(&mask, 4, 6, 6));
        assert!(!is_set(&mask, 6, 6, 6));
        assert!(!is_set(&mask, 0, 0, 0));
    }

    /// Du verre ou un four au milieu de la pierre restent exposés, et le verre expose ses voisins.
    #[test]
    fn ce_qui_n_est_jamais_ecarte() {
        let props = table();
        let mut ws = workspace();
        for x in 2..9 {
            for y in 2..9 {
                for z in 2..9 {
                    set(&mut ws, x, y, z, STONE, 0, 0);
                }
            }
        }
        set(&mut ws, 4, 5, 5, GLASS, 0, 0);
        set(&mut ws, 7, 5, 5, FURNACE, 0, 0);
        let light = LightReader::new(&mut ws, &props);
        let mut mask = [0u64; MASK_WORDS];
        exposed_blocks(&light, &mut mask);

        assert!(is_set(&mask, 4, 5, 5), "le verre");
        assert!(is_set(&mask, 3, 5, 5) && is_set(&mask, 5, 5, 5), "la pierre contre le verre");
        assert!(is_set(&mask, 7, 5, 5), "le four, pour son block entity");
        // Le four est un cube opaque : il ne révèle pas ses voisins.
        assert!(!is_set(&mask, 7, 6, 5));
        assert!(!is_set(&mask, 5, 6, 6));
    }

    /// L'eau au milieu de l'eau est écartée ; la surface et le fond restent.
    #[test]
    fn interieur_d_un_volume_d_eau() {
        let props = table();
        let mut ws = workspace();
        for x in 0..16 {
            for z in 0..16 {
                set(&mut ws, x, 3, z, STONE, 0, 0);
                for y in 4..9 {
                    set(&mut ws, x, y, z, WATER, 0, 0);
                }
            }
        }
        let light = LightReader::new(&mut ws, &props);
        let mut mask = [0u64; MASK_WORDS];
        exposed_blocks(&light, &mut mask);

        assert!(is_set(&mask, 8, 8, 8), "la surface");
        assert!(is_set(&mask, 8, 4, 8), "le fond, contre la pierre");
        assert!(!is_set(&mask, 8, 6, 8), "le milieu");
        assert!(is_set(&mask, 0, 6, 8), "le bord de la section : la voisine est vide");
        assert!(is_set(&mask, 8, 3, 8), "la pierre sous l'eau");
    }
}
