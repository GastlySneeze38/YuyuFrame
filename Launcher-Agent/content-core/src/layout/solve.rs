//! Calcul de la mise en page et sortie des rectangles en JSON.

use std::collections::HashMap;
use taffy::{AvailableSpace, NodeId, Size, TaffyTree, TraversePartialTree};

use super::tree::NodeDef;

/// Insère récursivement (post-ordre : enfants avant parent, requis par
/// `TaffyTree::new_with_children`) et peuple `id_to_node` pour la relecture
/// du résultat après `compute_layout`.
fn insert_recursive(tree: &mut TaffyTree<()>, def: &NodeDef, id_to_node: &mut HashMap<String, NodeId>) -> Result<NodeId, String> {
    let mut child_ids = Vec::with_capacity(def.children.len());
    for child in &def.children {
        child_ids.push(insert_recursive(tree, child, id_to_node)?);
    }
    let style = def.to_taffy_style();
    let node = if child_ids.is_empty() {
        tree.new_leaf(style)
    } else {
        tree.new_with_children(style, &child_ids)
    }
    .map_err(|e| format!("{e:?}"))?;
    if !def.id.is_empty() {
        id_to_node.insert(def.id.clone(), node);
    }
    Ok(node)
}

/// Relève les rects résolus en coordonnées ABSOLUES (accumulées depuis la
/// racine — `Layout::location` de taffy est relatif au PARENT, comme la
/// quasi-totalité des moteurs de ce type (Yoga, Flutter...), donc PAS
/// directement utilisable tel quel côté Java qui dessine en coordonnées
/// écran absolues).
fn collect_rects(
    tree: &TaffyTree<()>,
    node: NodeId,
    def: &NodeDef,
    parent_x: f32,
    parent_y: f32,
    out: &mut Vec<(String, f32, f32, f32, f32)>,
) -> Result<(), String> {
    let layout = tree.layout(node).map_err(|e| format!("{e:?}"))?;
    let abs_x = parent_x + layout.location.x;
    let abs_y = parent_y + layout.location.y;
    if !def.id.is_empty() {
        out.push((def.id.clone(), abs_x, abs_y, layout.size.width, layout.size.height));
    }
    for (child_node, child_def) in tree.child_ids(node).zip(&def.children) {
        collect_rects(tree, child_node, child_def, abs_x, abs_y, out)?;
    }
    Ok(())
}

fn escape_json_string(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"")
}

/// Point d'entrée unique — `tree_json` = un `NodeDef` racine (voir schéma
/// dans `mod.rs`), `avail_w`/`avail_h` = espace disponible en pixels
/// (0 ou négatif = contenu illimité, `AvailableSpace::MaxContent`, utile pour
/// mesurer une hauteur de contenu sans contrainte). Retourne un tableau JSON
/// plat `[{"id":"...","x":0,"y":0,"w":0,"h":0}, ...]`, ou
/// `{"error":"..."}` en cas d'échec (JSON invalide, id dupliqué...).
pub fn compute_layout_json(tree_json: &str, avail_w: f32, avail_h: f32) -> String {
    let root_def: NodeDef = match serde_json::from_str(tree_json) {
        Ok(d) => d,
        Err(e) => return format!("{{\"error\":\"parse: {}\"}}", escape_json_string(&e.to_string())),
    };

    let mut tree: TaffyTree<()> = TaffyTree::new();
    let mut id_to_node: HashMap<String, NodeId> = HashMap::new();
    let root_node = match insert_recursive(&mut tree, &root_def, &mut id_to_node) {
        Ok(n) => n,
        Err(e) => return format!("{{\"error\":\"build: {}\"}}", escape_json_string(&e)),
    };

    let available_space = Size {
        width: if avail_w > 0.0 { AvailableSpace::Definite(avail_w) } else { AvailableSpace::MaxContent },
        height: if avail_h > 0.0 { AvailableSpace::Definite(avail_h) } else { AvailableSpace::MaxContent },
    };
    if let Err(e) = tree.compute_layout(root_node, available_space) {
        return format!("{{\"error\":\"compute: {}\"}}", escape_json_string(&format!("{e:?}")));
    }

    let mut rects = Vec::new();
    if let Err(e) = collect_rects(&tree, root_node, &root_def, 0.0, 0.0, &mut rects) {
        return format!("{{\"error\":\"collect: {}\"}}", escape_json_string(&e));
    }

    let mut out = String::with_capacity(64 * rects.len());
    out.push('[');
    for (i, (id, x, y, w, h)) in rects.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push_str(&format!(
            "{{\"id\":\"{}\",\"x\":{:.2},\"y\":{:.2},\"w\":{:.2},\"h\":{:.2}}}",
            escape_json_string(id), x, y, w, h
        ));
    }
    out.push(']');
    out
}
