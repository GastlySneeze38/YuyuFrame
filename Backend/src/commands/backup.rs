//! Commandes de sauvegarde.
//!
//! Tout ce qui touche au disque passe par `spawn_blocking` : une instance
//! moddée avec ses mondes représente des dizaines de milliers de fichiers, et
//! lire tout ça sur le fil du runtime figerait l'interface entière.

use serde::{Deserialize, Serialize};

use crate::backup::{self, Backup, BackupSettings, BackupSummary};
use crate::commands::instance::crud::instance_dir;
use crate::state::SharedState;

/// Ce que l'écran de sauvegarde affiche en tête : la place occupée, et ce qui
/// se déclenche tout seul.
#[derive(Serialize)]
pub struct BackupOverview {
    pub backups: Vec<BackupSummary>,
    /// Place réellement prise sur le disque, morceaux partagés compris.
    pub disk_bytes: u64,
    pub settings: BackupSettings,
}

/// Réglage effectif d'une instance, et s'il lui est propre.
#[derive(Serialize)]
pub struct InstanceBackupSettings {
    pub settings: BackupSettings,
    /// Faux : l'instance suit le réglage général.
    pub custom: bool,
    pub global: BackupSettings,
}

async fn settings_of(state: &tauri::State<'_, SharedState>, instance_id: Option<&str>) -> BackupSettings {
    let db = state.read().await.db.clone();
    let conn = db.lock().await;
    match instance_id {
        Some(id) => backup::settings::effective(&conn, id),
        None => backup::settings::global(&conn),
    }
}

// ── Lecture ──────────────────────────────────────────────────────────────────

/// Les sauvegardes d'une instance, ou de toutes si `instance_id` est absent.
#[tauri::command]
pub async fn backup_list(instance_id: Option<String>, state: tauri::State<'_, SharedState>) -> Result<BackupOverview, String> {
    let settings = settings_of(&state, instance_id.as_deref()).await;
    let backups = tokio::task::spawn_blocking(move || {
        let list = match &instance_id {
            Some(id) => backup::list(id),
            None => backup::list_all(),
        };
        (list.iter().map(BackupSummary::from).collect::<Vec<_>>(), backup::disk_usage())
    })
    .await
    .map_err(|e| e.to_string())?;

    Ok(BackupOverview { backups: backups.0, disk_bytes: backups.1, settings })
}

#[tauri::command]
pub async fn backup_get(instance_id: String, backup_id: String) -> Result<Backup, String> {
    tokio::task::spawn_blocking(move || backup::get(&instance_id, &backup_id))
        .await
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "Sauvegarde introuvable".to_string())
}

// ── Création ─────────────────────────────────────────────────────────────────

/// Prend une sauvegarde à la demande.
#[tauri::command]
pub async fn backup_create(instance_id: String, state: tauri::State<'_, SharedState>) -> Result<BackupSummary, String> {
    let (name, settings) = {
        let s = state.read().await;
        let user_id = s.current_yuyu_user_id().unwrap_or(0);
        let conn = s.db.lock().await;
        let row = crate::db::instance_get(&conn, &instance_id, user_id)
            .map_err(|e| e.to_string())?
            .ok_or("Instance introuvable")?;
        (row.name, backup::settings::effective(&conn, &instance_id))
    };

    // Une sauvegarde demandée à la main se fait même si les automatiques sont
    // coupées : l'interrupteur règle ce qui se déclenche tout seul, pas le
    // droit d'appuyer sur le bouton.
    let settings = BackupSettings { enabled: true, ..settings };
    if settings.included_dirs().is_empty() {
        return Err("Rien à sauvegarder : choisis au moins les mondes dans les réglages.".into());
    }

    run(&instance_id, &name, &settings, "manual").await?.ok_or_else(|| "Cette instance n'a encore aucun monde à sauvegarder.".to_string())
}

/// Le travail commun aux trois déclencheurs.
async fn run(instance_id: &str, name: &str, settings: &BackupSettings, trigger: &str) -> Result<Option<BackupSummary>, String> {
    let dir = instance_dir(instance_id);
    let (id, name, settings, trigger) = (instance_id.to_string(), name.to_string(), settings.clone(), trigger.to_string());
    tokio::task::spawn_blocking(move || backup::create(&id, &name, &dir, &settings, &trigger))
        .await
        .map_err(|e| e.to_string())?
        .map(|opt| opt.as_ref().map(BackupSummary::from))
        .map_err(|e| format!("Sauvegarde impossible : {e}"))
}

/// Instantané juste avant un lancement. Silencieux par construction : appelée
/// depuis `launch_game`, elle ne doit jamais empêcher de jouer — une erreur
/// est journalisée, pas remontée.
pub async fn before_launch(state: &SharedState, instance_id: &str, instance_name: &str) {
    let settings = {
        let s = state.read().await;
        let conn = s.db.lock().await;
        backup::settings::effective(&conn, instance_id)
    };
    if !settings.enabled || !settings.on_launch || settings.included_dirs().is_empty() {
        return;
    }
    match run(instance_id, instance_name, &settings, "launch").await {
        Ok(Some(b)) => tracing::info!("[Backup] instantané avant lancement de {instance_id} ({} fichiers)", b.file_count),
        Ok(None) => {}
        Err(e) => tracing::warn!("[Backup] instantané avant lancement impossible : {e}"),
    }
}

/// Échéance quotidienne, passée en revue au démarrage du launcher.
pub fn spawn_daily(state: SharedState) {
    tauri::async_runtime::spawn(async move {
        let now = chrono::Utc::now().timestamp();
        let due: Vec<(String, String, BackupSettings)> = {
            let s = state.read().await;
            let user_id = s.current_yuyu_user_id().unwrap_or(0);
            let conn = s.db.lock().await;
            let Ok(instances) = crate::db::instance_list(&conn, user_id) else { return };
            instances
                .into_iter()
                .filter_map(|i| {
                    let settings = backup::settings::effective(&conn, &i.id);
                    let due = settings.enabled
                        && settings.daily
                        && !settings.included_dirs().is_empty()
                        && backup::settings::daily_due(backup::settings::last_daily(&conn, &i.id), now);
                    due.then_some((i.id, i.name, settings))
                })
                .collect()
        };

        for (id, name, settings) in due {
            match run(&id, &name, &settings, "daily").await {
                Ok(Some(b)) => tracing::info!("[Backup] sauvegarde quotidienne de {id} ({} fichiers)", b.file_count),
                Ok(None) => continue,
                Err(e) => {
                    tracing::warn!("[Backup] sauvegarde quotidienne de {id} impossible : {e}");
                    continue;
                }
            }
            let s = state.read().await;
            let conn = s.db.lock().await;
            let _ = backup::settings::mark_daily(&conn, &id, now);
        }
    });
}

// ── Restauration et suppression ──────────────────────────────────────────────

#[derive(Deserialize)]
pub struct RestoreOptions {
    /// Vide d'abord les dossiers couverts. Sans ça, la restauration ajoute et
    /// écrase, mais laisse en place ce qui a été créé depuis.
    #[serde(default)]
    pub replace: bool,
}

#[tauri::command]
pub async fn backup_restore(
    instance_id: String,
    backup_id: String,
    options: Option<RestoreOptions>,
    state: tauri::State<'_, SharedState>,
) -> Result<usize, String> {
    // Restaurer sous le jeu qui tourne écraserait des fichiers ouverts et
    // donnerait un monde à moitié ancien, à moitié neuf.
    if state.read().await.is_instance_running(&instance_id) {
        return Err("Ferme d'abord le jeu : restaurer pendant une partie abîmerait le monde.".into());
    }
    let replace = options.map(|o| o.replace).unwrap_or(false);
    let dir = instance_dir(&instance_id);
    tokio::task::spawn_blocking(move || {
        let backup = backup::get(&instance_id, &backup_id).ok_or_else(|| "Sauvegarde introuvable".to_string())?;
        backup::restore(&backup, &dir, replace).map_err(|e| format!("Restauration impossible : {e}"))
    })
    .await
    .map_err(|e| e.to_string())?
}

#[tauri::command]
pub async fn backup_delete(instance_id: String, backup_id: String) -> Result<(), String> {
    tokio::task::spawn_blocking(move || backup::delete(&instance_id, &backup_id))
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| e.to_string())
}

/// Ménage manuel du dépôt : rend les octets libérés.
#[tauri::command]
pub async fn backup_collect_garbage() -> Result<u64, String> {
    tokio::task::spawn_blocking(backup::collect_garbage).await.map_err(|e| e.to_string())
}

// ── Réglages ─────────────────────────────────────────────────────────────────

#[tauri::command]
pub async fn backup_settings_get(
    instance_id: Option<String>,
    state: tauri::State<'_, SharedState>,
) -> Result<InstanceBackupSettings, String> {
    let db = state.read().await.db.clone();
    let conn = db.lock().await;
    let global = backup::settings::global(&conn);
    Ok(match instance_id {
        Some(id) => InstanceBackupSettings {
            settings: backup::settings::effective(&conn, &id),
            custom: backup::settings::is_custom(&conn, &id),
            global,
        },
        None => InstanceBackupSettings { settings: global.clone(), custom: true, global },
    })
}

#[tauri::command]
pub async fn backup_settings_save(
    instance_id: Option<String>,
    settings: BackupSettings,
    state: tauri::State<'_, SharedState>,
) -> Result<(), String> {
    let db = state.read().await.db.clone();
    let conn = db.lock().await;
    backup::settings::save(&conn, instance_id.as_deref().unwrap_or(backup::settings::GLOBAL), &settings).map_err(|e| e.to_string())
}

/// Rend une instance au réglage général.
#[tauri::command]
pub async fn backup_settings_reset(instance_id: String, state: tauri::State<'_, SharedState>) -> Result<(), String> {
    let db = state.read().await.db.clone();
    let conn = db.lock().await;
    backup::settings::reset(&conn, &instance_id).map_err(|e| e.to_string())
}
