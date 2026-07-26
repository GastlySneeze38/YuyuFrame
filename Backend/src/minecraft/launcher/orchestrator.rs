use anyhow::{anyhow, Result};
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use tauri::Emitter;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::sync::{watch, Semaphore};
use tokio::task::JoinSet;

use crate::state::{MinecraftSession, SharedState};
use crate::minecraft::versions::{fetch_version_list, AssetIndexFile, VersionDetails};
use super::agent_deploy::launcher_agent_dir;
use super::agents::{setup_launcher_agent, setup_p2p, AgentSetup};
use super::classpath::{artifact_path, dedup_classpath, download_file, extract_natives, should_download_library};
use super::java::ensure_java;
use super::jvm_args::{build_game_args, build_jvm_args, ensure_gpu_preference, extract_mojang_jvm_args};
#[cfg(target_os = "windows")]
use super::jvm_args::{timeBeginPeriod, timeEndPeriod};
use super::loader_setup::{setup_fabric, setup_forge, setup_neoforge, setup_quilt, LoaderSetup};
use super::progress::{log_to_console, set_progress, set_progress_monotonic, tail_log_file, watch_agent_log_for_ready};
use super::ready_event::{create_ready_event, wait_for_ready_event};
use super::servers::build_server_connect_args;

/// Message d'erreur sentinelle renvoyé par `download_and_launch` quand l'arrêt
/// vient d'une annulation demandée par l'utilisateur (`cancel_launch`), pour
/// que `launch_game` émette `launch_cancelled` plutôt que `launch_error`.
pub const LAUNCH_CANCELLED_MSG: &str = "Lancement annulé";

fn cancelled(cancel: &watch::Receiver<bool>) -> bool {
    *cancel.borrow()
}

pub fn minecraft_dir() -> PathBuf {
    crate::paths::root().join(".minecraft")
}

/// `loader` — "vanilla" | "fabric" | "forge" (None treated as vanilla)
/// `game_dir` — instance directory (saves, mods, configs); shared assets stay in minecraft_dir()
/// `console_label` — label de la fenêtre console à cibler pour les game_log
///
/// Retourne, en cas de succès, la liste des avertissements non-bloquants
/// survenus pendant le lancement (lib Fabric/Forge ou dépendance de mod
/// manquante — le jeu a quand même démarré, mais pourrait planter ou
/// manquer une fonctionnalité). Vide si tout s'est bien passé.
///
/// Orchestre les étapes dans l'ordre : détails de version → assets (en tâche
/// de fond) → client jar + libs vanilla → natives → Java → setup loader
/// (Fabric/Forge, voir `loader_setup.rs`) → setup agents JVM (P2P/
/// LauncherAgent, voir `agents.rs`) → attente des assets → spawn + supervision
/// du process Java (voir `progress::tail_log_file`).
#[allow(clippy::too_many_arguments)]
pub async fn download_and_launch(
    version_id: &str,
    loader: Option<&str>,
    session: &MinecraftSession,
    ram_mb: u32,
    game_dir: &std::path::Path,
    app: tauri::AppHandle,
    state: SharedState,
    p2p: bool,
    avoid_beta: bool,
    console_label: &str,
    instance_id: &str,
    connect_server: Option<&str>,
    cancel: watch::Receiver<bool>,
) -> Result<Vec<String>> {
    let launch_start = std::time::Instant::now();
    crate::integrations::analytics::capture("download_started", serde_json::json!({
        "instance_id": instance_id,
        "mc_version": version_id,
        "loader": loader.unwrap_or("vanilla"),
    }));

    let mc_dir = minecraft_dir();
    tokio::fs::create_dir_all(game_dir).await?;
    let versions_dir = mc_dir.join("versions").join(version_id);
    let libraries_dir = mc_dir.join("libraries");
    let assets_dir = mc_dir.join("assets");
    let natives_dir = versions_dir.join("natives");

    for dir in [&versions_dir, &libraries_dir, &assets_dir, &natives_dir] {
        tokio::fs::create_dir_all(dir).await?;
    }

    // ── Vanilla download ──────────────────────────────────────────────────────

    // Le JSON de détails d'une version donnée ne change jamais une fois publié
    // par Mojang — s'il est déjà en cache local (lancements précédents), on
    // évite complètement le manifest + la requête détails par réseau, qui
    // se refaisaient sans condition à CHAQUE lancement même quand rien n'avait
    // changé depuis la fois précédente.
    let version_json_cache = versions_dir.join(version_id).join(format!("{}.json", version_id));
    let details: VersionDetails = if let Ok(text) = tokio::fs::read_to_string(&version_json_cache).await {
        set_progress(&app, 5, 100, "Détails de version (cache local)...");
        serde_json::from_str(&text)?
    } else {
        set_progress(&app, 0, 100, "Récupération du manifest...");
        let versions = fetch_version_list().await?;
        let version_info = versions
            .iter()
            .find(|v| v.id == version_id)
            .ok_or_else(|| anyhow!("Version {} introuvable", version_id))?;

        set_progress(&app, 5, 100, "Récupération des détails...");
        let raw = reqwest::Client::new().get(&version_info.url).send().await?.text().await?;
        if let Some(parent) = version_json_cache.parent() {
            let _ = tokio::fs::create_dir_all(parent).await;
        }
        let _ = tokio::fs::write(&version_json_cache, &raw).await;
        serde_json::from_str(&raw)?
    };

    // Client HTTP partagé — pool de connexions réutilisées pour tous les téléchargements
    let client = Arc::new(reqwest::Client::builder()
        .pool_max_idle_per_host(32)
        .build()?);

    // Plafond partagé entre les deux émetteurs concurrents (libs + assets, voir
    // set_progress_monotonic) — un seul par lancement, jamais partagé entre
    // deux lancements différents.
    let progress_floor = Arc::new(AtomicU64::new(0));

    // ── Assets en tâche de fond — démarre immédiatement, indépendant des libs ──
    // Les assets et les libs sont totalement indépendants : on les télécharge en parallèle.
    let assets_task = {
        let client = client.clone();
        let app = app.clone();
        let assets_dir = assets_dir.clone();
        let asset_index = details.asset_index.clone();
        let cancel = cancel.clone();
        let progress_floor = progress_floor.clone();
        tokio::spawn(async move {
            let asset_index_path = assets_dir
                .join("indexes")
                .join(format!("{}.json", asset_index.id));
            tokio::fs::create_dir_all(asset_index_path.parent().unwrap()).await?;
            if !asset_index_path.exists() {
                download_file(&client, &asset_index.url, &asset_index_path).await?;
            }
            // On vient de s'assurer que le fichier est sur disque (déjà présent ou
            // juste téléchargé) — on le relit localement au lieu de re-télécharger
            // le même contenu par réseau à chaque lancement (l'asset index d'une
            // version donnée ne change jamais une fois publié).
            let index_text = tokio::fs::read_to_string(&asset_index_path).await?;
            let index_file: AssetIndexFile = serde_json::from_str(&index_text)?;
            let objects_dir = assets_dir.join("objects");
            let total_assets = index_file.objects.len() as u64;

            let sem = Arc::new(Semaphore::new(32));
            let mut tasks: JoinSet<Result<()>> = JoinSet::new();

            for obj in index_file.objects.into_values() {
                let sem = sem.clone();
                let client = client.clone();
                let objects_dir = objects_dir.clone();
                tasks.spawn(async move {
                    let prefix = &obj.hash[..2];
                    let obj_dir = objects_dir.join(prefix);
                    let obj_path = obj_dir.join(&obj.hash);
                    if !obj_path.exists() {
                        let _permit = sem.acquire().await.unwrap();
                        tokio::fs::create_dir_all(&obj_dir).await?;
                        let url = format!(
                            "https://resources.download.minecraft.net/{}/{}",
                            prefix, obj.hash
                        );
                        download_file(&client, &url, &obj_path).await.ok();
                    }
                    Ok::<(), anyhow::Error>(())
                });
            }

            let mut done = 0u64;
            while let Some(r) = tasks.join_next().await {
                r??;
                if cancelled(&cancel) {
                    return Err(anyhow!(LAUNCH_CANCELLED_MSG));
                }
                done += 1;
                if done.is_multiple_of(200) || done == total_assets {
                    set_progress_monotonic(
                        &app,
                        &progress_floor,
                        50 + done * 40 / total_assets.max(1),
                        100,
                        &format!("Assets {}/{}", done, total_assets),
                    );
                }
            }
            anyhow::Ok(())
        })
    };

    let client_jar = versions_dir.join(format!("{}.jar", version_id));
    if !client_jar.exists() {
        set_progress_monotonic(&app, &progress_floor, 10, 100, "Téléchargement du client Minecraft...");
        download_file(&client, &details.downloads.client.url, &client_jar).await?;
    }

    set_progress_monotonic(&app, &progress_floor, 20, 100, "Téléchargement des bibliothèques...");
    let total_libs = details.libraries.len() as u64;

    // 16 téléchargements simultanés — équilibre bande passante / charge serveur Mojang
    let lib_sem = Arc::new(Semaphore::new(16));
    let mut lib_tasks: JoinSet<Result<(Option<String>, Vec<PathBuf>)>> = JoinSet::new();

    for lib in details.libraries.iter() {
        if !should_download_library(lib) {
            continue;
        }
        let Some(ref dl) = lib.downloads else { continue };

        let lib_name = lib.name.clone();
        let artifact = dl.artifact.clone();
        let classifiers = dl.classifiers.clone();
        let natives_map = lib.natives.clone();
        let sem = lib_sem.clone();
        let client = client.clone();
        let libraries_dir = libraries_dir.clone();

        lib_tasks.spawn(async move {
            let _permit = sem.acquire().await.unwrap();
            let mut cp_entry = None;
            let mut native_paths = Vec::new();

            if let Some(art) = artifact {
                let lib_path = artifact_path(&libraries_dir, &art, &lib_name);
                if let Some(parent) = lib_path.parent() {
                    tokio::fs::create_dir_all(parent).await?;
                }
                if !lib_path.exists() {
                    download_file(&client, &art.url, &lib_path).await?;
                }
                // Les JARs natifs (":natives-xxx") sont extraits vers natives_dir
                // ET ajoutés au classpath : LWJGL 3.x utilise le classpath comme fallback
                // si l'extraction échoue ou si java.library.path n'est pas trouvé.
                cp_entry = Some(lib_path.to_string_lossy().to_string());
                if lib_name.contains(":natives-") {
                    native_paths.push(lib_path);
                }
            }

            if let (Some(natives_map), Some(classifiers)) = (natives_map, classifiers) {
                let os_key = if cfg!(target_os = "windows") { "windows" }
                    else if cfg!(target_os = "macos") { "osx" }
                    else { "linux" };
                if let Some(classifier_key) = natives_map.get(os_key) {
                    let key = classifier_key.replace(
                        "${arch}",
                        if cfg!(target_pointer_width = "64") { "64" } else { "32" },
                    );
                    if let Some(native_art) = classifiers.get(&key) {
                        let native_path = artifact_path(
                            &libraries_dir,
                            native_art,
                            &format!("{}:{}", lib_name, key),
                        );
                        if let Some(parent) = native_path.parent() {
                            tokio::fs::create_dir_all(parent).await?;
                        }
                        if !native_path.exists() {
                            download_file(&client, &native_art.url, &native_path).await?;
                        }
                        native_paths.push(native_path);
                    }
                }
            }

            Ok((cp_entry, native_paths))
        });
    }

    let mut classpath = Vec::new();
    let mut natives_to_extract = Vec::new();
    let mut libs_done = 0u64;
    while let Some(result) = lib_tasks.join_next().await {
        let (cp_entry, native_paths) = result??;
        if cancelled(&cancel) {
            assets_task.abort();
            return Err(anyhow!(LAUNCH_CANCELLED_MSG));
        }
        if let Some(cp) = cp_entry { classpath.push(cp); }
        natives_to_extract.extend(native_paths);
        libs_done += 1;
        if libs_done.is_multiple_of(10) || libs_done == total_libs {
            set_progress_monotonic(&app, &progress_floor, 20 + libs_done * 30 / total_libs.max(1), 100,
                &format!("Bibliothèques {}/{}", libs_done, total_libs));
        }
    }

    let mut native_tasks: JoinSet<(PathBuf, Result<()>)> = JoinSet::new();
    for np in natives_to_extract {
        let natives_dir = natives_dir.clone();
        native_tasks.spawn(async move {
            let result = extract_natives(&np, &natives_dir).await;
            (np, result)
        });
    }
    while let Some(res) = native_tasks.join_next().await {
        match res {
            Ok((np, Err(e))) => tracing::warn!("Extraction natives échouée pour {} : {}", np.display(), e),
            Ok((_, Ok(()))) => {}
            Err(e) => tracing::warn!("Tâche extraction natives échouée : {}", e),
        }
    }

    // ── Loader-specific setup ────────────────────────────────────────────────
    // Les assets continuent de se télécharger en arrière-plan pendant ce temps.

    // Pas de javaVersion dans le manifest = ancienne version MC → Java 8 requis (LaunchWrapper)
    let required_java = details.java_version.as_ref().map(|j| j.major_version).unwrap_or(8);
    let java_component = details.java_version.as_ref()
        .map(|j| j.component.as_str())
        .unwrap_or("jre-legacy"); // composant Mojang pour Java 8
    let (java, java_major) = ensure_java(java_component, required_java, &mc_dir, &client, &app, &progress_floor).await?;
    ensure_gpu_preference(&java).await;
    let console_label = console_label.to_string();
    log_to_console(&app, &console_label, &format!("MC {} requiert Java {} — utilise : {}", version_id, required_java, java), "out");

    let loader_setup = match loader.unwrap_or("vanilla") {
        "fabric" => setup_fabric(version_id, &libraries_dir, &game_dir.join("mods"), &app, avoid_beta, &progress_floor).await?,
        "quilt" => setup_quilt(version_id, &libraries_dir, &game_dir.join("mods"), &app, avoid_beta, &progress_floor).await?,
        "forge" => setup_forge(version_id, &mc_dir, &libraries_dir, &java, &app, &progress_floor).await?,
        "neoforge" => setup_neoforge(version_id, &mc_dir, &libraries_dir, &java, &app, &progress_floor).await?,
        _ => LoaderSetup { main_class: details.main_class.clone(), ..Default::default() },
    };
    let (main_class, extra_classpath, extra_game_args, extra_jvm_args) = (
        loader_setup.main_class,
        loader_setup.classpath,
        loader_setup.extra_game_args,
        loader_setup.extra_jvm_args,
    );
    // Avertissements non-bloquants (lib Fabric/Forge ou dépendance de mod
    // manquante) — remontés à l'appelant même en cas de lancement réussi,
    // au lieu de rester silencieux dans les logs (voir commands/launch.rs,
    // événement `launch_warning`).
    let launch_warnings = loader_setup.warnings;

    // ── P2P setup ────────────────────────────────────────────────────────────
    // Le JAR original Minecraft est utilisé directement — le remapping est assuré
    // à l'exécution par MappingsRegistry (IRemapper Mixin) et les appels de
    // réflexion, pas besoin d'un JAR "effectif" différent ici.
    let p2p_setup = if p2p {
        setup_p2p(version_id, session, &natives_dir, loader, &client, &app, &console_label, &progress_floor).await?
    } else {
        AgentSetup::default()
    };
    let (p2p_jvm_args, p2p_extra_cp) = (p2p_setup.jvm_args, p2p_setup.extra_classpath);

    // ── LauncherAgent setup ──────────────────────────────────────────────────
    // Resource packs Modrinth in-game (voir docs/LauncherAgent/index.md). Agent
    // totalement indépendant du p2p-agent — actif que P2P soit activé ou non.
    //
    // Named Event Win32 (voir ready_event.rs) : canal principal pour
    // game_ready, en plus du fallback stdout+fichier déjà en place — créé ICI
    // (avant le spawn de la JVM) pour que son nom soit inclus dans l'argument
    // -javaagent, mais attendu seulement après le spawn (plus bas).
    let ready_event = create_ready_event(instance_id);
    let ready_event_name = ready_event.as_ref().map(|(name, _)| name.clone());
    let launcher_agent = setup_launcher_agent(version_id, loader, &client, &app, &console_label, &progress_floor, ready_event_name.as_deref()).await;
    let (launcher_agent_jvm_args, launcher_agent_extra_cp) = (launcher_agent.jvm_args, launcher_agent.extra_classpath);

    // ── Attente des assets ────────────────────────────────────────────────────
    // Libs + loader terminés, on attend que les assets finissent avant de lancer.
    assets_task.await.map_err(|e| anyhow!("Tâche assets : {}", e))??;

    let mc_game_dir: PathBuf = game_dir.to_path_buf();

    // ── Launch ───────────────────────────────────────────────────────────────

    // Valeur brute 100 (pas 95) : dernier point de la phase téléchargements,
    // rescale exactement à 60% affichés (voir DOWNLOAD_PHASE_PERCENT) — le
    // frontend reconnaît ce palier pile pour démarrer sa propre estimation de
    // la phase de lancement (60-100%, voir Home.tsx).
    set_progress_monotonic(&app, &progress_floor, 100, 100, "Lancement de Minecraft...");
    crate::integrations::analytics::capture("download_completed", serde_json::json!({
        "instance_id": instance_id,
        "mc_version": version_id,
    }));

    let classpath_sep = if cfg!(target_os = "windows") { ";" } else { ":" };

    let mut full_classpath: Vec<String> = extra_classpath;
    full_classpath.extend(p2p_extra_cp); // asm-9.5.jar + asm-tree-9.5.jar avant tout le reste
    full_classpath.extend(launcher_agent_extra_cp); // idem pour le LauncherAgent
    full_classpath.extend(classpath);
    full_classpath.push(client_jar.to_string_lossy().to_string());
    let classpath_str = dedup_classpath(full_classpath).join(classpath_sep);

    let mut args = build_jvm_args(ram_mb, &natives_dir, java_major);
    let gc_msg = if java_major >= 21 {
        format!("Java {} détecté — ZGC Generational activé", java_major)
    } else {
        format!("Java {} détecté — G1GC client activé", java_major)
    };
    log_to_console(&app, &console_label, &gc_msg, "out");
    // Correctifs OS spécifiques suggérés par Mojang (ex: -XstartOnFirstThread
    // obligatoire sur macOS) — en plus de notre tuning GC ci-dessus, jamais à
    // sa place. Voir extract_mojang_jvm_args pour ce qui est filtré/substitué.
    args.extend(extract_mojang_jvm_args(&details, &natives_dir));
    args.extend(extra_jvm_args);
    args.extend(p2p_jvm_args);
    args.extend(launcher_agent_jvm_args);
    args.extend(["-cp".to_string(), classpath_str, main_class]);
    args.extend(build_game_args(&details, session, &mc_game_dir, &assets_dir, version_id));
    args.extend(extra_game_args);
    if let Some(address) = connect_server {
        log_to_console(&app, &console_label, &format!("Connexion directe au serveur {}...", address), "out");
        args.extend(build_server_connect_args(version_id, address));
    }
    // NB : pas de sleep ici avant de spawner Java. La synchro avec la fenêtre
    // console (attendre que Console.tsx ait attaché son listener game_log)
    // est déjà faite bien plus tôt, dans commands/launch.rs, via
    // `console_ready.notified()` (voir register_console_waiter) — AVANT même
    // l'appel à download_and_launch. Un ancien sleep fixe de 1500ms vivait
    // ici en plus de ce mécanisme (vestige d'avant son introduction) et
    // ralentissait chaque lancement pour rien, y compris les lancements
    // 100% en cache où c'était la quasi-totalité du temps perçu.

    // Passe le timer Windows à 1ms (défaut : 15ms) pour réduire le jitter de scheduling
    #[cfg(target_os = "windows")]
    unsafe { timeBeginPeriod(1); }

    let log_path = mc_game_dir.join("logs").join("latest.log");
    let stop_flag = Arc::new(AtomicBool::new(false));
    let stop_flag_tailer = stop_flag.clone();
    let stop_flag_ready = stop_flag.clone();
    let stop_flag_event = stop_flag.clone();
    let app_log = app.clone();
    let label_log = console_label.clone();
    // Partagé entre les trois canaux de détection de game_ready (event +
    // stdout + fichier, voir plus bas) — le premier qui voit le signal gagne.
    let ready_sent = Arc::new(AtomicBool::new(false));
    let ready_sent_stdout = ready_sent.clone();
    let ready_sent_file = ready_sent.clone();
    let ready_sent_event = ready_sent.clone();

    let mut java_cmd = tokio::process::Command::new(&java);
    java_cmd
        .args(&args)
        .current_dir(&mc_game_dir)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .stdin(Stdio::null());
    // java.exe est une appli console Windows : sans console déjà attachée au
    // process parent (cas du build release, qui tourne en windows_subsystem
    // "windows"), Windows lui en alloue une nouvelle — d'où le terminal qui
    // s'ouvrait à côté du jeu uniquement en release (en dev, la console du
    // launcher déjà attachée était simplement héritée, donc invisible en plus).
    #[cfg(target_os = "windows")]
    {
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        java_cmd.creation_flags(CREATE_NO_WINDOW);
    }
    if cancelled(&cancel) {
        return Err(anyhow!(LAUNCH_CANCELLED_MSG));
    }

    tracing::info!("[MC launch] {} {}", java, args.join(" "));
    let mut child = java_cmd.spawn()?;

    let stdout = child.stdout.take().map(BufReader::new);
    let stderr = child.stderr.take().map(BufReader::new);

    let app_out = app.clone();
    let label_out = console_label.clone();
    let app_err = app.clone();
    let label_err = console_label.clone();
    let instance_id_out = instance_id.to_string();

    if let Some(mut reader) = stdout {
        tokio::spawn(async move {
            let mut line = String::new();
            // Signalé une seule fois par lancement — le hook TitleScreen.init()
            // du LauncherAgent (voir TitleScreenMixin*.java) se redéclenche à
            // chaque retour au menu principal pendant la session, pas juste au
            // premier chargement. `ready_sent` est partagé avec le filet de
            // sécurité côté fichier (watch_agent_log_for_ready) — le premier
            // des deux canaux qui voit le marqueur gagne.
            while reader.read_line(&mut line).await.unwrap_or(0) > 0 {
                let trimmed = line.trim_end().to_string();
                log_to_console(&app_out, &label_out, &trimmed, "out");
                if trimmed.contains("[YUYUFRAME_READY]")
                    && ready_sent_stdout.compare_exchange(false, true, Ordering::Relaxed, Ordering::Relaxed).is_ok()
                {
                    crate::integrations::analytics::capture("launch_completed", serde_json::json!({
                        "instance_id": &instance_id_out,
                        "duration_ms": launch_start.elapsed().as_millis() as u64,
                    }));
                    let _ = app_out.emit("game_ready", serde_json::json!({ "instance_id": &instance_id_out }));
                }
                // Persisté aussi dans yuyuframe.log (voir tracing_appender dans
                // main.rs) — la fenêtre console (webview) ne garde rien après
                // un crash/fermeture, ce qui rendait tout diagnostic après-coup
                // impossible sans que l'utilisateur ait déjà tout copié à temps.
                tracing::info!("[MC stdout] {}", trimmed);
                line.clear();
            }
        });
    }

    if let Some(mut reader) = stderr {
        tokio::spawn(async move {
            let mut line = String::new();
            while reader.read_line(&mut line).await.unwrap_or(0) > 0 {
                let trimmed = line.trim_end().to_string();
                log_to_console(&app_err, &label_err, &trimmed, "err");
                tracing::error!("[MC stderr] {}", trimmed);
                line.clear();
            }
        });
    }

    // Tailer logs/latest.log — Minecraft route ses logs via log4j2 vers ce fichier
    // plutôt que vers stdout, donc on lit le fichier directement.
    let log_tailer = tokio::spawn(tail_log_file(log_path, stop_flag_tailer, app_log, label_log));

    // Filet de sécurité game_ready (voir watch_agent_log_for_ready) — course
    // avec la détection stdout ci-dessus, `ready_sent` partagé garantit qu'un
    // seul des trois canaux émet l'événement.
    let agent_log_path = launcher_agent_dir().join("logs").join("launcher-agent.log");
    tokio::spawn(watch_agent_log_for_ready(agent_log_path, stop_flag_ready, ready_sent_file, app.clone(), instance_id.to_string(), launch_start));

    // Canal principal game_ready : Named Event Win32 (voir ready_event.rs) —
    // créé plus haut, avant le spawn, pour que son nom soit dans l'argument
    // -javaagent. `None` sur non-Windows ou si CreateEventW a échoué (le
    // fallback stdout+fichier ci-dessus suffit alors).
    match ready_event {
        Some((_, handle)) => {
            tracing::info!("[ReadyEvent] spawn de wait_for_ready_event (handle={:#x})", handle);
            tokio::spawn(wait_for_ready_event(handle, stop_flag_event, ready_sent_event, app.clone(), instance_id.to_string(), launch_start));
        }
        None => tracing::info!("[ReadyEvent] pas d'event créé — repli sur stdout/fichier uniquement pour ce lancement"),
    }

    // Clear progress — game is now running
    state.write().await.download_progress = None;

    let mut cancel_wait = cancel;
    let cancelled_while_running = tokio::select! {
        status = child.wait() => {
            tracing::info!("Minecraft terminé — code de sortie : {}", status?);
            false
        }
        _ = cancel_wait.changed() => {
            tracing::info!("Lancement annulé — arrêt de la JVM");
            let _ = child.kill().await;
            true
        }
    };

    // Arrêter le tailer et attendre qu'il finisse de vider les dernières lignes
    stop_flag.store(true, Ordering::Relaxed);
    let _ = tokio::time::timeout(std::time::Duration::from_secs(2), log_tailer).await;

    // Restaure la résolution du timer Windows
    #[cfg(target_os = "windows")]
    unsafe { timeEndPeriod(1); }

    if cancelled_while_running {
        return Err(anyhow!(LAUNCH_CANCELLED_MSG));
    }

    Ok(launch_warnings)
}
