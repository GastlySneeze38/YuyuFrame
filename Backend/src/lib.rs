mod api;
mod commands;
mod db;
mod integrations;
mod minecraft;
mod paths;
mod process;
mod state;

use std::sync::Arc;
use tauri::{Emitter, Manager};
use tokio::sync::{Mutex, RwLock};
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;

/// Renomme une seule fois les instances créées avant l'introduction du nouvel
/// id lisible (`<nom-slugifié>-<code>`, voir `commands::instance::crud::gen_id`)
/// — l'id sert à la fois de clé primaire DB et de nom de dossier disque
/// (`instance_dir()`), donc renommer l'un sans l'autre laisserait l'instance
/// introuvable. Best-effort et sûr : un dossier verrouillé/permissions
/// refusées laisse l'instance sur son ancien id, retentée au prochain
/// démarrage plutôt que de risquer une instance perdue. Retourne les paires
/// (ancien id, nouvel id) — le frontend les récupère via la commande
/// `instance_id_migrations` pour remapper ses propres clés persistées
/// (serveurs favoris, mods épinglés...) qui référencent encore l'ancien id.
fn migrate_legacy_instance_ids(conn: &rusqlite::Connection) -> Vec<(String, String)> {
    let legacy = match db::instance_legacy_ids(conn) {
        Ok(v) => v,
        Err(e) => {
            tracing::warn!("Migration ids instances : lecture échouée : {}", e);
            return Vec::new();
        }
    };
    if legacy.is_empty() {
        return Vec::new();
    }

    let instances_root = paths::root().join(".minecraft").join("instances");
    let mut migrated = Vec::new();

    for (old_id, name) in legacy {
        let new_id = commands::instance::crud::gen_id(&name);
        let old_dir = instances_root.join(&old_id);
        let new_dir = instances_root.join(&new_id);

        if !old_dir.is_dir() || new_dir.exists() {
            continue;
        }
        if let Err(e) = std::fs::rename(&old_dir, &new_dir) {
            tracing::warn!("Migration instance {} → {} : renommage du dossier échoué : {}", old_id, new_id, e);
            continue;
        }
        if let Err(e) = db::instance_rename_id(conn, &old_id, &new_id) {
            tracing::warn!("Migration instance {} → {} : mise à jour DB échouée, restauration du dossier : {}", old_id, new_id, e);
            let _ = std::fs::rename(&new_dir, &old_dir);
            continue;
        }

        // meta.json embarque aussi l'id (repli "disk_wins" de instance_startup_sync)
        // — best-effort, une erreur ici ne remet pas en cause le renommage déjà validé en DB.
        let meta_path = new_dir.join("meta.json");
        if let Ok(json) = std::fs::read_to_string(&meta_path) {
            if let Ok(mut v) = serde_json::from_str::<serde_json::Value>(&json) {
                v["id"] = serde_json::Value::String(new_id.clone());
                if let Ok(pretty) = serde_json::to_string_pretty(&v) {
                    let _ = std::fs::write(&meta_path, pretty);
                }
            }
        }

        tracing::info!("Instance renommée : {} → {}", old_id, new_id);
        migrated.push((old_id, new_id));
    }

    migrated
}

pub fn run() {
    // Le build release tourne en `windows_subsystem = "windows"` (cf.
    // main.rs) — aucune console n'est attachée, donc tous les logs qui
    // s'affichaient en dev étaient invisibles en prod, rendant tout bug
    // spécifique au build buildé impossible à diagnostiquer. On écrit
    // maintenant aussi dans un fichier `yuyuframe.log`, en plus du stdout
    // pour le dev. Toujours dans <racine YuyuFrame>\.minecraft (jamais dans
    // CARGO_MANIFEST_DIR) : en dev ce dossier est surveillé par `cargo
    // watch`, donc chaque écriture de log déclenchait un rebuild en boucle.
    // `paths::root()` respecte un éventuel déplacement (Settings.tsx, section
    // Stockage) — lu directement depuis le fichier ancre, pas de state Tauri
    // disponible à ce stade, avant même `tauri::Builder`.
    let log_dir = paths::root().join(".minecraft");
    std::fs::create_dir_all(&log_dir).ok();
    let file_appender = tracing_appender::rolling::never(&log_dir, "yuyuframe.log");
    let (non_blocking, _log_guard) = tracing_appender::non_blocking(file_appender);

    tracing_subscriber::registry()
        .with(tracing_subscriber::fmt::layer())
        .with(tracing_subscriber::fmt::layer().with_writer(non_blocking).with_ansi(false))
        .init();

    tauri::Builder::default()
        // Doit être le tout premier plugin enregistré (contrainte de la crate) :
        // un clic sur un lien yuyuframe:// alors que l'app tourne déjà relance
        // un second process côté OS — ce hook capte ses arguments dans le
        // process déjà ouvert au lieu de laisser un second process inutile se
        // lancer, et ramène la fenêtre principale au premier plan.
        .plugin(tauri_plugin_single_instance::init(|app, argv, _cwd| {
            if let Some(url) = argv.iter().find(|a| a.starts_with("yuyuframe://")) {
                let _ = app.emit("deep_link_join", url.clone());
            }
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.set_focus();
            }
        }))
        .plugin(tauri_plugin_deep_link::init())
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            // Premier lancement (app pas encore ouverte) : l'OS a passé l'URL
            // en argument de commande — voir commands::deep_link pour pourquoi
            // ça ne peut pas être émis directement ici (frontend pas encore monté).
            if let Some(url) = std::env::args().find(|a| a.starts_with("yuyuframe://")) {
                commands::deep_link::set_pending(url);
            }

            // Enregistrement du scheme yuyuframe:// — en prod l'installeur
            // NSIS/WiX s'en charge automatiquement (plugin déclaré dans
            // tauri.conf.json), mais rien ne l'enregistre en dev (`cargo tauri
            // dev`, jamais installé) : sans cet appel, un lien yuyuframe://
            // cliqué pendant le dev n'ouvrirait jamais rien.
            #[cfg(dev)]
            {
                use tauri_plugin_deep_link::DeepLinkExt;
                if let Err(e) = app.deep_link().register("yuyuframe") {
                    tracing::warn!("Enregistrement du scheme yuyuframe:// (dev) échoué : {}", e);
                }
            }

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

            let yuyu_session = db::load_yuyu_session(&conn).ok().flatten().inspect(|session| {
                tracing::info!("Session YuyuFrame restaurée pour {}", session.username);
                // Adopte les instances orphelines (yuyu_user_id = 0) au redémarrage
                db::instance_claim_unclaimed(&conn, session.user_id).ok();
            });

            // Comptes Minecraft propres au PC, restaurés avec ou sans session
            // YuyuFrame (voir db::mc_account).
            let mc_session = commands::account::startup_session(&conn);

            let instance_id_migrations = migrate_legacy_instance_ids(&conn);

            // Timeout par défaut généreux mais fini : les appels LauncherAPI
            // classiques (auth, métadonnées sync) répondent en dessous de la
            // seconde, mais sans timeout une API down/pool DB saturé bloque la
            // commande Tauri indéfiniment sans jamais remonter d'erreur au
            // frontend. Les uploads/téléchargements de sync (jusqu'à 200 Mo)
            // passent leur propre timeout plus large par requête.
            let http = reqwest::Client::builder()
                .user_agent("YuyuFrame/1.0")
                .connect_timeout(std::time::Duration::from_secs(10))
                .timeout(std::time::Duration::from_secs(30))
                .build()
                .expect("Impossible de construire le client HTTP");

            let app_state: state::SharedState = Arc::new(RwLock::new(state::AppState {
                db: Arc::new(Mutex::new(conn)),
                http,
                yuyu_session,
                yuyu_refresh: Arc::new(Mutex::new(())),
                session: mc_session,
                running_instances: std::collections::HashSet::new(),
                launch_cancel: std::collections::HashMap::new(),
                auth_device_code: None,
                instance_id_migrations,
            }));

            app.manage(app_state.clone());

            // Pilotage par le back-office (version minimale, interrupteurs,
            // bannières) : première lecture tout de suite, puis toutes les
            // 15 minutes. Jamais bloquant — sans réponse, rien n'est coupé.
            api::fleet::spawn_refresh(app_state);

            // Manifeste Mojang réchauffé en tâche de fond : l'écran des
            // instances le demandait à chaque ouverture et attendait le
            // réseau. Ici c'est fait pendant que la personne regarde
            // l'accueil, et le cache répond ensuite tout de suite.
            tauri::async_runtime::spawn(minecraft::versions::prefetch_version_list());

            // Garantit que .minecraft/agent/p2p existent tous, même vides —
            // voir Settings.tsx section Stockage : avant ça, `p2p/`
            // n'apparaissait qu'à la toute première session P2P.
            paths::ensure_structure();

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
            commands::account::yuyu::yuyu_ping,
            commands::account::yuyu::yuyu_register,
            commands::account::yuyu::yuyu_login,
            commands::account::yuyu::yuyu_logout,
            commands::account::yuyu::yuyu_refresh_plan,
            commands::account::yuyu::yuyu_create_checkout,
            commands::account::yuyu::yuyu_change_password,
            commands::account::yuyu::yuyu_set_email,
            commands::account::yuyu::yuyu_list_devices,
            commands::account::yuyu::yuyu_revoke_device,
            commands::account::yuyu::yuyu_sync_minecraft_accounts,
            commands::fleet::fleet_config,
            commands::fleet::fleet_refresh,
            commands::fleet::fleet_flag,
            commands::plan::plan_guard,
            commands::support::support_categories,
            commands::support::support_list,
            commands::support::support_get,
            commands::support::support_create,
            commands::support::support_reply,
            commands::support::support_hide,
            commands::support::support_diagnostic,
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
            commands::analytics::track_event,
            commands::analytics::analytics_get_disabled,
            commands::analytics::analytics_set_disabled,
            commands::deep_link::take_pending_deep_link,
            commands::launch::list_saved_servers,
            commands::launch::ping_server,
            commands::launch::preview_jvm_config,
            commands::jvm_profile::jvm_profile_list,
            commands::jvm_profile::jvm_profile_create,
            commands::jvm_profile::jvm_profile_save,
            commands::jvm_profile::jvm_profile_delete,
            commands::jvm_profile::instance_set_jvm_profile,
            commands::instance::mods::mods_list,
            commands::instance::mods::mods_toggle,
            commands::instance::mods::mods_delete,
            commands::instance::mods::mods_install,
            commands::instance::mods::mods_upload,
            commands::instance::mods::mod_icon,
            commands::instance::mods::mods_check_update_safety,
            commands::modrinth::mods_search_advanced,
            commands::curseforge::curseforge_search,
            commands::curseforge::curseforge_mod_details,
            commands::curseforge::curseforge_mod_files,
            commands::curseforge::curseforge_mod_install,
            commands::curseforge::curseforge_categories,
            commands::curseforge::curseforge_fingerprint_matches,
            commands::curseforge::curseforge_local_fingerprints,
            commands::instance::import::import_detect_launchers,
            commands::instance::import::import_scan_folder,
            commands::instance::import::import_check_duplicates,
            commands::instance::import::import_apply,
            commands::instance::import::mods_import_paths,
            commands::instance::modpack::modpack_fetch_index,
            commands::instance::modpack::modpack_fetch_curseforge_index,
            commands::instance::modpack::modpack_install,
            commands::instance::modpack::modpack_install_curseforge,
            commands::instance::modpack::modpack_install_from_path,
            commands::instance::modpack::modpack_remove,
            commands::instance::modpack::modpack_rename_file,
            commands::instance::modpack::modpack_get_meta,
            commands::instance::crud::instance_id_migrations,
            commands::instance::crud::instance_list,
            commands::instance::crud::instance_create,
            commands::instance::crud::instance_delete,
            commands::instance::crud::instance_update,
            commands::instance::crud::instance_toggle_favorite,
            commands::instance::crud::instance_duplicate,
            commands::instance::crud::instance_startup_sync,
            commands::instance::crud::instance_export_settings,
            commands::instance::crud::instance_apply_settings,
            commands::instance::crud::instance_open_folder,
            commands::sync::push_pull::sync_list_instances,
            commands::sync::push_pull::sync_list_saves,
            commands::sync::push_pull::sync_push_instance,
            commands::sync::push_pull::sync_pull_instance,
            commands::sync::push_pull::sync_delete_instance,
            commands::sync::stats::stats_get,
            commands::system::info::system_memory_info,
            commands::system::storage::data_root_get,
            commands::system::storage::data_root_set,
            commands::system::storage::open_folder,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
