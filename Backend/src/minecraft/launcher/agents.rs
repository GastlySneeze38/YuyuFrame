use anyhow::{anyhow, Result};
use std::path::{Path, PathBuf};

use crate::minecraft::p2p;
use crate::state::MinecraftSession;
use super::agent_deploy::{launcher_agent_dir, launcher_agent_libs_dir};
use super::progress::{log_to_console, ProgressFloor};

/// Sortie commune à `setup_p2p` et `setup_launcher_agent` : arguments
/// `-javaagent:...` à passer à la JVM et entrées de classpath associées
/// (ASM/JNA...). Vide par défaut (P2P désactivé, ou agent indisponible).
/// Version de Java minimale de launcher-agent.jar — celle de son `--release`
/// dans `Launcher-Agent/build.bat`. Les deux doivent bouger ensemble.
const LAUNCHER_AGENT_MIN_JAVA: u32 = 25;

#[derive(Default)]
pub(super) struct AgentSetup {
    pub(super) jvm_args: Vec<String>,
    pub(super) extra_classpath: Vec<String>,
}

/// Démarre le signaling P2P, télécharge les mappings Yarn et prépare le
/// javaagent p2p-agent. Échoue (au lieu de dégrader silencieusement) si
/// mixin.jar/p2p-agent.jar sont absents : contrairement à LauncherAgent, le
/// P2P a été explicitement demandé par l'utilisateur pour ce lancement.
pub(super) async fn setup_p2p(
    version_id: &str,
    session: &MinecraftSession,
    natives_dir: &Path,
    loader: Option<&str>,
    client: &reqwest::Client,
    app: &tauri::AppHandle,
    console_label: &str,
    progress_floor: &ProgressFloor,
) -> Result<AgentSetup> {
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
    let yarn_path = p2p::ensure_yarn_mappings(version_id, client, app, progress_floor).await?;

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
    let is_fabric = matches!(loader, Some("fabric") | Some("quilt"));
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
    log_to_console(app, console_label, &format!("[P2P] Code de session : {}", peer_id), "out");
    // mixin.jar DOIT être listé AVANT p2p-agent.jar : MixinAgent.premain() capture
    // l'Instrumentation que MixinBootstrap.init() utilisera ensuite.
    let mixin_arg = format!("-javaagent:{}", mixin_jar.display());
    let agent_arg = format!(
        "-javaagent:{}=peerId={},name={},server=ws://127.0.0.1:{},yarn={}",
        agent_jar.display(), peer_id, session.username, p2p::SIGNALING_PORT,
        yarn_path.display(),
    );

    log_to_console(app, console_label, &format!("[P2P] Mixin    : {}", mixin_arg), "out");
    log_to_console(app, console_label, &format!("[P2P] Agent    : {}", agent_arg), "out");
    log_to_console(app, console_label, &format!("[P2P] Yarn     : {}", yarn_path.display()), "out");

    Ok(AgentSetup { jvm_args: vec![mixin_arg, agent_arg], extra_classpath: extra_cp })
}

/// Résout le chemin des mappings SRG (Forge/NeoForge) à passer à l'agent via
/// `srg=...` — pendant exact de `yarn=...`.
///
/// Le lanceur est la bonne place pour ça : c'est lui qui a installé le loader,
/// qui connaît `version_id` avec certitude et qui possède le dossier
/// `.minecraft`. Côté Java, l'agent ne peut que FOUILLER le disque (plusieurs
/// racines candidates, récursion en profondeur) — moins fiable et
/// non déterministe. Sa recherche reste en place mais comme dernier recours,
/// pour un lancement manuel `-javaagent:` hors launcher.
///
/// Renvoie `None` sans bruit quand SRG n'a pas lieu d'être : loader non
/// Forge/NeoForge, ou version non obfusquée (≥ 26.1 — tous les loaders y
/// voient les mêmes vrais noms, voir `is_unobfuscated_version`).
///
/// Best-effort assumé : les installeurs Forge déposent MCPConfig dans
/// `libraries/de/oceanlabs/mcp/mcp_config/`, mais NeoForge a sa propre chaîne
/// de mappings et pourrait n'y rien mettre. Un `None` n'est jamais bloquant —
/// l'agent journalise alors une erreur explicite nommant l'argument à passer
/// à la main (voir `IsolatedBootstrap.loadSrgMappings`).
fn resolve_srg_mappings(version_id: &str, loader: Option<&str>) -> Option<PathBuf> {
    if !matches!(loader, Some("forge") | Some("neoforge")) {
        return None;
    }
    if p2p::is_unobfuscated_version(version_id) {
        return None;
    }

    // Le dossier de version porte un suffixe d'horodatage propre à la build
    // MCPConfig (ex: "1.20.1-20230612.114412") — d'où la recherche par préfixe
    // plutôt qu'un chemin construit en dur.
    let root = super::orchestrator::minecraft_dir()
        .join("libraries/de/oceanlabs/mcp/mcp_config");
    let entries = std::fs::read_dir(&root).ok()?;

    for entry in entries.flatten() {
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if !(name == version_id || name.starts_with(&format!("{}-", version_id))) {
            continue;
        }
        let inner = match std::fs::read_dir(entry.path()) {
            Ok(it) => it,
            Err(_) => continue,
        };
        for f in inner.flatten() {
            let fname = f.file_name();
            let fname = fname.to_string_lossy();
            if fname.ends_with(".zip") && fname.contains("mcp_config") {
                return Some(f.path());
            }
        }
    }
    None
}

/// Prépare le javaagent LauncherAgent (resource packs Modrinth in-game, voir
/// docs/LauncherAgent/index.md) — totalement indépendant du p2p-agent, actif
/// que P2P soit activé ou non. Contrairement à `setup_p2p`, ne fait jamais
/// échouer le lancement : un agent/mapping manquant désactive juste la
/// fonctionnalité (best-effort, loggé), jamais bloquant pour jouer.
pub(super) async fn setup_launcher_agent(
    version_id: &str,
    loader: Option<&str>,
    java_major: u32,
    client: &reqwest::Client,
    app: &tauri::AppHandle,
    console_label: &str,
    progress_floor: &ProgressFloor,
    ready_event_name: Option<&str>,
) -> AgentSetup {
    // launcher-agent.jar est compilé en --release 25 (build.bat) : sur une JVM
    // plus ancienne, son Premain-Class lève UnsupportedClassVersionError et la
    // JVM refuse de démarrer (« processing of -javaagent failed ») — le jeu ne
    // se lancerait plus du tout. On lance donc sans agent plutôt que de planter.
    if java_major < LAUNCHER_AGENT_MIN_JAVA {
        log_to_console(app, console_label, &format!(
            "[LauncherAgent] désactivé : MC {} tourne en Java {}, l'agent exige Java {}",
            version_id, java_major, LAUNCHER_AGENT_MIN_JAVA
        ), "out");
        return AgentSetup::default();
    }
    let libs_dir = launcher_agent_libs_dir();
    let mixin_jar    = libs_dir.join("mixin.jar");
    let agent_jar    = launcher_agent_dir().join("launcher-agent.jar");
    let asm_jar          = libs_dir.join("asm-9.5.jar");
    let asm_tree_jar     = libs_dir.join("asm-tree-9.5.jar");
    let asm_util_jar     = libs_dir.join("asm-util-9.5.jar");
    let asm_analysis_jar = libs_dir.join("asm-analysis-9.5.jar");
    let asm_commons_jar  = libs_dir.join("asm-commons-9.5.jar");
    // MixinExtras (ROADMAP-agent.md Phase 3) — même traitement que ASM :
    // Fabric Loader 0.15.0+ embarque déjà sa propre copie (mixinextras-fabric),
    // donc même risque "duplicate classes" que pour ASM si on ajoute la nôtre
    // en plus sur le classpath du jeu sous Fabric. Contrairement à Mixin
    // lui-même (jamais partageable, voir l'isolation par classloader côté
    // Java), MixinExtras n'a pas ce problème de singleton partagé — le skip
    // simple suffit ici.
    let mixinextras_jar = libs_dir.join("mixinextras.jar");
    // JNA (MumbleLinkBridge, ReadyEventSignal côté agent) —
    // pas de conflit "duplicate classes" façon ASM/Fabric, donc ajoutée au
    // classpath dans tous les cas (vanilla ET Fabric), pas seulement !is_fabric.
    let jna_jar          = libs_dir.join("jna.jar");
    let jna_platform_jar = libs_dir.join("jna-platform.jar");

    if !agent_jar.exists() {
        tracing::warn!(
            "[LauncherAgent] launcher-agent.jar manquant dans {} — resource packs in-game désactivés",
            launcher_agent_dir().display()
        );
        return AgentSetup::default();
    }
    if !mixin_jar.exists() {
        tracing::warn!(
            "[LauncherAgent] mixin.jar manquant dans {} — resource packs in-game désactivés",
            launcher_agent_dir().display()
        );
        return AgentSetup::default();
    }

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
        log_to_console(app, console_label, &format!(
            "[LauncherAgent] MC {} non obfusqué (schéma ≥26.1, voir FabricMC/fabric-loom#1585) — mappings Yarn ignorées",
            version_id), "out");
        Ok(None)
    } else {
        p2p::ensure_yarn_mappings(version_id, client, app, progress_floor).await.map(Some)
    };

    let yarn_path_opt = match yarn_result {
        Ok(opt) => opt,
        Err(e) => {
            tracing::warn!("[LauncherAgent] mappings Yarn indisponibles ({}) — resource packs in-game désactivés", e);
            return AgentSetup::default();
        }
    };

    // Même contrainte que pour le p2p-agent : ne pas ajouter notre copie
    // d'ASM si Fabric en apporte déjà une (conflit "duplicate ASM classes"
    // sinon — voir docs/LauncherAgent/index.md). CheckClassAdapter
    // (asm-util) est requis dès le bootstrap Mixin.
    let is_fabric = matches!(loader, Some("fabric") | Some("quilt"));
    let mut extra_cp: Vec<String> = Vec::new();
    if !is_fabric {
        for jar in [&asm_jar, &asm_tree_jar, &asm_util_jar, &asm_analysis_jar, &asm_commons_jar, &mixinextras_jar] {
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
    // loader=... (P0-5, voir audit launcher) : le Rust connaît le loader
    // avec certitude (choisi par l'utilisateur), l'agent n'a plus besoin de
    // le redeviner par Class.forName côté Java — évite l'incohérence
    // Rust/Java sur la classification de Quilt (voir `is_fabric` ci-dessus).
    //
    // yarn=... OMIS quand yarn_path_opt est None (26.1+) — AgentConfig
    // (Java) laisse alors yarnPath=null, MappingsRegistry reste en
    // scheme OFFICIAL sans jamais tenter de charger de jar Yarn.
    let mixin_arg = format!("-javaagent:{}", mixin_jar.display());
    let loader_name = loader.unwrap_or("vanilla");
    let mut agent_arg = match &yarn_path_opt {
        Some(yarn_path) => format!(
            "-javaagent:{}=yarn={},version={},loader={}",
            agent_jar.display(), yarn_path.display(), version_id, loader_name,
        ),
        None => format!(
            "-javaagent:{}=version={},loader={}",
            agent_jar.display(), version_id, loader_name,
        ),
    };
    // srg=... — mappings Forge/NeoForge, pendant de yarn=... : c'est le
    // launcher qui résout le chemin (il a installé le loader et possède le
    // dossier .minecraft), pas l'agent qui fouille le disque. Omis hors
    // Forge/NeoForge et sur une version non obfusquée — voir
    // resolve_srg_mappings.
    match resolve_srg_mappings(version_id, loader) {
        Some(srg_path) => {
            log_to_console(app, console_label, &format!(
                "[LauncherAgent] SRG : {}", srg_path.display()), "out");
            agent_arg.push_str(&format!(",srg={}", srg_path.display()));
        }
        None if matches!(loader, Some("forge") | Some("neoforge"))
            && !p2p::is_unobfuscated_version(version_id) => {
            // Le seul cas qui mérite un avertissement : on ATTENDAIT des
            // mappings SRG et on n'en a pas trouvé. L'agent retombera sur sa
            // propre recherche, puis échouera avec un message explicite.
            tracing::warn!(
                "[LauncherAgent] mappings SRG introuvables pour MC {} ({}) — \
                 l'agent tentera une auto-détection, sinon aucun nom du jeu ne sera résolu",
                version_id, loader.unwrap_or("?"));
        }
        None => {}
    }

    // readyEvent=... — Named Event Win32 (voir ready_event.rs) signalé par le
    // hook TitleScreen.init() de l'agent (ReadyEventSignal.java, JNA) une fois
    // le menu principal atteint. Absent (None) sur non-Windows ou si la
    // création de l'event a échoué — le fallback stdout+fichier suffit alors.
    if let Some(name) = ready_event_name {
        agent_arg.push_str(&format!(",readyEvent={}", name));
    }
    log_to_console(app, console_label, &format!("[LauncherAgent] Mixin : {}", mixin_arg), "out");
    log_to_console(app, console_label, &format!("[LauncherAgent] Agent : {}", agent_arg), "out");

    AgentSetup { jvm_args: vec![mixin_arg, agent_arg], extra_classpath: extra_cp }
}
