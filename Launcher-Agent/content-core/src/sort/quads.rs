//! Ce que le tri du translucide retient de chaque quad : son centre, sa
//! boîte, sa normale. Port de `TranslucentQuadRecorder` et `QuadSet` du
//! moteur Java (`celeritas/chunk/sorting`), mêmes calculs dans le même ordre.

pub const EPSILON: f32 = 1e-4;
pub const OVERLAP_EPSILON: f32 = 0.008;

/// Face « aucune » de `ModelQuadFacing`.
pub const UNASSIGNED: u8 = 6;

#[derive(Default)]
pub struct QuadSet {
    pub count: usize,
    /// Trois valeurs par quad.
    pub centers: Vec<f32>,
    /// Six valeurs par quad : les trois minimums, puis les trois maximums.
    pub bounds: Vec<f32>,
    pub normals: Vec<f32>,
    /// La normale projetée sur le centre : la position du plan du quad.
    pub dots: Vec<f32>,
    pub facings: Vec<u8>,
}

/// `Math.min` de Java pour des flottants ordinaires.
fn min(a: f32, b: f32) -> f32 {
    if b < a {
        b
    } else {
        a
    }
}

fn max(a: f32, b: f32) -> f32 {
    if b > a {
        b
    } else {
        a
    }
}

/// `QuadUtil.findNormalFace` : la face du bloc que regarde cette normale, si elle en regarde une.
fn facing_of(x: f32, y: f32, z: f32) -> u8 {
    if !x.is_finite() || !y.is_finite() || !z.is_finite() {
        return UNASSIGNED;
    }
    let mut best = UNASSIGNED;
    let mut max_dot = 0.0f32;
    for (face, dot) in [x, y, z, -x, -y, -z].into_iter().enumerate() {
        if dot > max_dot {
            max_dot = dot;
            best = face as u8;
        }
    }
    if best != UNASSIGNED && (max_dot - 1.0).abs() < 1.0e-5 {
        best
    } else {
        UNASSIGNED
    }
}

impl QuadSet {
    pub fn clear(&mut self) {
        self.count = 0;
        self.centers.clear();
        self.bounds.clear();
        self.normals.clear();
        self.dots.clear();
        self.facings.clear();
    }

    /// Ajoute un quad par ses quatre sommets, dans l'ordre où ils sont dessinés.
    pub fn push(&mut self, p: &[[f32; 3]; 4]) {
        let mut total = [0.0f32; 3];
        let mut low = [f32::INFINITY; 3];
        let mut high = [f32::NEG_INFINITY; 3];
        for vertex in p {
            for axis in 0..3 {
                total[axis] += vertex[axis];
                low[axis] = min(low[axis], vertex[axis]);
                high[axis] = max(high[axis], vertex[axis]);
            }
        }
        let center = [total[0] / 4.0, total[1] / 4.0, total[2] / 4.0];

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

        self.centers.extend_from_slice(&center);
        self.bounds.extend_from_slice(&low);
        self.bounds.extend_from_slice(&high);
        self.normals.extend_from_slice(&[nx, ny, nz]);
        self.dots.push(nx * center[0] + ny * center[1] + nz * center[2]);
        self.facings.push(facing_of(nx, ny, nz));
        self.count += 1;
    }

    pub fn normal(&self, quad: usize) -> [f32; 3] {
        [self.normals[quad * 3], self.normals[quad * 3 + 1], self.normals[quad * 3 + 2]]
    }

    pub fn bounds_min(&self, quad: usize, axis: usize) -> f32 {
        self.bounds[quad * 6 + axis]
    }

    pub fn bounds_max(&self, quad: usize, axis: usize) -> f32 {
        self.bounds[quad * 6 + 3 + axis]
    }

    /// La plus petite projection de la boîte du quad sur une direction.
    pub fn min_dot(&self, quad: usize, n: [f32; 3]) -> f32 {
        let b = &self.bounds[quad * 6..quad * 6 + 6];
        n[0] * (if n[0] >= 0.0 { b[0] } else { b[3] })
            + n[1] * (if n[1] >= 0.0 { b[1] } else { b[4] })
            + n[2] * (if n[2] >= 0.0 { b[2] } else { b[5] })
    }

    pub fn max_dot(&self, quad: usize, n: [f32; 3]) -> f32 {
        let b = &self.bounds[quad * 6..quad * 6 + 6];
        n[0] * (if n[0] >= 0.0 { b[3] } else { b[0] })
            + n[1] * (if n[1] >= 0.0 { b[4] } else { b[1] })
            + n[2] * (if n[2] >= 0.0 { b[5] } else { b[2] })
    }

    pub fn normal_dot(&self, a: usize, b: usize) -> f32 {
        let (ao, bo) = (a * 3, b * 3);
        self.normals[ao] * self.normals[bo] + self.normals[ao + 1] * self.normals[bo + 1] + self.normals[ao + 2] * self.normals[bo + 2]
    }

    pub fn is_degenerate(&self, quad: usize) -> bool {
        let n = self.normal(quad);
        n[0] == 0.0 && n[1] == 0.0 && n[2] == 0.0
    }

    pub fn is_coplanar(&self, a: usize, b: usize) -> bool {
        let normal_dot = self.normal_dot(a, b);
        if (normal_dot.abs() - 1.0).abs() > EPSILON {
            return false;
        }
        let offset_b = if normal_dot < 0.0 { -self.dots[b] } else { self.dots[b] };
        (self.dots[a] - offset_b).abs() < EPSILON
    }

    /// Vrai si `p` doit être dessiné avant `q` : on peut voir `p` à travers `q`.
    pub fn is_seen_through(&self, p: usize, q: usize) -> bool {
        if self.is_coplanar(p, q) {
            return false;
        }
        let (qn, pn) = (self.normal(q), self.normal(p));
        let (p_min, q_max) = (self.min_dot(p, qn), self.max_dot(q, pn));

        // Seul un quad qui s'étend le long de la normale de l'autre peut déborder de son plan.
        let p_tol = if self.max_dot(p, qn) - p_min > EPSILON { OVERLAP_EPSILON } else { EPSILON };
        let q_tol = if q_max - self.min_dot(q, pn) > EPSILON { OVERLAP_EPSILON } else { EPSILON };

        p_min < self.dots[q] - p_tol && q_max > self.dots[p] + q_tol
    }

    /// La boîte qui contient tous les quads : (minimums, maximums).
    pub fn union(&self) -> ([f32; 3], [f32; 3]) {
        let mut low = [f32::INFINITY; 3];
        let mut high = [f32::NEG_INFINITY; 3];
        for quad in 0..self.count {
            for axis in 0..3 {
                low[axis] = min(low[axis], self.bounds_min(quad, axis));
                high[axis] = max(high[axis], self.bounds_max(quad, axis));
            }
        }
        (low, high)
    }
}

/// Les indices des clés, de la plus grande à la plus petite ; à clé égale,
/// l'ordre de départ est gardé (comme `MergeSort.mergeSort` côté Java).
pub fn descending(keys: &[f32]) -> Vec<u32> {
    let mut indices: Vec<u32> = (0..keys.len() as u32).collect();
    indices.sort_by(|&a, &b| keys[b as usize].partial_cmp(&keys[a as usize]).unwrap_or(std::cmp::Ordering::Equal));
    indices
}
