use anyhow::{anyhow, Result};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::sync::Semaphore;
use tokio::task::JoinSet;

use crate::minecraft::versions::JavaVersionInfo;
use crate::process::hidden_command;
use super::classpath::download_verified;
use super::jvm_args::JvmVendor;
use super::progress::{set_progress_monotonic, ProgressFloor};

/// Runtime Java d'une version : `(composant Mojang, version majeure)`.
///
/// Par défaut, ce que déclare le manifeste de la version (`javaVersion`) ; sans
/// `javaVersion`, c'est une ancienne version → Java 8 (`jre-legacy`).
///
/// DÉROGATION 1.8.9 vanilla → Java 25 (`java-runtime-epsilon`, le runtime que
/// Mojang sert déjà pour la 26.1.2) : refonte 1.8.9 du client, voir
/// `docs/LauncherAgent/v1.8.9/README.md` § 3.1 (décision D7). Limitée au
/// loader vanilla : Forge 1.8.9 passe par LaunchWrapper, incompatible avec
/// Java 9+ (voir `ensure_java`), et Fabric/Quilt n'en sont pas concernés.
pub fn java_requirement(
    version_id: &str,
    loader: Option<&str>,
    declared: Option<&JavaVersionInfo>,
) -> (String, u32) {
    if super::legacy_lwjgl3::uses_legacy_lwjgl3(version_id, loader) {
        return ("java-runtime-epsilon".to_string(), 25);
    }
    match declared {
        Some(j) => (j.component.clone(), j.major_version),
        None => ("jre-legacy".to_string(), 8),
    }
}

/// Délai au-delà duquel on considère que ce `java` ne répondra pas.
///
/// Sans timeout, un exécutable qui se bloque — install corrompue, binaire sur
/// un lecteur réseau déconnecté, antivirus qui inspecte le process au premier
/// lancement — figeait TOUT le lancement, sans message ni moyen d'annuler.
/// Cinq secondes sont très larges pour un `java -version`, qui répond
/// normalement en moins de 200 ms.
const JAVA_VERSION_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);

/// `true` si cette JVM est une OpenJ9/Semeru — la SEULE famille dont les
/// drapeaux GC (`-Xgcpolicy:*`) sont incompatibles avec HotSpot.
///
/// BUG TROUVÉ (log utilisateur 2026-08-31) :
/// ```text
/// Java 25 (Eclipse OpenJ9) détecté, 2048 Mo alloués — -Xgcpolicy:gencon activé
/// Unrecognized option: -Xgcpolicy:gencon
/// Error: Could not create the Java Virtual Machine.
/// ```
/// Le vendeur servant à générer les drapeaux était celui **demandé**, jamais
/// celui **réellement obtenu**. À 2 Go le mode auto choisit OpenJ9 ;
/// `ensure_openj9` échouait (voir `download_adoptium` — mauvaise API
/// interrogée, 404 sur TOUTES les versions), on retombait proprement sur le
/// runtime Mojang — qui est du HotSpot — mais les drapeaux, eux, restaient
/// ceux d'OpenJ9. La JVM refusait de démarrer.
///
/// On interroge donc la JVM elle-même plutôt que le réglage : une
/// incompatibilité de famille devient structurellement impossible, y compris
/// avec un chemin personnalisé pointant sur une OpenJ9 alors que le réglage
/// dit « Temurin », ou l'inverse.
pub(super) async fn is_openj9(java: &str) -> bool {
    let Ok(Ok(out)) = tokio::time::timeout(
        JAVA_VERSION_TIMEOUT,
        hidden_command(java).arg("-version").output(),
    )
    .await
    else {
        return false;
    };
    let mut text = String::from_utf8_lossy(&out.stderr).into_owned();
    text.push_str(&String::from_utf8_lossy(&out.stdout));
    // La bannière annonce "Eclipse OpenJ9 VM" ou "IBM J9 VM" selon la build.
    let lower = text.to_ascii_lowercase();
    lower.contains("openj9") || lower.contains("j9 vm")
}

pub async fn detect_java_major_version(java: &str) -> Option<u32> {
    let out = tokio::time::timeout(
        JAVA_VERSION_TIMEOUT,
        hidden_command(java).arg("-version").output(),
    )
    .await
    .ok()?
    .ok()?;
    // `java -version` écrit sur stderr — mais pas TOUS les JVM : certaines
    // distributions et wrappers écrivent sur stdout, et `--version` (double
    // tiret, JDK 9+) y écrit toujours. On concatène les deux plutôt que de
    // parier : une détection qui échoue ici fait retomber l'appelant sur un
    // téléchargement complet du runtime, ou pire, sur « Java non détecté ».
    let mut text = String::from_utf8_lossy(&out.stderr).into_owned();
    text.push('\n');
    text.push_str(&String::from_utf8_lossy(&out.stdout));
    for line in text.lines() {
        if line.contains("version") {
            // Formats : `"21.0.2"` ou `"1.8.0_xxx"`
            if let (Some(s), Some(e)) = (line.find('"'), line.rfind('"')) {
                if s < e {
                    let ver = &line[s + 1..e];
                    let parts: Vec<&str> = ver.split('.').collect();
                    let major: u32 = parts[0].parse().ok()?;
                    return Some(if major == 1 {
                        parts.get(1)?.parse().ok()?
                    } else {
                        major
                    });
                }
            }
        }
    }
    None
}

const MOJANG_JAVA_MANIFEST: &str =
    "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

/// Retourne un exécutable Java prêt à l'emploi pour `required_major`, ainsi
/// que sa version majeure exacte — connue avec certitude pour tous les
/// chemins de résolution sauf JAVA_HOME (composants Mojang et Temurin ciblés
/// par version majeure par construction), ce qui évite à l'appelant de
/// relancer `java -version` juste après pour la redécouvrir.
///
/// `vendor`/`custom_path` (P1-6, audit launcher, Phase 6) : `custom_path`,
/// quand fourni et non-vide, est TOUJOURS prioritaire — quel que soit le
/// vendeur (retour utilisateur : la structure initiale ne permettait de
/// fournir un chemin custom que pour "Graal"/"Custom", impossible d'épingler
/// une install Temurin ou OpenJ9 précise sans changer de famille de flags).
/// `vendor` ne pilote donc plus QUE la famille de flags générée par
/// `build_jvm_args` et, en l'absence de chemin custom, la stratégie de
/// résolution/téléchargement automatique : `Custom` sans chemin est une
/// erreur explicite (un chemin custom manquant n'a pas de sens) ; `OpenJ9`
/// délègue à [`ensure_openj9`] avant de retomber sur la résolution standard
/// en cas d'échec ; `Graal` retombe directement dessus avec un avertissement
/// (le launcher ne télécharge jamais GraalVM lui-même — voir doc de
/// `JvmVendor::Graal`) ; `Temurin` (le défaut) et tous les replis best-effort
/// partagent la même résolution : JAVA_HOME → install système → runtime
/// Mojang en cache → téléchargement Mojang.
/// Ce qu'on peut dire d'une installation Java sans la lancer pour de bon.
///
/// Trois questions, de la moins chère à la plus chère, parce qu'elles
/// n'échouent pas pour les mêmes raisons : le fichier est-il là, le dossier
/// qui l'entoure est-il complet (une extraction interrompue laisse un
/// `java.exe` orphelin, sans `jvm.dll` — il existe et ne démarrera jamais), et
/// la JVM répond-elle quand on l'interroge.
pub struct JavaInspection {
    pub path: String,
    pub exists: bool,
    /// `None` quand la question n'a pas de sens : un `java` trouvé par le
    /// `PATH` n'a pas de dossier de runtime à inspecter.
    pub complete: Option<bool>,
    /// Version majeure rendue par la JVM elle-même, si elle a répondu.
    pub major: Option<u32>,
}

/// Examine une installation Java — voir [`JavaInspection`].
pub async fn inspect_java(path: &str) -> JavaInspection {
    let exe = Path::new(path);
    let exists = exe.exists();
    let complete = exe
        .parent()
        .and_then(Path::parent)
        .map(|home| runtime_is_complete(home, exe));
    // On n'interroge pas un fichier absent : le processus échouerait de toute
    // façon, et l'attente de cinq secondes serait gratuite.
    let major = if exists || !path.contains(std::path::MAIN_SEPARATOR) {
        detect_java_major_version(path).await
    } else {
        None
    };
    JavaInspection { path: path.to_string(), exists, complete, major }
}

/// Installe une version de Java **choisie**, à côté des runtimes gérés.
///
/// Le launcher sait déjà télécharger un JRE Eclipse Temurin et un OpenJ9 pour
/// ses propres besoins ; ici c'est l'utilisateur qui dit lequel et en quelle
/// version majeure. Chaque combinaison a son dossier
/// (`runtime/custom-temurin-21`), donc en installer une n'écrase pas une
/// autre, et une installation déjà complète n'est pas retéléchargée.
///
/// Ne touche pas au réglage de l'instance : l'appelant décide d'y poser le
/// chemin obtenu, ce qui laisse le geste réversible.
pub async fn install_custom_java(major: u32, vendor: &str) -> Result<String> {
    let jvm_impl = if vendor == "openj9" { "openj9" } else { "hotspot" };
    let dir = super::minecraft_dir()
        .join("runtime")
        .join(format!("custom-{jvm_impl}-{major}"));
    let exe = dir.join("bin").join(java_exe_name());

    if runtime_is_complete(&dir, &exe) {
        return Ok(exe.to_string_lossy().to_string());
    }

    let client = crate::minecraft::http::short_lived_client();
    download_adoptium(major, jvm_impl, &dir, &client).await?;
    if !exe.exists() {
        return Err(anyhow!(
            "Installation terminée mais aucun exécutable Java dans {}",
            dir.display()
        ));
    }
    Ok(exe.to_string_lossy().to_string())
}

/// Installe le runtime recommandé pour ce couple (composant, version).
///
/// Façade au-dessus d'[`ensure_java`] pour l'écran « Java et mémoire » :
/// l'appelant n'a ainsi à connaître ni `JvmVendor` ni le plancher de
/// progression, qui sont des détails du lancement. Même fonction que lui,
/// donc même runtime au même endroit — et comme elle cherche d'abord ce qui
/// est déjà présent, cliquer sur « Installer » quand tout va bien ne
/// retélécharge rien.
pub async fn install_java_runtime(
    component: &str,
    required_major: u32,
    app: &tauri::AppHandle,
    instance_id: &str,
) -> Result<String> {
    let client = crate::minecraft::http::short_lived_client();
    let progress = ProgressFloor::new(instance_id);
    let (path, _) = ensure_java(
        component,
        required_major,
        &super::minecraft_dir(),
        &client,
        app,
        &progress,
        // Le vendeur par défaut du launcher : c'est la résolution standard
        // (système, puis runtime Mojang/Temurin), pas un choix imposé.
        JvmVendor::Temurin,
        None,
    )
    .await?;
    Ok(path)
}

/// Où est le Java que le lancement utiliserait **sans rien télécharger**.
///
/// L'écran « Java et mémoire » a besoin de montrer un état, pas de provoquer
/// un téléchargement de 45 Mo parce qu'on a ouvert un onglet. C'est la même
/// cascade que [`ensure_java`] amputée de ses téléchargements : `JAVA_HOME`
/// à la version exacte, puis une installation système, puis les runtimes que
/// le launcher a déjà posés.
///
/// **À faire bouger avec [`ensure_java`]** : les deux décrivent la même
/// recherche, et une divergence ferait afficher un chemin qui n'est pas celui
/// que le jeu emploierait.
pub async fn resolve_existing_java(component: &str, required_major: u32, mc_dir: &Path) -> Option<String> {
    if let Ok(home) = std::env::var("JAVA_HOME") {
        let exe = PathBuf::from(&home).join("bin").join(java_exe_name());
        if exe.exists() {
            if detect_java_major_version(&exe.to_string_lossy()).await == Some(required_major) {
                return Some(exe.to_string_lossy().to_string());
            }
        }
    }

    if let Some(java) = find_system_java_verified(required_major).await {
        return Some(java);
    }

    if component == "jre-legacy" {
        let dir = mc_dir.join("runtime").join("jre-legacy-temurin");
        let exe = dir.join("bin").join(java_exe_name());
        if runtime_is_complete(&dir, &exe) {
            return Some(exe.to_string_lossy().to_string());
        }
    }

    let dir = mc_dir.join("runtime").join(component);
    let exe = if cfg!(target_os = "macos") {
        dir.join("jre.bundle").join("Contents").join("Home").join("bin").join("java")
    } else {
        dir.join("bin").join(java_exe_name())
    };
    runtime_is_complete(&dir, &exe).then(|| exe.to_string_lossy().to_string())
}

pub(super) async fn ensure_java(
    component: &str,
    required_major: u32,
    mc_dir: &Path,
    client: &reqwest::Client,
    app: &tauri::AppHandle,
    progress_floor: &ProgressFloor,
    vendor: JvmVendor,
    custom_path: Option<&str>,
) -> Result<(String, u32)> {
    if let Some(path) = custom_path.filter(|p| !p.is_empty()) {
        if !Path::new(path).exists() {
            return Err(anyhow!("Chemin JVM personnalisé introuvable : {}", path));
        }
        let major = detect_java_major_version(path).await
            .ok_or_else(|| anyhow!("Impossible de déterminer la version de la JVM personnalisée : {}", path))?;
        return Ok((path.to_string(), major));
    }

    if vendor == JvmVendor::Custom {
        return Err(anyhow!("Vendeur JVM \"custom\" sélectionné sans chemin fourni"));
    }

    if vendor == JvmVendor::Graal {
        tracing::warn!("Vendeur \"GraalVM\" sélectionné sans chemin — repli sur la résolution standard (Temurin/Mojang)");
    }

    if vendor == JvmVendor::OpenJ9 {
        match ensure_openj9(required_major, mc_dir, client, app, progress_floor).await {
            Ok(result) => return Ok(result),
            Err(e) => tracing::warn!("Résolution OpenJ9 échouée ({}), repli sur la résolution standard (Temurin/Mojang)", e),
        }
    }

    // 1. JAVA_HOME — version EXACTE requise, même contrainte que
    // find_system_java (Java 8 : LaunchWrapper incompatible Java 9+ ; Java 9+ :
    // une version plus récente que celle requise par MC fait planter LWJGL en
    // présence d'un agent JVM, voir find_system_java). Un JAVA_HOME moderne
    // pointant sur un JDK 21 utilisé pour lancer du 1.8.9 plantait sinon.
    if let Ok(home) = std::env::var("JAVA_HOME") {
        let exe = PathBuf::from(&home).join("bin").join(java_exe_name());
        if exe.exists() {
            if let Some(v) = detect_java_major_version(&exe.to_string_lossy()).await {
                if v == required_major {
                    return Ok((exe.to_string_lossy().to_string(), v));
                }
            }
        }
    }

    // 2. Installation système — reconnaissance par nom de dossier, puis
    // vérification par exécution si elle ne donne rien (voir
    // find_system_java_verified : c'est ce second passage qui règle les
    // « Java non détecté » remontés par les utilisateurs).
    if let Some(java) = find_system_java_verified(required_major).await {
        return Ok((java, required_major));
    }

    // 3. Java 8 : Mojang fige son propre runtime à la build 8u51 depuis des années
    // (vérifié sur son manifeste officiel — seuls les certificats racine sont
    // rafraîchis, jamais le JDK). On préfère un Eclipse Temurin récent (8u4xx+)
    // si on peut le récupérer, avant de retomber sur le runtime Mojang figé.
    if component == "jre-legacy" {
        let temurin_dir = mc_dir.join("runtime").join("jre-legacy-temurin");
        let temurin_exe = temurin_dir.join("bin").join(java_exe_name());
        // Même contrôle de COMPLÉTUDE que pour le runtime Mojang : une
        // extraction de zip interrompue laisse elle aussi un java.exe orphelin
        // (voir runtime_is_complete).
        if runtime_is_complete(&temurin_dir, &temurin_exe) {
            return Ok((temurin_exe.to_string_lossy().to_string(), required_major));
        }
        if cfg!(target_os = "windows") {
            tracing::info!("Java 8 Mojang figé à 8u51 — tentative de téléchargement d'un Temurin récent");
            set_progress_monotonic(app, progress_floor, 10, 100, "Téléchargement Java 8 récent (Eclipse Temurin)...");
            match download_adoptium_jre8(&temurin_dir, client).await {
                Ok(()) if temurin_exe.exists() => return Ok((temurin_exe.to_string_lossy().to_string(), required_major)),
                Ok(()) => tracing::warn!("Téléchargement Temurin terminé mais java introuvable, repli sur Mojang"),
                Err(e) => tracing::warn!("Téléchargement Temurin échoué ({}), repli sur Mojang", e),
            }
        }
    }

    // 4. Runtime Mojang déjà téléchargé — et COMPLET.
    let runtime_dir = mc_dir.join("runtime").join(component);
    let java_exe = if cfg!(target_os = "macos") {
        runtime_dir.join("jre.bundle").join("Contents").join("Home").join("bin").join("java")
    } else {
        runtime_dir.join("bin").join(java_exe_name())
    };
    if runtime_is_complete(&runtime_dir, &java_exe) {
        return Ok((java_exe.to_string_lossy().to_string(), required_major));
    }
    if java_exe.exists() {
        tracing::warn!(
            "Runtime Java {} présent mais INCOMPLET à {} — retéléchargement",
            required_major, runtime_dir.display()
        );
    }

    // 5. Téléchargement depuis Mojang
    tracing::info!("Java {} ({}) introuvable — téléchargement depuis Mojang", required_major, component);
    set_progress_monotonic(app, progress_floor, 12, 100, &format!("Téléchargement Java {} (Mojang)...", required_major));
    download_mojang_runtime(component, &runtime_dir, client, app, progress_floor).await?;

    if java_exe.exists() {
        Ok((java_exe.to_string_lossy().to_string(), required_major))
    } else {
        Err(anyhow!("Runtime Java installé mais introuvable à {}", java_exe.display()))
    }
}

/// Marqueur écrit UNIQUEMENT après un téléchargement de runtime entièrement
/// terminé — voir [`runtime_is_complete`].
const RUNTIME_COMPLETE_MARKER: &str = ".yuyuframe-complete";

/// `true` si ce runtime est utilisable, pas seulement « présent ».
///
/// BUG LE PLUS COURANT chez les utilisateurs (capture fournie) :
/// ```text
/// Error: could not find java.dll
/// Error: Could not find Java SE Runtime Environment.
/// ```
/// `java.exe` se lance mais ne trouve pas son propre runtime. La cause n'est
/// pas la détection de Java — c'est que le test d'installation se réduisait à
/// `java_exe.exists()`. Un téléchargement interrompu (fermeture du launcher,
/// coupure réseau, antivirus) laisse `bin/java.exe` écrit et des centaines
/// d'autres fichiers manquants ; au lancement suivant, cette condition est
/// vraie, le launcher répond « déjà téléchargé », saute l'étape 5 et lance un
/// runtime mutilé. **Définitivement** : rien ne redéclenche jamais le
/// téléchargement, l'utilisateur est bloqué jusqu'à suppression manuelle du
/// dossier.
///
/// Deux critères, dans cet ordre :
/// 1. le MARQUEUR, écrit seulement après un téléchargement complet — fiable
///    quel que soit le fichier manquant ;
/// 2. à défaut, la présence de la bibliothèque de la VM elle-même, pour les
///    runtimes installés par une version antérieure du launcher, qui n'ont
///    évidemment pas de marqueur. Sans ce repli, tout le monde
///    retéléchargerait son runtime une fois après la mise à jour.
fn runtime_is_complete(runtime_dir: &Path, java_exe: &Path) -> bool {
    if !java_exe.exists() {
        return false;
    }
    if runtime_dir.join(RUNTIME_COMPLETE_MARKER).exists() {
        return true;
    }

    // `java_home` = le dossier qui contient bin/ — diffère sur macOS, où le
    // runtime Mojang est empaqueté dans jre.bundle/Contents/Home.
    let Some(java_home) = java_exe.parent().and_then(Path::parent) else {
        return false;
    };
    // Exactement ce dont l'absence produit le message ci-dessus : sur Windows
    // java.exe charge bin/java.dll puis bin/server/jvm.dll ; ailleurs c'est
    // lib/libjava.* et lib/server/libjvm.*.
    let vm_lib_candidates = if cfg!(target_os = "windows") {
        vec![
            java_home.join("bin").join("java.dll"),
            java_home.join("bin").join("server").join("jvm.dll"),
        ]
    } else if cfg!(target_os = "macos") {
        vec![
            java_home.join("lib").join("libjava.dylib"),
            java_home.join("lib").join("server").join("libjvm.dylib"),
        ]
    } else {
        vec![
            java_home.join("lib").join("libjava.so"),
            java_home.join("lib").join("server").join("libjvm.so"),
        ]
    };
    vm_lib_candidates.iter().all(|p| p.exists())
}

/// P1-6 (audit launcher, Phase 6) : résout un runtime OpenJ9 pour
/// `required_major` — depuis le cache local si déjà téléchargé, sinon via
/// Adoptium (même limitation Windows-only que `download_adoptium_jre8`, voir
/// sa doc). Rangé sous `runtime/openj9-<major>/`, à part des runtimes Mojang
/// (`runtime/<component>/`) et du Temurin 8 dédié
/// (`runtime/jre-legacy-temurin/`) — trois familles de runtimes distinctes,
/// jamais mélangées.
async fn ensure_openj9(
    required_major: u32,
    mc_dir: &Path,
    client: &reqwest::Client,
    app: &tauri::AppHandle,
    progress_floor: &ProgressFloor,
) -> Result<(String, u32)> {
    let dir = mc_dir.join("runtime").join(format!("openj9-{required_major}"));
    let exe = dir.join("bin").join(java_exe_name());
    // Complétude, pas simple présence — voir runtime_is_complete.
    if runtime_is_complete(&dir, &exe) {
        return Ok((exe.to_string_lossy().to_string(), required_major));
    }
    if !cfg!(target_os = "windows") {
        return Err(anyhow!("Téléchargement OpenJ9 non supporté sur cette plateforme pour l'instant"));
    }

    tracing::info!("OpenJ9 {} introuvable — téléchargement depuis Adoptium", required_major);
    set_progress_monotonic(app, progress_floor, 10, 100, &format!("Téléchargement Java {required_major} (Eclipse OpenJ9)..."));
    download_adoptium(required_major, "openj9", &dir, client).await?;

    if exe.exists() {
        Ok((exe.to_string_lossy().to_string(), required_major))
    } else {
        Err(anyhow!("Runtime OpenJ9 installé mais introuvable à {}", exe.display()))
    }
}

async fn download_mojang_runtime(
    component: &str,
    dest: &Path,
    client: &reqwest::Client,
    app: &tauri::AppHandle,
    progress_floor: &ProgressFloor,
) -> Result<()> {
    let platform = mojang_platform_key();

    let all: serde_json::Value = client.get(MOJANG_JAVA_MANIFEST).send().await?.json().await?;

    let manifest_url = all
        .get(platform).and_then(|p| p.get(component))
        .and_then(|c| c.get(0)).and_then(|e| e.get("manifest"))
        .and_then(|m| m.get("url")).and_then(|u| u.as_str())
        .ok_or_else(|| anyhow!("Runtime Mojang '{}' indisponible pour '{}'", component, platform))?
        .to_string();

    let file_manifest: serde_json::Value = client.get(&manifest_url).send().await?.json().await?;
    let files = file_manifest["files"]
        .as_object()
        .ok_or_else(|| anyhow!("Manifest Java invalide"))?;

    // Crée d'abord tous les répertoires (pas de concurrence nécessaire)
    for (rel_path, info) in files.iter() {
        if info["type"].as_str() == Some("directory") {
            tokio::fs::create_dir_all(dest.join(rel_path)).await?;
        }
    }

    let total = files.len() as u64;
    let sem = Arc::new(Semaphore::new(24));
    let mut tasks: JoinSet<Result<()>> = JoinSet::new();

    for (rel_path, info) in files.iter() {
        match info["type"].as_str().unwrap_or("") {
            "file" => {
                let url = info["downloads"]["raw"]["url"].as_str().unwrap_or("").to_string();
                // Le manifeste Mojang fournit le SHA1 et la taille JUSTE À CÔTÉ
                // de l'URL — ils étaient ignorés, faisant du runtime Java le
                // SEUL téléchargement non vérifié de toute la chaîne, alors
                // que c'est le plus sensible de tous : l'exécutable qui va
                // lancer le jeu. Les bibliothèques et le client jar, eux,
                // étaient déjà vérifiés.
                let sha1 = info["downloads"]["raw"]["sha1"].as_str().map(str::to_string);
                let expected_size = info["downloads"]["raw"]["size"].as_u64();
                let executable = info["executable"].as_bool().unwrap_or(false);
                let file_dest = dest.join(rel_path);
                let sem = sem.clone();
                let client = client.clone();
                tasks.spawn(async move {
                    // Un fichier PRÉSENT mais de mauvaise taille est retéléchargé.
                    // Avant, la simple existence suffisait : un runtime tronqué
                    // par une coupure réseau restait cassé indéfiniment, sans
                    // aucun moyen de s'en sortir sans suppression manuelle —
                    // exactement le défaut déjà corrigé pour les natives, où la
                    // taille décompressée est comparée avant de sauter.
                    let up_to_date = match (file_dest.metadata(), expected_size) {
                        (Ok(m), Some(expected)) => m.len() == expected,
                        (Ok(_), None) => true,   // taille inconnue : on garde l'ancien comportement
                        (Err(_), _) => false,    // absent
                    };
                    if !up_to_date {
                        let _permit = sem.acquire().await.unwrap();
                        if let Some(p) = file_dest.parent() {
                            tokio::fs::create_dir_all(p).await?;
                        }
                        download_verified(&client, &url, &file_dest, sha1.as_deref()).await?;
                    }
                    #[cfg(unix)]
                    if executable {
                        use std::os::unix::fs::PermissionsExt;
                        tokio::fs::set_permissions(&file_dest, std::fs::Permissions::from_mode(0o755)).await?;
                    }
                    let _ = executable;
                    Ok::<(), anyhow::Error>(())
                });
            }
            #[cfg(unix)]
            "link" => {
                let target = info["target"].as_str().unwrap_or("").to_string();
                let link = dest.join(rel_path);
                if !link.exists() {
                    if let Some(p) = link.parent() { tokio::fs::create_dir_all(p).await?; }
                    tokio::fs::symlink(&target, &link).await.ok();
                }
            }
            _ => {}
        }
    }

    let mut done = 0u64;
    while let Some(r) = tasks.join_next().await {
        r??;
        done += 1;
        if done.is_multiple_of(100) || done == total {
            set_progress_monotonic(app, progress_floor, 12 + done * 8 / total.max(1), 100,
                &format!("Java runtime {}/{}", done, total));
        }
    }

    // Marqueur posé EN DERNIER, après que toutes les tâches se soient
    // terminées sans erreur (`r??` ci-dessus propage le premier échec). C'est
    // ce qui rend l'installation vérifiable au lancement suivant — voir
    // `runtime_is_complete`. Best-effort : son absence ne fait que déclencher
    // le repli sur la détection des bibliothèques de la VM.
    let _ = tokio::fs::write(dest.join(RUNTIME_COMPLETE_MARKER), b"ok").await;
    Ok(())
}

/// Télécharge un JRE 8 récent (Eclipse Temurin, build "latest" — 8u4xx+ au
/// lieu du 8u51 figé par Mojang) via l'API publique Adoptium — repli sur
/// [`download_adoptium`] (généralisée à n'importe quelle version majeure et
/// n'importe quel vendeur, voir P1-6/Phase 6, `ensure_openj9`).
async fn download_adoptium_jre8(dest: &Path, client: &reqwest::Client) -> Result<()> {
    download_adoptium(8, "hotspot", dest, client).await
}

/// Télécharge un JRE et l'extrait dans `dest` (structure finale :
/// `dest/bin/java.exe`, comme Mojang). `jvm_impl` : "hotspot" (Temurin) ou
/// "openj9" (Eclipse OpenJ9 / IBM Semeru). Windows uniquement pour l'instant
/// — ces API servent un .zip sur Windows mais un .tar.gz sur macOS/Linux, et
/// seul le crate `zip` est disponible ici.
///
/// ⚠️ DEUX HÔTES, PAS UN (bug corrigé le 2026-09-01) : la fondation Adoptium
/// ne publie QUE du HotSpot (c'est la définition de Temurin). Demander
/// `jvm_impl=openj9` à `api.adoptium.net` rend 404 quelle que soit la
/// version — vérifié sur 17, 21 et 25, et avec les deux vendeurs `eclipse`
/// et `ibm`. Le téléchargement OpenJ9 n'a donc JAMAIS pu aboutir depuis son
/// introduction : le mode auto sous 2 Go demandait OpenJ9, se prenait un 404,
/// et repartait silencieusement sur le runtime Mojang (HotSpot).
///
/// Les builds OpenJ9 vivent chez IBM Semeru, servies par l'API historique
/// AdoptOpenJDK, toujours en ligne et à jour : elle redirige vers les assets
/// GitHub de `ibmruntimes/semeru<N>-binaries` (vérifié : Java 25 rend
/// `ibm-semeru-open-jre_x64_windows_25.0.4.x.zip`, racine unique
/// `jdk-25.0.4+7-jre/` contenant `bin/java.exe` — compatible tel quel avec
/// [`extract_zip_flatten_root`]).
async fn download_adoptium(major: u32, jvm_impl: &str, dest: &Path, client: &reqwest::Client) -> Result<()> {
    let arch = if cfg!(target_arch = "aarch64") { "aarch64" } else { "x64" };
    let url = if jvm_impl == "openj9" {
        format!("https://api.adoptopenjdk.net/v3/binary/latest/{major}/ga/windows/{arch}/jre/openj9/normal/adoptopenjdk")
    } else {
        format!("https://api.adoptium.net/v3/binary/latest/{major}/ga/windows/{arch}/jre/{jvm_impl}/normal/eclipse?project=jdk")
    };

    let resp = client.get(&url).send().await?;
    if !resp.status().is_success() {
        return Err(anyhow!("Téléchargement JRE échoué ({} {}): {}", jvm_impl, major, resp.status()));
    }
    let bytes = resp.bytes().await?;

    tokio::fs::create_dir_all(dest).await?;
    let temp_zip = dest.with_extension("download.zip");
    tokio::fs::write(&temp_zip, &bytes).await?;

    let extract_result = extract_zip_flatten_root(&temp_zip, dest);
    let _ = tokio::fs::remove_file(&temp_zip).await;
    extract_result?;
    // Même marqueur que les runtimes Mojang — voir `runtime_is_complete`.
    // Ces runtimes-ci s'en sortaient par le repli « la bibliothèque de VM
    // est là », mais ce repli est justement le mode dégradé : posé ici,
    // l'extraction interrompue n'est plus jamais prise pour une install
    // terminée.
    let _ = tokio::fs::write(dest.join(RUNTIME_COMPLETE_MARKER), b"ok").await;
    Ok(())
}

/// Extrait un zip Adoptium en retirant son unique dossier racine (ex :
/// "jdk8u492-b09-jre/") pour que `dest` contienne directement `bin/`, `lib/`...
fn extract_zip_flatten_root(zip_path: &Path, dest: &Path) -> Result<()> {
    let file = std::fs::File::open(zip_path)?;
    let mut archive = zip::ZipArchive::new(file)?;

    for i in 0..archive.len() {
        let mut entry = archive.by_index(i)?;
        let entry_path = entry.mangled_name();
        let relative: PathBuf = entry_path.components().skip(1).collect();
        if relative.as_os_str().is_empty() {
            continue;
        }
        let out_path = dest.join(&relative);

        if entry.is_dir() {
            std::fs::create_dir_all(&out_path)?;
        } else {
            if let Some(parent) = out_path.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let mut out_file = std::fs::File::create(&out_path)?;
            std::io::copy(&mut entry, &mut out_file)?;
        }
    }
    Ok(())
}

fn mojang_platform_key() -> &'static str {
    if cfg!(target_os = "windows") {
        if cfg!(target_arch = "aarch64") { "windows-arm64" }
        else if cfg!(target_pointer_width = "64") { "windows-x64" }
        else { "windows-x86" }
    } else if cfg!(target_os = "macos") {
        if cfg!(target_arch = "aarch64") { "mac-os-arm64" } else { "mac-os" }
    } else {
        if cfg!(target_pointer_width = "64") { "linux" } else { "linux-i386" }
    }
}

/// Cherche un JDK système compatible avec `required_major`.
/// Java 8 : version exacte requise (LaunchWrapper incompatible Java 9+).
/// Java 9+ : version minimale (n'importe quelle version >= required_major convient).
/// Variante ASYNCHRONE de [`find_system_java`] : même balayage par NOM de
/// dossier, puis — s'il ne donne rien — vérification par EXÉCUTION.
///
/// Pourquoi les deux : la reconnaissance par nom est instantanée mais suppose
/// une convention de nommage. Or les conventions varient par vendeur et par
/// gestionnaire (`zulu21.32...`, `21.0.2-tem` de SDKMAN, `graalvm-jdk-21`,
/// un lien `default-java`, un dossier renommé à la main…). Un JDK
/// parfaitement valide dans un dossier au nom inattendu était purement et
/// simplement ignoré — c'est le « Java non détecté » remonté par les
/// utilisateurs, alors que `java -version` aurait répondu correctement.
///
/// La vérification par exécution est le contraire : elle demande son avis à
/// la JVM elle-même, donc elle ne peut pas se tromper, mais elle coûte un
/// process par candidat. D'où l'ordre — le chemin rapide d'abord, et le
/// chemin sûr seulement quand le rapide a échoué, c'est-à-dire dans le seul
/// cas où l'on s'apprêtait à retélécharger un runtime entier pour rien.
pub async fn find_system_java_verified(required_major: u32) -> Option<String> {
    if let Some(java) = find_system_java(required_major) {
        return Some(java);
    }

    // PATH d'abord : `java` y est de très loin la manière la plus courante
    // d'avoir un JDK, et il n'était consulté NULLE PART — ni ici, ni via
    // JAVA_HOME (qui n'est pas toujours posé, notamment quand Java vient
    // d'un gestionnaire de paquets).
    if let Some(v) = detect_java_major_version(java_exe_name()).await {
        if v == required_major {
            return Some(java_exe_name().to_string());
        }
    }

    for dir in candidate_java_dirs() {
        let exe = if cfg!(target_os = "macos") {
            dir.join("Contents").join("Home").join("bin").join("java")
        } else {
            dir.join("bin").join(java_exe_name())
        };
        if !exe.exists() {
            continue;
        }
        let path = exe.to_string_lossy().to_string();
        if detect_java_major_version(&path).await == Some(required_major) {
            tracing::info!(
                "Java {} trouvé par vérification à l'exécution : {} (nom de dossier non reconnu)",
                required_major, path
            );
            return Some(path);
        }
    }
    None
}

/// Tous les sous-dossiers des racines connues, sans aucun filtre sur le nom —
/// candidats bruts pour la vérification par exécution.
fn candidate_java_dirs() -> Vec<PathBuf> {
    let mut dirs = Vec::new();
    for root in java_roots() {
        let Ok(entries) = std::fs::read_dir(&root) else { continue };
        for entry in entries.flatten() {
            if entry.path().is_dir() {
                dirs.push(entry.path());
            }
        }
    }
    dirs
}

/// Racines à balayer — les emplacements historiques, plus ceux de
/// [`extra_java_roots`].
fn java_roots() -> Vec<PathBuf> {
    let fixed: &[&str] = if cfg!(target_os = "windows") {
        &[
            r"C:\Program Files\Java",
            r"C:\Program Files\Eclipse Adoptium",
            r"C:\Program Files\Microsoft",
            r"C:\Program Files\BellSoft",
            r"C:\Program Files\Amazon Corretto",
            r"C:\Program Files\Semeru Runtime",
        ]
    } else if cfg!(target_os = "macos") {
        &["/Library/Java/JavaVirtualMachines"]
    } else {
        &["/usr/lib/jvm", "/usr/local/lib/jvm", "/opt/java"]
    };
    let mut roots: Vec<PathBuf> = fixed.iter().map(PathBuf::from).collect();
    roots.extend(extra_java_roots());
    roots
}

fn find_system_java(required_major: u32) -> Option<String> {
    // Racines partagées avec la vérification par exécution — une seule liste,
    // sinon les emplacements ajoutés (Temurin installé « pour moi
    // uniquement », SDKMAN, Zulu…) ne profiteraient qu'au chemin lent.
    let roots = java_roots();

    // Java 8 : version exacte (LaunchWrapper incompatible Java 9+).
    // Java 9+ : version exacte uniquement — versions plus récentes (ex : Java 24 avec MC
    // qui requiert 21) changent l'ordre d'init des classes et font crasher LWJGL 3.3.3
    // nativement en présence d'un agent JVM. ensure_java() télécharge le runtime Mojang sinon.
    let max_major = required_major;

    let mut best: Option<(u32, String)> = None;
    for root in &roots {
        let Ok(entries) = std::fs::read_dir(root) else { continue };
        for entry in entries.flatten() {
            let dir_name = entry.file_name().to_string_lossy().to_lowercase();
            let Some(major) = java_major_from_dir_name(&dir_name) else { continue };
            if major < required_major || major > max_major { continue; }
            let exe = if cfg!(target_os = "macos") {
                entry.path().join("Contents").join("Home").join("bin").join("java")
            } else {
                entry.path().join("bin").join(java_exe_name())
            };
            if exe.exists() && (best.is_none() || major < best.as_ref().unwrap().0) {
                best = Some((major, exe.to_string_lossy().to_string()));
            }
        }
    }
    best.map(|(_, p)| p)
}

fn java_exe_name() -> &'static str {
    if cfg!(target_os = "windows") { "java.exe" } else { "java" }
}

/// Extrait la version majeure depuis un nom de répertoire JDK.
/// Reconnaît : "jdk-21", "jre-21", "jdk-21.0.3+9", "java-21-openjdk-amd64", "temurin-21", etc.
/// Et la forme legacy "1.X" des paquets Debian/Ubuntu/RHEL — "java-1.8.0-openjdk-amd64"
/// donnerait "1" avec un simple premier segment, jamais la vraie version majeure (8) :
/// sans ce cas, ces JDK système ne matchaient jamais `required_major` et étaient
/// silencieusement ignorés (retéléchargement inutile du runtime Mojang).
fn java_major_from_dir_name(name: &str) -> Option<u32> {
    const PREFIXES: [&str; 10] = [
        // Ordre important : les plus LONGS d'abord. "openjdk-21" doit être
        // reconnu par "openjdk-", pas laissé au "jdk-" qui ne matche pas en
        // début de chaîne — et "microsoft-jdk-21" doit passer par son propre
        // préfixe.
        "microsoft-jdk-", "graalvm-jdk-", "graalvm-", "openjdk-",
        "corretto-", "temurin-", "semeru-", "zulu", "jdk-", "jre-",
    ];
    let stripped = PREFIXES
        .iter()
        .find_map(|p| name.strip_prefix(p))
        // "java-21-openjdk-amd64" (Debian/RHEL) et "java-1.8.0-openjdk" :
        // testé APRÈS les autres, sinon "java-" avalerait des noms mieux
        // couverts au-dessus.
        .or_else(|| name.strip_prefix("java-"))?;
    let mut segments = stripped.split(['.', '+', '-', '_']);
    let first: u32 = segments.next()?.parse().ok()?;
    if first == 1 {
        segments.next()?.parse().ok()
    } else {
        Some(first)
    }
}

/// Chemins où chercher des JDK, en plus des racines fixes.
///
/// Le manque le plus courant en pratique : une installation Eclipse Temurin
/// « pour moi uniquement » (sans droits admin) atterrit sous
/// `%LOCALAPPDATA%\Programs\Eclipse Adoptium\` et n'était jamais parcourue —
/// l'utilisateur avait bien Java, le launcher ne le voyait pas et
/// retéléchargeait un runtime complet, ou échouait.
fn extra_java_roots() -> Vec<PathBuf> {
    let mut roots = Vec::new();
    if cfg!(target_os = "windows") {
        if let Ok(local) = std::env::var("LOCALAPPDATA") {
            let programs = PathBuf::from(&local).join("Programs");
            roots.push(programs.join("Eclipse Adoptium"));
            roots.push(programs.join("Microsoft"));
            roots.push(programs.join("Zulu"));
        }
        for pf in ["ProgramFiles", "ProgramFiles(x86)"] {
            if let Ok(dir) = std::env::var(pf) {
                for vendor in ["Zulu", "Java", "Eclipse Adoptium", "Microsoft",
                               "BellSoft", "Amazon Corretto", "Semeru Runtime", "GraalVM"] {
                    roots.push(PathBuf::from(&dir).join(vendor));
                }
            }
        }
    } else if let Ok(home) = std::env::var("HOME") {
        // SDKMAN : de très loin le gestionnaire de JDK le plus répandu sous
        // Linux/macOS, et ses dossiers (`21.0.2-tem`) ne portent AUCUN des
        // préfixes reconnus — d'où la vérification par exécution ci-dessous.
        roots.push(PathBuf::from(&home).join(".sdkman").join("candidates").join("java"));
        roots.push(PathBuf::from(&home).join(".jdks")); // IntelliJ IDEA
    }
    roots
}
