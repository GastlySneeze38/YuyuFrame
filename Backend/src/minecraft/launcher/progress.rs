use std::collections::HashMap;
use std::sync::{Arc, LazyLock, Mutex};
use tauri::{Emitter, Manager};
use tokio::sync::Notify;

use crate::state::DownloadProgress;

pub(super) fn set_progress(app: &tauri::AppHandle, current: u64, total: u64, message: &str) {
    let _ = app.emit("download_progress", DownloadProgress {
        current,
        total,
        message: message.to_string(),
    });
}

/// Émet un game_log vers la fenêtre console dédiée à cette instance.
/// Fallback sur broadcast global si la fenêtre n'existe plus.
pub(super) fn log_to_console(app: &tauri::AppHandle, console_label: &str, line: &str, level: &str) {
    let short_id = console_label.strip_prefix("mc-console-").unwrap_or(console_label);
    let payload = serde_json::json!({ "line": line, "level": level, "instance_id": short_id });
    if let Some(win) = app.get_webview_window(console_label) {
        let _ = win.emit("game_log", &payload);
    } else {
        let _ = app.emit("game_log", &payload);
    }
}

/// Registre des signaux "la fenêtre console a fini d'attacher son listener JS
/// game_log" — un `Notify` par fenêtre, indexé par son label. Remplace un
/// délai fixe (1500ms) qui laissait passer les toutes premières lignes quand
/// le lancement était rapide (tout en cache — typiquement 1.8.9 vanilla) : le
/// webview n'avait alors pas forcément fini son démarrage React/JS avant que
/// nos premiers logs ne soient déjà émis, qui étaient donc silencieusement
/// perdus (aucun listener encore attaché côté frontend pour les recevoir).
/// Voir `register_console_waiter`/`signal_console_ready` et la commande Tauri
/// `console_ready` (invoquée par Console.tsx une fois ses listeners attachés).
static CONSOLE_READY: LazyLock<Mutex<HashMap<String, Arc<Notify>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

/// À appeler SYNCHRONEMENT juste après la création de la fenêtre console,
/// avant tout travail async — garantit qu'aucun signal_console_ready() ne
/// peut arriver avant que ce Notify n'existe déjà dans le registre.
pub fn register_console_waiter(console_label: &str) -> Arc<Notify> {
    let notify = Arc::new(Notify::new());
    CONSOLE_READY.lock().unwrap().insert(console_label.to_string(), notify.clone());
    notify
}

/// Invoqué par la commande Tauri `console_ready` — réveille l'attente
/// éventuelle de `register_console_waiter` pour cette fenêtre.
pub fn signal_console_ready(console_label: &str) {
    if let Some(notify) = CONSOLE_READY.lock().unwrap().get(console_label) {
        notify.notify_waiters();
    }
}
