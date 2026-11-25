//! Ce que le launcher règle dans une instance Horizon sur téléphone, par l'API
//! du moteur (`horizon-engine-api`, `horizon-android/yuyu/API-MOTEUR.md`) :
//! les réglages que le jeu lit (résolution, images/s, affichage, gyroscope) et
//! les fichiers que le joueur peut voir (mondes, packs, captures…).
//!
//! Le moteur réécrit ses fichiers en quittant : rien ne s'écrit pendant que
//! l'instance tourne.
use horizon_engine_api::files::{self, Folder, Item};
use horizon_engine_api::settings::Settings;
use std::path::PathBuf;
use tauri::State;

use crate::state::SharedState;

/// Ramène l'écran du jeu, déjà ouvert, au premier plan : le bouton Jouer du
/// launcher pendant une partie (« Revenir au jeu »). Le pendant de la ligne
/// « Launcher » du menu pause du jeu.
#[tauri::command]
pub async fn horizon_game_resume(app: tauri::AppHandle) -> Result<(), String> {
    #[cfg(target_os = "android")]
    let resumed = super::android::resume(&app).await;
    // iOS : la fenêtre du jeu, cachée dans l'appli, revient devant.
    #[cfg(target_os = "ios")]
    let resumed = super::ios::resume(&app).await;
    resumed.map_err(|e| e.to_string())
}

fn game_dir(instance_id: &str) -> Result<PathBuf, String> {
    if instance_id.is_empty() || instance_id.contains(['/', '\\']) || instance_id.contains("..") {
        return Err("Instance inconnue".into());
    }
    let dir = crate::instances::crud::instance_dir(instance_id);
    if !dir.is_dir() {
        return Err("Instance introuvable".into());
    }
    Ok(dir)
}

async fn refuse_while_running(state: &SharedState, instance_id: &str) -> Result<(), String> {
    if state.read().await.is_instance_running(instance_id) {
        return Err("Le jeu tourne dans cette instance : quitte-le avant de changer ses fichiers".into());
    }
    Ok(())
}

async fn blocking<T: Send + 'static>(f: impl FnOnce() -> std::io::Result<T> + Send + 'static) -> Result<T, String> {
    tokio::task::spawn_blocking(f)
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| match e.kind() {
            std::io::ErrorKind::InvalidInput => "Nom de fichier refusé".to_string(),
            std::io::ErrorKind::AlreadyExists => "Un fichier porte déjà ce nom".to_string(),
            std::io::ErrorKind::NotFound => "Fichier introuvable".to_string(),
            _ => e.to_string(),
        })
}

/// Les réglages du jeu de l'instance, tels qu'il les lira (`None` : son défaut).
#[tauri::command]
pub async fn horizon_engine_settings(instance_id: String) -> Result<Settings, String> {
    let dir = game_dir(&instance_id)?;
    blocking(move || Settings::read(&dir)).await
}

/// Écrit les réglages donnés (les autres restent comme ils sont).
#[tauri::command]
pub async fn horizon_engine_settings_set(
    state: State<'_, SharedState>,
    instance_id: String,
    settings: Settings,
) -> Result<Settings, String> {
    refuse_while_running(&state, &instance_id).await?;
    let dir = game_dir(&instance_id)?;
    tokio::task::spawn_blocking(move || {
        settings.apply(&dir)?;
        Settings::read(&dir).map_err(|e| e.to_string())
    })
    .await
    .map_err(|e| e.to_string())?
}

#[tauri::command]
pub async fn horizon_files_list(instance_id: String, folder: Folder, path: Vec<String>) -> Result<Vec<Item>, String> {
    let dir = game_dir(&instance_id)?;
    blocking(move || files::list(&dir, folder, &path.iter().map(String::as_str).collect::<Vec<_>>())).await
}

#[tauri::command]
pub async fn horizon_files_remove(
    state: State<'_, SharedState>,
    instance_id: String,
    folder: Folder,
    path: Vec<String>,
) -> Result<(), String> {
    refuse_while_running(&state, &instance_id).await?;
    let dir = game_dir(&instance_id)?;
    blocking(move || files::remove(&dir, folder, &path.iter().map(String::as_str).collect::<Vec<_>>())).await
}

#[tauri::command]
pub async fn horizon_files_rename(
    state: State<'_, SharedState>,
    instance_id: String,
    folder: Folder,
    path: Vec<String>,
    name: String,
) -> Result<(), String> {
    refuse_while_running(&state, &instance_id).await?;
    let dir = game_dir(&instance_id)?;
    blocking(move || files::rename(&dir, folder, &path.iter().map(String::as_str).collect::<Vec<_>>(), &name)).await
}

#[tauri::command]
pub async fn horizon_files_mkdir(
    state: State<'_, SharedState>,
    instance_id: String,
    folder: Folder,
    path: Vec<String>,
) -> Result<(), String> {
    refuse_while_running(&state, &instance_id).await?;
    let dir = game_dir(&instance_id)?;
    blocking(move || files::create_dir(&dir, folder, &path.iter().map(String::as_str).collect::<Vec<_>>())).await
}

/// Le chemin d'un fichier, pour l'ouvrir ou le partager avec une autre appli.
#[tauri::command]
pub async fn horizon_files_path(instance_id: String, folder: Folder, path: Vec<String>) -> Result<String, String> {
    let dir = game_dir(&instance_id)?;
    let found = blocking(move || files::resolve(&dir, folder, &path.iter().map(String::as_str).collect::<Vec<_>>())).await?;
    Ok(found.display().to_string())
}

/// Un fichier choisi dans le sélecteur d'Android, reçu en octets bruts (le
/// sélecteur donne des adresses `content://`, que seul le WebView sait lire).
/// En-têtes : `x-instance`, `x-folder` (nom de `Folder`, ex. `resource_packs`),
/// `x-path` (dossiers séparés par `/`, peut être vide) et `x-name`, encodés
/// comme une adresse (`encodeURIComponent`). Rend le nom pris (jamais
/// par-dessus un fichier : `nom (2).zip`…).
#[tauri::command]
pub async fn horizon_files_import(state: State<'_, SharedState>, request: tauri::ipc::Request<'_>) -> Result<String, String> {
    let header = |name: &str| -> Result<String, String> {
        let raw = request.headers().get(name).and_then(|v| v.to_str().ok()).ok_or(format!("{name} manquant"))?;
        crate::share_link::percent_decode(raw).ok_or(format!("{name} illisible"))
    };
    let instance_id = header("x-instance")?;
    let folder: Folder = serde_json::from_value(serde_json::Value::String(header("x-folder")?)).map_err(|_| "Dossier inconnu")?;
    let path: Vec<String> = header("x-path")?.split('/').filter(|p| !p.is_empty()).map(str::to_owned).collect();
    let name = header("x-name")?;
    let tauri::ipc::InvokeBody::Raw(bytes) = request.body() else {
        return Err("Fichier attendu en octets bruts".into());
    };
    let bytes = bytes.clone();
    refuse_while_running(&state, &instance_id).await?;
    let dir = game_dir(&instance_id)?;
    blocking(move || {
        let parts: Vec<&str> = path.iter().map(String::as_str).collect();
        files::import_from(&dir, folder, &parts, &mut bytes.as_slice(), &name)
    })
    .await
}
