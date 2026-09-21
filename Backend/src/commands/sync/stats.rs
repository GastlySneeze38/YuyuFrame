//! Lecture des statistiques de jeu.
//!
//! Les stats sont **locales**. Elles décrivent ce qui a été joué sur ce PC, et
//! plus rien ne les rattache au compte YuyuFrame : avant, elles étaient
//! filtrées par `yuyu_user_id`, si bien que se déconnecter faisait disparaître
//! tout l'historique. Personne ne s'attend à ça de données qui n'ont jamais
//! quitté sa machine.
//!
//! Le calcul lui-même est dans `crate::stats` (fonctions pures, testées). Ici
//! on ne fait que lire la base et passer les filtres.

use crate::{
    db,
    state::SharedState,
    stats::{compute, Filters, StatsPayload},
};

/// Période demandée. `from`/`to` en secondes Unix ; `to` absent = maintenant.
#[derive(serde::Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct StatsQuery {
    #[serde(default)]
    pub from: Option<i64>,
    #[serde(default)]
    pub to: Option<i64>,
    #[serde(default)]
    pub instance_id: Option<String>,
    #[serde(default)]
    pub loader: Option<String>,
    #[serde(default)]
    pub mc_version: Option<String>,
}

/// Période par défaut quand rien n'est demandé.
const DEFAULT_DAYS: i64 = 30;

#[tauri::command]
pub async fn stats_get(query: Option<StatsQuery>, state: tauri::State<'_, SharedState>) -> Result<StatsPayload, String> {
    let query = query.unwrap_or_default();
    let now = chrono::Utc::now().timestamp();
    let to = query.to.unwrap_or(now);
    let from = query.from.unwrap_or(to - DEFAULT_DAYS * 86_400).min(to);

    let db = state.read().await.db.clone();
    // `spawn_blocking` : SQLite est synchrone, et une lecture de plusieurs
    // milliers de lignes n'a rien à faire sur le fil du runtime async.
    let conn = db.lock().await;
    let sessions = db::sessions_in_range(&conn, from, to).map_err(|e| e.to_string())?;
    // Les listes de filtres doivent couvrir tout l'historique, pas la seule
    // période affichée : sinon choisir « 7 jours » ferait disparaître les
    // instances auxquelles on n'a pas touché cette semaine, et donc
    // l'impossibilité de les sélectionner.
    let all_known = db::sessions_in_range(&conn, 0, now).map_err(|e| e.to_string())?;
    let first_session_at = db::first_session_at(&conn).map_err(|e| e.to_string())?;
    drop(conn);

    let filters = Filters {
        instance_id: query.instance_id.filter(|v| !v.is_empty()),
        loader: query.loader.filter(|v| !v.is_empty()),
        mc_version: query.mc_version.filter(|v| !v.is_empty()),
    };
    Ok(compute(&sessions, &all_known, &filters, from, to, now, first_session_at))
}

/// Efface tout l'historique de jeu de ce PC. Irréversible, et demandé depuis
/// la page Stats avec une confirmation.
#[tauri::command]
pub async fn stats_clear(state: tauri::State<'_, SharedState>) -> Result<usize, String> {
    let db = state.read().await.db.clone();
    let conn = db.lock().await;
    db::sessions_clear(&conn).map_err(|e| e.to_string())
}
