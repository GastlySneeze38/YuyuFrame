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

/// Chaque événement porte l'id de l'instance lancée : plusieurs instances
/// peuvent se lancer en parallèle, et l'accueil n'affiche que la progression
/// de celle sélectionnée.
pub(crate) fn set_progress(app: &tauri::AppHandle, instance_id: &str, current: u64, total: u64, message: &str) {
    let scaled = if total > 0 { current * DOWNLOAD_PHASE_PERCENT / total } else { 0 };
    let _ = app.emit("download_progress", DownloadProgress {
        instance_id: instance_id.to_string(),
        current: scaled,
        total: 100,
        message: message.to_string(),
    });
}

/// Plancher de progression d'UN lancement (voir `set_progress_monotonic`) —
/// porte aussi l'id de l'instance, pour que chaque émetteur publie sous le bon
/// id sans le recevoir en paramètre supplémentaire.
pub(crate) struct ProgressFloor {
    value: AtomicU64,
    instance_id: String,
}

impl ProgressFloor {
    pub(crate) fn new(instance_id: &str) -> Self {
        Self { value: AtomicU64::new(0), instance_id: instance_id.to_string() }
    }
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
pub(crate) fn set_progress_monotonic(app: &tauri::AppHandle, floor: &ProgressFloor, current: u64, total: u64, message: &str) {
    let prev = floor.value.load(Ordering::Relaxed);
    if current > prev {
        // Best-effort : si un autre appel concurrent a déjà avancé le plancher
        // plus loin entre le load et ici, on perd la course sans problème —
        // `displayed` ci-dessous relira la valeur qui a gagné de toute façon.
        let _ = floor.value.compare_exchange(prev, current, Ordering::Relaxed, Ordering::Relaxed);
    }
    let displayed = current.max(floor.value.load(Ordering::Relaxed));
    set_progress(app, &floor.instance_id, displayed, total, message);
}

/// Lit une ligne en octets et la décode en UTF-8 TOLÉRANT — `None` en fin de
/// flux (ou sur erreur d'E/S réelle).
///
/// `read_line` exige de l'UTF-8 valide et renvoie une erreur sinon ; avec
/// `unwrap_or(0)`, cette erreur passait pour une fin de flux et la lecture
/// s'arrêtait DÉFINITIVEMENT. Or la JVM écrit dans le codage de la plateforme
/// (Cp1252 sous Windows) : le premier accent de l'agent (« Démarrage… »,
/// juste après la ligne VERSION) coupait stdout, et stderr était muet depuis
/// juillet 2026 — aucune erreur de l'agent ni de la JVM n'atteignait la
/// console (constaté le 2026-09-15). Un octet invalide devient maintenant un
/// caractère de remplacement, et la lecture continue.
pub(super) async fn read_line_lossy<R>(reader: &mut R, buf: &mut Vec<u8>) -> Option<(usize, String)>
where
    R: tokio::io::AsyncBufRead + Unpin,
{
    buf.clear();
    match reader.read_until(b'\n', buf).await {
        Ok(0) | Err(_) => None,
        Ok(n) => Some((n, String::from_utf8_lossy(buf).trim_end().to_string())),
    }
}

/// Lignes du jeu en attente de traitement, par flux (stdout ou stderr).
const GAME_OUTPUT_QUEUE: usize = 10_000;

/// Relaie un flux de sortie du jeu en séparant LECTURE et TRAITEMENT
/// (2026-09-15).
///
/// Avant, chaque ligne était affichée (événement console, terminal du
/// launcher, `yuyuframe.log`) avant de lire la suivante. Le terminal du
/// launcher est une écriture bloquante : pendant une rafale (spam
/// d'exceptions sur un serveur), le tube stdout de la JVM se remplissait, et
/// `System.out.println` — synchronisé — bloquait le fil de rendu du jeu, qui
/// gelait en attendant le launcher.
///
/// Ici, la tâche de lecture ne fait que vider le tube dans une file bornée ;
/// `inspect` y tourne sur chaque ligne et doit rester instantané. Le
/// traitement lent (`handle`) consomme la file dans sa propre tâche. File
/// pleine : la ligne est abandonnée plutôt que de bloquer le jeu, et le nombre
/// de lignes perdues est signalé à `handle` dès que la file se libère.
pub(super) fn spawn_game_output_pump<R, I, H>(mut reader: R, mut inspect: I, mut handle: H)
where
    R: tokio::io::AsyncBufRead + Unpin + Send + 'static,
    I: FnMut(&str) + Send + 'static,
    H: FnMut(String) + Send + 'static,
{
    let (tx, mut rx) = tokio::sync::mpsc::channel::<String>(GAME_OUTPUT_QUEUE);
    tokio::spawn(async move {
        let mut buf = Vec::new();
        let mut dropped: u64 = 0;
        while let Some((_, line)) = read_line_lossy(&mut reader, &mut buf).await {
            inspect(&line);
            if dropped > 0 {
                let notice = format!("[YuyuFrame] {dropped} ligne(s) du jeu ignorée(s) : console saturée");
                if tx.try_send(notice).is_ok() {
                    dropped = 0;
                }
            }
            match tx.try_send(line) {
                Ok(()) => {}
                Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => dropped += 1,
                Err(tokio::sync::mpsc::error::TrySendError::Closed(_)) => break,
            }
        }
    });
    tokio::spawn(async move {
        while let Some(line) = rx.recv().await {
            handle(line);
        }
    });
}

/// Masque les jetons d'authentification d'une ligne avant affichage ou
/// journalisation (2026-09-15) :
/// - `--accessToken <jeton>` — dans la commande de lancement journalisée
///   (`[MC launch]`), écrite en clair dans `yuyuframe.log` à chaque lancement ;
/// - `token:<jeton>:<uuid>` — la ligne « (Session ID is token:…) » que
///   Minecraft écrit lui-même au démarrage. L'UUID, public, est conservé.
///
/// Le jeton d'accès Microsoft permet de se connecter aux serveurs sous le
/// compte du joueur : il ne doit apparaître ni dans la console, qu'on copie
/// pour demander de l'aide, ni dans un fichier sur le disque.
pub(super) fn redact_secrets(line: &str) -> String {
    const MASK: &str = "***";
    let mut out = String::with_capacity(line.len());
    let mut rest = line;
    loop {
        let flag = rest.find("--accessToken ").map(|i| (i, "--accessToken ", " "));
        let session = rest.find("token:").map(|i| (i, "token:", ": )"));
        let next = match (flag, session) {
            (Some(a), Some(b)) => Some(if a.0 <= b.0 { a } else { b }),
            (a, b) => a.or(b),
        };
        let Some((start, prefix, stops)) = next else {
            out.push_str(rest);
            return out;
        };
        let value_start = start + prefix.len();
        let value_len = rest[value_start..]
            .find(|c: char| stops.contains(c) || c.is_whitespace())
            .unwrap_or(rest.len() - value_start);
        out.push_str(&rest[..value_start]);
        if value_len > 0 {
            out.push_str(MASK);
        }
        rest = &rest[value_start + value_len..];
    }
}

/// Émet un game_log vers la fenêtre console dédiée à cette instance.
/// Fallback sur broadcast global si la fenêtre n'existe plus.
pub(super) fn log_to_console(app: &tauri::AppHandle, console_label: &str, line: &str, level: &str) {
    let console = app.get_webview_window(console_label);
    // Minecraft écrit des milliers de lignes par partie. Quand ni la console
    // ni la fenêtre principale ne sont là pour les recevoir, chacune coûtait
    // un masquage des secrets, une sérialisation JSON et une diffusion
    // d'événement — un travail continu, pour personne. C'est le gros du poids
    // d'une partie launcher fermé.
    if console.is_none() && !crate::state::window_open() {
        return;
    }
    let short_id = console_label.strip_prefix("mc-console-").unwrap_or(console_label);
    let line = redact_secrets(line);
    let payload = serde_json::json!({ "line": line, "level": level, "instance_id": short_id });
    match console {
        Some(win) => {
            let _ = win.emit("game_log", &payload);
        }
        None => {
            let _ = app.emit("game_log", &payload);
        }
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
                        let mut buf = Vec::new();
                        while let Some((n, line)) = read_line_lossy(&mut reader, &mut buf).await {
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
                    }
                }
            } else if len < pos {
                // Fichier vidé : depuis le 2026-09-15, l'agent REPART DE ZÉRO à
                // chaque lancement (voir LauncherLog.toFile). Tout ce qui suit la
                // troncature appartient à CE lancement : relire depuis 0, sinon
                // les lignes écrites entre la troncature et ce tick seraient
                // sautées.
                pos = 0;
            }
        }
        tokio::time::sleep(std::time::Duration::from_millis(200)).await;
    }
}

#[cfg(test)]
mod tests {
    use super::redact_secrets;

    /// Ligne réelle écrite par Minecraft : le jeton disparaît, l'UUID reste.
    #[test]
    fn masque_le_jeton_de_session_minecraft() {
        let line = "[19:32:44] [Client thread/INFO]: (Session ID is token:eyJraWQ.eyJ4dWlk.BJXQ-Qc_Sm:8ac3a8cea6304fe89c1c5c0c88b1962e)";
        assert_eq!(
            redact_secrets(line),
            "[19:32:44] [Client thread/INFO]: (Session ID is token:***:8ac3a8cea6304fe89c1c5c0c88b1962e)"
        );
    }

    /// Commande de lancement : seul l'argument de --accessToken est masqué.
    #[test]
    fn masque_l_argument_access_token() {
        let line = "java -Xmx4g Main --username Ghast --accessToken eyJhbGc.abc-def_123 --userType msa";
        assert_eq!(
            redact_secrets(line),
            "java -Xmx4g Main --username Ghast --accessToken *** --userType msa"
        );
    }

    #[test]
    fn laisse_intacte_une_ligne_sans_jeton() {
        let line = "[AGENT] [LauncherAgent] Profil de version résolu : 1_8_9";
        assert_eq!(redact_secrets(line), line);
    }

    /// Masquer deux fois ne change plus rien (la console repasse une ligne déjà masquée).
    #[test]
    fn masquer_deux_fois_est_sans_effet() {
        let once = redact_secrets("(Session ID is token:abc:uuid) --accessToken xyz");
        assert_eq!(redact_secrets(&once), once);
    }
}
