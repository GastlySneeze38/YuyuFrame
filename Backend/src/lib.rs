mod api;
mod backup;
mod commands;
mod db;
mod integrations;
mod minecraft;
mod paths;
mod process;
mod recovery;
mod state;
mod stats;
mod sync;

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

/// Récupère la base restée à côté de l'exécutable par les versions ≤ 0.1.0-27.
///
/// Copie plutôt que déplace : si quelque chose se passe mal pendant la
/// migration, l'original est toujours là. L'ancienne base n'est pas effacée —
/// elle sera emportée par la prochaine réinstallation, ce qui est justement
/// la raison de ce déménagement.
///
/// Ne fait rien si la nouvelle existe déjà : elle fait autorité, et écraser
/// une base en service par une vieille copie serait pire que le bug d'origine.
fn migrate_db_from_exe_dir(target: &std::path::Path) {
    if target.exists() {
        return;
    }
    let Some(old) = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.join("yuyu.db")))
    else {
        return;
    };
    if !old.is_file() {
        return;
    }
    if let Some(parent) = target.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    match std::fs::copy(&old, target) {
        Ok(_) => tracing::info!(
            "Base de données récupérée depuis {} vers {}",
            old.display(),
            target.display()
        ),
        Err(e) => tracing::error!(
            "Récupération de la base {} impossible : {} — le launcher démarre sur une base neuve",
            old.display(),
            e
        ),
    }
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
            // `show()` avant `set_focus()` : la fenêtre peut être seulement
            // masquée (réglage « masquer au lancement »), et donner le focus à
            // une fenêtre cachée ne la fait pas réapparaître — relancer
            // l'exécutable semblait alors ne rien faire du tout.
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.show();
                let _ = w.set_focus();
            } else {
                // Fenêtre fermée pendant une partie : le launcher vit encore
                // sans elle (voir `install_tray`), on la reconstruit.
                reopen_main_window(app);
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
                // Prod : dans les données du launcher, **jamais** à côté de
                // l'exécutable.
                //
                // Elle y vivait jusqu'au 2026-09-28, et c'est ce qui a coûté
                // leurs instances à tous ceux qui ont installé la version
                // suivante : l'installeur remplace son dossier, la base part
                // avec, le launcher redémarre sur une base vide — et la
                // synchronisation de démarrage effaçait alors les dossiers
                // qu'elle ne reconnaissait plus (voir `instance_startup_sync`,
                // qui ne supprime plus rien).
                //
                // Le dossier de données, lui, survit aux mises à jour et suit
                // un éventuel déplacement (Réglages → Stockage).
                let target = paths::root().join("yuyu.db");
                migrate_db_from_exe_dir(&target);
                target
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

            let shared_db = Arc::new(Mutex::new(conn));
            let app_state: state::SharedState = Arc::new(RwLock::new(state::AppState {
                db: shared_db.clone(),
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

            // Sessions de jeu laissées ouvertes par un launcher qui n'a pas
            // vu la fin de la partie : reprises en charge si le jeu tourne
            // encore, closes à leur dernière trace de vie sinon, et un rapport
            // de plantage reconstruit s'il y en avait un (voir recovery.rs).
            recovery::run(shared_db, app.handle().clone());

            // Arrière-plan refusé : on attendait que le jeu soit réellement
            // là pour s'effacer. Écouté ici plutôt que sur les trois canaux
            // qui peuvent signaler « prêt » (événement Win32, sortie standard,
            // journal de l'agent) — un seul d'entre eux gagne, mais aucun ne
            // devrait avoir à connaître ce réglage.
            {
                use tauri::Listener;
                let handle = app.handle().clone();
                app.listen("game_ready", move |_| {
                    if state::exit_when_ready() {
                        quit_now(&handle);
                    }
                });
            }

            // Sauvegardes quotidiennes dont l'échéance est passée : passées en
            // revue une fois au démarrage. Pas de minuterie qui tourne toute
            // la journée — le launcher n'est pas ouvert en permanence, et une
            // échéance de 20 h suffit à ne jamais sauter un jour.
            commands::backup::spawn_daily(app_state.clone());

            // Pilotage par le back-office (version minimale, interrupteurs,
            // bannières) : première lecture tout de suite, puis toutes les
            // 15 minutes. Jamais bloquant — sans réponse, rien n'est coupé.
            api::fleet::spawn_refresh(app_state, app.handle().clone());

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
            commands::reviews::review_mine,
            commands::reviews::review_submit,
            commands::reviews::review_delete,
            commands::support::support_categories,
            commands::support::support_list,
            commands::support::support_get,
            commands::support::support_create,
            commands::support::support_reply,
            commands::support::support_hide,
            commands::support::support_diagnostic,
            commands::crash::crash_list,
            commands::crash::crash_get,
            commands::crash::crash_delete,
            commands::crash::crash_send,
            commands::crash::crash_remote_list,
            commands::crash::crash_remote_get,
            commands::crash::crash_remote_hide,
            commands::crash::crash_as_text,
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
            commands::pending::take_pending_events,
            commands::launch::running_instances,
            commands::launch::launcher_agent_status,
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
            commands::instance::mods::mods_check_conflicts,
            commands::instance::packs::packs_list,
            commands::instance::packs::packs_install,
            commands::instance::packs::packs_delete,
            commands::instance::packs::packs_import_paths,
            commands::instance::options::mc_options_read,
            commands::instance::options::mc_options_write,
            commands::instance::agent_options::agent_options_read,
            commands::instance::agent_options::agent_options_write,
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
            commands::instance::options::instance_export_settings,
            commands::instance::options::instance_apply_settings,
            commands::instance::options::shared_options_status,
            commands::instance::options::set_sync_game_settings,
            commands::instance::crud::instance_open_folder,
            commands::sync::push_pull::sync_list_instances,
            commands::sync::push_pull::sync_list_saves,
            commands::sync::push_pull::sync_push_instance,
            commands::sync::push_pull::sync_pull_instance,
            commands::sync::push_pull::sync_delete_instance,
            commands::sync::push_pull::sync_manifest,
            commands::sync::push_pull::sync_diff,
            commands::sync::push_pull::sync_referenced_mods,
            commands::sync::stats::stats_get,
            commands::sync::stats::stats_clear,
            commands::backup::backup_list,
            commands::backup::backup_get,
            commands::backup::backup_create,
            commands::backup::backup_restore,
            commands::backup::backup_delete,
            commands::backup::backup_collect_garbage,
            commands::backup::backup_settings_get,
            commands::backup::backup_settings_save,
            commands::backup::backup_settings_reset,
            commands::locale::detect_country,
            commands::patch_notes::patch_notes_latest,
            commands::patch_notes::patch_notes_list,
            commands::system::info::system_memory_info,
            commands::system::storage::data_root_get,
            commands::system::storage::data_root_set,
            commands::system::storage::open_folder,
            commands::window::window_hide_for_launch,
            commands::window::window_set_background_allowed,
            commands::window::window_background_status,
        ])
        // Fermer la fenêtre pendant une partie ne doit pas emporter le
        // launcher avec elle : c'est lui qui lit la sortie du jeu, qui tient
        // le compteur de la session et qui construira le rapport si ça plante.
        // On laisse la fenêtre se détruire — c'est la webview qui pèse, et sa
        // destruction rend la mémoire — et on garde le cœur Rust, quelques
        // mégaoctets et un fil en attente.
        .on_window_event(|window, event| {
            if window.label() != "main" {
                return;
            }
            match event {
                tauri::WindowEvent::CloseRequested { .. } => {
                    // Dit tout de suite que la fenêtre s'en va : tout ce qui
                    // ne sert qu'à l'afficher se tait à partir d'ici (journal
                    // du jeu, configuration de flotte).
                    state::set_window_open(false);
                    if state::any_game_running() && state::background_allowed() {
                        install_tray(window.app_handle());
                    }
                }
                // Une fenêtre reconstruite (icône de notification, second
                // lancement) remet tout en route.
                tauri::WindowEvent::Focused(_) => state::set_window_open(true),
                _ => {}
            }
        })
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|_app, event| {
            if let tauri::RunEvent::ExitRequested { api, .. } = event {
                // Deux raisons de rester : surveiller une partie quand
                // l'arrière-plan est autorisé, ou attendre que le jeu démarre
                // avant de s'effacer quand il ne l'est pas. Sans la seconde,
                // fermer la fenêtre au clic sur « Jouer » tuerait le
                // téléchargement en cours.
                let watching = state::any_game_running() && state::background_allowed();
                if watching || state::exit_when_ready() {
                    api.prevent_exit();
                }
            }
        });
}

/// Éteint le launcher s'il ne lui reste plus rien à faire : aucune fenêtre
/// ouverte et aucune partie en cours. Appelée à la fin de chaque partie —
/// c'est le moment où un launcher resté en vie uniquement pour la surveiller
/// n'a plus de raison d'être.
pub fn restore_after_game(app: &tauri::AppHandle) {
    // Une autre partie tourne encore : le veilleur a toujours une raison
    // d'être, et personne n'a demandé à revoir le launcher.
    if state::any_game_running() {
        return;
    }
    // L'arrière-plan est refusé et le lancement s'est terminé sans que le jeu
    // démarre (échec, annulation) : on s'efface comme promis plutôt que de
    // rouvrir une fenêtre que personne n'attend.
    if state::exit_when_ready() {
        quit_now(app);
        return;
    }
    // Fenêtre seulement réduite (arrière-plan refusé, ou fermeture jamais
    // demandée) : on la remonte sans la reconstruire.
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
        state::set_window_open(true);
        return;
    }
    tracing::info!("Partie terminée — le launcher revient au premier plan");
    // L'icône de notification n'avait de sens que pendant la partie :
    // `reopen_main_window` la retire en même temps qu'elle rend la fenêtre.
    reopen_main_window(app);
}

/// Éteint le launcher pour de bon.
///
/// Le drapeau est levé AVANT `exit` : il fait justement refuser les demandes
/// de sortie, et le garder ici empêcherait celle-ci d'aboutir.
pub fn quit_now(app: &tauri::AppHandle) {
    state::set_exit_when_ready(false);
    if let Some(tray) = TRAY.get() {
        let _ = tray.set_visible(false);
    }
    tracing::info!("Arrière-plan refusé — le launcher s'efface");
    app.exit(0);
}

/// L'icône reste en mémoire après sa création : la recréer à chaque fermeture
/// en empilerait plusieurs dans la zone de notification.
static TRAY: std::sync::OnceLock<tauri::tray::TrayIcon> = std::sync::OnceLock::new();

/// Pose l'icône dans la zone de notification. C'est le seul moyen de revenir
/// dans le launcher une fois sa fenêtre fermée — sans elle, un processus
/// tournerait sans que personne puisse ni le voir ni l'arrêter.
fn install_tray(app: &tauri::AppHandle) {
    use tauri::menu::{Menu, MenuItem};
    use tauri::tray::TrayIconBuilder;

    if let Some(tray) = TRAY.get() {
        let _ = tray.set_visible(true);
        return;
    }

    let build = || -> tauri::Result<tauri::tray::TrayIcon> {
        let open = MenuItem::with_id(app, "open", "Ouvrir YuyuFrame", true, None::<&str>)?;
        let quit = MenuItem::with_id(app, "quit", "Quitter", true, None::<&str>)?;
        let menu = Menu::with_items(app, &[&open, &quit])?;
        let mut builder = TrayIconBuilder::with_id("yuyuframe")
            .tooltip("YuyuFrame — partie en cours")
            .menu(&menu)
            // Un clic gauche rouvre : c'est le geste attendu, le menu n'est
            // qu'un recours.
            .show_menu_on_left_click(false)
            .on_menu_event(|app, event| match event.id().as_ref() {
                "open" => reopen_main_window(app),
                "quit" => app.exit(0),
                _ => {}
            })
            .on_tray_icon_event(|tray, event| {
                if let tauri::tray::TrayIconEvent::Click { button: tauri::tray::MouseButton::Left, button_state: tauri::tray::MouseButtonState::Up, .. } = event {
                    reopen_main_window(tray.app_handle());
                }
            });
        if let Some(icon) = app.default_window_icon() {
            builder = builder.icon(icon.clone());
        }
        builder.build(app)
    };

    match build() {
        Ok(tray) => {
            let _ = TRAY.set(tray);
        }
        // Sans icône, on ne peut plus rouvrir : mieux vaut alors s'éteindre
        // normalement que laisser un processus invisible derrière soi.
        Err(e) => tracing::warn!("Icône de notification impossible à créer, le launcher s'arrêtera normalement : {e}"),
    }
}

/// Reconstruit la fenêtre principale à l'identique de `tauri.conf.json`.
fn reopen_main_window(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.set_focus();
        return;
    }
    let built = tauri::WebviewWindowBuilder::new(app, "main", tauri::WebviewUrl::App("index.html".into()))
        .title("YuyuFrame")
        .inner_size(1280.0, 760.0)
        .min_inner_size(900.0, 560.0)
        .decorations(false)
        .background_color(tauri::window::Color(9, 9, 13, 255))
        .build();
    match built {
        Ok(window) => {
            state::set_window_open(true);
            let _ = window.set_focus();
            if let Some(tray) = TRAY.get() {
                let _ = tray.set_visible(false);
            }
        }
        Err(e) => {
            tracing::warn!("Réouverture de la fenêtre impossible : {e}");
            // Sans fenêtre ni partie à surveiller, le processus n'aurait plus
            // aucun moyen d'être vu ni arrêté : mieux vaut s'éteindre que de
            // laisser un fantôme dans le gestionnaire des tâches.
            if !state::any_game_running() {
                app.exit(0);
            }
        }
    }
}
