use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, Mutex};
use tauri::{Emitter, Manager};
use tokio::io::{AsyncBufReadExt, AsyncSeekExt, BufReader};
use tokio::sync::Notify;

use crate::state::DownloadProgress;

/// Les téléchargements (assets/libs/Java/loader/deps/P2P) ne représentent
/// qu'une partie du temps perçu par l'utilisateur — le démarrage de la JVM
/// et le chargement interne de Minecraft (splash, ressources) jusqu'au menu
/// principal (voir `game_ready`, signalé par le LauncherAgent) prennent
/// souvent tout autant de temps, mais n'avaient AUCUNE représentation dans la
/// barre (figée au dernier pourcentage de téléchargement pendant tout ce
/// temps). On compresse donc tous les téléchargements dans 0-60%, laissant
/// 60-100% au frontend pour animer la phase de lancement (voir Home.tsx,
/// qui mesure la durée réelle d'un lancement à l'autre pour l'estimer).
pub(crate) const DOWNLOAD_PHASE_PERCENT: u64 = 60;

pub(crate) fn set_progress(app: &tauri::AppHandle, current: u64, total: u64, message: &str) {
    let scaled = if total > 0 { current * DOWNLOAD_PHASE_PERCENT / total } else { 0 };
    let _ = app.emit("download_progress", DownloadProgress {
        current: scaled,
        total: 100,
        message: message.to_string(),
    });
}

/// Variante monotone — tout le lancement (téléchargements vanilla, Java,
/// setup Fabric/Forge, résolution de dépendances de mods, mappings Yarn P2P)
/// publie sa progression via CE `floor` partagé plutôt que directement via
/// `set_progress`. Deux besoins couverts :
///
/// 1. Les libs (thread principal) et les assets (tâche de fond, voir
///    `assets_task` dans orchestrator.rs) avancent en parallèle et publiaient
///    chacun leur propre pourcentage dans des plages disjointes en théorie
///    (libs 20-50, assets 50-90) mais qui s'entremêlaient dans le temps —
///    un appel plus tardif pouvait arriver avec une valeur PLUS PETITE que
///    le dernier déjà affiché (ex: assets atteint 80% avant que libs n'ait
///    fini de rattraper 35%), faisant visiblement reculer la barre. Pareil
///    pour Java/Fabric/Forge/P2P, qui utilisent chacun leurs propres plages
///    fixes sans coordination avec les assets toujours en cours derrière.
/// 2. Certains appels (résolution de dépendances de mods, mappings Yarn P2P)
///    ne portent qu'un MESSAGE informatif sans pourcentage réel à eux —
///    plutôt que de les ignorer (perdant le message) ou de les laisser
///    écraser le pourcentage avec 0, on les affiche au pourcentage déjà
///    atteint (clampé), jamais en dessous.
pub(crate) fn set_progress_monotonic(app: &tauri::AppHandle, floor: &AtomicU64, current: u64, total: u64, message: &str) {
    let prev = floor.load(Ordering::Relaxed);
    if current > prev {
        // Best-effort : si un autre appel concurrent a déjà avancé le plancher
        // plus loin entre le load et ici, on perd la course sans problème —
        // `displayed` ci-dessous relira la valeur qui a gagné de toute façon.
        let _ = floor.compare_exchange(prev, current, Ordering::Relaxed, Ordering::Relaxed);
    }
    let displayed = current.max(floor.load(Ordering::Relaxed));
    set_progress(app, displayed, total, message);
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

/// Relit en continu `logs/latest.log` (log4j2, où Minecraft écrit ses vrais
/// logs — pas sur stdout) et republie chaque nouvelle ligne vers la fenêtre
/// console. Démarre à la fin du fichier existant pour ignorer les logs d'une
/// session précédente ; repart de 0 si le fichier est recréé plus petit
/// (nouveau lancement). S'arrête quand `stop_flag` passe à `true`.
pub(super) async fn tail_log_file(log_path: PathBuf, stop_flag: Arc<AtomicBool>, app: tauri::AppHandle, console_label: String) {
    let current_end = tokio::fs::metadata(&log_path).await.map(|m| m.len()).unwrap_or(0);
    let mut pos: u64 = current_end;
    let mut last_len: u64 = current_end;

    loop {
        if let Ok(metadata) = tokio::fs::metadata(&log_path).await {
            let len = metadata.len();
            if len < last_len {
                // Fichier recréé au démarrage — recommencer depuis le début
                pos = 0;
            }
            last_len = len;

            if len > pos {
                if let Ok(mut file) = tokio::fs::File::open(&log_path).await {
                    if file.seek(std::io::SeekFrom::Start(pos)).await.is_ok() {
                        let mut reader = BufReader::new(file);
                        let mut line = String::new();
                        loop {
                            line.clear();
                            match reader.read_line(&mut line).await {
                                Ok(n) if n > 0 => {
                                    pos += n as u64;
                                    let trimmed = line.trim_end().to_string();
                                    if !trimmed.is_empty() {
                                        log_to_console(&app, &console_label, &trimmed, "out");
                                    }
                                }
                                // Ok(0) = EOF, Err(_) = erreur de lecture — dans les deux cas
                                // on arrête cette passe et on retente au prochain tick.
                                _ => break,
                            }
                        }
                    }
                }
            }
        }

        if stop_flag.load(Ordering::Relaxed) {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
}

/// Filet de sécurité pour `game_ready` (marqueur `[YUYUFRAME_READY]` du
/// LauncherAgent, voir TitleScreenMixin*.java) — la capture du stdout du
/// process Java par Rust s'est déjà avérée pas fiable à 100% par le passé
/// (voir le commentaire sur `LauncherLog` côté Java, qui documente le même
/// problème et pour ça écrit systématiquement aussi dans ce fichier). En
/// plus de la lecture stdout (voir orchestrator.rs), on relit donc aussi
/// `launcher-agent.log` — quel que soit le canal qui voit le marqueur en
/// premier, il déclenche `ready_sent` (partagé) et émet l'événement.
///
/// Fichier GLOBAL (pas par instance) : deux lancements simultanés y écrivent
/// tous les deux. On démarre la lecture à la fin du fichier existant pour
/// ignorer tout marqueur d'un lancement précédent — dans le cas rare de deux
/// lancements concurrents, la fenêtre de confusion possible est minime et
/// sans conséquence grave (au pire un `game_ready` reçu un peu tôt/tard pour
/// la mauvaise instance, jamais un lancement qui ne se termine jamais).
pub(super) async fn watch_agent_log_for_ready(
    log_path: PathBuf,
    stop_flag: Arc<AtomicBool>,
    ready_sent: Arc<AtomicBool>,
    app: tauri::AppHandle,
    instance_id: String,
    launch_start: std::time::Instant,
) {
    let mut pos: u64 = tokio::fs::metadata(&log_path).await.map(|m| m.len()).unwrap_or(0);

    loop {
        if ready_sent.load(Ordering::Relaxed) || stop_flag.load(Ordering::Relaxed) {
            break;
        }
        if let Ok(metadata) = tokio::fs::metadata(&log_path).await {
            let len = metadata.len();
            if len > pos {
                if let Ok(mut file) = tokio::fs::File::open(&log_path).await {
                    if file.seek(std::io::SeekFrom::Start(pos)).await.is_ok() {
                        let mut reader = BufReader::new(file);
                        let mut line = String::new();
                        loop {
                            line.clear();
                            match reader.read_line(&mut line).await {
                                Ok(n) if n > 0 => {
                                    pos += n as u64;
                                    if line.contains("[YUYUFRAME_READY]")
                                        && ready_sent.compare_exchange(false, true, Ordering::Relaxed, Ordering::Relaxed).is_ok()
                                    {
                                        crate::integrations::analytics::capture("launch_completed", serde_json::json!({
                                            "instance_id": &instance_id,
                                            "duration_ms": launch_start.elapsed().as_millis() as u64,
                                        }));
                                        let _ = app.emit("game_ready", serde_json::json!({ "instance_id": &instance_id }));
                                        return;
                                    }
                                }
                                _ => break,
                            }
                        }
                    }
                }
            } else if len < pos {
                // Fichier recréé/tronqué — repart de la fin actuelle plutôt que
                // de re-scanner depuis 0 (pas notre log à nous, pas de besoin
                // de tout relire).
                pos = len;
            }
        }
        tokio::time::sleep(std::time::Duration::from_millis(200)).await;
    }
}
