//! La lumière et l'occlusion ambiante des sommets d'un quad.
//!
//! Port du calcul du moteur Java (`celeritas/model/light` : `LightDataAccess`,
//! `SmoothLightPipeline`, `FlatLightPipeline`, `AoFaceData`, `AoNeighborInfo`,
//! dérivés de Sodium) et de ce que la 1.8.9 fait pour donner la lumière d'un
//! bloc (`World.getLight`, `Block.getBrightness`, repris côté Java dans
//! `WorldSlice189` et `LightCache189`).
//!
//! Les opérations sur les flottants sont faites dans le même ordre que le
//! Java : le résultat doit être le même au bit près, c'est ce que vérifie le
//! contrôle en jeu.
//!
//! Le noyau ne peut rien demander au jeu. Il travaille sur :
//! - une copie de la section et de trois blocs autour d'elle (`Workspace`),
//!   remplie par Java section voisine par section voisine ;
//! - une table des propriétés de chaque état de bloc (`PROP_*`).

/// Blocs lus autour de la section. Le calcul regarde à un bloc ; la règle des
/// dalles (lumière des voisins, puis du dessous) en ajoute deux.
const RADIUS: i32 = 3;
const SIDE: usize = 16 + 2 * RADIUS as usize;
const CELLS: usize = SIDE * SIDE * SIDE;

/// Rayon du cache des « mots » de lumière, comme `LightDataAccess`.
const WORD_RADIUS: i32 = 2;
const WORD_SIDE: usize = 16 + 2 * WORD_RADIUS as usize;
const WORD_CELLS: usize = WORD_SIDE * WORD_SIDE * WORD_SIDE;

const DEFAULT_SKY_LIGHT: u8 = 15;

// ── Propriétés d'un état de bloc (table tendue par Java, un u32 par état) ────

/// Cube plein opaque (`Block.hasTransparency` chez Yarn legacy).
pub const PROP_FULL_OPAQUE: u32 = 1 << 1;
/// Cube plein (`Block.isNormalBlock`).
pub const PROP_FULL_CUBE: u32 = 1 << 2;
/// Bouche la lumière pour l'occlusion ambiante.
pub const PROP_OPAQUE: u32 = 1 << 3;
/// Prend la lumière de ses voisins (dalles, escaliers, terre labourée).
pub const PROP_NEIGHBOUR_LIGHT: u32 = 1 << 4;
/// Règle de `Block.getBrightness` : 0 = commune, 1 = dalle, 2 = fluide.
pub const PROP_RULE_SHIFT: u32 = 5;
pub const RULE_SLAB: u32 = 1;
pub const RULE_FLUID: u32 = 2;
/// Lumière émise, 0 à 15.
pub const PROP_EMITTED_SHIFT: u32 = 8;
/// Occlusion ambiante du bloc, en 4096ᵉ (0 à 4096).
pub const PROP_AO_SHIFT: u32 = 12;

pub const STATES: usize = 65536;

// ── Faces, dans l'ordre de `ModelQuadFacing` ─────────────────────────────────

pub const POS_X: usize = 0;
pub const POS_Y: usize = 1;
pub const POS_Z: usize = 2;
pub const NEG_X: usize = 3;
pub const NEG_Y: usize = 4;
pub const NEG_Z: usize = 5;
pub const UNASSIGNED: usize = 6;

const STEP: [[i32; 3]; 6] = [[1, 0, 0], [0, 1, 0], [0, 0, 1], [-1, 0, 0], [0, -1, 0], [0, 0, -1]];

/// Pour chaque face, les quatre directions de ses blocs de bord (`AoNeighborInfo.faces`).
const EDGE_FACES: [[usize; 4]; 6] = [
    [NEG_Y, POS_Y, NEG_Z, POS_Z],
    [POS_X, NEG_X, NEG_Z, POS_Z],
    [NEG_X, POS_X, NEG_Y, POS_Y],
    [POS_Y, NEG_Y, NEG_Z, POS_Z],
    [NEG_X, POS_X, NEG_Z, POS_Z],
    [POS_Y, NEG_Y, POS_X, NEG_X],
];

/// `AoNeighborInfo.mapCorners` : le sommet `i` du quad prend le coin `CORNER_OF_VERTEX[face][i]`.
const CORNER_OF_VERTEX: [[usize; 4]; 6] = [
    [3, 0, 1, 2],
    [2, 3, 0, 1],
    [0, 1, 2, 3],
    [1, 2, 3, 0],
    [0, 1, 2, 3],
    [1, 2, 3, 0],
];

// Drapeaux de forme d'un quad (`ModelQuadFlags`).
const QUAD_PARTIAL: u32 = 0b001;
const QUAD_PARALLEL: u32 = 0b010;
const QUAD_ALIGNED: u32 = 0b100;
const QUAD_VANILLA_SHADED: u32 = 0b1000;

// ── Mot de lumière d'un bloc (`LightDataAccess`) ─────────────────────────────

fn word_bl(word: u32) -> u32 {
    word & 0xF
}

fn word_sl(word: u32) -> u32 {
    (word >> 4) & 0xF
}

fn word_lu(word: u32) -> u32 {
    (word >> 8) & 0xF
}

fn word_ao(word: u32) -> f32 {
    ((word >> 12) & 0xFFFF) as f32 * (1.0f32 / 4096.0f32)
}

fn word_op(word: u32) -> bool {
    (word >> 29) & 1 != 0
}

fn word_fo(word: u32) -> bool {
    (word >> 30) & 1 != 0
}

fn word_fc(word: u32) -> bool {
    (word >> 31) & 1 != 0
}

/// Coordonnées de la texture de lumière : blocs dans les bits 4 et plus, ciel dans les bits 20 et plus.
fn pack_lightmap(block: u32, sky: u32) -> u32 {
    block << 4 | sky << 20
}

fn word_lightmap(word: u32) -> u32 {
    pack_lightmap(word_bl(word).max(word_lu(word)), word_sl(word))
}

// ── Copie de travail ─────────────────────────────────────────────────────────

/// La mémoire de travail d'un fil de construction. Allouée par Java (qui ne
/// connaît que sa taille) et remise à chaque appel : le noyau ne garde rien
/// entre deux appels.
#[repr(C)]
pub struct Workspace {
    /// Les mots de lumière déjà calculés ; 0 = pas encore.
    words: [u32; WORD_CELLS],
    states: [u16; CELLS],
    sky: [u8; CELLS],
    block: [u8; CELLS],
    /// Coin de la section, en blocs du monde.
    base: [i32; 3],
    has_sky: u32,
}

/// Une section voisine telle que Java la tend (`SectionSnapshot`).
pub struct SectionData<'a> {
    pub blocks: Option<&'a [u16; 4096]>,
    pub block_light: Option<&'a [u8; 2048]>,
    pub sky_light: Option<&'a [u8; 2048]>,
    pub heights: Option<&'a [i32; 256]>,
}

fn nibble(data: &[u8; 2048], index: usize) -> u8 {
    let packed = data[index >> 1];
    if index & 1 == 0 {
        packed & 15
    } else {
        packed >> 4
    }
}

fn cell(x: i32, y: i32, z: i32) -> usize {
    (((y + RADIUS) as usize * SIDE) + (z + RADIUS) as usize) * SIDE + (x + RADIUS) as usize
}

impl Workspace {
    /// Avant une section : tout est de l'air en plein jour, comme hors des copies côté Java.
    pub fn begin(&mut self, base_x: i32, base_y: i32, base_z: i32, has_sky: bool) {
        self.words.fill(0);
        self.states.fill(0);
        self.block.fill(0);
        self.sky.fill(if has_sky { DEFAULT_SKY_LIGHT } else { 0 });
        self.base = [base_x, base_y, base_z];
        self.has_sky = has_sky as u32;
    }

    /// Recopie ce qui, d'une des 27 sections, tombe à trois blocs ou moins de
    /// la section à mailler. `dx`, `dy`, `dz` : 0, 1 ou 2 (1 = la section elle-même).
    pub fn load_section(&mut self, dx: usize, dy: usize, dz: usize, data: &SectionData) {
        // Pour chaque axe : (première coordonnée dans la section source, première coordonnée locale, longueur).
        let span = |d: usize| -> (usize, i32, usize) {
            match d {
                0 => (16 - RADIUS as usize, -RADIUS, RADIUS as usize),
                1 => (0, 0, 16),
                _ => (0, 16, RADIUS as usize),
            }
        };
        let (sx0, lx0, nx) = span(dx);
        let (sy0, ly0, ny) = span(dy);
        let (sz0, lz0, nz) = span(dz);
        let has_sky = self.has_sky != 0;

        for iy in 0..ny {
            let sy = sy0 + iy;
            let ly = ly0 + iy as i32;
            let world_y = self.base[1] + ly;
            if world_y > 255 {
                // Au-dessus du monde : air en plein jour, déjà en place.
                continue;
            }
            for iz in 0..nz {
                let sz = sz0 + iz;
                let lz = lz0 + iz as i32;
                for ix in 0..nx {
                    let sx = sx0 + ix;
                    let at = cell(lx0 + ix as i32, ly, lz);
                    let source = sy << 8 | sz << 4 | sx;
                    match data.blocks {
                        Some(blocks) => {
                            self.states[at] = blocks[source];
                            self.block[at] = data.block_light.map_or(0, |light| nibble(light, source));
                            self.sky[at] = match data.sky_light {
                                Some(light) if has_sky => nibble(light, source),
                                _ => 0,
                            };
                        }
                        None => {
                            // Section vide : le ciel n'éclaire qu'au-dessus du plus haut bloc de la colonne.
                            self.states[at] = 0;
                            self.block[at] = 0;
                            let lit = data.heights.map_or(true, |heights| world_y >= heights[sz << 4 | sx]);
                            self.sky[at] = if lit && has_sky { DEFAULT_SKY_LIGHT } else { 0 };
                        }
                    }
                }
            }
        }
    }
}

/// Lecture de la lumière d'une section en cours de maillage.
pub struct LightReader<'a> {
    ws: &'a mut Workspace,
    props: &'a [u32; STATES],
}

impl<'a> LightReader<'a> {
    pub fn new(ws: &'a mut Workspace, props: &'a [u32; STATES]) -> Self {
        LightReader { ws, props }
    }

    pub fn base(&self) -> [i32; 3] {
        self.ws.base
    }

    fn props_at(&self, x: i32, y: i32, z: i32) -> u32 {
        self.props[self.ws.states[cell(x, y, z)] as usize]
    }

    /// `World.getLight` de la 1.8.9 pour une des deux lumières : un bloc qui
    /// « prend la lumière de ses voisins » reçoit la plus forte de celles du
    /// dessus et des quatre côtés.
    fn light_with_neighbours(&self, sky: bool, x: i32, mut y: i32, z: i32) -> u32 {
        if sky && self.ws.has_sky == 0 {
            return 0;
        }
        let world_y = self.ws.base[1] + y;
        if world_y < 0 {
            y -= world_y;
        } else if world_y > 255 {
            return if sky { DEFAULT_SKY_LIGHT as u32 } else { 0 };
        }
        let raw = |x: i32, y: i32, z: i32| -> u32 {
            let at = cell(x, y, z);
            (if sky { self.ws.sky[at] } else { self.ws.block[at] }) as u32
        };
        if self.props_at(x, y, z) & PROP_NEIGHBOUR_LIGHT == 0 {
            return raw(x, y, z);
        }
        raw(x, y + 1, z).max(raw(x + 1, y, z)).max(raw(x - 1, y, z)).max(raw(x, y, z + 1)).max(raw(x, y, z - 1))
    }

    /// (blocs, ciel) : `BlockView.getLight(pos, minBlockLight)`.
    fn combined_light(&self, x: i32, y: i32, z: i32, min_block: u32) -> (u32, u32) {
        let sky = self.light_with_neighbours(true, x, y, z);
        let block = self.light_with_neighbours(false, x, y, z).max(min_block);
        (block, sky)
    }

    /// (blocs, ciel) : `Block.getBrightness`, avec ses deux cas particuliers.
    fn brightness(&self, x: i32, y: i32, z: i32, props: u32) -> (u32, u32) {
        let rule = (props >> PROP_RULE_SHIFT) & 3;
        if rule == RULE_FLUID {
            // Un fluide prend la plus forte de sa lumière et de celle du dessus.
            let (block, sky) = self.combined_light(x, y, z, 0);
            let (block_up, sky_up) = self.combined_light(x, y + 1, z, 0);
            return (block.max(block_up), sky.max(sky_up));
        }
        let emitted = (props >> PROP_EMITTED_SHIFT) & 15;
        let (block, sky) = self.combined_light(x, y, z, emitted);
        if block == 0 && sky == 0 && rule == RULE_SLAB {
            // Une dalle dans le noir prend la lumière du bloc du dessous.
            let below = (self.props_at(x, y - 1, z) >> PROP_EMITTED_SHIFT) & 15;
            return self.combined_light(x, y - 1, z, below);
        }
        (block, sky)
    }

    /// `LightCache189.compute`.
    fn compute(&self, x: i32, y: i32, z: i32) -> u32 {
        let props = self.props_at(x, y, z);
        let emitted = (props >> PROP_EMITTED_SHIFT) & 15;
        let full_opaque = props & PROP_FULL_OPAQUE != 0;

        // Rien ne se dessine à l'intérieur d'un cube opaque : sa lumière ne sert pas.
        let (block, sky) = if full_opaque && emitted == 0 { (0, 0) } else { self.brightness(x, y, z, props) };
        let ao = if emitted == 0 { (props >> PROP_AO_SHIFT) & 0xFFFF } else { 4096 };

        ((props & PROP_FULL_CUBE != 0) as u32) << 31
            | (full_opaque as u32) << 30
            | ((props & PROP_OPAQUE != 0) as u32) << 29
            | ao << 12
            | emitted << 8
            | (sky & 0xF) << 4
            | (block & 0xF)
    }

    /// Le mot de lumière d'un bloc, calculé au premier besoin.
    fn word(&mut self, x: i32, y: i32, z: i32) -> u32 {
        let index = (((z + WORD_RADIUS) as usize * WORD_SIDE) + (y + WORD_RADIUS) as usize) * WORD_SIDE + (x + WORD_RADIUS) as usize;
        let word = self.ws.words[index];
        if word != 0 {
            return word;
        }
        let word = self.compute(x, y, z);
        self.ws.words[index] = word;
        word
    }

    fn word_toward(&mut self, x: i32, y: i32, z: i32, face: usize) -> u32 {
        let s = STEP[face];
        self.word(x + s[0], y + s[1], z + s[2])
    }
}

// ── Lumière des quatre coins d'une face (`AoFaceData`) ───────────────────────

#[derive(Clone, Copy, Default)]
struct FaceData {
    lm: [u32; 4],
    ao: [f32; 4],
    bl: [f32; 4],
    sl: [f32; 4],
    ready: bool,
}

fn min_non_zero(a: u32, b: u32) -> u32 {
    if a == 0 {
        b
    } else if b == 0 {
        a
    } else {
        a.min(b)
    }
}

fn corner_brightness(mut a: u32, mut b: u32, mut c: u32, mut d: u32) -> u32 {
    if a == 0 || b == 0 || c == 0 || d == 0 {
        let min = min_non_zero(min_non_zero(a, b), min_non_zero(c, d));
        a = a.max(min);
        b = b.max(min);
        c = c.max(min);
        d = d.max(min);
    }
    ((a + b + c + d) >> 2) & 0xFF00FF
}

fn weighted_sum(v: &[f32; 4], w: &[f32; 4]) -> f32 {
    let t0 = v[0] * w[0];
    let t1 = v[1] * w[1];
    let t2 = v[2] * w[2];
    let t3 = v[3] * w[3];
    t0 + t1 + t2 + t3
}

impl FaceData {
    fn init(&mut self, light: &mut LightReader, x: i32, y: i32, z: i32, face: usize, offset: bool) {
        let s = STEP[face];
        let (ax, ay, az) = if offset { (x + s[0], y + s[1], z + s[2]) } else { (x, y, z) };

        let adj = light.word(ax, ay, az);
        let calm = if offset && word_fo(adj) { word_lightmap(light.word(x, y, z)) } else { word_lightmap(adj) };
        let caao = word_ao(adj);

        let faces = EDGE_FACES[face];
        let e0 = light.word_toward(ax, ay, az, faces[0]);
        let e1 = light.word_toward(ax, ay, az, faces[1]);
        let e2 = light.word_toward(ax, ay, az, faces[2]);
        let e3 = light.word_toward(ax, ay, az, faces[3]);
        let (e0lm, e1lm, e2lm, e3lm) = (word_lightmap(e0), word_lightmap(e1), word_lightmap(e2), word_lightmap(e3));
        let (e0ao, e1ao, e2ao, e3ao) = (word_ao(e0), word_ao(e1), word_ao(e2), word_ao(e3));
        let (e0op, e1op, e2op, e3op) = (word_op(e0), word_op(e1), word_op(e2), word_op(e3));

        // Un coin caché par ses deux bords prend la lumière d'un bord ; sinon celle du bloc en diagonale.
        let corner = |light: &mut LightReader, hidden: bool, edge_lm: u32, edge_ao: f32, f1: usize, f2: usize| -> (u32, f32) {
            if hidden {
                (edge_lm, edge_ao)
            } else {
                let (s1, s2) = (STEP[f1], STEP[f2]);
                let d = light.word(ax + s1[0] + s2[0], ay + s1[1] + s2[1], az + s1[2] + s2[2]);
                (word_lightmap(d), word_ao(d))
            }
        };
        let (c0lm, c0ao) = corner(light, e2op && e0op, e0lm, e0ao, faces[0], faces[2]);
        let (c1lm, c1ao) = corner(light, e3op && e0op, e0lm, e0ao, faces[0], faces[3]);
        let (c2lm, c2ao) = corner(light, e2op && e1op, e1lm, e1ao, faces[1], faces[2]);
        let (c3lm, c3ao) = corner(light, e3op && e1op, e1lm, e1ao, faces[1], faces[3]);

        self.ao[0] = (e3ao + e0ao + c1ao + caao) * 0.25;
        self.ao[1] = (e2ao + e0ao + c0ao + caao) * 0.25;
        self.ao[2] = (e2ao + e1ao + c2ao + caao) * 0.25;
        self.ao[3] = (e3ao + e1ao + c3ao + caao) * 0.25;

        self.lm[0] = corner_brightness(e3lm, e0lm, c1lm, calm);
        self.lm[1] = corner_brightness(e2lm, e0lm, c0lm, calm);
        self.lm[2] = corner_brightness(e2lm, e1lm, c2lm, calm);
        self.lm[3] = corner_brightness(e3lm, e1lm, c3lm, calm);

        for i in 0..4 {
            self.bl[i] = (self.lm[i] & 0xFF) as f32;
            self.sl[i] = ((self.lm[i] >> 16) & 0xFF) as f32;
        }
        self.ready = true;
    }
}

// ── Calcul d'un quad ─────────────────────────────────────────────────────────

/// La forme d'un quad, lue par le calcul de lumière.
pub struct QuadShape<'a> {
    pub x: &'a [f32; 4],
    pub y: &'a [f32; 4],
    pub z: &'a [f32; 4],
    pub flags: u32,
    pub light_face: usize,
    pub normal_face: usize,
    pub computed_normal: u32,
}

/// Ce que le calcul rend pour les quatre sommets : assombrissement et coordonnées de lumière.
#[derive(Default)]
pub struct QuadLight {
    pub br: [f32; 4],
    pub lm: [u32; 4],
}

fn clamp01(v: f32) -> f32 {
    if v < 0.0 {
        0.0
    } else if v > 1.0 {
        1.0
    } else {
        v
    }
}

fn roughly_equal(a: f32, b: f32) -> bool {
    (b - a).abs() < 0.00001
}

/// `AoNeighborInfo.calculateCornerWeights`.
fn corner_weights(face: usize, x: f32, y: f32, z: f32) -> [f32; 4] {
    let (u, v) = match face {
        POS_X => (z, 1.0 - y),
        POS_Y => (z, x),
        POS_Z => (y, 1.0 - x),
        NEG_X => (z, y),
        NEG_Y => (z, 1.0 - x),
        _ => (1.0 - x, y),
    };
    if face == POS_Z {
        [u * v, (1.0 - u) * v, (1.0 - u) * (1.0 - v), u * (1.0 - v)]
    } else {
        [v * u, v * (1.0 - u), (1.0 - v) * (1.0 - u), (1.0 - v) * u]
    }
}

/// `AoNeighborInfo.getDepth` : de combien le sommet est enfoncé dans le bloc, vu de cette face.
fn depth(face: usize, x: f32, y: f32, z: f32) -> f32 {
    match face {
        POS_X => 1.0 - x,
        POS_Y => 1.0 - y,
        POS_Z => 1.0 - z,
        NEG_X => x,
        NEG_Y => y,
        _ => z,
    }
}

fn lightmap_coord(sl: f32, bl: f32) -> u32 {
    ((sl as i32 & 0xFF) as u32) << 16 | (bl as i32 & 0xFF) as u32
}

/// L'éclairage adouci, avec ce qu'il garde d'un quad à l'autre d'un même bloc.
pub struct Lighter {
    /// Les six faces vues de l'extérieur du bloc, puis les six vues de l'intérieur.
    faces: [FaceData; 12],
    cached: (i32, i32, i32),
}

impl Default for Lighter {
    fn default() -> Self {
        Self::new()
    }
}

impl Lighter {
    pub fn new() -> Self {
        Lighter { faces: [FaceData::default(); 12], cached: (i32::MIN, 0, 0) }
    }

    fn face_data(&mut self, light: &mut LightReader, x: i32, y: i32, z: i32, face: usize, offset: bool) -> &FaceData {
        let data = &mut self.faces[if offset { face } else { face + 6 }];
        if !data.ready {
            data.init(light, x, y, z, face, offset);
        }
        data
    }

    /// (occlusion, ciel, blocs) d'un sommet, mélange des quatre coins de la face.
    fn blended(&mut self, light: &mut LightReader, x: i32, y: i32, z: i32, face: usize, w: &[f32; 4], offset: bool) -> (f32, f32, f32) {
        let data = self.face_data(light, x, y, z, face, offset);
        let sl = weighted_sum(&data.sl, w);
        let bl = weighted_sum(&data.bl, w);
        let ao = weighted_sum(&data.ao, w);
        (ao, sl, bl)
    }

    /// `SmoothLightPipeline.calculate`, sans mélange selon la profondeur (jamais demandé en 1.8.9).
    pub fn smooth(&mut self, light: &mut LightReader, quad: &QuadShape, x: i32, y: i32, z: i32, out: &mut QuadLight) {
        if self.cached != (x, y, z) {
            for face in self.faces.iter_mut() {
                face.ready = false;
            }
            self.cached = (x, y, z);
        }

        let flags = quad.flags;
        let face = quad.light_face;

        if flags & QUAD_ALIGNED != 0 || (flags & QUAD_PARALLEL != 0 && word_fc(light.word(x, y, z))) {
            if flags & QUAD_PARTIAL == 0 {
                // Face entière collée à la grille : chaque sommet prend un coin, sans mélange.
                let data = *self.face_data(light, x, y, z, face, true);
                for i in 0..4 {
                    let corner = CORNER_OF_VERTEX[face][i];
                    out.lm[i] = data.lm[corner];
                    out.br[i] = data.ao[corner];
                }
            } else {
                for i in 0..4 {
                    let (cx, cy, cz) = (clamp01(quad.x[i]), clamp01(quad.y[i]), clamp01(quad.z[i]));
                    let w = corner_weights(face, cx, cy, cz);
                    let (ao, sl, bl) = self.blended(light, x, y, z, face, &w, true);
                    out.br[i] = ao;
                    out.lm[i] = lightmap_coord(sl, bl);
                }
            }
        } else if flags & QUAD_VANILLA_SHADED == 0 && quad.normal_face == UNASSIGNED {
            // Quad en biais : chaque axe de la normale contribue.
            for i in 0..4 {
                let (cx, cy, cz) = (clamp01(quad.x[i]), clamp01(quad.y[i]), clamp01(quad.z[i]));
                let (mut weighted_ao, mut weighted_bl, mut weighted_sl) = (0.0f32, 0.0f32, 0.0f32);
                let (mut max_ao, mut max_bl, mut max_sl) = (0.0f32, 0.0f32, 0.0f32);

                for axis in 0..3 {
                    let projected = ((quad.computed_normal >> (axis * 8)) & 0xFF) as u8 as i8 as f32 * (1.0f32 / 127.0f32);
                    if projected == 0.0 {
                        continue;
                    }
                    let dir = if projected > 0.0 { axis } else { axis + 3 };
                    let w = corner_weights(dir, cx, cy, cz);
                    let inset = depth(dir, cx, cy, cz);
                    let (ao, sl, bl) = self.blended(light, x, y, z, dir, &w, roughly_equal(inset, 0.0));

                    let combine = projected * projected;
                    weighted_ao += ao * combine;
                    weighted_bl += bl * combine;
                    weighted_sl += sl * combine;
                    max_ao = ao.max(max_ao);
                    max_sl = sl.max(max_sl);
                    max_bl = bl.max(max_bl);
                }

                out.br[i] = clamp01(weighted_ao * 0.75 + max_ao * 0.25);
                out.lm[i] = lightmap_coord(weighted_sl * 0.75 + max_sl * 0.25, weighted_bl * 0.75 + max_bl * 0.25);
            }
        } else {
            // Quad droit mais en retrait de la face.
            for i in 0..4 {
                let (cx, cy, cz) = (clamp01(quad.x[i]), clamp01(quad.y[i]), clamp01(quad.z[i]));
                let w = corner_weights(face, cx, cy, cz);
                let inset = depth(face, cx, cy, cz);
                let (ao, sl, bl) = self.blended(light, x, y, z, face, &w, roughly_equal(inset, 0.0));
                out.br[i] = ao;
                out.lm[i] = lightmap_coord(sl, bl);
            }
        }
    }
}

/// `FlatLightPipeline.calculate` : la lumière du bloc d'en face, la même aux quatre sommets.
pub fn flat(light: &mut LightReader, quad: &QuadShape, x: i32, y: i32, z: i32, cull_face: usize, out: &mut QuadLight) {
    let origin = light.word(x, y, z);
    // La lumière du voisin, mais l'émission du bloc lui-même.
    let offset = |light: &mut LightReader, face: usize| -> u32 {
        let adj = light.word_toward(x, y, z, face);
        pack_lightmap(word_bl(adj).max(word_lu(origin)), word_sl(adj))
    };

    let lightmap = if cull_face != UNASSIGNED {
        offset(light, cull_face)
    } else if quad.flags & QUAD_ALIGNED != 0 || (quad.flags & QUAD_PARALLEL != 0 && word_fc(origin)) {
        offset(light, quad.light_face)
    } else {
        word_lightmap(origin)
    };

    out.lm = [lightmap; 4];
    out.br = [1.0; 4];
}

pub const WORKSPACE_BYTES: usize = std::mem::size_of::<Workspace>();

#[cfg(test)]
pub mod tests {
    use super::*;

    pub const AIR: u16 = 0;
    pub const STONE: u16 = 1 << 4;
    pub const TORCH: u16 = 50 << 4;
    pub const SLAB: u16 = 44 << 4;

    const AO_ONE: u32 = 4096 << PROP_AO_SHIFT;
    /// 0,2 en flottant simple, comme Java le range : (int) (0.2f * 4096).
    const AO_SOLID: u32 = 819 << PROP_AO_SHIFT;

    pub fn props() -> Box<[u32; STATES]> {
        let mut props: Box<[u32; STATES]> = vec![AO_ONE; STATES].into_boxed_slice().try_into().unwrap();
        props[STONE as usize] = PROP_FULL_OPAQUE | PROP_FULL_CUBE | PROP_OPAQUE | AO_SOLID;
        props[TORCH as usize] = 14 << PROP_EMITTED_SHIFT | AO_ONE;
        props[SLAB as usize] = PROP_NEIGHBOUR_LIGHT | RULE_SLAB << PROP_RULE_SHIFT | AO_ONE;
        props
    }

    pub fn workspace() -> Box<Workspace> {
        // Comme Java : de la mémoire à zéro, de la bonne taille.
        let mut ws: Box<Workspace> = unsafe { Box::new(std::mem::zeroed()) };
        ws.begin(0, 64, 0, true);
        ws
    }

    pub fn set(ws: &mut Workspace, x: i32, y: i32, z: i32, state: u16, block: u8, sky: u8) {
        let at = cell(x, y, z);
        ws.states[at] = state;
        ws.block[at] = block;
        ws.sky[at] = sky;
    }

    const FULL_X: [f32; 4] = [0.0, 0.0, 1.0, 1.0];
    const FULL_Y: [f32; 4] = [1.0, 1.0, 1.0, 1.0];
    const FULL_Z: [f32; 4] = [0.0, 1.0, 1.0, 0.0];

    fn top_face() -> QuadShape<'static> {
        QuadShape { x: &FULL_X, y: &FULL_Y, z: &FULL_Z, flags: QUAD_PARALLEL | QUAD_ALIGNED, light_face: POS_Y, normal_face: POS_Y, computed_normal: 0x007F00 }
    }

    #[test]
    fn taille_de_l_espace_de_travail() {
        assert_eq!(WORKSPACE_BYTES % 4, 0);
        assert!(WORKSPACE_BYTES < 96 * 1024);
    }

    /// Un bloc seul en plein jour : aucune ombre, lumière du ciel au maximum.
    #[test]
    fn bloc_isole_en_plein_jour() {
        let props = props();
        let mut ws = workspace();
        set(&mut ws, 5, 5, 5, STONE, 0, 0);
        let mut light = LightReader::new(&mut ws, &props);
        let mut out = QuadLight::default();
        Lighter::new().smooth(&mut light, &top_face(), 5, 5, 5, &mut out);
        assert_eq!(out.br, [1.0; 4]);
        assert_eq!(out.lm, [pack_lightmap(0, 15); 4]);

        let mut out = QuadLight::default();
        flat(&mut light, &top_face(), 5, 5, 5, POS_Y, &mut out);
        assert_eq!(out.lm, [pack_lightmap(0, 15); 4]);
    }

    /// Un bloc posé à côté, un cran plus haut, assombrit les deux coins qu'il touche.
    #[test]
    fn un_voisin_fait_de_l_ombre() {
        let props = props();
        let mut ws = workspace();
        set(&mut ws, 5, 5, 5, STONE, 0, 0);
        set(&mut ws, 6, 6, 5, STONE, 0, 0);
        let mut light = LightReader::new(&mut ws, &props);
        let mut out = QuadLight::default();
        Lighter::new().smooth(&mut light, &top_face(), 5, 5, 5, &mut out);

        let shaded = out.br.iter().filter(|&&b| b < 1.0).count();
        assert_eq!(shaded, 2);
        // Trois voisins à 1,0 et un à 819/4096, en moyenne.
        let expected = (1.0 + 819.0 / 4096.0 + 1.0 + 1.0) * 0.25;
        assert!(out.br.iter().any(|&b| b == expected), "{:?}", out.br);
        // Les sommets du côté x = 1 (indices 2 et 3 du quad) sont ceux qui s'assombrissent.
        assert!(out.br[2] < 1.0 && out.br[3] < 1.0);
    }

    /// La lumière émise passe dans le mot du bloc, et un cube opaque n'a pas de lumière propre.
    #[test]
    fn mots_de_lumiere() {
        let props = props();
        let mut ws = workspace();
        set(&mut ws, 3, 3, 3, TORCH, 14, 0);
        set(&mut ws, 4, 3, 3, STONE, 7, 9);
        let mut light = LightReader::new(&mut ws, &props);

        let torch = light.word(3, 3, 3);
        assert_eq!((word_bl(torch), word_sl(torch), word_lu(torch)), (14, 0, 14));
        assert_eq!(word_ao(torch), 1.0);

        let stone = light.word(4, 3, 3);
        assert_eq!((word_bl(stone), word_sl(stone)), (0, 0));
        assert!(word_fo(stone) && word_fc(stone) && word_op(stone));
    }

    /// Une dalle prend la lumière de ses voisins ; dans le noir complet, celle du dessous.
    #[test]
    fn regle_des_dalles() {
        let props = props();
        let mut ws = workspace();
        ws.sky.fill(0);
        set(&mut ws, 8, 8, 8, SLAB, 0, 0);
        set(&mut ws, 9, 8, 8, AIR, 11, 0);
        {
            let mut light = LightReader::new(&mut ws, &props);
            assert_eq!(word_bl(light.word(8, 8, 8)), 11);
        }

        ws.words.fill(0);
        set(&mut ws, 9, 8, 8, AIR, 0, 0);
        set(&mut ws, 8, 7, 8, AIR, 6, 0);
        let mut light = LightReader::new(&mut ws, &props);
        assert_eq!(word_bl(light.word(8, 8, 8)), 6);
    }

    /// Le chargement d'une section voisine ne recopie que sa bordure, au bon endroit.
    #[test]
    fn chargement_des_voisines() {
        let mut ws = workspace();
        let mut blocks = [AIR; 4096];
        // Dernier bloc en x de la section à l'ouest, à y = 2, z = 3.
        blocks[2 << 8 | 3 << 4 | 15] = STONE;
        let mut block_light = [0u8; 2048];
        block_light[(2 << 8 | 3 << 4 | 15) >> 1] = 0xA0;
        let data = SectionData { blocks: Some(&blocks), block_light: Some(&block_light), sky_light: None, heights: None };
        ws.load_section(0, 1, 1, &data);

        let at = cell(-1, 2, 3);
        assert_eq!(ws.states[at], STONE);
        assert_eq!(ws.block[at], 10);
        assert_eq!(ws.sky[at], 0);

        // Section vide au-dessus : le ciel éclaire à partir de la hauteur de la colonne.
        let mut heights = [0i32; 256];
        heights[5 << 4 | 4] = 82;
        let data = SectionData { blocks: None, block_light: None, sky_light: None, heights: Some(&heights) };
        ws.load_section(1, 2, 1, &data);
        assert_eq!(ws.sky[cell(4, 17, 5)], 0); // y du monde = 81
        assert_eq!(ws.sky[cell(4, 18, 5)], 15); // y du monde = 82
        assert_eq!(ws.sky[cell(5, 16, 5)], 15);
    }
}
