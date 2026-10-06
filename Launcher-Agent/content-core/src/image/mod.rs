//! Images distantes pour l'interface en jeu (icônes, captures, bannières) :
//! téléchargées et décodées ici, affichées par `UiRemoteImage` côté Java.
//! Java y entre par `bridge.rs` (JNI, `ContentBridge.fetchImageRgba`).

mod bridge;
mod fetch;

pub use fetch::fetch_image_rgba;
