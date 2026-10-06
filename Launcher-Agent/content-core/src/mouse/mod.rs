//! Souris brute de la 1.8.9 (docs/LauncherAgent/v1.8.9/README.md § 9 bis).
//!
//! Un thread à nous lit la souris au niveau du système (`windows.rs`) et
//! cumule les déplacements dans deux compteurs qui ne reviennent jamais à
//! zéro (`counters.rs`). Java garde la valeur lue à l'image précédente et
//! fait la soustraction : aucun mouvement ne peut se perdre entre une lecture
//! et une remise à zéro, puisqu'il n'y a pas de remise à zéro.
//!
//! Java y entre par `bridge.rs`, appelé par FFM (`java.lang.foreign`) et non
//! par JNI. Côté Java : `lwjgl2compat/rawinput/NativeMouse.java`.
//!
//! Hors Windows, `yf_mouse_start` répond « indisponible » et Java garde la
//! souris de GLFW.

mod bridge;
mod counters;
#[cfg(windows)]
mod windows;
