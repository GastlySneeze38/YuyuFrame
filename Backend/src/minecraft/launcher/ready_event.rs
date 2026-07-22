use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tauri::Emitter;

// Signal "Minecraft prêt" (menu principal atteint) via un Named Event Win32
// — canal minimal (juste un booléen, pas de payload), pas de port réseau,
// pas de fichier à surveiller par polling coûteux. Rust crée l'event
// (CreateEventW) avant de spawner Java et attend dessus (WaitForSingleObject) ;
// le LauncherAgent (voir ReadyEventSignal.java côté Java, JNA) se contente de
// l'ouvrir par son nom (OpenEventW) et de le signaler (SetEvent) une fois le
// hook TitleScreen.init() déclenché (voir TitleScreenMixin*.java).
//
// Reste un complément, pas un remplacement : le canal stdout+fichier déjà en
// place (voir orchestrator.rs/progress.rs) continue de tourner en parallèle,
// `ready_sent` partagé garantit qu'un seul des canaux émet réellement
// l'événement `game_ready`.

#[cfg(target_os = "windows")]
#[link(name = "kernel32")]
extern "system" {
    fn CreateEventW(
        lpEventAttributes: *const std::ffi::c_void,
        bManualReset: i32,
        bInitialState: i32,
        lpName: *const u16,
    ) -> *mut std::ffi::c_void;
    fn WaitForSingleObject(hHandle: *mut std::ffi::c_void, dwMilliseconds: u32) -> u32;
    fn CloseHandle(hObject: *mut std::ffi::c_void) -> i32;
}

#[cfg(target_os = "windows")]
const WAIT_OBJECT_0: u32 = 0;

/// Crée l'event Windows et retourne (nom à passer au javaagent, handle brut).
/// `None` si la création échoue (best-effort — le fallback stdout+fichier
/// suffit dans ce cas, jamais bloquant pour le lancement).
#[cfg(target_os = "windows")]
pub(super) fn create_ready_event(instance_id: &str) -> Option<(String, usize)> {
    let short_id: String = instance_id.chars().filter(|c| c.is_ascii_alphanumeric()).take(8).collect();
    let name = format!(r"Local\yuyuframe-ready-{}-{}", short_id, uuid::Uuid::new_v4());
    let wide: Vec<u16> = name.encode_utf16().chain(std::iter::once(0)).collect();
    let handle = unsafe { CreateEventW(std::ptr::null(), 1, 0, wide.as_ptr()) };
    if handle.is_null() {
        tracing::warn!("[ReadyEvent] CreateEventW a échoué — repli sur stdout/fichier uniquement");
        return None;
    }
    Some((name, handle as usize))
}

#[cfg(not(target_os = "windows"))]
pub(super) fn create_ready_event(_instance_id: &str) -> Option<(String, usize)> {
    None
}

/// Attend le signal (poll toutes les 500ms via WaitForSingleObject, pour
/// pouvoir sortir sur `stop_flag`/`ready_sent` sans bloquer indéfiniment si le
/// jeu crashe avant d'atteindre le menu principal). Ferme le handle en sortie
/// quel que soit le chemin de sortie.
#[cfg(target_os = "windows")]
pub(super) async fn wait_for_ready_event(
    handle_raw: usize,
    stop_flag: Arc<AtomicBool>,
    ready_sent: Arc<AtomicBool>,
    app: tauri::AppHandle,
    instance_id: String,
    launch_start: std::time::Instant,
) {
    tracing::info!("[ReadyEvent] wait_for_ready_event démarré (handle={:#x}, instance={})", handle_raw, instance_id);
    let mut iterations: u64 = 0;
    loop {
        if stop_flag.load(Ordering::Relaxed) {
            tracing::info!("[ReadyEvent] sortie sur stop_flag (jeu terminé avant signal) après {} itérations", iterations);
            break;
        }
        if ready_sent.load(Ordering::Relaxed) {
            tracing::info!("[ReadyEvent] sortie sur ready_sent (un autre canal a déjà signalé) après {} itérations", iterations);
            break;
        }
        let result = tokio::task::spawn_blocking(move || unsafe {
            WaitForSingleObject(handle_raw as *mut std::ffi::c_void, 500)
        }).await;
        iterations += 1;
        let code = match result {
            Ok(c) => c,
            Err(e) => {
                tracing::warn!("[ReadyEvent] spawn_blocking a paniqué : {}", e);
                continue;
            }
        };
        if code == WAIT_OBJECT_0 {
            tracing::info!("[ReadyEvent] WAIT_OBJECT_0 reçu après {} itérations — émission game_ready", iterations);
            if ready_sent.compare_exchange(false, true, Ordering::Relaxed, Ordering::Relaxed).is_ok() {
                crate::integrations::analytics::capture("launch_completed", serde_json::json!({
                    "instance_id": &instance_id,
                    "duration_ms": launch_start.elapsed().as_millis() as u64,
                }));
                let emit_result = app.emit("game_ready", serde_json::json!({ "instance_id": &instance_id }));
                tracing::info!("[ReadyEvent] app.emit(game_ready) résultat : {:?}", emit_result);
            } else {
                tracing::info!("[ReadyEvent] WAIT_OBJECT_0 reçu mais ready_sent déjà pris par un autre canal");
            }
            break;
        } else if code != 258 /* WAIT_TIMEOUT */ {
            tracing::warn!("[ReadyEvent] WaitForSingleObject code inattendu : {} (ni WAIT_OBJECT_0 ni WAIT_TIMEOUT)", code);
        }
    }
    unsafe { CloseHandle(handle_raw as *mut std::ffi::c_void); }
    tracing::info!("[ReadyEvent] wait_for_ready_event terminé, handle fermé");
}

#[cfg(not(target_os = "windows"))]
pub(super) async fn wait_for_ready_event(
    _handle_raw: usize,
    _stop_flag: Arc<AtomicBool>,
    _ready_sent: Arc<AtomicBool>,
    _app: tauri::AppHandle,
    _instance_id: String,
    _launch_start: std::time::Instant,
) {
}
