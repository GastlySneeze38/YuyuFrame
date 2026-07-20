use anyhow::{anyhow, Result};
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::io::{AsyncBufReadExt, AsyncSeekExt, BufReader};
use tokio::sync::{watch, Semaphore};
use tokio::task::JoinSet;

use crate::state::{MinecraftSession, SharedState};
use crate::minecraft::loaders::{deps, fabric, forge};
use crate::minecraft::p2p;
use crate::minecraft::versions::{fetch_version_list, AssetIndexFile, VersionDetails};
use super::agent_deploy::{launcher_agent_dir, launcher_agent_libs_dir};
use super::classpath::{artifact_path, dedup_classpath, download_file, extract_natives, should_download_library};
use super::java::{detect_java_major_version, ensure_java};
use super::jvm_args::{build_game_args, build_jvm_args, ensure_gpu_preference, extract_tweak_class_args};
#[cfg(target_os = "windows")]
use super::jvm_args::{timeBeginPeriod, timeEndPeriod};
use super::progress::{log_to_console, set_progress};

/// Message d'erreur sentinelle renvoyé par `download_and_launch` quand l'arrêt
/// vient d'une annulation demandée par l'utilisateur (`cancel_launch`), pour
/// que `launch_game` émette `launch_cancelled` plutôt que `launch_error`.
pub const LAUNCH_CANCELLED_MSG: &str = "Lancement annulé";

fn cancelled(cancel: &watch::Receiver<bool>) -> bool {
    *cancel.borrow()
}

pub fn minecraft_dir() -> PathBuf {
    dirs::data_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("YuyuFrame")
        .join(".minecraft")
}

/// `loader` — "vanilla" | "fabric" | "forge" (None treated as vanilla)
/// `game_dir` — instance directory (saves, mods, configs); shared assets stay in minecraft_dir()
/// `console_label` — label de la fenêtre console à cibler pour les game_log
///
/// Retourne, en cas de succès, la liste des avertissements non-bloquants
/// survenus pendant le lancement (lib Fabric/Forge ou dépendance de mod
/// manquante — le jeu a quand même démarré, mais pourrait planter ou
/// manquer une fonctionnalité). Vide si tout s'est bien passé.
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
    cancel: watch::Receiver<bool>,
) -> Result<Vec<String>> {
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

    // ── Assets en tâche de fond — démarre immédiatement, indépendant des libs ──
    // Les assets et les libs sont totalement indépendants : on les télécharge en parallèle.
    let assets_task = {
        let client = client.clone();
        let app = app.clone();
        let assets_dir = assets_dir.clone();
        let asset_index = details.asset_index.clone();
        let cancel = cancel.clone();
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
                    set_progress(
                        &app,
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
        set_progress(&app, 10, 100, "Téléchargement du client Minecraft...");
        download_file(&client, &details.downloads.client.url, &client_jar).await?;
    }

    set_progress(&app, 20, 100, "Téléchargement des bibliothèques...");
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
            set_progress(&app, 20 + libs_done * 30 / total_libs.max(1), 100,
                &format!("Bibliothèques {}/{}", libs_done, total_libs));
        }
    }

    for np in natives_to_extract {
        if let Err(e) = extract_natives(&np, &natives_dir).await {
            tracing::warn!("Extraction natives échouée pour {} : {}", np.display(), e);
        }
    }

    // ── Loader-specific setup ────────────────────────────────────────────────
    // Les assets continuent de se télécharger en arrière-plan pendant ce temps.

    // Pas de javaVersion dans le manifest = ancienne version MC → Java 8 requis (LaunchWrapper)
    let required_java = details.java_version.as_ref().map(|j| j.major_version).unwrap_or(8);
    let java_component = details.java_version.as_ref()
        .map(|j| j.component.as_str())
        .unwrap_or("jre-legacy"); // composant Mojang pour Java 8
    let java = ensure_java(java_component, required_java, &mc_dir, &client, &app).await?;
    ensure_gpu_preference(&java).await;
    let java_major = detect_java_major_version(&java).await.unwrap_or(17);
    let console_label = console_label.to_string();
    log_to_console(&app, &console_label, &format!("MC {} requiert Java {} — utilise : {}", version_id, required_java, java), "out");

    let loader_setup = match loader.unwrap_or("vanilla") {
        "fabric" => setup_fabric(version_id, &libraries_dir, &game_dir.join("mods"), &app, avoid_beta).await?,
        "forge" => setup_forge(version_id, &mc_dir, &libraries_dir, &java, &app).await?,
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
    // Démarre le signaling, télécharge les mappings Mojang et prépare les javaagents.
    // Le JAR original Minecraft est utilisé directement — le remapping est assuré à
    // l'exécution par MappingsRegistry (IRemapper Mixin) et les appels de réflexion.
    let (effective_client_jar, p2p_jvm_args, p2p_extra_cp) = if p2p {
        p2p::start_signaling(app.clone());

        // Copier rust_core.dll dans natives_dir pour que -Djava.library.path le trouve
        let dll_name = if cfg!(target_os = "windows") { "rust_core.dll" } else { "librust_core.so" };
        let dll_src = p2p::p2p_dir().join(dll_name);
        if dll_src.exists() {
            tokio::fs::copy(&dll_src, natives_dir.join(dll_name)).await.ok();
        } else {
            tracing::warn!("[P2P] {} manquant dans {} — JNI désactivé", dll_name, p2p::p2p_dir().display());
        }

        // Télécharger les mappings Yarn (Fabric mergedv2)
        let yarn_path = p2p::ensure_yarn_mappings(version_id, &client, &app).await?;

        let mixin_jar     = p2p::p2p_dir().join("mixin.jar");
        let agent_jar     = p2p::p2p_dir().join("p2p-agent.jar");
        let asm_jar          = p2p::p2p_dir().join("asm-9.5.jar");
        let asm_tree_jar     = p2p::p2p_dir().join("asm-tree-9.5.jar");
        let asm_util_jar     = p2p::p2p_dir().join("asm-util-9.5.jar");
        let asm_analysis_jar = p2p::p2p_dir().join("asm-analysis-9.5.jar");
        let asm_commons_jar  = p2p::p2p_dir().join("asm-commons-9.5.jar");

        if !mixin_jar.exists() {
            return Err(anyhow!(
                "mixin.jar manquant dans {}\n  Copier P2P-Server/p2p-agent/lib/mixin.jar vers ce dossier",
                p2p::p2p_dir().display()
            ));
        }
        if !agent_jar.exists() {
            return Err(anyhow!(
                "p2p-agent.jar manquant dans {}\n  Compiler P2P-Server/p2p-agent/ et copier le JAR vers ce dossier",
                p2p::p2p_dir().display()
            ));
        }

        // MixinAgent (dans mixin.jar) déclare registerTargetClass(String, ClassNode).
        // Le JVM résout toutes les signatures déclarées au chargement de la classe, donc
        // org.objectweb.asm.tree.ClassNode doit être sur le classpath AVANT que mixin.jar
        // soit traité comme javaagent. On ajoute asm-9.5.jar et asm-tree-9.5.jar au -cp —
        // SAUF en mode Fabric, où Fabric Loader apporte déjà sa propre copie d'ASM
        // (généralement plus récente) sur le classpath. En ajouter une deuxième fait
        // échouer Fabric Knot au démarrage : "duplicate ASM classes found on classpath"
        // (vu en jeu avec LauncherAgent — voir docs/LauncherAgent/index.md). p2p-agent.jar
        // ne doit plus jamais embarquer ASM lui-même (cf. build.bat) pour ne pas être,
        // à lui seul, une troisième source du même conflit.
        // asm-util/-analysis/-commons sont nécessaires en plus de asm/-tree : Mixin
        // (DefaultExtensions.create()) référence org.objectweb.asm.util.CheckClassAdapter
        // dès le bootstrap, même sans activer les checks — son absence provoque un
        // NoClassDefFoundError immédiat (vu en 1.20.4 vanilla, pas en Fabric où Fabric
        // Loader apporte déjà sa copie complète d'ASM).
        let is_fabric = matches!(loader, Some("fabric"));
        let mut extra_cp: Vec<String> = Vec::new();
        if !is_fabric {
            for jar in [&asm_jar, &asm_tree_jar, &asm_util_jar, &asm_analysis_jar, &asm_commons_jar] {
                if jar.exists() {
                    extra_cp.push(jar.to_string_lossy().to_string());
                } else {
                    tracing::warn!("[P2P] {} manquant — peut causer NoClassDefFoundError au démarrage", jar.display());
                }
            }
        }

        // Le peerId = PeerId libp2p base58 : c'est le code que l'hôte partage en jeu.
        let peer_id = p2p::start_libp2p().await.unwrap_or_else(|e| {
            tracing::warn!("[P2P] libp2p non démarré: {} — fallback UUID", e);
            uuid::Uuid::new_v4().to_string()
        });
        log_to_console(&app, &console_label, &format!("[P2P] Code de session : {}", peer_id), "out");
        // mixin.jar DOIT être listé AVANT p2p-agent.jar : MixinAgent.premain() capture
        // l'Instrumentation que MixinBootstrap.init() utilisera ensuite.
        let mixin_arg = format!("-javaagent:{}", mixin_jar.display());
        let agent_arg = format!(
            "-javaagent:{}=peerId={},name={},server=ws://127.0.0.1:{},yarn={}",
            agent_jar.display(), peer_id, session.username, p2p::SIGNALING_PORT,
            yarn_path.display(),
        );

        log_to_console(&app, &console_label, &format!("[P2P] Mixin    : {}", mixin_arg), "out");
        log_to_console(&app, &console_label, &format!("[P2P] Agent    : {}", agent_arg), "out");
        log_to_console(&app, &console_label, &format!("[P2P] Yarn     : {}", yarn_path.display()), "out");
        (client_jar.clone(), vec![mixin_arg, agent_arg], extra_cp)
    } else {
        (client_jar.clone(), vec![], vec![])
    };

    // ── LauncherAgent setup ──────────────────────────────────────────────────
    // Resource packs Modrinth in-game (voir docs/LauncherAgent/index.md). Agent
    // totalement indépendant du p2p-agent — actif que P2P soit activé ou non.
    let (launcher_agent_jvm_args, launcher_agent_extra_cp): (Vec<String>, Vec<String>) = {
        let libs_dir = launcher_agent_libs_dir();
        let mixin_jar    = libs_dir.join("mixin.jar");
        let agent_jar    = launcher_agent_dir().join("launcher-agent.jar");
        let asm_jar          = libs_dir.join("asm-9.5.jar");
        let asm_tree_jar     = libs_dir.join("asm-tree-9.5.jar");
        let asm_util_jar     = libs_dir.join("asm-util-9.5.jar");
        let asm_analysis_jar = libs_dir.join("asm-analysis-9.5.jar");
        let asm_commons_jar  = libs_dir.join("asm-commons-9.5.jar");
        // JNA (module optimodule "Fenêtre sans bordure", BorderlessWindowNative) —
        // pas de conflit "duplicate classes" façon ASM/Fabric, donc ajoutée au
        // classpath dans tous les cas (vanilla ET Fabric), pas seulement !is_fabric.
        let jna_jar          = libs_dir.join("jna.jar");
        let jna_platform_jar = libs_dir.join("jna-platform.jar");

        if !agent_jar.exists() {
            tracing::warn!(
                "[LauncherAgent] launcher-agent.jar manquant dans {} — resource packs in-game désactivés",
                launcher_agent_dir().display()
            );
            (vec![], vec![])
        } else if !mixin_jar.exists() {
            tracing::warn!(
                "[LauncherAgent] mixin.jar manquant dans {} — resource packs in-game désactivés",
                launcher_agent_dir().display()
            );
            (vec![], vec![])
        } else {
            // Mêmes mappings Yarn que le p2p-agent (cache partagé dans
            // AppData/YuyuFrame/p2p/cache/) — ensure_yarn_mappings() court-circuite
            // si déjà téléchargées pour cette version, donc pas de double téléchargement.
            // Sans ce remapper, Mixin tente de résoudre les noms Yarn littéralement
            // et échoue (ClassNotFoundException) puisque le JAR client est obfusqué.
            //
            // EXCEPTION (bracket 26.1.2, voir mixin/client/v26_1 côté Java) : à
            // partir de la ligne 26.1.x, Mojang ne publie PLUS AUCUNE mapping —
            // ni officielle, ni Yarn, ni intermediary Fabric (is_unobfuscated_version,
            // voir sa javadoc pour les sources) — le jeu contient déjà ses VRAIS
            // noms. Appeler ensure_yarn_mappings pour une telle version échouerait
            // TOUJOURS (rien à télécharger nulle part) ; on saute directement à
            // "pas de chemin Yarn", exactement l'état déjà validé pour un
            // lancement vanilla classique sans Fabric (MappingsRegistry reste en
            // scheme OFFICIAL, YarnMappings jamais chargé — voir AgentConfig/
            // MappingsRegistry côté Java).
            let yarn_result: Result<Option<PathBuf>> = if p2p::is_unobfuscated_version(version_id) {
                log_to_console(&app, &console_label, &format!(
                    "[LauncherAgent] MC {} non obfusqué (schéma ≥26.1, voir FabricMC/fabric-loom#1585) — mappings Yarn ignorées",
                    version_id), "out");
                Ok(None)
            } else {
                p2p::ensure_yarn_mappings(version_id, &client, &app).await.map(Some)
            };

            match yarn_result {
                Ok(yarn_path_opt) => {
                    // Même contrainte que pour le p2p-agent : ne pas ajouter notre
                    // copie d'ASM si Fabric en apporte déjà une (conflit "duplicate
                    // ASM classes" sinon — voir docs/LauncherAgent/index.md).
                    // Même contrainte que pour le p2p-agent (voir plus haut) :
                    // CheckClassAdapter (asm-util) est requis dès le bootstrap Mixin.
                    let is_fabric = matches!(loader, Some("fabric"));
                    let mut extra_cp: Vec<String> = Vec::new();
                    if !is_fabric {
                        for jar in [&asm_jar, &asm_tree_jar, &asm_util_jar, &asm_analysis_jar, &asm_commons_jar] {
                            if jar.exists() {
                                extra_cp.push(jar.to_string_lossy().to_string());
                            } else {
                                tracing::warn!("[LauncherAgent] {} manquant — peut causer NoClassDefFoundError au démarrage", jar.display());
                            }
                        }
                    }
                    for jar in [&jna_jar, &jna_platform_jar] {
                        if jar.exists() {
                            extra_cp.push(jar.to_string_lossy().to_string());
                        } else {
                            tracing::warn!("[LauncherAgent] {} manquant — module \"Fenêtre sans bordure\" indisponible", jar.display());
                        }
                    }

                    // mixin.jar DOIT être listé AVANT launcher-agent.jar — même contrainte
                    // que pour le p2p-agent (MixinAgent.premain() capture l'Instrumentation).
                    //
                    // version=... explicite ici : -Dminecraft.version n'est posé QUE par
                    // Fabric, jamais par un lancement vanilla (Mojang passe la version en
                    // argument de jeu "--version", pas en system property) — sans ce
                    // paramètre, MinecraftVersionDetector.detect() renvoie "unknown" sur
                    // vanilla, et LauncherAgent charge par erreur la config Mixin 1.21+
                    // contre un jeu 1.8.9 (mismatch fatal). Rust connaît déjà version_id
                    // avec certitude, pas besoin de deviner côté agent.
                    //
                    // yarn=... OMIS quand yarn_path_opt est None (26.1+) — AgentConfig
                    // (Java) laisse alors yarnPath=null, MappingsRegistry reste en
                    // scheme OFFICIAL sans jamais tenter de charger de jar Yarn.
                    let mixin_arg = format!("-javaagent:{}", mixin_jar.display());
                    let agent_arg = match &yarn_path_opt {
                        Some(yarn_path) => format!(
                            "-javaagent:{}=yarn={},version={}",
                            agent_jar.display(), yarn_path.display(), version_id,
                        ),
                        None => format!(
                            "-javaagent:{}=version={}",
                            agent_jar.display(), version_id,
                        ),
                    };
                    log_to_console(&app, &console_label, &format!("[LauncherAgent] Mixin : {}", mixin_arg), "out");
                    log_to_console(&app, &console_label, &format!("[LauncherAgent] Agent : {}", agent_arg), "out");
                    (vec![mixin_arg, agent_arg], extra_cp)
                }
                Err(e) => {
                    tracing::warn!("[LauncherAgent] mappings Yarn indisponibles ({}) — resource packs in-game désactivés", e);
                    (vec![], vec![])
                }
            }
        }
    };

    // ── Attente des assets ────────────────────────────────────────────────────
    // Libs + loader terminés, on attend que les assets finissent avant de lancer.
    assets_task.await.map_err(|e| anyhow!("Tâche assets : {}", e))??;

    let mc_game_dir: PathBuf = game_dir.to_path_buf();

    // ── Launch ───────────────────────────────────────────────────────────────

    set_progress(&app, 95, 100, "Lancement de Minecraft...");

    let classpath_sep = if cfg!(target_os = "windows") { ";" } else { ":" };

    let mut full_classpath: Vec<String> = extra_classpath;
    full_classpath.extend(p2p_extra_cp); // asm-9.5.jar + asm-tree-9.5.jar avant tout le reste
    full_classpath.extend(launcher_agent_extra_cp); // idem pour le LauncherAgent
    full_classpath.extend(classpath);
    full_classpath.push(effective_client_jar.to_string_lossy().to_string());
    let classpath_str = dedup_classpath(full_classpath).join(classpath_sep);

    let mut args = build_jvm_args(ram_mb, &natives_dir, java_major);
    let gc_msg = if java_major >= 21 {
        format!("Java {} détecté — ZGC Generational activé", java_major)
    } else {
        format!("Java {} détecté — G1GC client activé", java_major)
    };
    log_to_console(&app, &console_label, &gc_msg, "out");
    args.extend(extra_jvm_args);
    args.extend(p2p_jvm_args);
    args.extend(launcher_agent_jvm_args);
    args.extend(["-cp".to_string(), classpath_str, main_class]);
    args.extend(build_game_args(&details, session, &mc_game_dir, &assets_dir, version_id));
    args.extend(extra_game_args);
    // Laisser à la fenêtre console le temps d'enregistrer ses listeners JS
    // avant de spawner Java — évite de perdre les premières lignes de log
    // quand tout est en cache et que le lancement est quasi-instantané.
    tokio::time::sleep(std::time::Duration::from_millis(1500)).await;

    // Passe le timer Windows à 1ms (défaut : 15ms) pour réduire le jitter de scheduling
    #[cfg(target_os = "windows")]
    unsafe { timeBeginPeriod(1); }

    let log_path = mc_game_dir.join("logs").join("latest.log");
    let stop_flag = Arc::new(AtomicBool::new(false));
    let stop_flag_tailer = stop_flag.clone();
    let app_log = app.clone();
    let label_log = console_label.clone();

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

    if let Some(mut reader) = stdout {
        tokio::spawn(async move {
            let mut line = String::new();
            while reader.read_line(&mut line).await.unwrap_or(0) > 0 {
                let trimmed = line.trim_end().to_string();
                log_to_console(&app_out, &label_out, &trimmed, "out");
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
    let log_tailer = tokio::spawn(async move {
        // Démarrer à la fin du fichier existant pour ignorer les logs des sessions précédentes.
        // Quand Minecraft recrée le fichier (taille < last_len), on repart de 0.
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
                                    Ok(0) => break,
                                    Ok(n) => {
                                        pos += n as u64;
                                        let trimmed = line.trim_end().to_string();
                                        if !trimmed.is_empty() {
                                            log_to_console(&app_log, &label_log, &trimmed, "out");
                                        }
                                    }
                                    Err(_) => break,
                                }
                            }
                        }
                    }
                }
            }

            if stop_flag_tailer.load(Ordering::Relaxed) {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        }
    });

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

// ── Loader setup helpers ──────────────────────────────────────────────────────

/// Extrait les chaînes d'un tableau JSON brut (`arguments.jvm`/`arguments.game`
/// des profils Fabric/Forge), en ignorant silencieusement les entrées non-string
/// (objets conditionnels de règles OS, non gérés ici).
fn json_str_array(values: &[serde_json::Value]) -> Vec<String> {
    values.iter().filter_map(|v| v.as_str().map(|s| s.to_string())).collect()
}

#[derive(Default)]
struct LoaderSetup {
    main_class: String,
    classpath: Vec<String>,
    extra_game_args: Vec<String>,
    extra_jvm_args: Vec<String>,
    /// Libs ou dépendances qui n'ont pas pu être installées — le jeu démarre
    /// quand même (best-effort), mais l'utilisateur doit en être informé
    /// plutôt que de découvrir un crash Java sans indice.
    warnings: Vec<String>,
}

async fn setup_fabric(
    mc_version: &str,
    libraries_dir: &Path,
    mods_dir: &Path,
    app: &tauri::AppHandle,
    avoid_beta: bool,
) -> Result<LoaderSetup> {
    set_progress(app, 72, 100, "Téléchargement Fabric Loader...");

    let profile = fabric::get_latest_profile(mc_version).await?;
    let mut warnings = Vec::new();

    if let Err(e) = fabric::ensure_fabric_api(mc_version, mods_dir).await {
        tracing::warn!("Fabric API auto-install échoué: {}", e);
        warnings.push("Fabric API n'a pas pu être installée automatiquement".to_string());
    }

    set_progress(app, 74, 100, "Résolution des dépendances des mods...");
    match deps::resolve_and_install_deps(mc_version, "fabric", mods_dir, app, avoid_beta).await {
        Ok(failed) => warnings.extend(
            failed.into_iter().map(|id| format!("Dépendance de mod manquante : {id}")),
        ),
        Err(e) => {
            tracing::warn!("Résolution des dépendances échouée: {}", e);
            warnings.push(format!("Résolution des dépendances de mods échouée : {e}"));
        }
    }

    let mut fabric_cp = Vec::new();
    let total = profile.libraries.len();
    for (i, lib) in profile.libraries.iter().enumerate() {
        match fabric::download_library(lib, libraries_dir).await {
            Some(path) => fabric_cp.push(path.to_string_lossy().to_string()),
            None => warnings.push(format!("Bibliothèque Fabric manquante : {}", lib.name)),
        }
        if i % 5 == 0 {
            set_progress(app, 72 + i as u64 * 20 / total.max(1) as u64, 100, &format!("Fabric libs {}/{}", i + 1, total));
        }
    }

    let extra_jvm: Vec<String> = profile
        .arguments
        .as_ref()
        .and_then(|a| a.jvm.as_ref())
        .map(|jvm| json_str_array(jvm))
        .unwrap_or_default();

    Ok(LoaderSetup {
        main_class: profile.main_class,
        classpath: fabric_cp,
        extra_game_args: vec![],
        extra_jvm_args: extra_jvm,
        warnings,
    })
}

async fn setup_forge(
    mc_version: &str,
    mc_dir: &Path,
    libraries_dir: &Path,
    java: &str,
    app: &tauri::AppHandle,
) -> Result<LoaderSetup> {
    set_progress(app, 70, 100, "Recherche de la version Forge...");

    let forge_ver = forge::fetch_latest_version(mc_version).await?;
    tracing::info!("Forge {} pour MC {}", forge_ver, mc_version);

    let version_id = match forge::find_installed(mc_version, &forge_ver, mc_dir) {
        Some(id) => id,
        None => {
            set_progress(app, 72, 100, "Téléchargement de l'installeur Forge...");
            forge::install(mc_version, &forge_ver, mc_dir, libraries_dir, java).await?
        }
    };

    let forge_json = forge::read_version_json(&version_id, mc_dir)?;
    let mut forge_cp = Vec::new();
    let mut warnings = Vec::new();

    if let Some(libs) = &forge_json.libraries {
        let total = libs.len();
        for (i, lib) in libs.iter().enumerate() {
            match forge::download_library(lib, libraries_dir).await {
                Some(path) => forge_cp.push(path.to_string_lossy().to_string()),
                None => warnings.push(format!("Bibliothèque Forge manquante : {}", lib.name)),
            }
            if i % 5 == 0 {
                set_progress(app, 80 + i as u64 * 12 / total.max(1) as u64, 100, &format!("Forge libs {}/{}", i + 1, total));
            }
        }
    }

    let extra_game: Vec<String> = forge_json
        .arguments.as_ref().and_then(|a| a.game.as_ref())
        .map(|g| json_str_array(g))
        .unwrap_or_else(|| {
            // Legacy Forge (pré-1.13) : pas de bloc "arguments", seulement une
            // "minecraftArguments" à plat dont on extrait juste --tweakClass
            // (le reste duplique les args vanilla déjà posés ailleurs).
            forge_json.minecraft_arguments.as_deref().map(extract_tweak_class_args).unwrap_or_default()
        });

    let mut extra_jvm: Vec<String> = forge_json
        .arguments.as_ref().and_then(|a| a.jvm.as_ref())
        .map(|j| json_str_array(j))
        .unwrap_or_default();

    // Forge legacy (pré-1.13, pas de bloc "arguments") : FML revérifie par défaut
    // à chaque lancement le certificat/checksum du client jar et compare son hash
    // de patch attendu — utile une seule fois à l'installation, inutile ensuite
    // puisque le jar ne change plus. On désactive ces deux contrôles redondants.
    // Volontairement on NE touche PAS à -Xverify:none : ça désactiverait la
    // vérification bytecode pour TOUTES les classes chargées, y compris les mods
    // que l'utilisateur ajoute lui-même (non auditables par nous).
    if forge_json.arguments.is_none() {
        extra_jvm.push("-Dfml.ignoreInvalidMinecraftCertificates=true".into());
        extra_jvm.push("-Dfml.ignorePatchDiscrepancies=true".into());
    }

    Ok(LoaderSetup {
        main_class: forge_json.main_class,
        classpath: forge_cp,
        extra_game_args: extra_game,
        extra_jvm_args: extra_jvm,
        warnings,
    })
}
