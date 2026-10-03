// Pont entre le protocole custom `yuyuframe://` (voir tauri.conf.json,
// plugin deep-link) et le frontend — deux chemins d'arrivée possibles :
//
// 1. Premier lancement (app pas encore ouverte) : l'OS invoque
//    `yuyuframe.exe yuyuframe://join?...`, l'URL arrive dans les arguments
//    de la ligne de commande AVANT que la fenêtre principale n'ait fini de
//    monter son frontend React — un simple `app.emit` à cet instant serait
//    perdu (aucun listener encore attaché côté JS, voir le même problème déjà
//    résolu pour `console_ready` dans launch.rs). On la range ici, le
//    frontend vient la récupérer une fois monté via `take_pending_deep_link`.
//
// 2. App déjà ouverte : un second clic relance un second process, capté par
//    `tauri_plugin_single_instance` (voir lib.rs) qui réémet directement
//    l'event `deep_link_join` — le frontend est monté depuis longtemps, pas
//    de risque de le perdre, pas besoin de passer par cette boîte aux lettres.

use std::sync::Mutex;

static PENDING: Mutex<Option<String>> = Mutex::new(None);

pub fn set_pending(url: String) {
    *PENDING.lock().unwrap() = Some(url);
}

#[tauri::command]
pub async fn take_pending_deep_link() -> Result<Option<String>, String> {
    Ok(PENDING.lock().unwrap().take())
}
