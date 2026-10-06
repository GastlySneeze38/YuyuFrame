//! La « forme » d'une section, déduite de ses seuls identifiants de blocs :
//! par quelles faces on voit à travers elle, quel volume contient quelque
//! chose à dessiner, et quelles boîtes pleines bouchent la vue.
//!
//! Même résultat que le Java qu'il remplace (`ChunkOcclusionDataBuilder` pour
//! la visibilité, `SectionVisibilityBuilder` pour le reste) — aux boîtes de
//! même volume près, voir `boxes.rs`.

use super::boxes::{pack, BoxFinder, RANGE_EXTREME, WORDS};

pub const BLOCKS: usize = 4096;
/// Une entrée par identifiant d'état de bloc (`identifiant << 4 | métadonnée`).
pub const STATES: usize = 65536;

/// Le bloc se dessine ou compte dans le volume de la section (tout sauf l'air).
/// Le calcul ne teste que « différent de zéro » : la constante dit le contrat de la table.
#[allow(dead_code)]
pub const FLAG_RENDERABLE: u8 = 1;
/// Le bloc est un cube plein opaque : il bouche la vue.
pub const FLAG_OPAQUE: u8 = 2;

// Les six faces, dans l'ordre de `GraphDirection` côté Java.
const DOWN: u32 = 0;
const UP: u32 = 1;
const NORTH: u32 = 2;
const SOUTH: u32 = 3;
const WEST: u32 = 4;
const EAST: u32 = 5;

/// Bit « depuis la face `from`, on voit la face `to` » : comme `VisibilityEncoding.bit`.
const fn pair(from: u32, to: u32) -> u64 {
    1 << (from * 8 + to)
}

const fn everything() -> u64 {
    let mut bits = 0;
    let mut from = 0;
    while from < 6 {
        let mut to = 0;
        while to < 6 {
            bits |= pair(from, to);
            to += 1;
        }
        from += 1;
    }
    bits
}

pub const VISIBILITY_NONE: u64 = 0;
pub const VISIBILITY_EVERYTHING: u64 = everything();

/// Toutes les paires de faces d'un ensemble de faces (6 bits).
fn pairs_of(faces: u32) -> u64 {
    let mut bits = 0;
    for from in 0..6 {
        if faces & (1 << from) != 0 {
            bits |= (faces as u64) << (from * 8);
        }
    }
    bits
}

pub struct Shape {
    pub visibility: u64,
    /// Le volume à dessiner, élargi d'un bloc ; rangé comme `PackedBox`.
    pub bounds: i32,
    /// Faux : la section n'a ni bloc à dessiner ni bloc opaque (Java range alors `null`).
    pub has_data: bool,
}

/// L'espace de travail d'un fil de construction.
pub struct Workspace {
    finder: BoxFinder,
    stack: Vec<u16>,
}

impl Default for Workspace {
    fn default() -> Self {
        Self::new()
    }
}

impl Workspace {
    pub fn new() -> Self {
        Workspace { finder: BoxFinder::new(), stack: Vec::with_capacity(BLOCKS) }
    }

    /// `blocks` : les 4 096 états de la section, à l'indice `y << 8 | z << 4 | x`.
    /// `boxes` est vidé puis rempli des boîtes occultantes.
    pub fn section_shape(&mut self, blocks: &[u16; BLOCKS], flags: &[u8; STATES], boxes: &mut Vec<i32>) -> Shape {
        boxes.clear();

        let mut opaque = [0u64; WORDS];
        let mut opaque_count = 0;
        // Bornes du volume à dessiner. Un masque par axe : bit n = « quelque chose à la coordonnée n ».
        let (mut seen_x, mut seen_y, mut seen_z) = (0u32, 0u32, 0u32);

        for (index, &state) in blocks.iter().enumerate() {
            let flag = flags[state as usize];
            if flag == 0 {
                continue;
            }
            seen_x |= 1 << (index & 15);
            seen_z |= 1 << ((index >> 4) & 15);
            seen_y |= 1 << (index >> 8);
            if flag & FLAG_OPAQUE != 0 {
                opaque[index >> 6] |= 1 << (index & 63);
                opaque_count += 1;
            }
        }

        let visibility = if opaque_count == BLOCKS {
            VISIBILITY_NONE
        } else if opaque_count < 256 {
            // Pas de quoi boucher ne serait-ce qu'une face : on voit tout depuis partout.
            VISIBILITY_EVERYTHING
        } else {
            self.flood_fill(&opaque)
        };

        if seen_x == 0 {
            return Shape { visibility, bounds: pack(0, 0, 0, 16, 16, 16, RANGE_EXTREME), has_data: false };
        }

        let low = |seen: u32| (seen.trailing_zeros() as i32 - 1).max(0);
        let high = |seen: u32| (33 - seen.leading_zeros() as i32).min(16);
        let bounds = pack(low(seen_x), low(seen_y), low(seen_z), high(seen_x), high(seen_y), high(seen_z), RANGE_EXTREME);

        if opaque_count > 0 {
            self.finder.occluder_boxes(&opaque, boxes);
        }

        Shape { visibility, bounds, has_data: true }
    }

    /// Depuis chaque bloc non opaque du bord, on se répand dans le vide : les
    /// faces atteintes par une même poche se voient l'une l'autre.
    fn flood_fill(&mut self, opaque: &[u64; WORDS]) -> u64 {
        let mut visited = *opaque;
        let mut result = 0u64;
        let stack = &mut self.stack;

        let seen = |visited: &mut [u64; WORDS], index: usize| -> bool {
            let word = &mut visited[index >> 6];
            let bit = 1u64 << (index & 63);
            let was = *word & bit != 0;
            *word |= bit;
            was
        };

        for start in 0..BLOCKS {
            let (x, z, y) = (start & 15, (start >> 4) & 15, start >> 8);
            if !(x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15) {
                continue;
            }
            if seen(&mut visited, start) {
                continue;
            }

            let mut faces = 0u32;
            stack.clear();
            stack.push(start as u16);

            while let Some(index) = stack.pop() {
                let index = index as usize;
                let (x, z, y) = (index & 15, (index >> 4) & 15, index >> 8);

                if x == 0 {
                    faces |= 1 << WEST;
                } else if !seen(&mut visited, index - 1) {
                    stack.push((index - 1) as u16);
                }
                if x == 15 {
                    faces |= 1 << EAST;
                } else if !seen(&mut visited, index + 1) {
                    stack.push((index + 1) as u16);
                }
                if z == 0 {
                    faces |= 1 << NORTH;
                } else if !seen(&mut visited, index - 16) {
                    stack.push((index - 16) as u16);
                }
                if z == 15 {
                    faces |= 1 << SOUTH;
                } else if !seen(&mut visited, index + 16) {
                    stack.push((index + 16) as u16);
                }
                if y == 0 {
                    faces |= 1 << DOWN;
                } else if !seen(&mut visited, index - 256) {
                    stack.push((index - 256) as u16);
                }
                if y == 15 {
                    faces |= 1 << UP;
                } else if !seen(&mut visited, index + 256) {
                    stack.push((index + 256) as u16);
                }
            }

            result |= pairs_of(faces);
            if result == VISIBILITY_EVERYTHING {
                break;
            }
        }

        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const AIR: u16 = 0;
    const STONE: u16 = 1 << 4;
    const GLASS: u16 = 20 << 4;

    fn flags() -> Box<[u8; STATES]> {
        let mut flags: Box<[u8; STATES]> = vec![0u8; STATES].into_boxed_slice().try_into().unwrap();
        flags[STONE as usize] = FLAG_RENDERABLE | FLAG_OPAQUE;
        flags[GLASS as usize] = FLAG_RENDERABLE;
        flags
    }

    fn shape(blocks: &[u16; BLOCKS]) -> (Shape, Vec<i32>) {
        let mut boxes = Vec::new();
        let shape = Workspace::new().section_shape(blocks, &flags(), &mut boxes);
        (shape, boxes)
    }

    #[test]
    fn section_vide() {
        let (shape, boxes) = shape(&[AIR; BLOCKS]);
        assert_eq!(shape.visibility, VISIBILITY_EVERYTHING);
        assert!(!shape.has_data);
        assert!(boxes.is_empty());
    }

    #[test]
    fn section_pleine() {
        let (shape, boxes) = shape(&[STONE; BLOCKS]);
        assert_eq!(shape.visibility, VISIBILITY_NONE);
        assert!(shape.has_data);
        assert_eq!(shape.bounds, pack(0, 0, 0, 16, 16, 16, RANGE_EXTREME));
        assert_eq!(boxes, vec![pack(0, 0, 0, 16, 16, 16, RANGE_EXTREME)]);
    }

    #[test]
    fn un_bloc_de_verre() {
        let mut blocks = [AIR; BLOCKS];
        blocks[5 << 8 | 7 << 4 | 3] = GLASS;
        let (shape, boxes) = shape(&blocks);
        assert_eq!(shape.visibility, VISIBILITY_EVERYTHING);
        assert!(shape.has_data);
        // x = 3, y = 5, z = 7, élargi d'un bloc de chaque côté.
        assert_eq!(shape.bounds, pack(2, 4, 6, 5, 7, 9, RANGE_EXTREME));
        assert!(boxes.is_empty());
    }

    /// Un plancher plein coupe la section en deux : le haut ne voit pas le bas.
    #[test]
    fn plancher() {
        let mut blocks = [AIR; BLOCKS];
        for i in 0..256 {
            blocks[8 << 8 | i] = STONE;
        }
        let (shape, boxes) = shape(&blocks);

        let sides = 1 << NORTH | 1 << SOUTH | 1 << WEST | 1 << EAST;
        let expected = pairs_of(sides | 1 << UP) | pairs_of(sides | 1 << DOWN);
        assert_eq!(shape.visibility, expected);
        assert_eq!(shape.visibility & pair(UP, DOWN), 0);
        assert_eq!(shape.visibility & pair(DOWN, UP), 0);
        assert_ne!(shape.visibility & pair(UP, EAST), 0);

        assert_eq!(shape.bounds, pack(0, 7, 0, 16, 10, 16, RANGE_EXTREME));
        assert_eq!(boxes, vec![pack(0, 8, 0, 16, 9, 16, RANGE_EXTREME)]);
    }

    /// Une poche d'air enfermée dans la pierre ne relie aucune face.
    #[test]
    fn poche_fermee() {
        let mut blocks = [STONE; BLOCKS];
        blocks[8 << 8 | 8 << 4 | 8] = AIR;
        let (shape, _) = shape(&blocks);
        assert_eq!(shape.visibility, VISIBILITY_NONE);
    }

    #[test]
    fn toutes_les_paires() {
        assert_eq!(pairs_of(0b111111), VISIBILITY_EVERYTHING);
        assert_eq!(pairs_of(1 << UP), pair(UP, UP));
    }
}
