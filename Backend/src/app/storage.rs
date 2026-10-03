// Emplacement des données YuyuFrame (agent, p2p, .minecraft, skins) —
// configurable depuis Settings.tsx, section Stockage. Voir crate::paths pour
// la logique de résolution/déplacement ; ce module n'expose que les
// commandes Tauri et la garde métier (aucune instance en cours de lancement).

use tauri::State;

use crate::state::SharedState;

#[tauri::command]
pub async fn data_root_get() -> Result<String, String> {
    Ok(crate::paths::root().to_string_lossy().to_string())
}

/// Déplace tout le dossier YuyuFrame vers `new_parent/YuyuFrame`. Refuse si
/// une instance est en cours de lancement (fichiers potentiellement ouverts
/// par le process Java sous l'ancienne racine).
#[tauri::command]
pub async fn data_root_set(state: State<'_, SharedState>, new_parent: String) -> Result<String, String> {
    {
        let app_state = state.read().await;
        if !app_state.running_instances.is_empty() {
            return Err("Ferme d'abord toute instance Minecraft en cours de lancement".into());
        }
    }

    let path = std::path::PathBuf::from(&new_parent);
    if !path.is_dir() {
        return Err("Dossier invalide".into());
    }

    let new_root = crate::paths::set_root(&path)?;
    Ok(new_root.to_string_lossy().to_string())
}

/// Ouvre n'importe quel chemin absolu déjà connu du frontend (ex: le dossier
/// racine affiché dans Settings.tsx) dans l'explorateur Windows.
#[tauri::command]
pub async fn open_folder(path: String) -> Result<(), String> {
    crate::paths::open_in_explorer(std::path::Path::new(&path))
}
