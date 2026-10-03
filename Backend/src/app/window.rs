//! Fenêtre principale : effacement au lancement, et arrière-plan.
//!
//! Le masquage au lancement vivait côté interface (`getCurrentWindow().hide()`).
//! Deux défauts : la fenêtre restait vivante — donc WebView2 aussi, et ses
//! cent cinquante mégaoctets — et le geste dépendait d'un appel du frontend
//! au bon moment, fragile et impossible à tester.
//!
//! Il est ici, sur le même chemin que le bouton de fermeture : ce qui marche
//! pour l'un marche pour l'autre.

use crate::state;

/// Ce que le launcher fait de sa fenêtre au lancement d'une partie.
#[derive(serde::Serialize)]
pub struct HideOutcome {
    /// `closed` : fenêtre détruite, mémoire rendue, le cœur veille.
    /// `closing` : fenêtre détruite, et le launcher s'éteindra dès que le jeu
    /// sera là — l'arrière-plan est refusé.
    /// `kept` : rien à faire.
    pub mode: String,
}

/// Efface la fenêtre après un lancement réussi.
///
/// Fermer plutôt que masquer : c'est la webview qui pèse, et seule sa
/// destruction rend la mémoire. Masquer la laissait entière — on payait le
/// prix d'un launcher ouvert en croyant l'avoir rangé.
#[tauri::command]
pub async fn window_hide_for_launch(app: tauri::AppHandle, enabled: bool) -> Result<HideOutcome, String> {
    use tauri::Manager;

    if !enabled {
        return Ok(HideOutcome { mode: "kept".into() });
    }
    let Some(window) = app.get_webview_window("main") else {
        return Ok(HideOutcome { mode: "kept".into() });
    };

    // Sans arrière-plan autorisé, le launcher doit disparaître pour de bon.
    // Mais pas tout de suite : au clic sur « Jouer », le téléchargement n'a
    // souvent même pas commencé, et tuer le processus maintenant tuerait le
    // lancement. On ferme la fenêtre et on s'efface dès que la JVM est là
    // (voir `state::exit_when_ready`).
    if !state::background_allowed() {
        state::set_exit_when_ready(true);
    }

    state::set_window_open(false);
    window.close().map_err(|e| e.to_string())?;
    Ok(HideOutcome { mode: if state::exit_when_ready() { "closing".into() } else { "closed".into() } })
}

/// Pousse le réglage « continuer en arrière-plan » vers le Rust.
///
/// La boucle d'événements de Tauri est synchrone : elle décide de laisser ou
/// non le processus s'éteindre sans pouvoir lire la base ni attendre. Le
/// réglage vit donc aussi dans un booléen atomique, poussé par l'interface au
/// démarrage et à chaque changement. Vrai par défaut : si l'interface ne dit
/// rien, on garde le comportement qui protège la session.
#[tauri::command]
pub fn window_set_background_allowed(allowed: bool) {
    state::set_background_allowed(allowed);
}

/// Ce que le launcher fait en ce moment, pour l'afficher dans les réglages.
#[derive(serde::Serialize)]
pub struct BackgroundStatus {
    pub allowed: bool,
    pub game_running: bool,
}

#[tauri::command]
pub fn window_background_status() -> BackgroundStatus {
    BackgroundStatus { allowed: state::background_allowed(), game_running: state::any_game_running() }
}
