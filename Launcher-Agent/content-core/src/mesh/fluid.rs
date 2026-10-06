//! Le rendu de l'eau et de la lave.
//!
//! Port du rendu de fluides de la 1.8.9, relevé dans le bytecode du jeu :
//! `FluidRenderer.render` (`bge`) et ce qu'il appelle dans
//! `AbstractFluidBlock` (`ahv`) — faces à dessiner, hauteur des quatre coins,
//! sens du courant, lumière. Tout se déduit des états de blocs voisins et de
//! la lumière : le noyau n'a rien à demander au jeu, sauf la teinte de l'eau
//! (elle dépend du biome), que Java donne dans la commande.
//!
//! **Même image que vanilla, au bit près** : les calculs sont faits dans le
//! même ordre et avec les mêmes types (positions en double précision à partir
//! des coordonnées du monde, comme le tampon de sommets du jeu). C'est ce que
//! vérifie le contrôle en jeu, contre le rendu vanilla lui-même.
//!
//! L'éclairage est donc celui de vanilla pour les fluides : à plat, une
//! lumière par face, sans occlusion ambiante.

use super::light::{
    LightReader, FLUID_LAVA, PROP_BLOCKS_MOVEMENT, PROP_FLUID_SHIFT, PROP_FULL_BLOCK, PROP_FULL_OPAQUE, PROP_ICE,
    PROP_MATERIAL_SOLID, UNASSIGNED,
};

/// Le courant va de −32 à 32 sur chaque axe dans la table de Java.
pub const FLOW_RANGE: i32 = 32;
const FLOW_SIDE: usize = (2 * FLOW_RANGE + 1) as usize;
/// Valeurs avant la table du courant : quatre textures de quatre bornes.
const SPRITE_VALUES: usize = 16;
/// Longueur du tableau tendu par Java (`FluidData189`) : les bornes des
/// quatre textures (lave immobile, lave qui coule, eau immobile, eau qui
/// coule — chacune `minU, maxU, minV, maxV`), puis la table du courant : pour
/// chaque (x, z, chute), le sinus et le cosinus de l'angle déjà multipliés
/// par 0,25, ou deux NaN s'il n'y a pas de courant.
pub const FLUID_DATA_LEN: usize = SPRITE_VALUES + FLOW_SIDE * FLOW_SIDE * 2 * 2;

pub struct FluidData<'a> {
    pub values: &'a [f32; FLUID_DATA_LEN],
}

/// Un quad de fluide prêt à recopier : 4 sommets de 8 mots.
pub struct FluidQuad {
    /// Face de sa normale (`ModelQuadFacing`), qui choisit son tampon dans une passe non triée.
    pub facing: u8,
    pub words: [u32; 32],
}

#[derive(Clone, Copy)]
struct Sprite {
    min_u: f32,
    max_u: f32,
    min_v: f32,
    max_v: f32,
}

impl Sprite {
    /// `Sprite.getInterpolatedU` : la multiplication avant la division.
    fn u(&self, at: f32) -> f32 {
        let span = self.max_u - self.min_u;
        self.min_u + span * at / 16.0
    }

    /// `Sprite.getInterpolatedV` : la division avant la multiplication.
    fn v(&self, at: f32) -> f32 {
        let span = self.max_v - self.min_v;
        self.min_v + span * (at / 16.0)
    }
}

/// `AbstractFluidBlock.getHeightPercent` : la part vide d'un bloc de fluide de ce niveau.
fn level_percent(level: u32) -> f32 {
    let level = if level >= 8 { 0 } else { level };
    (level + 1) as f32 / 9.0
}

struct Fluid<'a, 'b> {
    light: &'a LightReader<'b>,
    kind: u32,
}

impl Fluid<'_, '_> {
    fn is_same(&self, x: i32, y: i32, z: i32) -> bool {
        (self.light.props_at(x, y, z) >> PROP_FLUID_SHIFT) & 3 == self.kind
    }

    /// `AbstractFluidBlock.isSideInvisible` (nom Yarn trompeur : vrai = la face se dessine).
    fn side_drawn(&self, x: i32, y: i32, z: i32, up: bool) -> bool {
        let props = self.light.props_at(x, y, z);
        if (props >> PROP_FLUID_SHIFT) & 3 == self.kind {
            return false;
        }
        up || props & PROP_FULL_OPAQUE == 0
    }

    /// La hauteur du fluide à un coin : moyenne des quatre blocs qui le touchent.
    fn corner_height(&self, x: i32, y: i32, z: i32) -> f32 {
        let mut count = 0;
        let mut total = 0.0f32;
        for j in 0..4 {
            let (qx, qz) = (x - (j & 1), z - ((j >> 1) & 1));
            if self.is_same(qx, y + 1, qz) {
                return 1.0;
            }
            let props = self.light.props_at(qx, y, qz);
            if (props >> PROP_FLUID_SHIFT) & 3 == self.kind {
                let level = (self.light.state_at(qx, y, qz) & 15) as u32;
                if level >= 8 || level == 0 {
                    total += level_percent(level) * 10.0;
                    count += 10;
                }
                total += level_percent(level);
                count += 1;
            } else if props & PROP_MATERIAL_SOLID == 0 {
                total += 1.0;
                count += 1;
            }
        }
        1.0 - total / count as f32
    }

    /// Le niveau du fluide pour le courant : −1 si ce n'est pas le même fluide, 0 s'il tombe.
    fn flow_level(&self, x: i32, y: i32, z: i32) -> i32 {
        if !self.is_same(x, y, z) {
            return -1;
        }
        let level = (self.light.state_at(x, y, z) & 15) as i32;
        if level >= 8 {
            0
        } else {
            level
        }
    }

    /// `AbstractFluidBlock.isBlockSolid` pour un côté : un bloc qui retient le fluide.
    fn holds_fluid(&self, x: i32, y: i32, z: i32) -> bool {
        let props = self.light.props_at(x, y, z);
        if (props >> PROP_FLUID_SHIFT) & 3 == self.kind || props & PROP_ICE != 0 {
            return false;
        }
        props & PROP_MATERIAL_SOLID != 0
    }

    /// Le courant, avant normalisation (`AbstractFluidBlock.getFlowVector`) : ses
    /// deux composantes entières, et s'il faut lui ajouter la chute.
    fn flow(&self, x: i32, y: i32, z: i32) -> (i32, i32, bool) {
        const SIDES: [(i32, i32); 4] = [(0, -1), (1, 0), (0, 1), (-1, 0)];
        let own = self.flow_level(x, y, z);
        let (mut fx, mut fz) = (0, 0);
        for (dx, dz) in SIDES {
            let (nx, nz) = (x + dx, z + dz);
            let level = self.flow_level(nx, y, nz);
            if level < 0 {
                if self.light.props_at(nx, y, nz) & PROP_BLOCKS_MOVEMENT == 0 {
                    let below = self.flow_level(nx, y - 1, nz);
                    if below >= 0 {
                        let weight = below - (own - 8);
                        fx += dx * weight;
                        fz += dz * weight;
                    }
                }
            } else {
                let weight = level - own;
                fx += dx * weight;
                fz += dz * weight;
            }
        }

        let mut falling = false;
        if self.light.state_at(x, y, z) & 15 >= 8 {
            for (dx, dz) in SIDES {
                if self.holds_fluid(x + dx, y, z + dz) || self.holds_fluid(x + dx, y + 1, z + dz) {
                    falling = true;
                    break;
                }
            }
        }
        (fx, fz, falling)
    }

    /// `AbstractFluidBlock.g` : vrai si, autour de ce bloc, quelque chose
    /// laisse voir la surface par en dessous.
    fn surface_seen_from_below(&self, x: i32, y: i32, z: i32) -> bool {
        for dx in -1..=1 {
            for dz in -1..=1 {
                let props = self.light.props_at(x + dx, y, z + dz);
                if (props >> PROP_FLUID_SHIFT) & 3 != self.kind && props & PROP_FULL_BLOCK == 0 {
                    return true;
                }
            }
        }
        false
    }
}

/// La face de la normale d'un quad, comme `QuadUtil.calculateNormal` puis `findNormalFace`.
fn facing_of(p: &[[f32; 3]; 4]) -> u8 {
    let (dx0, dy0, dz0) = (p[2][0] - p[0][0], p[2][1] - p[0][1], p[2][2] - p[0][2]);
    let (dx1, dy1, dz1) = (p[3][0] - p[1][0], p[3][1] - p[1][1], p[3][2] - p[1][2]);

    let mut nx = dy0 * dz1 - dz0 * dy1;
    let mut ny = dz0 * dx1 - dx0 * dz1;
    let mut nz = dx0 * dy1 - dy0 * dx1;
    let length = ((nx * nx + ny * ny + nz * nz) as f64).sqrt() as f32;
    if length != 0.0 {
        nx /= length;
        ny /= length;
        nz /= length;
    }

    // La normale passe par un octet signé par composante, comme côté Java.
    let through_byte = |c: f32| -> f32 {
        let clamped = if c < -1.0 { -1.0 } else if c > 1.0 { 1.0 } else { c };
        ((clamped * 127.0) as i32 & 255) as u8 as i8 as f32 * (1.0f32 / 127.0f32)
    };
    let (x, y, z) = (through_byte(nx), through_byte(ny), through_byte(nz));

    let mut best = UNASSIGNED;
    let mut max_dot = 0.0f32;
    for (face, dot) in [x, y, z, -x, -y, -z].into_iter().enumerate() {
        if dot > max_dot {
            max_dot = dot;
            best = face;
        }
    }
    if best != UNASSIGNED && (max_dot - 1.0).abs() < 1.0e-5 {
        best as u8
    } else {
        UNASSIGNED as u8
    }
}

fn color_of(r: f32, g: f32, b: f32) -> u32 {
    let byte = |c: f32| (c * 255.0) as i32 as u32 & 0xFF;
    0xFF00_0000 | byte(b) << 16 | byte(g) << 8 | byte(r)
}

/// Les quads d'un bloc de fluide, ajoutés à `out` dans l'ordre où vanilla les dessine.
///
/// `tint` : la teinte du bloc (rouge, vert, bleu dans les trois octets bas).
/// `parameters` : ce qui va dans chaque sommet avec la lumière (matériau, section).
pub fn fluid_quads(light: &LightReader, data: &FluidData, x: i32, y: i32, z: i32, tint: u32, parameters: u32, out: &mut Vec<FluidQuad>) {
    let kind = (light.props_at(x, y, z) >> PROP_FLUID_SHIFT) & 3;
    if kind == 0 {
        return;
    }
    let fluid = Fluid { light, kind };

    let up = fluid.side_drawn(x, y + 1, z, true);
    let down = fluid.side_drawn(x, y - 1, z, false);
    // Nord, sud, ouest, est : l'ordre de vanilla.
    let sides = [
        fluid.side_drawn(x, y, z - 1, false),
        fluid.side_drawn(x, y, z + 1, false),
        fluid.side_drawn(x - 1, y, z, false),
        fluid.side_drawn(x + 1, y, z, false),
    ];
    if !up && !down && !sides.iter().any(|&s| s) {
        return;
    }

    let sprite = |index: usize| -> Sprite {
        let at = (if kind == FLUID_LAVA { 0 } else { 8 }) + index * 4;
        Sprite { min_u: data.values[at], max_u: data.values[at + 1], min_v: data.values[at + 2], max_v: data.values[at + 3] }
    };
    let (still, flowing) = (sprite(0), sprite(1));

    let red = ((tint >> 16) & 255) as i32 as f32 / 255.0;
    let green = ((tint >> 8) & 255) as i32 as f32 / 255.0;
    let blue = (tint & 255) as i32 as f32 / 255.0;

    let mut h00 = fluid.corner_height(x, y, z);
    let mut h01 = fluid.corner_height(x, y, z + 1);
    let mut h11 = fluid.corner_height(x + 1, y, z + 1);
    let mut h10 = fluid.corner_height(x + 1, y, z);

    // Vanilla calcule en double à partir des coordonnées du monde, puis son
    // tampon ajoute le décalage de la section et arrondit en simple.
    let base = light.base();
    let (wx, wy, wz) = ((base[0] + x) as f64, (base[1] + y) as f64, (base[2] + z) as f64);
    let offset = [-(base[0] as f64), -(base[1] as f64), -(base[2] as f64)];
    let epsilon = 0.001f32;

    let mut push = |corners: [(f64, f64, f64, f32, f32); 4], color: u32, lightmap: u32| {
        let mut positions = [[0.0f32; 3]; 4];
        let mut words = [0u32; 32];
        let encoded_light = (lightmap & 0xFF) | ((lightmap >> 16) & 0xFF) << 8;
        for (i, &(px, py, pz, u, v)) in corners.iter().enumerate() {
            positions[i] = [(px + offset[0]) as f32, (py + offset[1]) as f32, (pz + offset[2]) as f32];
            let at = i * 8;
            words[at] = positions[i][0].to_bits();
            words[at + 1] = positions[i][1].to_bits();
            words[at + 2] = positions[i][2].to_bits();
            words[at + 3] = color;
            words[at + 4] = u.min(0.99999997f32).to_bits();
            words[at + 5] = v.min(0.99999997f32).to_bits();
            words[at + 6] = parameters | encoded_light << 16;
            // Couleur unie sur le quad : pas de correction de mélange.
            words[at + 7] = 0;
        }
        out.push(FluidQuad { facing: facing_of(&positions), words });
    };

    if up {
        let (fx, fz, falling) = fluid.flow(x, y, z);
        let entry = if fx.abs() > FLOW_RANGE || fz.abs() > FLOW_RANGE {
            None
        } else {
            let index = (fx + FLOW_RANGE) as usize + FLOW_SIDE * ((fz + FLOW_RANGE) as usize + FLOW_SIDE * falling as usize);
            let (sin, cos) = (data.values[SPRITE_VALUES + index * 2], data.values[SPRITE_VALUES + index * 2 + 1]);
            if sin.is_nan() {
                None
            } else {
                Some((sin, cos))
            }
        };

        h00 -= epsilon;
        h01 -= epsilon;
        h11 -= epsilon;
        h10 -= epsilon;

        // Coordonnées de texture des coins (0,0), (0,1), (1,1), (1,0).
        let (u0, v0, u1, v1, u2, v2, u3, v3) = match entry {
            None => {
                let (u_low, v_low, v_high, u_high) = (still.u(0.0), still.v(0.0), still.v(16.0), still.u(16.0));
                (u_low, v_low, u_low, v_high, u_high, v_high, u_high, v_low)
            }
            Some((sin, cos)) => (
                flowing.u(8.0 + (-cos - sin) * 16.0),
                flowing.v(8.0 + (-cos + sin) * 16.0),
                flowing.u(8.0 + (-cos + sin) * 16.0),
                flowing.v(8.0 + (cos + sin) * 16.0),
                flowing.u(8.0 + (cos + sin) * 16.0),
                flowing.v(8.0 + (cos - sin) * 16.0),
                flowing.u(8.0 + (cos - sin) * 16.0),
                flowing.v(8.0 + (-cos - sin) * 16.0),
            ),
        };

        let lightmap = light.fluid_light(x, y, z);
        let color = color_of(1.0 * red, 1.0 * green, 1.0 * blue);
        let c00 = (wx + 0.0, wy + h00 as f64, wz + 0.0, u0, v0);
        let c01 = (wx + 0.0, wy + h01 as f64, wz + 1.0, u1, v1);
        let c11 = (wx + 1.0, wy + h11 as f64, wz + 1.0, u2, v2);
        let c10 = (wx + 1.0, wy + h10 as f64, wz + 0.0, u3, v3);
        push([c00, c01, c11, c10], color, lightmap);
        if fluid.surface_seen_from_below(x, y + 1, z) {
            push([c00, c10, c11, c01], color, lightmap);
        }
    }

    if down {
        let lightmap = light.fluid_light(x, y - 1, z);
        let color = color_of(0.5, 0.5, 0.5);
        push(
            [
                (wx, wy, wz + 1.0, still.min_u, still.max_v),
                (wx, wy, wz, still.min_u, still.min_v),
                (wx + 1.0, wy, wz, still.max_u, still.min_v),
                (wx + 1.0, wy, wz + 1.0, still.max_u, still.max_v),
            ],
            color,
            lightmap,
        );
    }

    let e = epsilon as f64;
    for side in 0..4 {
        if !sides[side] {
            continue;
        }
        // Les deux coins du haut de la face, leurs hauteurs, et le voisin dont elle prend la lumière.
        let (ha, hb, xa, xb, za, zb, nx, nz) = match side {
            0 => (h00, h10, wx, wx + 1.0, wz + e, wz + e, x, z - 1),
            1 => (h11, h01, wx + 1.0, wx, wz + 1.0 - e, wz + 1.0 - e, x, z + 1),
            2 => (h01, h00, wx + e, wx + e, wz + 1.0, wz, x - 1, z),
            _ => (h10, h11, wx + 1.0 - e, wx + 1.0 - e, wz, wz + 1.0, x + 1, z),
        };

        let (u_a, u_b) = (flowing.u(0.0), flowing.u(8.0));
        let v_a = flowing.v((1.0 - ha) * 16.0 * 0.5);
        let v_b = flowing.v((1.0 - hb) * 16.0 * 0.5);
        let v_low = flowing.v(8.0);

        let lightmap = light.fluid_light(nx, y, nz);
        let shade = if side < 2 { 0.8f32 } else { 0.6f32 };
        let color = color_of(1.0 * shade * red, 1.0 * shade * green, 1.0 * shade * blue);

        let top_a = (xa, wy + ha as f64, za, u_a, v_a);
        let top_b = (xb, wy + hb as f64, zb, u_b, v_b);
        let low_b = (xb, wy + 0.0, zb, u_b, v_low);
        let low_a = (xa, wy + 0.0, za, u_a, v_low);
        // Deux fois, une par côté : la face se voit aussi de l'intérieur du fluide.
        push([top_a, top_b, low_b, low_a], color, lightmap);
        push([low_a, low_b, top_b, top_a], color, lightmap);
    }
}

#[cfg(test)]
mod tests {
    use super::super::light::tests::{props, set, workspace, STONE};
    use super::super::light::{FLUID_WATER, NEG_Y, NEG_Z, POS_Y, PROP_AO_SHIFT};
    use super::*;

    const WATER: u16 = 9 << 4;

    fn fluid_props() -> Box<[u32; super::super::light::STATES]> {
        let mut props = props();
        for level in 0..16u16 {
            props[(WATER | level) as usize] = FLUID_WATER << PROP_FLUID_SHIFT | PROP_FULL_BLOCK | 4096 << PROP_AO_SHIFT;
        }
        props[STONE as usize] |= PROP_MATERIAL_SOLID | PROP_BLOCKS_MOVEMENT | PROP_FULL_BLOCK;
        props
    }

    fn data() -> Box<[f32; FLUID_DATA_LEN]> {
        let mut values: Box<[f32; FLUID_DATA_LEN]> = vec![f32::NAN; FLUID_DATA_LEN].into_boxed_slice().try_into().unwrap();
        // Quatre textures côte à côte, d'un seizième de l'atlas chacune.
        for sprite in 0..4 {
            let left = sprite as f32 / 16.0;
            values[sprite * 4..sprite * 4 + 4].copy_from_slice(&[left, left + 1.0 / 16.0, 0.0, 1.0 / 16.0]);
        }
        // Un courant vers +x : angle nul, sinus 0, cosinus 0,25.
        let index = (1 + FLOW_RANGE) as usize + FLOW_SIDE * FLOW_RANGE as usize;
        values[SPRITE_VALUES + index * 2] = 0.0;
        values[SPRITE_VALUES + index * 2 + 1] = 0.25;
        values
    }

    fn y_of(quad: &FluidQuad, vertex: usize) -> f32 {
        f32::from_bits(quad.words[vertex * 8 + 1])
    }

    /// Une flaque d'un bloc posée sur de la pierre : le dessus et les quatre côtés, pas le dessous.
    #[test]
    fn flaque_isolee() {
        let props = fluid_props();
        let mut ws = workspace();
        set(&mut ws, 4, 3, 4, STONE, 0, 0);
        set(&mut ws, 4, 4, 4, WATER, 0, 15);
        let light = LightReader::new(&mut ws, &props);
        let values = data();
        let mut quads = Vec::new();
        fluid_quads(&light, &FluidData { values: &values }, 4, 4, 4, 0x3F76E4, 0xAB05, &mut quads);

        // Dessus vu des deux côtés (rien ne cache la surface par en dessous), puis deux quads par côté.
        assert_eq!(quads.len(), 2 + 4 * 2);
        assert_eq!(quads[0].facing as usize, POS_Y);
        assert_eq!(quads[1].facing as usize, NEG_Y);
        assert_eq!(quads[2].facing as usize, NEG_Z);

        // Eau à plein niveau entourée d'air : chaque coin compte le bloc onze fois, et trois vides.
        let expected = (4.0 + (1.0f32 - (1.0f32 / 9.0 * 10.0 + 1.0 / 9.0 + 3.0) / 14.0)) - 0.001;
        assert!((y_of(&quads[0], 0) - expected).abs() < 1.0e-5, "{}", y_of(&quads[0], 0));

        // Teinte, lumière du ciel au maximum, matériau et section recopiés.
        assert_eq!(quads[0].words[3], color_of(0x3F as f32 / 255.0, 0x76 as f32 / 255.0, 0xE4 as f32 / 255.0));
        assert_eq!(quads[0].words[6], 0xF000_AB05);
        // Les côtés nord et sud sont assombris à 0,8.
        assert_eq!(quads[2].words[3] & 0xFF, ((0.8f32 * (0x3F as f32 / 255.0)) * 255.0) as u32);
    }

    /// Au milieu d'une étendue d'eau, sous de l'eau : rien à dessiner.
    #[test]
    fn eau_entouree_d_eau() {
        let props = fluid_props();
        let mut ws = workspace();
        for dx in -1..=1 {
            for dy in -1..=1 {
                for dz in -1..=1 {
                    set(&mut ws, 8 + dx, 8 + dy, 8 + dz, WATER, 0, 0);
                }
            }
        }
        let light = LightReader::new(&mut ws, &props);
        let values = data();
        let mut quads = Vec::new();
        fluid_quads(&light, &FluidData { values: &values }, 8, 8, 8, 0xFFFFFF, 0, &mut quads);
        assert!(quads.is_empty());
    }

    /// Une surface d'eau calme : seul le dessus se dessine, avec la texture immobile.
    #[test]
    fn surface_calme() {
        let props = fluid_props();
        let mut ws = workspace();
        for dx in -2..=2 {
            for dz in -2..=2 {
                set(&mut ws, 8 + dx, 7, 8 + dz, STONE, 0, 0);
                set(&mut ws, 8 + dx, 8, 8 + dz, WATER, 0, 15);
            }
        }
        let light = LightReader::new(&mut ws, &props);
        let values = data();
        let mut quads = Vec::new();
        fluid_quads(&light, &FluidData { values: &values }, 8, 8, 8, 0xFFFFFF, 0, &mut quads);

        // Le dessus, deux fois : sous de l'air, la surface se voit aussi par en dessous.
        assert_eq!(quads.len(), 2);
        // Texture de l'eau immobile : la troisième, de 2/16 à 3/16 en largeur.
        assert_eq!(f32::from_bits(quads[0].words[4]), 2.0 / 16.0);
        assert_eq!(f32::from_bits(quads[0].words[2 * 8 + 4]), 3.0 / 16.0);
    }

    /// De l'eau plus basse à l'est : le courant va vers +x, et le dessus prend la texture qui coule.
    #[test]
    fn courant() {
        let props = fluid_props();
        let mut ws = workspace();
        for dx in -2..=2 {
            for dz in -2..=2 {
                set(&mut ws, 8 + dx, 7, 8 + dz, STONE, 0, 0);
            }
        }
        set(&mut ws, 8, 8, 8, WATER | 2, 0, 15);
        set(&mut ws, 9, 8, 8, WATER | 3, 0, 15);
        set(&mut ws, 7, 8, 8, STONE, 0, 0);
        set(&mut ws, 8, 8, 7, STONE, 0, 0);
        set(&mut ws, 8, 8, 9, STONE, 0, 0);
        let light = LightReader::new(&mut ws, &props);

        let fluid = Fluid { light: &light, kind: FLUID_WATER };
        assert_eq!(fluid.flow(8, 8, 8), (1, 0, false));

        let values = data();
        let mut quads = Vec::new();
        fluid_quads(&light, &FluidData { values: &values }, 8, 8, 8, 0xFFFFFF, 0, &mut quads);
        // Texture de l'eau qui coule : la quatrième, à partir de 3/16.
        let u = f32::from_bits(quads[0].words[4]);
        assert!((3.0 / 16.0..=4.0 / 16.0).contains(&u), "{u}");
    }
}
