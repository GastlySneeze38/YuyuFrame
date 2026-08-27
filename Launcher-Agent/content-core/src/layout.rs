//! Moteur de layout Flexbox/Grid (roadmap Phase 5.2) — wrapper JSON autour du
//! crate `taffy` (même lib que Bevy UI/Dioxus/Zed). Décision actée : Taffy
//! plutôt qu'un système maison léger ou Yoga C++ (nouveau binaire natif par
//! plateforme à charger, alors que `taffy` est du Rust pur qui rejoint le
//! reste de content-core sans dépendance système supplémentaire).
//!
//! Wire format DÉLIBÉRÉMENT séparé du format serde natif de `taffy::Style`
//! (qui expose ~25 champs, certains derrière des `#[cfg(feature=...)]`, sans
//! garantie de stabilité entre versions du crate) — `NodeDef` ci-dessous est
//! NOTRE propre schéma minimal (implémente `Default` + `#[serde(default)]`,
//! donc chaque champ omis côté Java retombe sur une valeur par défaut sans
//! erreur), traduit explicitement vers `taffy::Style` par `to_taffy_style()`.
//! Toute dimension (`width`/`padding`/`gap`/`inset`/...) est encodée en
//! chaîne JSON : `"auto"`, `"50%"`, ou un nombre en pixels (`"120"`) — évite
//! un enum JSON nombre/chaîne mêlé (`untagged`), plus simple à générer
//! côté Java qu'à parser côté Rust.
//!
//! Stateless par appel : `compute_layout_json` reconstruit un `TaffyTree`
//! neuf à chaque appel (pas de handle persistant à gérer à travers JNI) —
//! acceptable car le layout n'est recalculé QUE sur changement réel côté
//! Java (resize, ajout/suppression de widget), jamais à chaque frame (voir
//! ROADMAP-agent.md §5.2, "invalider/recalculer uniquement sur changement
//! réel").

use serde::Deserialize;
use std::collections::HashMap;
use taffy::style_helpers::{length, percent, TaffyAuto};
use taffy::{
    AlignContent, AlignItems, AvailableSpace, Dimension, Display, FlexDirection, FlexWrap,
    JustifyContent, LengthPercentage, LengthPercentageAuto, NodeId, Position, Rect, Size, Style,
    TaffyTree, TraversePartialTree,
};

#[derive(Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct NodeDef {
    id: String,
    display: String,
    flex_direction: String,
    flex_wrap: String,
    justify_content: Option<String>,
    align_items: Option<String>,
    align_content: Option<String>,
    gap_row: String,
    gap_col: String,
    padding: [String; 4], // top right bottom left
    margin: [String; 4],
    width: String,
    height: String,
    min_width: String,
    min_height: String,
    max_width: String,
    max_height: String,
    flex_grow: f32,
    flex_shrink: f32,
    flex_basis: String,
    position: String, // "relative" | "absolute"
    inset: [String; 4], // top right bottom left
    children: Vec<NodeDef>,
}

impl Default for NodeDef {
    fn default() -> Self {
        NodeDef {
            id: String::new(),
            display: "flex".into(),
            flex_direction: "row".into(),
            flex_wrap: "no-wrap".into(),
            justify_content: None,
            align_items: None,
            align_content: None,
            gap_row: "0".into(),
            gap_col: "0".into(),
            padding: ["0".into(), "0".into(), "0".into(), "0".into()],
            margin: ["0".into(), "0".into(), "0".into(), "0".into()],
            width: "auto".into(),
            height: "auto".into(),
            min_width: "auto".into(),
            min_height: "auto".into(),
            max_width: "auto".into(),
            max_height: "auto".into(),
            flex_grow: 0.0,
            flex_shrink: 1.0,
            flex_basis: "auto".into(),
            position: "relative".into(),
            inset: ["auto".into(), "auto".into(), "auto".into(), "auto".into()],
            children: Vec::new(),
        }
    }
}

fn parse_dimension(s: &str) -> Dimension {
    let s = s.trim();
    if s.is_empty() || s == "auto" {
        return Dimension::AUTO;
    }
    if let Some(p) = s.strip_suffix('%') {
        return percent(p.parse::<f64>().unwrap_or(0.0) / 100.0);
    }
    length(s.parse::<f64>().unwrap_or(0.0))
}

fn parse_lp_auto(s: &str) -> LengthPercentageAuto {
    let s = s.trim();
    if s.is_empty() || s == "auto" {
        return LengthPercentageAuto::AUTO;
    }
    if let Some(p) = s.strip_suffix('%') {
        return percent(p.parse::<f64>().unwrap_or(0.0) / 100.0);
    }
    length(s.parse::<f64>().unwrap_or(0.0))
}

fn parse_lp(s: &str) -> LengthPercentage {
    let s = s.trim();
    if let Some(p) = s.strip_suffix('%') {
        return percent(p.parse::<f64>().unwrap_or(0.0) / 100.0);
    }
    length(s.parse::<f64>().unwrap_or(0.0))
}

fn parse_rect_lp(vals: &[String; 4]) -> Rect<LengthPercentage> {
    Rect { top: parse_lp(&vals[0]), right: parse_lp(&vals[1]), bottom: parse_lp(&vals[2]), left: parse_lp(&vals[3]) }
}

fn parse_rect_lp_auto(vals: &[String; 4]) -> Rect<LengthPercentageAuto> {
    Rect {
        top: parse_lp_auto(&vals[0]),
        right: parse_lp_auto(&vals[1]),
        bottom: parse_lp_auto(&vals[2]),
        left: parse_lp_auto(&vals[3]),
    }
}

// Taffy 0.14 : AlignItems/AlignContent/JustifyContent sont des STRUCTS (pas
// des enums) avec des constantes associées UPPER_SNAKE_CASE (voir
// src/style/alignment.rs du crate, vérifié directement — pas deviné,
// contrairement à ma première tentative en PascalCase façon ancien enum).
fn parse_justify_content(s: &str) -> Option<JustifyContent> {
    match s {
        "start" => Some(JustifyContent::START),
        "end" => Some(JustifyContent::END),
        "flex-start" => Some(JustifyContent::FLEX_START),
        "flex-end" => Some(JustifyContent::FLEX_END),
        "center" => Some(JustifyContent::CENTER),
        "space-between" => Some(JustifyContent::SPACE_BETWEEN),
        "space-around" => Some(JustifyContent::SPACE_AROUND),
        "space-evenly" => Some(JustifyContent::SPACE_EVENLY),
        _ => None,
    }
}

fn parse_align_items(s: &str) -> Option<AlignItems> {
    match s {
        "start" => Some(AlignItems::START),
        "end" => Some(AlignItems::END),
        "flex-start" => Some(AlignItems::FLEX_START),
        "flex-end" => Some(AlignItems::FLEX_END),
        "center" => Some(AlignItems::CENTER),
        "baseline" => Some(AlignItems::BASELINE),
        "stretch" => Some(AlignItems::STRETCH),
        _ => None,
    }
}

fn parse_align_content(s: &str) -> Option<AlignContent> {
    match s {
        "start" => Some(AlignContent::START),
        "end" => Some(AlignContent::END),
        "flex-start" => Some(AlignContent::FLEX_START),
        "flex-end" => Some(AlignContent::FLEX_END),
        "center" => Some(AlignContent::CENTER),
        "stretch" => Some(AlignContent::STRETCH),
        "space-between" => Some(AlignContent::SPACE_BETWEEN),
        "space-around" => Some(AlignContent::SPACE_AROUND),
        "space-evenly" => Some(AlignContent::SPACE_EVENLY),
        _ => None,
    }
}

impl NodeDef {
    fn to_taffy_style(&self) -> Style {
        Style {
            display: match self.display.as_str() {
                "none" => Display::None,
                "grid" => Display::Grid,
                _ => Display::Flex,
            },
            flex_direction: match self.flex_direction.as_str() {
                "column" => FlexDirection::Column,
                "row-reverse" => FlexDirection::RowReverse,
                "column-reverse" => FlexDirection::ColumnReverse,
                _ => FlexDirection::Row,
            },
            flex_wrap: match self.flex_wrap.as_str() {
                "wrap" => FlexWrap::Wrap,
                "wrap-reverse" => FlexWrap::WrapReverse,
                _ => FlexWrap::NoWrap,
            },
            justify_content: self.justify_content.as_deref().and_then(parse_justify_content),
            align_items: self.align_items.as_deref().and_then(parse_align_items),
            align_content: self.align_content.as_deref().and_then(parse_align_content),
            gap: Size { width: parse_lp(&self.gap_col), height: parse_lp(&self.gap_row) },
            padding: parse_rect_lp(&self.padding),
            margin: parse_rect_lp_auto(&self.margin),
            size: Size { width: parse_dimension(&self.width), height: parse_dimension(&self.height) },
            min_size: Size { width: parse_lp_auto(&self.min_width), height: parse_lp_auto(&self.min_height) },
            max_size: Size { width: parse_lp_auto(&self.max_width), height: parse_lp_auto(&self.max_height) },
            flex_grow: self.flex_grow,
            flex_shrink: self.flex_shrink,
            flex_basis: parse_dimension(&self.flex_basis),
            position: if self.position == "absolute" { Position::Absolute } else { Position::Relative },
            inset: parse_rect_lp_auto(&self.inset),
            ..Default::default()
        }
    }
}

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

/// Point d'entrée unique — `tree_json` = un `NodeDef` racine (voir schéma en
/// tête de fichier), `avail_w`/`avail_h` = espace disponible en pixels
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
