mod account;
mod app;
mod backup;
mod commands;
mod db;
mod instances;
mod integrations;
mod minecraft;
mod paths;
mod play;
mod security;
mod server;
mod share_link;
mod state;
mod sync;

use std::sync::Arc;
use tauri::{Emitter, Manager};
use tokio::sync::{Mutex, RwLock};
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;

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
                app::tray::reopen_main_window(app);
            }
        }))
        .plugin(tauri_plugin_deep_link::init())
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            // Premier lancement (app pas encore ouverte) : l'OS a passé l'URL
            // en argument de commande — voir app::deep_link pour pourquoi
            // ça ne peut pas être émis directement ici (frontend pas encore monté).
            if let Some(url) = std::env::args().find(|a| a.starts_with("yuyuframe://")) {
                app::deep_link::set_pending(url);
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
                app::startup::migrate_db_from_exe_dir(&target);
                target
            };

            let conn = db::init_db(&db_path).expect("Impossible d'initialiser la base de données");
            tracing::info!("Base de données : {}", db_path.display());

            let yuyu_session = db::load_yuyu_session(&conn).ok().flatten().inspect(|session| {
                tracing::info!("Session YuyuFrame restaurée pour {}", session.username);
                // Adopte les instances orphelines (yuyu_user_id = 0) au redémarrage
                db::instance_claim_unclaimed(&conn, session.user_id).ok();
            });
            // Démarrage sans session : les instances restent visibles. Rattrape
            // aussi une déconnexion faite par une version qui les masquait.
            if yuyu_session.is_none() {
                if let Err(e) = db::instance_release_all(&conn) {
                    tracing::warn!("instances non rendues au PC au démarrage : {e}");
                }
            }

            // Comptes Minecraft propres au PC, restaurés avec ou sans session
            // YuyuFrame (voir `account::minecraft::store`).
            let mc_session = account::minecraft::startup_session(&conn);

            let instance_id_migrations = app::startup::migrate_legacy_instance_ids(&conn);

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
                compat_running: std::collections::HashSet::new(),
                launch_cancel: std::collections::HashMap::new(),
                auth_device_code: None,
                instance_id_migrations,
            }));

            app.manage(app_state.clone());

            // Sessions de jeu laissées ouvertes par un launcher qui n'a pas
            // vu la fin de la partie : reprises en charge si le jeu tourne
            // encore, closes à leur dernière trace de vie sinon, et un rapport
            // de plantage reconstruit s'il y en avait un (voir recovery.rs).
            play::recovery::run(shared_db, app.handle().clone());

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
                        app::tray::quit_now(&handle);
                    }
                });
            }

            // Sauvegardes quotidiennes dont l'échéance est passée : passées en
            // revue une fois au démarrage. Pas de minuterie qui tourne toute
            // la journée — le launcher n'est pas ouvert en permanence, et une
            // échéance de 20 h suffit à ne jamais sauter un jour.
            backup::commands::spawn_daily(app_state.clone());

            // Pilotage par le back-office (version minimale, interrupteurs,
            // bannières) : première lecture tout de suite, puis toutes les
            // 15 minutes. Jamais bloquant — sans réponse, rien n'est coupé.
            server::fleet::spawn_refresh(app_state, app.handle().clone());

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
        .invoke_handler(commands::handler())
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
                        app::tray::install_tray(window.app_handle());
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
