/*
 * Ported from Canvas (https://github.com/vram-guild/canvas), Copyright 2019, 2020 grondag.
 * Licensed under the GNU Lesser General Public License v3.0.
 */

//! Découpe les blocs opaques d'une section en boîtes disjointes, que le
//! culling par rastérisation dessine comme occulteurs.
//!
//! Port des classes `Area`, `AreaFinder`, `BoxFinder` et `OccluderBoxes` du
//! moteur Java (`celeritas/chunk/occlusion/geometry`), elles-mêmes venues de
//! Canvas. Même algorithme, deux écarts :
//! - l'ordre des rectangles de même surface et de même périmètre est fixé (en
//!   Java il dépend d'une table de hachage) — à volume égal, deux boîtes
//!   peuvent donc être choisies dans un autre ordre ;
//! - seuls les rectangles réellement rencontrés sont parcourus et remis à
//!   zéro, au lieu des 18 496 possibles à chaque passe.
//!
//! Une boîte ne couvre jamais un bloc non opaque ; des blocs opaques peuvent
//! rester hors de toute boîte. Cela coûte du culling, jamais de l'exactitude.
//!
//! L'entrée est rangée comme en Java : 64 mots, un bit par bloc à l'indice
//! `y << 8 | z << 4 | x`. Une « tranche » (4 mots) est donc une couche
//! horizontale ; les rectangles sont cherchés dans la couche (x, z) puis
//! étirés en hauteur.

use std::sync::OnceLock;

pub const WORDS: usize = 64;
const SLICE_WORDS: usize = 4;

const RANGE_NEAR: i32 = 0;
const RANGE_MID: i32 = 1;
const RANGE_FAR: i32 = 2;
pub const RANGE_EXTREME: i32 = 3;

/// En dessous, une boîte coûte un dessin pour une surface d'écran négligeable.
const MIN_VOLUME: i32 = 8;

/// Même rangement que `PackedBox.pack` côté Java : 5 bits par borne, puis la portée.
pub const fn pack(x0: i32, y0: i32, z0: i32, x1: i32, y1: i32, z1: i32, range: i32) -> i32 {
    x0 | (y0 << 5) | (z0 << 10) | (x1 << 15) | (y1 << 20) | (z1 << 25) | (range << 30)
}

// ── Rectangles d'une couche ──────────────────────────────────────────────────

// Clé d'un rectangle : x0 | x1 << 4 | y0 << 8 | y1 << 12, bornes incluses.
// « y » est ici le second axe de la couche (le z du monde).

fn key_x0(key: u32) -> i32 {
    (key & 15) as i32
}

fn key_x1(key: u32) -> i32 {
    ((key >> 4) & 15) as i32
}

fn key_y0(key: u32) -> i32 {
    ((key >> 8) & 15) as i32
}

fn key_y1(key: u32) -> i32 {
    ((key >> 12) & 15) as i32
}

fn key_of(x0: i32, y0: i32, x1: i32, y1: i32) -> u32 {
    (x0 | (x1 << 4) | (y0 << 8) | (y1 << 12)) as u32
}

fn key_size(key: u32) -> i32 {
    (key_x1(key) - key_x0(key) + 1) * (key_y1(key) - key_y0(key) + 1)
}

fn key_edges(key: u32) -> i32 {
    (key_x1(key) - key_x0(key) + 1) + (key_y1(key) - key_y0(key) + 1)
}

/// Tous les rectangles d'une couche 16×16, du plus grand au plus petit.
struct Areas {
    index_to_key: Vec<u16>,
    key_to_index: Vec<u16>,
    /// Les 256 bits de chaque rectangle.
    bits: Vec<[u64; 4]>,
    /// Les rectangles qui traversent la couche de part en part, dans un sens ou dans l'autre.
    sections: Vec<u16>,
}

fn areas() -> &'static Areas {
    static AREAS: OnceLock<Areas> = OnceLock::new();
    AREAS.get_or_init(build_areas)
}

fn build_areas() -> Areas {
    let mut keys: Vec<u16> = Vec::with_capacity(136 * 136);
    for x0 in 0..16 {
        for x1 in x0..16 {
            for y0 in 0..16 {
                for y1 in y0..16 {
                    keys.push(key_of(x0, y0, x1, y1) as u16);
                }
            }
        }
    }
    // Les plus grands d'abord ; à surface égale, les plus compacts.
    keys.sort_by_key(|&k| (-key_size(k as u32), key_edges(k as u32), k));

    let mut key_to_index = vec![0u16; 0x10000];
    for (index, &key) in keys.iter().enumerate() {
        key_to_index[key as usize] = index as u16;
    }

    let mut bits = Vec::with_capacity(keys.len());
    let mut sections = Vec::new();
    for (index, &key) in keys.iter().enumerate() {
        let key = key as u32;
        let (x0, x1, y0, y1) = (key_x0(key), key_x1(key), key_y0(key), key_y1(key));
        let row = ((0xFFFFu64 << x0) & (0xFFFFu64 >> (15 - x1))) & 0xFFFF;
        let mut words = [0u64; 4];
        for y in y0..=y1 {
            words[(y >> 2) as usize] |= row << ((y & 3) << 4);
        }
        bits.push(words);
        if (x0 == 0 && x1 == 15) || (y0 == 0 && y1 == 15) {
            sections.push(index as u16);
        }
    }

    Areas { index_to_key: keys, key_to_index, bits, sections }
}

fn included(sample: &[u64], area: &[u64; 4]) -> bool {
    (area[0] & sample[0]) == area[0]
        && (area[1] & sample[1]) == area[1]
        && (area[2] & sample[2]) == area[2]
        && (area[3] & sample[3]) == area[3]
}

fn intersects(sample: &[u64], area: &[u64; 4]) -> bool {
    ((area[0] & sample[0]) | (area[1] & sample[1]) | (area[2] & sample[2]) | (area[3] & sample[3])) != 0
}

fn get15(packed: u64, x: i32) -> i32 {
    ((packed >> (x << 2)) & 0xF) as i32
}

fn set15(packed: u64, x: i32, value: i32) -> u64 {
    let shift = x << 2;
    (packed & !(0xFu64 << shift)) | ((value as u64) << shift)
}

/// Le plus grand rectangle plein d'une couche (problème du « rectangle
/// maximal », par pile de hauteurs). Rend sa clé.
fn find_largest(bits: &[u64; 4]) -> u32 {
    let (mut best_x0, mut best_y0, mut best_x1, mut best_y1) = (0, 0, -1, -1);
    let mut best_area = 0;

    // Hauteur des colonnes de la rangée précédente, 4 bits par colonne.
    let mut heights: u64 = 0;

    for y in 0..16 {
        let row = ((bits[(y >> 2) as usize] >> ((y & 3) << 4)) & 0xFFFF) as u32;
        if row == 0 {
            heights = 0;
            continue;
        }

        // Les débuts de séries en attente : position (0-15) et hauteur (1-16, rangée moins un).
        let mut stack_x: u64 = 0;
        let mut stack_h: u64 = 0;
        let mut stack_size = 0;

        let mut run_height = if row & 1 == 0 { 0 } else { 1 + get15(heights, 0) };
        let mut run_start = 0;

        if y != 15 {
            heights = set15(heights, 0, run_height);
        }

        // Jusqu'à 16 inclus : la hauteur y vaut toujours zéro, ce qui ferme la dernière colonne.
        for x in 1..=16 {
            let h = if x == 16 || row & (1 << x) == 0 { 0 } else { 1 + get15(heights, x) };

            if h > run_height {
                if run_height != 0 {
                    stack_x = set15(stack_x, stack_size, run_start);
                    stack_h = set15(stack_h, stack_size, run_height - 1);
                    stack_size += 1;
                }
                run_start = x;
                run_height = h;
            } else {
                while h < run_height {
                    let area = (x - run_start) * run_height;
                    if area > best_area {
                        best_area = area;
                        best_x0 = run_start;
                        best_x1 = x - 1;
                        best_y0 = y - run_height + 1;
                        best_y1 = y;
                    }

                    if stack_size == 0 {
                        // La série à la nouvelle hauteur commence là où commençait celle qu'on ferme.
                        run_height = h;
                    } else {
                        stack_size -= 1;
                        let stack_start = get15(stack_x, stack_size);
                        let stack_height = 1 + get15(stack_h, stack_size);

                        if stack_height == h {
                            run_height = h;
                            run_start = stack_start;
                        } else if stack_height < h {
                            stack_size += 1;
                            run_height = h;
                        } else {
                            run_height = stack_height;
                            run_start = stack_start;
                        }
                    }
                }
            }

            if y != 15 && x < 16 {
                heights = set15(heights, x, h);
            }
        }
    }

    key_of(best_x0, best_y0, best_x1, best_y1)
}

// ── Boîtes ───────────────────────────────────────────────────────────────────

/// L'espace de travail d'un fil : gros, donc gardé d'une section à l'autre.
pub struct BoxFinder {
    source: [u64; WORDS],
    filled: [u64; WORDS],
    /// Pour chaque rectangle, les couches (bits 0 à 15) où il a été trouvé.
    area_slices: Vec<u16>,
    /// Les rectangles dont `area_slices` n'est pas nul.
    touched: Vec<u16>,
    sorted: Vec<u64>,
    boxes: Vec<i32>,
    voxel_count: i32,
}

impl Default for BoxFinder {
    fn default() -> Self {
        Self::new()
    }
}

impl BoxFinder {
    pub fn new() -> Self {
        BoxFinder {
            source: [0; WORDS],
            filled: [0; WORDS],
            area_slices: vec![0; areas().index_to_key.len()],
            touched: Vec::new(),
            sorted: Vec::new(),
            boxes: Vec::new(),
            voxel_count: 0,
        }
    }

    /// Les boîtes occultantes de la section, en axes du monde, les plus
    /// visibles de loin d'abord. `out` est vidé puis rempli.
    pub fn occluder_boxes(&mut self, opaque: &[u64; WORDS], out: &mut Vec<i32>) {
        out.clear();
        self.find_boxes(opaque);

        for range in (RANGE_NEAR..=RANGE_EXTREME).rev() {
            for &b in &self.boxes {
                let (x0, y0, z0) = (b & 31, (b >> 5) & 31, (b >> 10) & 31);
                let (x1, y1, z1) = ((b >> 15) & 31, (b >> 20) & 31, (b >> 25) & 31);
                let volume = (x1 - x0) * (y1 - y0) * (z1 - z0);
                if ((b >> 30) & 3) == range && volume >= MIN_VOLUME {
                    // La recherche range (x, z, y) : on remet les axes du monde.
                    out.push(pack(x0, z0, y0, x1, z1, y1, range));
                }
            }
        }
    }

    fn find_boxes(&mut self, opaque: &[u64; WORDS]) {
        self.filled = [0; WORDS];
        self.source = *opaque;
        self.boxes.clear();

        // D'abord les tranches entières et les colonnes qui traversent la section.
        self.mark_section_slices();
        self.build_sorted_sections();
        self.find_sections();

        self.clear_filled_bits();
        self.voxel_count = self.source.iter().map(|w| w.count_ones() as i32).sum();
        if self.voxel_count == 0 {
            return;
        }

        self.mark_box_slices();
        self.mark_box_neighbor_slices();
        self.build_sorted_boxes();
        self.find_disjoint_boxes();

        if self.voxel_count > 0 {
            self.clear_filled_bits();
            self.mark_box_slices();
            self.mark_box_neighbor_slices();
            self.build_sorted_boxes();
            self.find_disjoint_boxes();
        }
    }

    fn reset_slices(&mut self) {
        for &index in &self.touched {
            self.area_slices[index as usize] = 0;
        }
        self.touched.clear();
    }

    fn mark(&mut self, index: u16, mask: u16) {
        let slot = &mut self.area_slices[index as usize];
        if *slot == 0 {
            self.touched.push(index);
        }
        *slot |= mask;
    }

    fn mark_section_slices(&mut self) {
        let areas = areas();
        self.reset_slices();
        for layer in 0..16 {
            let start = layer * SLICE_WORDS;
            let sample = [self.source[start], self.source[start + 1], self.source[start + 2], self.source[start + 3]];
            if sample == [0; 4] {
                continue;
            }
            for &index in &areas.sections {
                if included(&sample, &areas.bits[index as usize]) {
                    self.mark(index, 1 << layer);
                }
            }
        }
    }

    fn mark_box_slices(&mut self) {
        let areas = areas();
        self.reset_slices();
        for layer in 0..16 {
            let start = layer * SLICE_WORDS;
            let mut bits = [self.source[start], self.source[start + 1], self.source[start + 2], self.source[start + 3]];
            let mut count: i32 = bits.iter().map(|w| w.count_ones() as i32).sum();

            // Le plus grand rectangle, puis le plus grand de ce qui reste, jusqu'à épuisement.
            while count > 0 {
                let key = find_largest(&bits);
                let index = areas.key_to_index[key as usize];
                self.mark(index, 1 << layer);
                let area = &areas.bits[index as usize];
                for w in 0..4 {
                    bits[w] &= !area[w];
                }
                count -= key_size(key);
            }
        }
    }

    /// Un rectangle trouvé dans une couche s'étend aux couches voisines qui le contiennent aussi.
    fn mark_box_neighbor_slices(&mut self) {
        let areas = areas();
        for i in 0..self.touched.len() {
            let index = self.touched[i] as usize;
            let area = &areas.bits[index];
            let mut slice = self.area_slices[index] as u32;

            if slice & 1 == 1 && slice & 2 == 0 && included(&self.source[SLICE_WORDS..], area) {
                slice |= 2;
            }

            let mut mask = 2u32;
            for z in 1..15usize {
                if slice & mask != 0 {
                    let low = mask >> 1;
                    if slice & low == 0 && included(&self.source[(z - 1) * SLICE_WORDS..], area) {
                        slice |= low;
                    }
                    let high = mask << 1;
                    if slice & high == 0 && included(&self.source[(z + 1) * SLICE_WORDS..], area) {
                        slice |= high;
                    }
                }
                mask <<= 1;
            }

            if slice & 0x8000 != 0 && slice & 0x4000 == 0 && included(&self.source[14 * SLICE_WORDS..], area) {
                slice |= 0x4000;
            }

            self.area_slices[index] = slice as u16;
        }
    }

    fn build_sorted_sections(&mut self) {
        let areas = areas();
        self.sorted.clear();

        // Le rectangle d'indice 0 est la couche entière.
        if self.area_slices[0] != 0 {
            self.add_runs(0, self.area_slices[0]);
        }

        for &index in &areas.sections[1..] {
            if self.area_slices[index as usize] == 0xFFFF {
                let volume = (key_size(areas.index_to_key[index as usize] as u32) * 16) as u64;
                self.sorted.push((volume << 34) | ((index as u64) << 10) | (16 << 5));
            }
        }

        self.sorted.sort_unstable_by(|a, b| b.cmp(a));
    }

    fn build_sorted_boxes(&mut self) {
        self.sorted.clear();
        for i in 0..self.touched.len() {
            let index = self.touched[i];
            let slice = self.area_slices[index as usize];
            if slice != 0 {
                self.add_runs(index, slice);
            }
        }
        self.sorted.sort_unstable_by(|a, b| b.cmp(a));
    }

    /// Une boîte candidate par suite de couches consécutives : volume, rectangle, couche de fin (exclue), de début.
    fn add_runs(&mut self, index: u16, slice: u16) {
        let size = key_size(areas().index_to_key[index as usize] as u32) as u64;
        let mut start: i32 = -1;
        for z in 0..=16 {
            let set = z < 16 && slice & (1 << z) != 0;
            if set {
                if start == -1 {
                    start = z;
                }
            } else if start != -1 {
                let volume = size * (z - start) as u64;
                self.sorted.push((volume << 34) | ((index as u64) << 10) | ((z as u64) << 5) | start as u64);
                start = -1;
            }
        }
    }

    fn find_sections(&mut self) {
        let areas = areas();
        for i in 0..self.sorted.len() {
            let candidate = self.sorted[i];
            let index = ((candidate >> 10) & 0xFF_FFFF) as usize;
            let z0 = (candidate & 31) as usize;
            let z1 = ((candidate >> 5) & 31) as usize;
            let area = &areas.bits[index];

            // Gardée si elle couvre au moins un bloc que rien ne couvre encore.
            let additive = (z0..z1).any(|z| {
                let f = &self.filled[z * SLICE_WORDS..];
                ((!f[0] & area[0]) | (!f[1] & area[1]) | (!f[2] & area[2]) | (!f[3] & area[3])) != 0
            });
            if additive {
                self.fill(area, z0, z1);
                let key = areas.index_to_key[index] as u32;
                self.boxes.push(pack(key_x0(key), key_y0(key), z0 as i32, key_x1(key) + 1, key_y1(key) + 1, z1 as i32, RANGE_EXTREME));
            }
        }
    }

    fn find_disjoint_boxes(&mut self) {
        let areas = areas();
        for i in 0..self.sorted.len() {
            let candidate = self.sorted[i];
            let index = ((candidate >> 10) & 0xFF_FFFF) as usize;
            let z0 = (candidate & 31) as usize;
            let z1 = ((candidate >> 5) & 31) as usize;
            let area = &areas.bits[index];

            if (z0..z1).any(|z| intersects(&self.filled[z * SLICE_WORDS..], area)) {
                continue;
            }
            self.fill(area, z0, z1);
            let volume = (candidate >> 34) as i32;
            let range = if volume <= 64 {
                RANGE_NEAR
            } else if volume > 512 {
                RANGE_FAR
            } else {
                RANGE_MID
            };
            let key = areas.index_to_key[index] as u32;
            self.boxes.push(pack(key_x0(key), key_y0(key), z0 as i32, key_x1(key) + 1, key_y1(key) + 1, z1 as i32, range));
            self.voxel_count -= volume;
            if self.voxel_count == 0 {
                break;
            }
        }
    }

    fn fill(&mut self, area: &[u64; 4], z0: usize, z1: usize) {
        for z in z0..z1 {
            let f = &mut self.filled[z * SLICE_WORDS..(z + 1) * SLICE_WORDS];
            for w in 0..4 {
                f[w] |= area[w];
            }
        }
    }

    fn clear_filled_bits(&mut self) {
        for i in 0..WORDS {
            self.source[i] &= !self.filled[i];
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn set(bits: &mut [u64; WORDS], x: usize, y: usize, z: usize) {
        let index = y << 8 | z << 4 | x;
        bits[index >> 6] |= 1 << (index & 63);
    }

    fn get(bits: &[u64; WORDS], x: usize, y: usize, z: usize) -> bool {
        let index = y << 8 | z << 4 | x;
        bits[index >> 6] >> (index & 63) & 1 != 0
    }

    fn unpack(b: i32) -> [usize; 6] {
        [b & 31, (b >> 5) & 31, (b >> 10) & 31, (b >> 15) & 31, (b >> 20) & 31, (b >> 25) & 31].map(|v| v as usize)
    }

    #[test]
    fn table_des_rectangles() {
        let areas = areas();
        assert_eq!(areas.index_to_key.len(), 136 * 136);
        assert_eq!(areas.index_to_key[0] as u32, key_of(0, 0, 15, 15));
        assert_eq!(areas.bits[0], [u64::MAX; 4]);
        assert_eq!(areas.sections.len(), 136 + 136 - 1);
        assert_eq!(areas.sections[0], 0);
    }

    #[test]
    fn section_pleine_une_seule_boite() {
        let mut out = Vec::new();
        BoxFinder::new().occluder_boxes(&[u64::MAX; WORDS], &mut out);
        assert_eq!(out, vec![pack(0, 0, 0, 16, 16, 16, RANGE_EXTREME)]);
    }

    #[test]
    fn couche_horizontale() {
        let mut bits = [0u64; WORDS];
        for z in 0..16 {
            for x in 0..16 {
                set(&mut bits, x, 8, z);
            }
        }
        let mut out = Vec::new();
        BoxFinder::new().occluder_boxes(&bits, &mut out);
        assert_eq!(out, vec![pack(0, 8, 0, 16, 9, 16, RANGE_EXTREME)]);
    }

    #[test]
    fn pave_au_milieu() {
        let mut bits = [0u64; WORDS];
        for y in 2..7 {
            for z in 3..9 {
                for x in 4..12 {
                    set(&mut bits, x, y, z);
                }
            }
        }
        let mut out = Vec::new();
        BoxFinder::new().occluder_boxes(&bits, &mut out);
        assert_eq!(out, vec![pack(4, 2, 3, 12, 7, 9, RANGE_MID)]);
    }

    /// Sur du bruit : aucune boîte ne couvre un bloc non opaque, et deux boîtes ne se recouvrent pas.
    #[test]
    fn boites_incluses_et_disjointes() {
        let mut finder = BoxFinder::new();
        let mut out = Vec::new();
        let mut seed = 0x9E37_79B9_7F4A_7C15u64;
        for round in 0..40 {
            let mut bits = [0u64; WORDS];
            for word in bits.iter_mut() {
                // Trois tirages réunis : environ 7 blocs sur 8 opaques, de quoi former des boîtes.
                let mut value = 0;
                for _ in 0..3 {
                    seed ^= seed << 13;
                    seed ^= seed >> 7;
                    seed ^= seed << 17;
                    value |= seed;
                }
                *word = if round % 2 == 0 { value } else { value | (seed >> 3) };
            }
            finder.occluder_boxes(&bits, &mut out);

            let mut covered = [0u64; WORDS];
            for &b in &out {
                let [x0, y0, z0, x1, y1, z1] = unpack(b);
                assert!((x1 - x0) * (y1 - y0) * (z1 - z0) >= MIN_VOLUME as usize);
                for y in y0..y1 {
                    for z in z0..z1 {
                        for x in x0..x1 {
                            assert!(get(&bits, x, y, z), "boîte sur un bloc non opaque");
                            assert!(!get(&covered, x, y, z), "deux boîtes se recouvrent");
                            set(&mut covered, x, y, z);
                        }
                    }
                }
            }
        }
    }
}
