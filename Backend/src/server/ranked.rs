//! Le classement compétitif vérifié (`/v1/ranked/…` de la LauncherAPI,
//! docs/scoring/classement.md « Classement vérifié »), pour l'onglet
//! Compétitif du launcher.
//!
//! Lecture seule : c'est le jeu qui envoie les duels et fait avancer l'état
//! (`content-core/src/combat/ranked_sync.rs`). Ici on ne fait que demander
//! où en est un compte Minecraft. L'état est rendu tel quel (`LadderState`) :
//! l'interface n'en lit que les positions par mode.

use serde_json::Value;

use crate::server as api;
use crate::state::SharedState;

/// L'état vérifié du classement d'un compte Minecraft de l'utilisateur :
/// `{ version, state, badge, show_rank }`. Le serveur le crée vide s'il
/// n'existe pas encore. Refusé pour un compte hors ligne ou non prouvé.
#[tauri::command]
pub async fn ranked_state(state: tauri::State<'_, SharedState>, uuid: String) -> Result<Value, String> {
    // Un UUID sans tirets ni caractères parasites : il part dans l'adresse.
    let uuid: String = uuid.chars().filter(|c| c.is_ascii_hexdigit()).collect();
    if uuid.len() != 32 {
        return Err(String::from(api::error::ApiError::new("bad_request", "UUID Minecraft invalide")));
    }
    api::get(&state, &format!("/ranked/state/{uuid}"), &[]).await.map_err(|e| String::from(explain(e)))
}

/// Donne aux refus du serveur un code que l'interface sait expliquer.
///
/// - un 404 **sans corps** : la route n'existe pas, l'API n'est pas encore
///   déployée avec le classement → `ranked_unavailable` ;
/// - un 404 ou un 400 du classement lui-même : compte Minecraft inconnu de ce
///   compte YuyuFrame, ou pas encore prouvé (la preuve se fait au lancement
///   du jeu) → `ranked_unproven`.
fn explain(e: api::error::ApiError) -> api::error::ApiError {
    let route_missing = e.code == "not_found" && e.message.starts_with("Erreur 404");
    match e.code.as_str() {
        "not_found" if route_missing => api::error::ApiError::new("ranked_unavailable", e.message),
        "not_found" | "bad_request" => api::error::ApiError::new("ranked_unproven", e.message),
        _ => e,
    }
}

/// Un UUID Minecraft propre (32 hexa), ou une erreur.
fn clean_uuid(uuid: &str) -> Result<String, String> {
    let uuid: String = uuid.chars().filter(|c| c.is_ascii_hexdigit()).collect();
    if uuid.len() == 32 {
        Ok(uuid)
    } else {
        Err(String::from(api::error::ApiError::new("bad_request", "UUID Minecraft invalide")))
    }
}

/// Un mode du classement : `mace` ou `sword`.
fn clean_mode(mode: &str) -> Result<String, String> {
    match mode {
        "mace" | "sword" => Ok(mode.to_string()),
        _ => Err(String::from(api::error::ApiError::new("bad_request", "Mode inconnu"))),
    }
}

/// L'historique des duels classés d'un compte : `{ duels, next }`, du plus
/// récent au plus ancien. `before` = le `next` de la page précédente.
#[tauri::command]
pub async fn ranked_history(state: tauri::State<'_, SharedState>, uuid: String, mode: Option<String>, before: Option<i64>) -> Result<Value, String> {
    let uuid = clean_uuid(&uuid)?;
    let mut query = Vec::new();
    if let Some(mode) = mode {
        query.push(("mode", clean_mode(&mode)?));
    }
    if let Some(before) = before {
        query.push(("before", before.to_string()));
    }
    api::get(&state, &format!("/ranked/history/{uuid}"), &query).await.map_err(|e| String::from(explain(e)))
}

/// Positions, statistiques par mode et saisons d'un compte.
#[tauri::command]
pub async fn ranked_summary(state: tauri::State<'_, SharedState>, uuid: String) -> Result<Value, String> {
    let uuid = clean_uuid(&uuid)?;
    api::get(&state, &format!("/ranked/summary/{uuid}"), &[]).await.map_err(|e| String::from(explain(e)))
}

/// Le classement entre joueurs d'un mode, et la place de `uuid`.
#[tauri::command]
pub async fn ranked_leaderboard(state: tauri::State<'_, SharedState>, mode: String, uuid: Option<String>) -> Result<Value, String> {
    let mut query = vec![("mode", clean_mode(&mode)?)];
    if let Some(uuid) = uuid {
        query.push(("uuid", clean_uuid(&uuid)?));
    }
    api::get(&state, "/ranked/leaderboard", &query).await.map_err(|e| String::from(explain(e)))
}
