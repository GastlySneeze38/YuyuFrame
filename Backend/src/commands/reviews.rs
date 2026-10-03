//! Avis déposés depuis le launcher (`/v1/reviews` de la LauncherAPI).
//!
//! Trois appels et rien d'autre : on ne garde aucune copie locale. L'avis vit
//! côté serveur, et c'est lui qui dit si on en a déjà un — un réglage local
//! mentirait dès la première réinstallation, ou dès qu'on se connecte depuis
//! un deuxième poste.
//!
//! Le compte YuyuFrame suffit à signer : le pseudo affiché sur le site est lu
//! côté serveur, jamais envoyé d'ici (voir `LauncherAPI/src/launcher/reviews.rs`).

use serde_json::{json, Value};

use crate::api;
use crate::state::SharedState;

/// Mon avis, ou `null` si je n'en ai pas déposé.
#[tauri::command]
pub async fn review_mine(state: tauri::State<'_, SharedState>) -> Result<Value, String> {
    api::get(&state, "/reviews/mine", &[]).await.map_err(String::from)
}

/// Dépose ou remplace mon avis. `comment` vide = la note seule : elle compte
/// dans la moyenne du site sans y publier de carte.
#[tauri::command]
pub async fn review_submit(
    state: tauri::State<'_, SharedState>,
    rating: i16,
    comment: Option<String>,
) -> Result<(), String> {
    api::put(&state, "/reviews/mine", json!({ "rating": rating, "comment": comment.unwrap_or_default() }))
        .await
        .map(|_| ())
        .map_err(String::from)
}

/// Retire mon avis — la note quitte aussi la moyenne.
#[tauri::command]
pub async fn review_delete(state: tauri::State<'_, SharedState>) -> Result<(), String> {
    api::delete(&state, "/reviews/mine").await.map(|_| ()).map_err(String::from)
}
