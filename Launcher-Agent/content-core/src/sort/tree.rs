//! Le tri du translucide d'une section : choisir le tri le moins cher qui
//! reste juste, et, quand l'ordre dépend de la caméra, construire un arbre de
//! découpe relu à chaque déplacement.
//!
//! Port de `TranslucentSorter` et `PartitionTree` du moteur Java
//! (`celeritas/chunk/sorting`). Même algorithme, mêmes seuils.
//!
//! L'arbre sort d'ici « à plat », dans un tableau de mots de 4 octets que Java
//! garde et retend à chaque relecture : le noyau ne garde rien.

use super::quads::{descending, QuadSet, EPSILON, OVERLAP_EPSILON, UNASSIGNED};

/// À quelle distance, le long de la normale commune, se tient la caméra fictive d'un tri fixe.
const FAKE_STATIC_CAMERA_OFFSET: f32 = 1000.0;
/// À partir de quel accord deux normales sont dites parallèles.
const PARALLEL_THRESHOLD: f32 = 0.98;
pub const MAX_TOPO_QUADS: usize = 2048;
const STATIC_LEAF_QUADS: usize = 256;

pub enum Node {
    /// Des quads dans un ordre juste de partout.
    Leaf(Vec<u32>),
    /// Des plans de coupe perpendiculaires à un axe : entre eux des tranches, et sur eux des quads.
    Split { axis: u32, planes: Vec<f32>, slabs: Vec<Node>, on_plane: Vec<Vec<u32>> },
    /// Deux groupes, toujours dans cet ordre.
    Fixed(Box<Node>, Box<Node>),
}

pub struct Tree {
    pub root: Node,
    /// Les plans de coupe de tout l'arbre, par axe, triés et sans doublon :
    /// la caméra en franchit un, l'ordre change.
    pub cut_planes: [Vec<f32>; 3],
}

pub enum SortResult {
    /// N'importe quel ordre est juste.
    None,
    /// Un ordre juste de partout, calculé une fois.
    Static(Vec<u32>),
    Tree(Tree),
    /// À retrier par distance à chaque déplacement : il reste les centres des quads.
    Dynamic,
}

/// Choisit le tri de la section. Premier cas qui convient :
/// rien à trier, tout dans un plan, tout sur la boîte englobante et tourné
/// vers l'extérieur, normales toutes parallèles, ordre fixe trouvé, arbre, et
/// en dernier le tri par distance.
pub fn analyze(quads: &QuadSet) -> SortResult {
    let count = quads.count;
    if count == 0 {
        return SortResult::None;
    }
    let Some(reference) = (0..count).find(|&quad| !quads.is_degenerate(quad)) else {
        return SortResult::None;
    };
    if (0..count).all(|quad| quads.is_coplanar(reference, quad)) {
        return SortResult::None;
    }
    if all_outward_on_bounding_box(quads) {
        return SortResult::None;
    }

    let mut flipped = vec![false; count];
    let mut all_parallel = true;
    for quad in 0..count {
        if quads.is_degenerate(quad) {
            continue;
        }
        let dot = quads.normal_dot(quad, reference);
        if dot.abs() < PARALLEL_THRESHOLD {
            all_parallel = false;
            break;
        }
        if dot < 0.0 {
            flipped[quad] = true;
        }
    }
    if all_parallel {
        return SortResult::Static(sort_normal_relative(quads, quads.normal(reference), &flipped));
    }

    if count <= MAX_TOPO_QUADS {
        let all: Vec<u32> = (0..count as u32).collect();
        if let Some(order) = topological_order(quads, &all) {
            return SortResult::Static(order);
        }
    }

    match build(quads) {
        Some(tree) => SortResult::Tree(tree),
        None => SortResult::Dynamic,
    }
}

/// Chaque quad à plat sur l'enveloppe de l'ensemble et tourné vers
/// l'extérieur : aucun ne peut en cacher un autre.
fn all_outward_on_bounding_box(quads: &QuadSet) -> bool {
    const STEPS: [[f32; 3]; 6] = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0], [-1.0, 0.0, 0.0], [0.0, -1.0, 0.0], [0.0, 0.0, -1.0]];
    let (low, high) = quads.union();
    for quad in 0..quads.count {
        let facing = quads.facings[quad];
        if facing == UNASSIGNED {
            return false;
        }
        let n = STEPS[facing as usize];
        let (quad_min, quad_max) = (quads.min_dot(quad, n), quads.max_dot(quad, n));
        if (quad_max - quad_min).abs() > EPSILON {
            return false;
        }
        let box_max = n[0] * (if n[0] >= 0.0 { high[0] } else { low[0] })
            + n[1] * (if n[1] >= 0.0 { high[1] } else { low[1] })
            + n[2] * (if n[2] >= 0.0 { high[2] } else { low[2] });
        if (quad_max - box_max).abs() > EPSILON {
            return false;
        }
    }
    true
}

/// Des quads de même normale, au signe près : un ordre le long de cette normale est juste de partout.
fn sort_normal_relative(quads: &QuadSet, n: [f32; 3], flipped: &[bool]) -> Vec<u32> {
    let centers = &quads.centers;
    let cam = [
        centers[0] + n[0] * FAKE_STATIC_CAMERA_OFFSET,
        centers[1] + n[1] * FAKE_STATIC_CAMERA_OFFSET,
        centers[2] + n[2] * FAKE_STATIC_CAMERA_OFFSET,
    ];
    let keys: Vec<f32> = (0..quads.count)
        .map(|quad| {
            let c = quad * 3;
            let (qx, qy, qz) = (centers[c] - cam[0], centers[c + 1] - cam[1], centers[c + 2] - cam[2]);
            (n[0] * qx + n[1] * qy + n[2] * qz) * (if flipped[quad] { 1.0 } else { -1.0 })
        })
        .collect();
    descending(&keys)
}

/// Les quads du groupe dans un ordre juste de partout, ou rien si « se voit
/// à travers » fait une boucle. Parcours en profondeur, sans récursion.
pub fn topological_order(quads: &QuadSet, group: &[u32]) -> Option<Vec<u32>> {
    let n = group.len();
    // 0 = pas vu, 1 = en cours, 2 = fini
    let mut state = vec![0u8; n];
    let mut stack = vec![0usize; n];
    let mut next = vec![0isize; n];
    let mut out = vec![0u32; n];
    let mut finished = 0;

    for root in (0..n).rev() {
        if state[root] != 0 {
            continue;
        }
        let mut depth: isize = 0;
        stack[0] = root;
        next[0] = n as isize - 1;
        state[root] = 1;

        while depth >= 0 {
            let d = depth as usize;
            let current = stack[d];
            let mut candidate = next[d];

            // Le prochain quad à dessiner après celui-ci.
            while candidate >= 0 {
                let c = candidate as usize;
                if state[c] == 2 || c == current || !quads.is_seen_through(group[current] as usize, group[c] as usize) {
                    candidate -= 1;
                } else {
                    break;
                }
            }

            if candidate < 0 {
                state[current] = 2;
                out[n - 1 - finished] = group[current];
                finished += 1;
                depth -= 1;
                continue;
            }

            let c = candidate as usize;
            if state[c] == 1 {
                return None;
            }
            next[d] = candidate - 1;
            depth += 1;
            stack[d + 1] = c;
            next[d + 1] = n as isize - 1;
            state[c] = 1;
        }
    }
    Some(out)
}

// ── Construction de l'arbre ──────────────────────────────────────────────────

fn sort_and_dedup(mut values: Vec<f32>) -> Vec<f32> {
    values.sort_unstable_by(f32::total_cmp);
    values.dedup();
    values
}

fn build(quads: &QuadSet) -> Option<Tree> {
    let all: Vec<u32> = (0..quads.count as u32).collect();
    let mut cuts: [Vec<f32>; 3] = Default::default();
    let root = partition(quads, all, 2, &mut cuts)?;
    Some(Tree { root, cut_planes: cuts.map(sort_and_dedup) })
}

fn partition(quads: &QuadSet, group: Vec<u32>, parent_axis: usize, cuts: &mut [Vec<f32>; 3]) -> Option<Node> {
    if group.len() <= 1 {
        return Some(Node::Leaf(group));
    }
    if let Some(parallel) = parallel_order(quads, &group) {
        return Some(Node::Leaf(parallel));
    }

    let tried_static = group.len() <= STATIC_LEAF_QUADS;
    if tried_static {
        if let Some(order) = topological_order(quads, &group) {
            return Some(Node::Leaf(order));
        }
    }

    let mut axis = 0;
    let mut planes = Vec::new();
    for i in 0..3 {
        axis = (parent_axis + 1 + i) % 3;
        planes = cuts_on(quads, &group, axis);
        if !planes.is_empty() {
            break;
        }
    }
    if planes.is_empty() {
        // Pas de coupe libre ne veut pas dire boucle : un ordre fixe peut encore exister.
        if !tried_static && group.len() <= MAX_TOPO_QUADS {
            if let Some(order) = topological_order(quads, &group) {
                return Some(Node::Leaf(order));
            }
        }
        return partition_intersectors(quads, &group, cuts);
    }

    let (mut on_plane, mut slabs) = bucket(quads, &group, axis, &planes);

    // Des vides entre des quads tous parallèles ne feraient que découper une
    // feuille fixe en morceaux : on ne garde que les coupes qui portent des quads.
    let carrying: Vec<f32> = planes.iter().zip(&on_plane).filter(|(_, quads)| !quads.is_empty()).map(|(&plane, _)| plane).collect();
    if !carrying.is_empty() && carrying.len() < planes.len() && all_parallel(quads, &slabs) {
        planes = carrying;
        (on_plane, slabs) = bucket(quads, &group, axis, &planes);
    }

    let mut children = Vec::with_capacity(slabs.len());
    for slab in slabs {
        if slab.is_empty() {
            children.push(Node::Leaf(Vec::new()));
        } else {
            children.push(partition(quads, slab, axis, cuts)?);
        }
    }

    cuts[axis].extend_from_slice(&planes);
    Some(Node::Split { axis: axis as u32, planes, slabs: children, on_plane })
}

/// Range le groupe : les quads posés sur chaque plan, et ceux de chaque tranche entre les plans.
fn bucket(quads: &QuadSet, group: &[u32], axis: usize, planes: &[f32]) -> (Vec<Vec<u32>>, Vec<Vec<u32>>) {
    let mut on_plane = vec![Vec::new(); planes.len()];
    let mut slabs = vec![Vec::new(); planes.len() + 1];
    for &quad in group {
        let (lo, hi) = (quads.bounds_min(quad as usize, axis), quads.bounds_max(quad as usize, axis));
        let mut slab = 0;
        while slab < planes.len() && planes[slab] <= lo + OVERLAP_EPSILON {
            slab += 1;
        }
        if hi - lo <= EPSILON && slab > 0 && (planes[slab - 1] - lo).abs() <= EPSILON {
            on_plane[slab - 1].push(quad);
        } else {
            slabs[slab].push(quad);
        }
    }
    (on_plane, slabs)
}

fn all_parallel(quads: &QuadSet, slabs: &[Vec<u32>]) -> bool {
    let mut reference = None;
    for &quad in slabs.iter().flatten() {
        match reference {
            None => reference = Some(quad),
            Some(first) => {
                if quads.normal_dot(quad as usize, first as usize).abs() < 1.0 - EPSILON {
                    return false;
                }
            }
        }
    }
    true
}

/// Quand rien ne se coupe : on met à part les quads qui se croisent le plus, et on trie les deux lots séparément.
fn partition_intersectors(quads: &QuadSet, group: &[u32], cuts: &mut [Vec<f32>; 3]) -> Option<Node> {
    let n = group.len();
    if n > MAX_TOPO_QUADS {
        return None;
    }

    let mut degree = vec![0i32; n];
    for i in 0..n {
        for j in i + 1..n {
            if intersects(quads, group[i], group[j]) {
                degree[i] += 1;
                degree[j] += 1;
            }
        }
    }

    let mut primary = vec![false; n];
    let mut count = 0;
    loop {
        let mut best: Option<usize> = None;
        for i in 0..n {
            if degree[i] > 0 && best.map_or(true, |b| degree[i] > degree[b]) {
                best = Some(i);
            }
        }
        let Some(best) = best else { break };
        primary[best] = true;
        count += 1;
        degree[best] = 0;
        for j in 0..n {
            if !primary[j] && intersects(quads, group[best], group[j]) {
                degree[j] -= 1;
            }
        }
    }
    if count == 0 || count == n {
        return None;
    }

    let first: Vec<u32> = (0..n).filter(|&i| primary[i]).map(|i| group[i]).collect();
    let rest: Vec<u32> = (0..n).filter(|&i| !primary[i]).map(|i| group[i]).collect();
    let a = partition(quads, first, 2, cuts)?;
    let b = partition(quads, rest, 2, cuts)?;
    Some(Node::Fixed(Box::new(a), Box::new(b)))
}

fn intersects(quads: &QuadSet, p: u32, q: u32) -> bool {
    let (p, q) = (p as usize, q as usize);
    for axis in 0..3 {
        if quads.bounds_min(p, axis) >= quads.bounds_max(q, axis) - OVERLAP_EPSILON
            || quads.bounds_min(q, axis) >= quads.bounds_max(p, axis) - OVERLAP_EPSILON
        {
            return false;
        }
    }
    true
}

/// L'ordre fixe d'un groupe dont toutes les normales sont parallèles, ou rien s'il ne l'est pas.
fn parallel_order(quads: &QuadSet, group: &[u32]) -> Option<Vec<u32>> {
    let reference = group[0] as usize;
    let n = quads.normal(reference);
    let centers = &quads.centers;
    let cam = [
        centers[reference * 3] + n[0] * FAKE_STATIC_CAMERA_OFFSET,
        centers[reference * 3 + 1] + n[1] * FAKE_STATIC_CAMERA_OFFSET,
        centers[reference * 3 + 2] + n[2] * FAKE_STATIC_CAMERA_OFFSET,
    ];

    let mut keys = Vec::with_capacity(group.len());
    for &quad in group {
        let dot = quads.normal_dot(quad as usize, reference);
        if dot.abs() < 1.0 - EPSILON {
            return None;
        }
        let c = quad as usize * 3;
        let key = n[0] * (centers[c] - cam[0]) + n[1] * (centers[c + 1] - cam[1]) + n[2] * (centers[c + 2] - cam[2]);
        keys.push(key * (if dot < 0.0 { 1.0 } else { -1.0 }));
    }
    Some(descending(&keys).into_iter().map(|i| group[i as usize]).collect())
}

/// Les plans perpendiculaires à l'axe qu'aucun quad du groupe ne traverse et qui séparent quelque chose.
fn cuts_on(quads: &QuadSet, group: &[u32], axis: usize) -> Vec<f32> {
    let n = group.len() as i32;
    // Un quad s'étend sur un intervalle le long de l'axe, ou tient à plat dans un seul plan.
    let (mut starts, mut ends, mut points) = (Vec::new(), Vec::new(), Vec::new());
    for &quad in group {
        let (lo, hi) = (quads.bounds_min(quad as usize, axis), quads.bounds_max(quad as usize, axis));
        if hi - lo <= EPSILON {
            points.push(lo);
        } else {
            starts.push(lo);
            ends.push(hi);
        }
    }
    starts.sort_unstable_by(f32::total_cmp);
    ends.sort_unstable_by(f32::total_cmp);
    points.sort_unstable_by(f32::total_cmp);

    let mut cuts = Vec::new();
    let (mut next_start, mut next_end, mut next_point) = (0usize, 0usize, 0usize);
    let (mut opened, mut below, mut since_cut) = (0usize, 0i32, 0i32);

    while next_end < ends.len() || next_point < points.len() {
        let plane = if next_point == points.len() {
            ends[next_end]
        } else if next_end == ends.len() {
            points[next_point]
        } else if points[next_point] < ends[next_end] {
            points[next_point]
        } else {
            ends[next_end]
        };

        // Les fins d'abord, puis les quads sur le plan, puis les débuts : un quad qui commence ici ne gêne pas encore.
        while next_end < ends.len() && ends[next_end] <= plane + OVERLAP_EPSILON {
            next_end += 1;
            below += 1;
            since_cut += 1;
        }
        let mut on_plane = 0;
        while next_point < points.len() && points[next_point] <= plane + EPSILON {
            next_point += 1;
            on_plane += 1;
        }
        while next_start < starts.len() && starts[next_start] < plane - OVERLAP_EPSILON {
            next_start += 1;
            opened += 1;
        }

        let above = n - below - on_plane;
        let free = opened == next_end;
        let useful = (below > 0) as i32 + (on_plane > 0) as i32 + (above > 0) as i32 >= 2;
        let separates_something_new = on_plane > 0 || since_cut > 0;

        if free && useful && separates_something_new {
            cuts.push(plane);
            since_cut = 0;
        }

        below += on_plane;
        since_cut += on_plane;
    }
    cuts
}

// ── L'arbre à plat ───────────────────────────────────────────────────────────
//
// Un nœud commence par son genre :
//   0, n, n quads                                   une feuille ;
//   1, position du premier lot, position du second  deux lots dans cet ordre ;
//   2, axe, p, p plans (bits des flottants),        une coupe : p + 1 tranches
//      p + 1 positions de tranches,                 (des nœuds) et, sur chaque
//      p positions de listes « n, n quads »         plan, une liste de quads.
// Les positions sont des rangs dans le tableau ; la racine est au rang 0.

const KIND_LEAF: u32 = 0;
const KIND_FIXED: u32 = 1;
const KIND_SPLIT: u32 = 2;

pub fn flatten(node: &Node, out: &mut Vec<u32>) {
    match node {
        Node::Leaf(quads) => {
            out.push(KIND_LEAF);
            out.push(quads.len() as u32);
            out.extend_from_slice(quads);
        }
        Node::Fixed(first, rest) => {
            let at = out.len();
            out.extend_from_slice(&[KIND_FIXED, 0, 0]);
            out[at + 1] = out.len() as u32;
            flatten(first, out);
            out[at + 2] = out.len() as u32;
            flatten(rest, out);
        }
        Node::Split { axis, planes, slabs, on_plane } => {
            let count = planes.len();
            out.extend_from_slice(&[KIND_SPLIT, *axis, count as u32]);
            out.extend(planes.iter().map(|plane| plane.to_bits()));
            let positions = out.len();
            out.resize(positions + 2 * count + 1, 0);
            for (i, slab) in slabs.iter().enumerate() {
                out[positions + i] = out.len() as u32;
                flatten(slab, out);
            }
            for (i, quads) in on_plane.iter().enumerate() {
                out[positions + count + 1 + i] = out.len() as u32;
                out.push(quads.len() as u32);
                out.extend_from_slice(quads);
            }
        }
    }
}

/// Relit l'arbre pour une position de caméra : appelle `sink` avec les quads,
/// du plus loin au plus près. Faux si le tableau n'est pas un arbre valide.
pub fn read(flat: &[u32], at: usize, cam: [f32; 3], sink: &mut impl FnMut(&[u32]), depth: u32) -> bool {
    // Un arbre de section ne dépasse jamais quelques dizaines de niveaux.
    if depth > 4096 {
        return false;
    }
    let list = |position: usize| -> Option<&[u32]> {
        let count = *flat.get(position)? as usize;
        flat.get(position + 1..position + 1 + count)
    };

    match flat.get(at) {
        Some(&KIND_LEAF) => match list(at + 1) {
            Some(quads) => {
                sink(quads);
                true
            }
            None => false,
        },
        Some(&KIND_FIXED) => {
            let (Some(&first), Some(&rest)) = (flat.get(at + 1), flat.get(at + 2)) else {
                return false;
            };
            read(flat, first as usize, cam, sink, depth + 1) && read(flat, rest as usize, cam, sink, depth + 1)
        }
        Some(&KIND_SPLIT) => {
            let (Some(&axis), Some(&count)) = (flat.get(at + 1), flat.get(at + 2)) else {
                return false;
            };
            let count = count as usize;
            if axis > 2 {
                return false;
            }
            let Some(planes) = flat.get(at + 3..at + 3 + count) else {
                return false;
            };
            let Some(positions) = flat.get(at + 3 + count..at + 3 + count + 2 * count + 1) else {
                return false;
            };
            let (slabs, on_plane) = positions.split_at(count + 1);

            let cam_coord = cam[axis as usize];
            let mut slab = 0;
            while slab < count && f32::from_bits(planes[slab]) <= cam_coord + EPSILON {
                slab += 1;
            }

            // Les tranches sous celle de la caméra, la plus loin d'abord, chacune suivie du plan au-dessus d'elle.
            for i in 0..slab {
                if !read(flat, slabs[i] as usize, cam, sink, depth + 1) {
                    return false;
                }
                match list(on_plane[i] as usize) {
                    Some(quads) => sink(quads),
                    None => return false,
                }
            }
            // Les tranches au-dessus, la plus loin d'abord, chacune suivie du plan sous elle.
            for i in (slab + 1..=count).rev() {
                if !read(flat, slabs[i] as usize, cam, sink, depth + 1) {
                    return false;
                }
                match list(on_plane[i - 1] as usize) {
                    Some(quads) => sink(quads),
                    None => return false,
                }
            }
            // La tranche de la caméra est la plus proche : en dernier.
            read(flat, slabs[slab] as usize, cam, sink, depth + 1)
        }
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Un carré d'un bloc de côté, perpendiculaire à un axe, à la coordonnée donnée, tourné vers le plus.
    fn square(set: &mut QuadSet, axis: usize, at: f32, origin: [f32; 2]) {
        let (a, b) = ((axis + 1) % 3, (axis + 2) % 3);
        let corner = |da: f32, db: f32| {
            let mut p = [0.0f32; 3];
            p[axis] = at;
            p[a] = origin[0] + da;
            p[b] = origin[1] + db;
            p
        };
        set.push(&[corner(0.0, 0.0), corner(1.0, 0.0), corner(1.0, 1.0), corner(0.0, 1.0)]);
    }

    fn order_of(result: &SortResult, cam: [f32; 3]) -> Vec<u32> {
        match result {
            SortResult::Static(order) => order.clone(),
            SortResult::Tree(tree) => {
                let mut flat = Vec::new();
                flatten(&tree.root, &mut flat);
                let mut order = Vec::new();
                assert!(read(&flat, 0, cam, &mut |quads| order.extend_from_slice(quads), 0));
                order
            }
            _ => panic!("pas d'ordre"),
        }
    }

    #[test]
    fn un_seul_plan_rien_a_trier() {
        let mut set = QuadSet::default();
        square(&mut set, 1, 5.0, [0.0, 0.0]);
        square(&mut set, 1, 5.0, [3.0, 2.0]);
        assert!(matches!(analyze(&set), SortResult::None));
        assert!(matches!(analyze(&QuadSet::default()), SortResult::None));
    }

    /// Trois surfaces d'eau l'une sur l'autre : un ordre fixe le long de la normale.
    #[test]
    fn plans_paralleles_ordre_fixe() {
        let mut set = QuadSet::default();
        square(&mut set, 1, 2.0, [0.0, 0.0]);
        square(&mut set, 1, 9.0, [0.0, 0.0]);
        square(&mut set, 1, 5.0, [0.0, 0.0]);
        let result = analyze(&set);
        assert!(matches!(result, SortResult::Static(_)));
        // Tournés vers le haut : du plus bas au plus haut.
        assert_eq!(order_of(&result, [0.0; 3]), vec![0, 2, 1]);
    }

    /// Deux vitres en croix : aucune ne passe toujours devant l'autre, mais on peut les trier sans arbre
    /// tant qu'elles ne se voient pas l'une à travers l'autre dans les deux sens.
    #[test]
    fn ordre_qui_depend_de_la_camera() {
        let mut set = QuadSet::default();
        // Deux murs face à face (x = 2 et x = 6) et deux autres (z = 2 et z = 6), tous tournés vers le plus :
        // de l'intérieur comme de l'extérieur, l'ordre change avec la caméra.
        square(&mut set, 0, 2.0, [0.0, 3.5]);
        square(&mut set, 0, 6.0, [0.0, 3.5]);
        square(&mut set, 2, 2.0, [3.5, 0.0]);
        square(&mut set, 2, 6.0, [3.5, 0.0]);
        let result = analyze(&set);

        let position = |order: &[u32], quad: u32| order.iter().position(|&q| q == quad).unwrap();
        // Caméra loin en x positif : le mur x = 2 est derrière le mur x = 6, donc dessiné avant.
        let from_east = order_of(&result, [50.0, 0.5, 4.0]);
        assert_eq!(from_east.len(), 4);
        assert!(position(&from_east, 0) < position(&from_east, 1));
        if let SortResult::Tree(tree) = &result {
            // Caméra loin en x négatif : l'inverse.
            let from_west = order_of(&result, [-50.0, 0.5, 4.0]);
            assert!(position(&from_west, 1) < position(&from_west, 0));
            assert!(tree.cut_planes.iter().any(|cuts| !cuts.is_empty()));
        }
    }

    #[test]
    fn tableau_invalide_refuse() {
        let mut sink = |_: &[u32]| {};
        assert!(!read(&[], 0, [0.0; 3], &mut sink, 0));
        assert!(!read(&[KIND_LEAF, 5, 1], 0, [0.0; 3], &mut sink, 0));
        assert!(!read(&[KIND_FIXED, 9, 9], 0, [0.0; 3], &mut sink, 0));
        assert!(!read(&[7], 0, [0.0; 3], &mut sink, 0));
    }

    #[test]
    fn ordre_topologique() {
        let mut set = QuadSet::default();
        square(&mut set, 1, 2.0, [0.0, 0.0]);
        square(&mut set, 1, 5.0, [0.0, 0.0]);
        // Deux quads parallèles tournés du même côté ne se voient pas l'un à travers l'autre dans les deux sens.
        assert!(set.is_seen_through(0, 1) != set.is_seen_through(1, 0) || !set.is_seen_through(0, 1));
        assert!(topological_order(&set, &[0, 1]).is_some());
    }
}
