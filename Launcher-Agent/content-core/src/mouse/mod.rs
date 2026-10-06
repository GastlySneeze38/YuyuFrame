//! Souris brute de la 1.8.9 (docs/LauncherAgent/v1.8.9/README.md § 9 bis).
//!
//! Les déplacements sont cumulés dans deux compteurs qui ne reviennent jamais
//! à zéro (`counters.rs`). Java garde la valeur lue à l'image précédente et
//! fait la soustraction : aucun mouvement ne peut se perdre entre une lecture
//! et une remise à zéro, puisqu'il n'y a pas de remise à zéro.
//!
//! Java y entre par `bridge.rs`, appelé par FFM (`java.lang.foreign`) et non
//! par JNI. Côté Java : `lwjgl2compat/rawinput/NativeMouse.java`.
//!
//! La source Windows (thread dédié, fenêtre invisible, `GetRawInputBuffer`)
//! n'est pas encore écrite — elle ira dans `windows.rs` et alimentera
//! `counters::accumulate`. En attendant, `yf_mouse_start` répond
//! « indisponible » et Java garde la souris de GLFW.

mod bridge;
mod counters;
