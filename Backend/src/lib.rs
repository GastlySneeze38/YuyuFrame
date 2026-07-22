mod commands;
mod db;
mod integrations;
mod minecraft;
mod state;

/// Miroir de `Frontend/src/config/beta.ts` — pendant la beta, le frontend
/// saute l'écran de connexion YuyuFrame, mais le backend l'ignorait
/// totalement et continuait à exiger un `yuyu_session` valide pour lier un
/// compte Minecraft (auth_start_device/auth_poll), bloquant tout le monde en
/// beta. Garder les deux flags synchronisés à la main (pas de mécanisme de
/// partage Frontend/Backend pour cette constante).
pub const BETA_TEST: bool = true;

use std::sync::Arc;
use tauri::Manager;
use tokio::sync::{Mutex, RwLock};
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;

pub fn run() {
    // Le build release tourne en `windows_subsystem = "windows"` (cf.
    // main.rs) — aucune console n'est attachée, donc tous les logs qui
    // s'affichaient en dev étaient invisibles en prod, rendant tout bug
    // spécifique au build buildé impossible à diagnostiquer. On écrit
    // maintenant aussi dans un fichier `yuyuframe.log`, en plus du stdout
    // pour le dev. Toujours dans %APPDATA%\YuyuFrame\.minecraft (jamais dans
    // CARGO_MANIFEST_DIR) : en dev ce dossier est surveillé par `cargo
    // watch`, donc chaque écriture de log déclenchait un rebuild en boucle.
    let log_dir = dirs::data_dir()
        .map(|d| d.join("YuyuFrame").join(".minecraft"))
        .unwrap_or_else(|| std::path::PathBuf::from("."));
    std::fs::create_dir_all(&log_dir).ok();
    let file_appender = tracing_appender::rolling::never(&log_dir, "yuyuframe.log");
    let (non_blocking, _log_guard) = tracing_appender::non_blocking(file_appender);

    tracing_subscriber::registry()
        .with(tracing_subscriber::fmt::layer())
        .with(tracing_subscriber::fmt::layer().with_writer(non_blocking).with_ansi(false))
        .init();

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let db_path = if cfg!(dev) {
                // Dev : garde la DB dans Backend/ à côté du code source
                std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("yuyu.db")
            } else {
                // Prod : à côté de l'exécutable
                std::env::current_exe()
                    .ok()
                    .and_then(|p| p.parent().map(|d| d.join("yuyu.db")))
                    .unwrap_or_else(|| std::path::PathBuf::from("yuyu.db"))
            };

            let conn = db::init_db(&db_path).expect("Impossible d'initialiser la base de données");
            tracing::info!("Base de données : {}", db_path.display());

            let yuyu_session = db::load_yuyu_jwt(&conn)
                .ok()
                .flatten()
                .map(|row| {
                    tracing::info!("Session YuyuFrame restaurée pour {}", row.username);
                    // Adopte les instances orphelines (yuyu_user_id = 0) au redémarrage
                    db::instance_claim_unclaimed(&conn, row.user_id).ok();
                    state::YuyuSession {
                        user_id: row.user_id,
                        username: row.username,
                        token: row.jwt,
                        plan: row.plan,
                        plan_expires_at: row.plan_expires_at,
                    }
                });

            // Restaurer la session MC active depuis la DB. En BETA_TEST il n'y a
            // jamais de `yuyu_session` (login YuyuFrame skippé), donc gater cette
            // restauration sur sa présence faisait que `session` restait toujours
            // `None` au démarrage — le jeu refusait de se lancer depuis Home tant
            // qu'on n'était pas passé par mc_switch (page Login) pour le repeupler
            // en mémoire. Même règle que `state::AppState::current_yuyu_user_id`
            // (dupliquée ici car l'`AppState` n'existe pas encore à ce stade du
            // setup) : 0 est le placeholder "pas de compte" déjà utilisé dans le
            // schéma (cf. table `instances`, colonne yuyu_user_id DEFAULT 0).
            let mc_yuyu_user_id = yuyu_session.as_ref().map(|ys| ys.user_id).unwrap_or(0);
            let mc_session = (|| {
                let active_uuid = db::get_active_mc_uuid(&conn, mc_yuyu_user_id).ok().flatten()?;
                let row = db::get_mc_session(&conn, mc_yuyu_user_id, &active_uuid).ok().flatten()?;
                tracing::info!("Session Minecraft restaurée pour {}", row.mc_username);
                Some(state::MinecraftSession {
                    username: row.mc_username,
                    uuid: row.mc_uuid,
                    access_token: row.access_token,
                    refresh_token: Some(row.ms_refresh_token),
                    expires_at: row.expires_at,
                })
            })();

            let app_state: state::SharedState = Arc::new(RwLock::new(state::AppState {
                db: Arc::new(Mutex::new(conn)),
                yuyu_session,
                session: mc_session,
                download_progress: None,
                running_instances: std::collections::HashSet::new(),
                launch_cancel: std::collections::HashMap::new(),
                auth_device_code: None,
            }));

            app.manage(app_state);

            // Déploie le LauncherAgent (jar + libs) embarqué dans l'installateur
            // vers %AppData%\YuyuFrame\agent\ — voir minecraft::launcher pour le
            // pourquoi (avant ça, un beta testeur n'avait jamais ces fichiers).
            // JAMAIS en dev : `cargo tauri dev` résout resource_dir() vers un
            // instantané de ressources potentiellement périmé (pris au premier
            // démarrage du process, pas re-synchronisé à chaque hot-reload) —
            // un dev qui relance le process après un rebuild de l'agent voyait
            // ce jar figé (parfois un ancien build cassé) ÉCRASER le jar tout
            // frais déployé par build.bat, seule source de vérité en dev (voir
            // CLAUDE.md : toujours build.bat, jamais un autre mécanisme).
            if !cfg!(dev) {
                minecraft::launcher::deploy_bundled_agent(app.handle());
            }

            // Discord Rich Presence — connexion IPC + activité initiale (voir
            // discord.rs). Thread natif séparé (pas tokio::spawn : le crate
            // fait de l'IPC bloquante, pas async) pour ne jamais retarder le
            // reste du démarrage si Discord met du temps à répondre ou n'est
            // pas lancé du tout sur la machine.
            std::thread::spawn(integrations::discord::connect_and_announce);

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::account::yuyu::yuyu_status,
            commands::account::yuyu::yuyu_register,
            commands::account::yuyu::yuyu_login,
            commands::account::yuyu::yuyu_logout,
            commands::account::yuyu::yuyu_refresh_plan,
            commands::account::yuyu::yuyu_create_checkout,
            commands::account::yuyu::yuyu_dev_simulate_payment,
            commands::account::microsoft::auth_start_device,
            commands::account::microsoft::auth_poll,
            commands::account::microsoft::auth_status,
            commands::account::microsoft::auth_logout,
            commands::account::minecraft::mc_list_accounts,
            commands::account::minecraft::mc_switch,
            commands::account::minecraft::mc_delete,
            commands::account::offline::mc_add_offline,
            commands::account::skin::set_account_skin,
            commands::account::skin::set_account_skin_from_url,
            commands::account::skin::get_account_skin,
            commands::account::skin::remove_account_skin,
            commands::system::versions::list_versions,
            commands::launch::launch_game,
            commands::launch::cancel_launch,
            commands::launch::reload_agent,
            commands::launch::console_ready,
            commands::launch::list_saved_servers,
            commands::launch::ping_server,
            commands::instance::mods::mods_list,
            commands::instance::mods::mods_toggle,
            commands::instance::mods::mods_delete,
            commands::instance::mods::mods_install,
            commands::instance::mods::mods_upload,
            commands::instance::mods::mod_icon,
            commands::instance::mods::mods_check_update_safety,
            commands::instance::import::import_scan_folder,
            commands::instance::import::import_check_duplicates,
            commands::instance::import::import_apply,
            commands::instance::import::mods_import_paths,
            commands::instance::modpack::modpack_fetch_index,
            commands::instance::modpack::modpack_install,
            commands::instance::modpack::modpack_remove,
            commands::instance::modpack::modpack_rename_file,
            commands::instance::modpack::modpack_get_meta,
            commands::instance::crud::instance_list,
            commands::instance::crud::instance_create,
            commands::instance::crud::instance_delete,
            commands::instance::crud::instance_update,
            commands::instance::crud::instance_toggle_favorite,
            commands::instance::crud::instance_duplicate,
            commands::instance::crud::instance_startup_sync,
            commands::instance::crud::instance_export_settings,
            commands::instance::crud::instance_apply_settings,
            commands::sync::push_pull::sync_list_instances,
            commands::sync::push_pull::sync_list_saves,
            commands::sync::push_pull::sync_push_instance,
            commands::sync::push_pull::sync_pull_instance,
            commands::sync::push_pull::sync_delete_instance,
            commands::sync::stats::stats_get,
            commands::system::info::system_memory_info,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
