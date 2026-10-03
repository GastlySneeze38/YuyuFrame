//! Le launcher sans sa fenêtre : icône de la zone de notification pendant une
//! partie, retour au premier plan quand elle se termine, extinction quand
//! l'arrière-plan est refusé.

use tauri::Manager;

use crate::state;

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
pub fn install_tray(app: &tauri::AppHandle) {
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
pub fn reopen_main_window(app: &tauri::AppHandle) {
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
