use crate::db::{self, JvmProfileRow};
use crate::state::SharedState;

fn user_id(s: &crate::state::AppState) -> i64 {
    s.current_yuyu_user_id().unwrap_or(0)
}

#[tauri::command]
pub async fn jvm_profile_list(state: tauri::State<'_, SharedState>) -> Result<Vec<JvmProfileRow>, String> {
    let s = state.read().await;
    let uid = user_id(&s);
    let db = s.db.lock().await;
    db::jvm_profile_list(&db, uid).map_err(|e| e.to_string())
}

/// Crée une config. `from_id` la duplique à partir d'une existante — le geste
/// central quand on compare des jeux de drapeaux : on part de ce qui marche et
/// on ne change qu'une chose.
#[tauri::command]
pub async fn jvm_profile_create(
    state: tauri::State<'_, SharedState>,
    name: String,
    from_id: Option<String>,
) -> Result<JvmProfileRow, String> {
    let name = name.trim().to_string();
    if name.is_empty() {
        return Err("Le nom de la configuration est requis".into());
    }
    let s = state.read().await;
    let uid = user_id(&s);
    let db = s.db.lock().await;

    let base = match from_id {
        Some(id) => db::jvm_profile_get(&db, &id, uid)
            .map_err(|e| e.to_string())?
            .ok_or("Configuration source introuvable")?,
        None => JvmProfileRow {
            id: String::new(),
            name: String::new(),
            ram_mb: None,
            jvm_vendor: "auto".into(),
            jvm_custom_path: None,
            gc_policy: "auto".into(),
            args_mode: "append".into(),
            args_jvm: String::new(),
            args_gc: String::new(),
            args_jit: String::new(),
        },
    };
    let profile = JvmProfileRow {
        id: crate::commands::instance::crud::gen_id(&name),
        name,
        ..base
    };
    db::jvm_profile_insert(&db, uid, &profile).map_err(|e| e.to_string())?;
    Ok(profile)
}

#[tauri::command]
pub async fn jvm_profile_save(
    state: tauri::State<'_, SharedState>,
    profile: JvmProfileRow,
) -> Result<JvmProfileRow, String> {
    if profile.name.trim().is_empty() {
        return Err("Le nom de la configuration est requis".into());
    }
    let s = state.read().await;
    let uid = user_id(&s);
    let db = s.db.lock().await;
    db::jvm_profile_update(&db, uid, &profile).map_err(|e| e.to_string())?;
    Ok(profile)
}

#[tauri::command]
pub async fn jvm_profile_delete(state: tauri::State<'_, SharedState>, id: String) -> Result<(), String> {
    let s = state.read().await;
    let uid = user_id(&s);
    let db = s.db.lock().await;
    db::jvm_profile_delete(&db, &id, uid).map_err(|e| e.to_string())
}

/// Relie une instance à une config (ou la délie avec `profile_id: None`).
#[tauri::command]
pub async fn instance_set_jvm_profile(
    state: tauri::State<'_, SharedState>,
    instance_id: String,
    profile_id: Option<String>,
) -> Result<(), String> {
    let s = state.read().await;
    let uid = user_id(&s);
    let db = s.db.lock().await;
    db::instance_set_jvm_profile(&db, &instance_id, uid, profile_id.as_deref())
        .map_err(|e| e.to_string())
}
