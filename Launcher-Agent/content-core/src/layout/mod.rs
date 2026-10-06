//! Moteur de layout Flexbox/Grid (roadmap Phase 5.2) — wrapper JSON autour du
//! crate `taffy` (même lib que Bevy UI/Dioxus/Zed). Décision actée : Taffy
//! plutôt qu'un système maison léger ou Yoga C++ (nouveau binaire natif par
//! plateforme à charger, alors que `taffy` est du Rust pur qui rejoint le
//! reste de content-core sans dépendance système supplémentaire).
//!
//! Wire format DÉLIBÉRÉMENT séparé du format serde natif de `taffy::Style`
//! (qui expose ~25 champs, certains derrière des `#[cfg(feature=...)]`, sans
//! garantie de stabilité entre versions du crate) — `NodeDef` (`tree.rs`) est
//! NOTRE propre schéma minimal (implémente `Default` + `#[serde(default)]`,
//! donc chaque champ omis côté Java retombe sur une valeur par défaut sans
//! erreur), traduit explicitement vers `taffy::Style` par `to_taffy_style()`.
//! Toute dimension (`width`/`padding`/`gap`/`inset`/...) est encodée en
//! chaîne JSON : `"auto"`, `"50%"`, ou un nombre en pixels (`"120"`) — évite
//! un enum JSON nombre/chaîne mêlé (`untagged`), plus simple à générer
//! côté Java qu'à parser côté Rust.
//!
//! Stateless par appel : `compute_layout_json` (`solve.rs`) reconstruit un
//! `TaffyTree` neuf à chaque appel (pas de handle persistant à gérer à travers
//! JNI) — acceptable car le layout n'est recalculé QUE sur changement réel
//! côté Java (resize, ajout/suppression de widget), jamais à chaque frame
//! (voir ROADMAP-agent.md §5.2, "invalider/recalculer uniquement sur
//! changement réel").
//!
//! Java y entre par `bridge.rs` (JNI, `TaffyBridge`).

mod bridge;
mod solve;
mod tree;

pub use solve::compute_layout_json;
