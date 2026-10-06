//! L'écriture des quads d'une section : Java a décidé quels quads se
//! dessinent (modèle du bloc, faces cachées, teinte — tout ce qui demande le
//! code du jeu) et les tend sous forme de commandes ; ici on calcule leur
//! lumière et on écrit leurs sommets, directement au format du moteur.
//!
//! Port de `BlockRenderer189.renderQuad` et de ce qu'il appelle
//! (`ModelQuadOrientation`, `ChunkColorWriter.EMBEDDIUM`, `ModelQuadUtil.mixARGBColors`,
//! `ChunkMeshBufferBuilder.push`, `VanillaLikeChunkVertex`). Même résultat au
//! bit près : c'est ce que vérifie le contrôle en jeu.

use super::light::{flat, LightReader, Lighter, QuadLight, QuadShape, UNASSIGNED};

/// Un quad de modèle, tel que Java le range une fois pour toutes en mémoire
/// native (`CachedQuad`) : 28 mots de 4 octets.
#[repr(C)]
pub struct QuadRecord {
    pub x: [f32; 4],
    pub y: [f32; 4],
    pub z: [f32; 4],
    pub u: [f32; 4],
    pub v: [f32; 4],
    pub color: [u32; 4],
    /// Drapeaux de forme (`ModelQuadFlags`).
    pub flags: u32,
    /// Face d'éclairage dans l'octet bas, face de la normale dans le suivant (`ModelQuadFacing`).
    pub faces: u32,
    pub computed_normal: u32,
    pub reserved: u32,
}

/// Une commande : un quad à dessiner pour un bloc. Quatre mots de 4 octets.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct Command {
    /// Adresse du `QuadRecord`, moitié basse puis haute.
    pub quad_low: u32,
    pub quad_high: u32,
    /// Voir les `CMD_*`.
    pub packed: u32,
    /// Teinte du bloc pour ce quad, ou −1.
    pub tint: u32,
}

// `Command.packed` : position du bloc dans la section (3 × 4 bits), puis :
/// Face du bloc à laquelle le quad est collé (`ModelQuadFacing`), 6 = aucune.
pub const CMD_CULL_SHIFT: u32 = 12;
/// Éclairage adouci (sinon : à plat).
pub const CMD_SMOOTH: u32 = 1 << 15;
/// Décalage aléatoire du modèle (`Block.OffsetType`) : 0 = aucun, 1 = XZ, 2 = XYZ.
pub const CMD_OFFSET_SHIFT: u32 = 16;
/// Tampon de destination, indice dans la table tendue à l'appel.
pub const CMD_BUCKET_SHIFT: u32 = 18;
pub const CMD_BUCKET_MASK: u32 = 31;
/// Bits du matériau, recopiés dans chaque sommet.
pub const CMD_MATERIAL_SHIFT: u32 = 23;

pub const MAX_BUCKETS: usize = 32;

/// Octets d'un sommet (`VanillaLikeChunkVertex.STRIDE`).
pub const VERTEX_BYTES: usize = 32;

/// `Math.round(float)` de Java : à l'entier le plus proche, la moitié vers le haut.
fn java_round(value: f32) -> i32 {
    let floor = value.floor();
    if value - floor >= 0.5 {
        floor as i32 + 1
    } else {
        floor as i32
    }
}

/// `ModelQuadUtil.mixARGBColors` : le produit de deux couleurs, −1 valant « aucune ».
fn mix_colors(a: u32, b: u32) -> u32 {
    if a == u32::MAX {
        return b;
    }
    if b == u32::MAX {
        return a;
    }
    let channel = |shift: u32| -> u32 {
        let ca = ((a >> shift) & 0xFF) as i32 as f32 / 255.0;
        let cb = ((b >> shift) & 0xFF) as i32 as f32 / 255.0;
        ((ca * cb * 255.0) as i32 as u32) << shift
    };
    channel(24) | channel(16) | channel(8) | channel(0)
}

/// `ColorMixer.mulSingleWithoutAlpha` : assombrit les trois couleurs, garde l'opacité.
fn darken(color: u32, factor: u32) -> u32 {
    let factor = factor & 0xFF;
    let c0 = (((color & 0xFF) * factor) + 0xFF) >> 8;
    let c1 = ((((color >> 8) & 0xFF) * factor) + 0xFF) >> 8;
    let c2 = ((((color >> 16) & 0xFF) * factor) + 0xFF) >> 8;
    c0 | c1 << 8 | c2 << 16 | (color & 0xFF00_0000)
}

/// `ChunkMeshBufferBuilder.encodeBilinearCorrection`, pour les quatre composantes.
fn bilinear_correction(colors: &[u32; 4]) -> u32 {
    let mut packed = 0;
    for shift in [0u32, 8, 16, 24] {
        let c = |i: usize| ((colors[i] >> shift) & 0xFF) as i32;
        let factor = c(3) + c(1) - c(0) - c(2);
        packed |= ((java_round(factor as f32 * (127.0f32 / 510.0f32)) & 0xFF) as u32) << shift;
    }
    packed
}

/// Le décalage des herbes et des fleurs, selon la position du bloc. Formule de vanilla.
fn model_offset(kind: u32, world_x: i32, world_z: i32) -> [f32; 3] {
    if kind == 0 {
        return [0.0; 3];
    }
    let mut seed = (world_x.wrapping_mul(3129871) as i64) ^ (world_z as i64).wrapping_mul(116129781);
    seed = seed.wrapping_mul(seed).wrapping_mul(42317861).wrapping_add(seed.wrapping_mul(11));
    let x = ((((seed >> 16 & 15) as f32 / 15.0f32) as f64 - 0.5) * 0.5) as f32;
    let z = ((((seed >> 24 & 15) as f32 / 15.0f32) as f64 - 0.5) * 0.5) as f32;
    let y = if kind == 2 { ((((seed >> 20 & 15) as f32 / 15.0f32) as f64 - 1.0) * 0.2) as f32 } else { 0.0 };
    [x, y, z]
}

/// Écrit les quads des commandes dans les tampons de destination.
///
/// # Safety
/// Chaque commande doit porter l'adresse d'un `QuadRecord` valide, et chaque
/// tampon de `dests` avoir la place pour tous les quads qui lui reviennent
/// (4 sommets de `VERTEX_BYTES` octets chacun).
pub unsafe fn emit_quads(light: &mut LightReader, commands: &[Command], dests: &mut [*mut u8], section_index: u32) {
    let base = light.base();
    let mut lighter = Lighter::new();
    let mut quad_light = QuadLight::default();

    // Ce qui se garde d'un quad à l'autre d'un même bloc.
    let mut block_key = u32::MAX;
    let mut offset = [0.0f32; 3];
    // Le sens de découpe d'une face, choisi sur son premier quad : deux quads
    // d'une même face coupés différemment laisseraient voir un pli.
    let mut face_key = u32::MAX;
    let mut face_flipped = false;

    for command in commands {
        let quad = &*((command.quad_low as u64 | (command.quad_high as u64) << 32) as usize as *const QuadRecord);
        let packed = command.packed;
        let (lx, ly, lz) = ((packed & 15) as i32, ((packed >> 4) & 15) as i32, ((packed >> 8) & 15) as i32);
        let cull_face = ((packed >> CMD_CULL_SHIFT) & 7) as usize;

        let key = packed & 0xFFF;
        if key != block_key {
            block_key = key;
            face_key = u32::MAX;
            offset = model_offset((packed >> CMD_OFFSET_SHIFT) & 3, base[0] + lx, base[2] + lz);
        }

        let shape = QuadShape {
            x: &quad.x,
            y: &quad.y,
            z: &quad.z,
            flags: quad.flags,
            light_face: (quad.faces & 0xFF) as usize,
            normal_face: ((quad.faces >> 8) & 0xFF) as usize,
            computed_normal: quad.computed_normal,
        };
        if packed & CMD_SMOOTH != 0 {
            lighter.smooth(light, &shape, lx, ly, lz, &mut quad_light);
        } else {
            flat(light, &shape, lx, ly, lz, cull_face, &mut quad_light);
        }

        // `ModelQuadOrientation.orientByBrightness` : la diagonale la plus claire sert de pli.
        let flipped = if cull_face == UNASSIGNED {
            false
        } else {
            let this_face = key | (cull_face as u32) << 12;
            if this_face != face_key {
                face_key = this_face;
                let br02 = quad_light.br[0] + quad_light.br[2];
                let br13 = quad_light.br[1] + quad_light.br[3];
                face_flipped = if br02 > br13 {
                    false
                } else if br02 < br13 {
                    true
                } else {
                    // Sommes d'entiers signés côté Java : même débordement ici.
                    let lm02 = (quad_light.lm[0] as i32).wrapping_add(quad_light.lm[2] as i32);
                    let lm13 = (quad_light.lm[1] as i32).wrapping_add(quad_light.lm[3] as i32);
                    lm02 > lm13
                };
            }
            face_flipped
        };

        let origin = [lx as f32 + offset[0], ly as f32 + offset[1], lz as f32 + offset[2]];
        let mut colors = [0u32; 4];
        let mut sources = [0usize; 4];
        for dst in 0..4 {
            let src = if flipped { (dst + 1) & 3 } else { dst };
            sources[dst] = src;
            colors[dst] = darken(mix_colors(command.tint, quad.color[src]), (quad_light.br[src] * 255.0) as i32 as u32);
        }
        let correction = bilinear_correction(&colors);

        let bucket = ((packed >> CMD_BUCKET_SHIFT) & CMD_BUCKET_MASK) as usize;
        let material = (packed >> CMD_MATERIAL_SHIFT) & 0xFF;
        let parameters = (section_index & 0xFF) << 8 | material;

        let mut out = dests[bucket] as *mut u32;
        for dst in 0..4 {
            let src = sources[dst];
            let lm = quad_light.lm[src];
            let encoded_light = (lm & 0xFF) | ((lm >> 16) & 0xFF) << 8;

            (out as *mut f32).write_unaligned(origin[0] + quad.x[src]);
            (out.add(1) as *mut f32).write_unaligned(origin[1] + quad.y[src]);
            (out.add(2) as *mut f32).write_unaligned(origin[2] + quad.z[src]);
            out.add(3).write_unaligned(colors[dst]);
            (out.add(4) as *mut f32).write_unaligned(quad.u[src].min(0.99999997f32));
            (out.add(5) as *mut f32).write_unaligned(quad.v[src].min(0.99999997f32));
            out.add(6).write_unaligned(parameters | encoded_light << 16);
            out.add(7).write_unaligned(correction);
            out = out.add(VERTEX_BYTES / 4);
        }
        dests[bucket] = out as *mut u8;
    }
}

#[cfg(test)]
mod tests {
    use super::super::light::tests::{props, set, workspace, STONE};
    use super::super::light::POS_Y;
    use super::*;

    fn top_quad() -> QuadRecord {
        QuadRecord {
            x: [0.0, 0.0, 1.0, 1.0],
            y: [1.0; 4],
            z: [0.0, 1.0, 1.0, 0.0],
            u: [0.0, 0.0, 0.5, 0.5],
            v: [0.0, 0.5, 0.5, 2.0],
            color: [0xFFFF_FFFF; 4],
            flags: 0b110,
            faces: (POS_Y | POS_Y << 8) as u32,
            computed_normal: 0x007F00,
            reserved: 0,
        }
    }

    fn command(quad: &QuadRecord, x: u32, y: u32, z: u32, extra: u32, tint: u32) -> Command {
        let address = quad as *const QuadRecord as u64;
        Command { quad_low: address as u32, quad_high: (address >> 32) as u32, packed: x | y << 4 | z << 8 | extra, tint }
    }

    fn words(bytes: &[u8]) -> Vec<u32> {
        bytes.chunks(4).map(|c| u32::from_le_bytes(c.try_into().unwrap())).collect()
    }

    #[test]
    fn couleurs() {
        assert_eq!(mix_colors(u32::MAX, 0x1234_5678), 0x1234_5678);
        assert_eq!(mix_colors(0xFF80_80FF, 0xFFFF_FFFF), 0xFF80_80FF);
        assert_eq!(mix_colors(0xFF80_4020, 0xFF80_8080), 0xFF40_2010);
        assert_eq!(darken(0xFFFF_FFFF, 255), 0xFFFF_FFFF);
        assert_eq!(darken(0x80FF_8040, 128), 0x8080_4020);
        assert_eq!(java_round(63.5), 64);
        assert_eq!(java_round(-63.5), -63);
        assert_eq!(java_round(0.49999997), 0);
        assert_eq!(bilinear_correction(&[0xFFFF_FFFF; 4]), 0);
    }

    #[test]
    fn decalage_des_plantes() {
        assert_eq!(model_offset(0, 12, -7), [0.0; 3]);
        let [x, y, z] = model_offset(2, 12, -7);
        assert!(x.abs() <= 0.25 && z.abs() <= 0.25 && (-0.2..=0.0).contains(&y));
        assert_eq!(model_offset(1, 12, -7), [x, 0.0, z]);
    }

    /// Un quad du dessus, en plein jour, avec une teinte : position, couleur, texture, lumière, matériau.
    #[test]
    fn un_quad_ecrit() {
        let props = props();
        let mut ws = workspace();
        set(&mut ws, 3, 4, 5, STONE, 0, 0);
        let mut light = LightReader::new(&mut ws, &props);

        let quad = top_quad();
        let extra = (POS_Y as u32) << CMD_CULL_SHIFT | CMD_SMOOTH | 1 << CMD_BUCKET_SHIFT | 0x5 << CMD_MATERIAL_SHIFT;
        let commands = [command(&quad, 3, 4, 5, extra, 0xFF20_4080)];

        let mut buffers = [[0u8; 4 * VERTEX_BYTES]; 2];
        let mut dests = [buffers[0].as_mut_ptr(), buffers[1].as_mut_ptr()];
        unsafe { emit_quads(&mut light, &commands, &mut dests, 0x1AB) };

        // Rien dans le premier tampon, quatre sommets dans le second.
        assert_eq!(dests[0], buffers[0].as_mut_ptr());
        assert_eq!(dests[1] as usize - buffers[1].as_ptr() as usize, 4 * VERTEX_BYTES);
        assert!(buffers[0].iter().all(|&b| b == 0));

        let out = words(&buffers[1]);
        // Pas d'ombre : le sens de découpe reste celui du modèle, sommet 0 d'abord.
        assert_eq!(f32::from_bits(out[0]), 3.0);
        assert_eq!(f32::from_bits(out[1]), 5.0);
        assert_eq!(f32::from_bits(out[2]), 5.0);
        assert_eq!(out[3], 0xFF20_4080);
        assert_eq!(f32::from_bits(out[4]), 0.0);
        // Ciel à 15 (240 dans la texture de lumière), blocs à 0 ; section 0xAB, matériau 5.
        assert_eq!(out[6], 0xF000_AB05);
        assert_eq!(out[7], 0);
        // Dernier sommet : x = 1, et la coordonnée de texture au-delà de 1 est ramenée juste en dessous.
        assert_eq!(f32::from_bits(out[24]), 4.0);
        assert_eq!(f32::from_bits(out[29]), 0.99999994);
    }

    /// Deux quads d'une même face gardent le sens de découpe du premier.
    #[test]
    fn sens_de_decoupe_garde_par_face() {
        let props = props();
        let mut ws = workspace();
        set(&mut ws, 5, 5, 5, STONE, 0, 0);
        // Un voisin en diagonale, côté x = 0 et z = 0 : le sommet 0 s'assombrit seul.
        set(&mut ws, 4, 6, 4, STONE, 0, 0);
        let mut light = LightReader::new(&mut ws, &props);

        let quad = top_quad();
        let extra = (POS_Y as u32) << CMD_CULL_SHIFT | CMD_SMOOTH;
        let commands = [command(&quad, 5, 5, 5, extra, u32::MAX), command(&quad, 5, 5, 5, extra, u32::MAX)];

        let mut buffer = [0u8; 8 * VERTEX_BYTES];
        let mut dests = [buffer.as_mut_ptr()];
        unsafe { emit_quads(&mut light, &commands, &mut dests, 0) };

        let out = words(&buffer);
        // La diagonale 0-2 est la plus sombre : le quad est retourné, il commence par le sommet 1 (x = 0, z = 1).
        assert_eq!((f32::from_bits(out[0]), f32::from_bits(out[2])), (5.0, 6.0));
        assert_eq!(out[..32], out[32..]);
        // Le sommet assombri est écrit en dernier, avec une correction de mélange non nulle.
        assert!(out[3 * 8 + 3] & 0xFF < 0xFF);
        assert_ne!(out[7], 0);
    }
}
