use anyhow::{anyhow, Result};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::sync::Semaphore;
use tokio::task::JoinSet;

use crate::minecraft::loaders::{deps, fabric, forge, neoforge, quilt};
use super::jvm_args::extract_tweak_class_args;
use super::mojang_rules::extract_conditional_args;
use super::progress::{set_progress_monotonic, ProgressFloor};

/// Substitue les placeholders propres au version json de Forge moderne
/// (>= ~1.17, vérifié empiriquement sur le JSON réel de 1.20.1-47.2.20) et
/// absents du vanilla : `${library_directory}`, `${classpath_separator}`,
/// `${version_name}`. Sans ça, l'argument `-p <module-path>` de
/// `cpw.mods.bootstraplauncher.BootstrapLauncher` (le vrai main class Forge
/// moderne) contient des chemins littéralement `${library_directory}/...`
/// qui ne résolvent à rien : le module `cpw.mods.securejarhandler` ne se
/// charge jamais, et `--add-opens ...=cpw.mods.securejarhandler` fait
/// planter la JVM au tout premier démarrage (avant même d'ouvrir une
/// fenêtre) — la cause du "Forge ne se lance pas" en 1.20.1 et probablement
/// toute version moderne (le Forge legacy pré-1.13 n'a pas ce bloc
/// `arguments` du tout, voir `minecraft_arguments`/tweakClass plus bas).
fn substitute_forge_placeholders(args: Vec<String>, version_id: &str, libraries_dir: &Path) -> Vec<String> {
    let classpath_sep = if cfg!(target_os = "windows") { ";" } else { ":" };
    let lib_dir = libraries_dir.to_string_lossy();
    args.into_iter()
        .map(|s| s
            .replace("${library_directory}", &lib_dir)
            .replace("${classpath_separator}", classpath_sep)
            .replace("${version_name}", version_id))
        .collect()
}

#[derive(Default)]
pub(super) struct LoaderSetup {
    pub(super) main_class: String,
    pub(super) classpath: Vec<String>,
    pub(super) extra_game_args: Vec<String>,
    pub(super) extra_jvm_args: Vec<String>,
    /// Libs ou dépendances qui n'ont pas pu être installées — le jeu démarre
    /// quand même (best-effort), mais l'utilisateur doit en être informé
    /// plutôt que de découvrir un crash Java sans indice.
    pub(super) warnings: Vec<String>,
}

pub(super) async fn setup_fabric(
    mc_version: &str,
    libraries_dir: &Path,
    mods_dir: &Path,
    app: &tauri::AppHandle,
    avoid_beta: bool,
    progress_floor: &ProgressFloor,
    client: &reqwest::Client,
    // Version de loader épinglée sur l'instance, `None` = la plus récente
    // compatible (le comportement historique).
    pinned: Option<&str>,
) -> Result<LoaderSetup> {
    set_progress_monotonic(app, progress_floor, 72, 100, "Téléchargement Fabric Loader...");

    let profile = match pinned {
        Some(version) => fabric::get_profile(mc_version, version).await?,
        None => fabric::get_latest_profile(mc_version).await?,
    };
    let mut warnings = Vec::new();

    if let Err(e) = fabric::ensure_fabric_api(mc_version, mods_dir).await {
        tracing::warn!("Fabric API auto-install échoué: {}", e);
        warnings.push("Fabric API n'a pas pu être installée automatiquement".to_string());
    }

    set_progress_monotonic(app, progress_floor, 74, 100, "Résolution des dépendances des mods...");
    match deps::resolve_and_install_deps(mc_version, "fabric", mods_dir, app, avoid_beta, progress_floor).await {
        Ok(failed) => warnings.extend(
            failed.into_iter().map(|id| format!("Dépendance de mod manquante : {id}")),
        ),
        Err(e) => {
            tracing::warn!("Résolution des dépendances échouée: {}", e);
            warnings.push(format!("Résolution des dépendances de mods échouée : {e}"));
        }
    }

    // 16 téléchargements simultanés — même limite que les libs vanilla plus
    // haut. Avant, ces libs se téléchargeaient une par une : négligeable pour
    // Fabric (15-30 libs), mais le même code sert de modèle à setup_forge où
    // Forge en a couramment 50-150+.
    let total = profile.libraries.len() as u64;
    let fabric_sem = Arc::new(Semaphore::new(16));
    let mut fabric_tasks: JoinSet<(String, Option<PathBuf>)> = JoinSet::new();
    for lib in profile.libraries {
        let sem = fabric_sem.clone();
        let libraries_dir = libraries_dir.to_path_buf();
        let client = client.clone();
        fabric_tasks.spawn(async move {
            let _permit = sem.acquire().await.unwrap();
            let name = lib.name.clone();
            let path = fabric::download_library(&lib, &libraries_dir, &client).await;
            (name, path)
        });
    }

    let mut fabric_cp = Vec::new();
    let mut done = 0u64;
    while let Some(result) = fabric_tasks.join_next().await {
        let (name, path) = result.map_err(|e| anyhow!("Tâche lib Fabric : {}", e))?;
        match path {
            Some(path) => fabric_cp.push(path.to_string_lossy().to_string()),
            None => warnings.push(format!("Bibliothèque Fabric manquante : {}", name)),
        }
        done += 1;
        if done.is_multiple_of(5) || done == total {
            set_progress_monotonic(app, progress_floor, 72 + done * 20 / total.max(1), 100, &format!("Fabric libs {}/{}", done, total));
        }
    }

    let extra_jvm: Vec<String> = profile
        .arguments
        .as_ref()
        .and_then(|a| a.jvm.as_ref())
        .map(|jvm| extract_conditional_args(jvm))
        .unwrap_or_default();
    // Rarement fourni par les profils Fabric en pratique, mais on l'applique
    // par cohérence avec le vanilla (build_game_args) et Forge (extra_game) —
    // avant, ce champ était désérialisé puis silencieusement jeté.
    let extra_game: Vec<String> = profile
        .arguments
        .as_ref()
        .and_then(|a| a.game.as_ref())
        .map(|game| extract_conditional_args(game))
        .unwrap_or_default();

    Ok(LoaderSetup {
        main_class: profile.main_class,
        classpath: fabric_cp,
        extra_game_args: extra_game,
        extra_jvm_args: extra_jvm,
        warnings,
    })
}

/// Miroir de `setup_fabric`, sans l'auto-install de Fabric API : QSL (Quilt
/// Standard Libraries) n'est pas systématiquement requis comme l'est Fabric
/// API pour la quasi-totalité des mods Fabric. `deps::resolve_and_install_deps`
/// reste appelé tel quel (déjà générique par chaîne de loader).
pub(super) async fn setup_quilt(
    mc_version: &str,
    libraries_dir: &Path,
    mods_dir: &Path,
    app: &tauri::AppHandle,
    avoid_beta: bool,
    progress_floor: &ProgressFloor,
    client: &reqwest::Client,
    // Version de loader épinglée sur l'instance, `None` = la plus récente
    // compatible (le comportement historique).
    pinned: Option<&str>,
) -> Result<LoaderSetup> {
    set_progress_monotonic(app, progress_floor, 72, 100, "Téléchargement Quilt Loader...");

    let profile = match pinned {
        Some(version) => quilt::get_profile(mc_version, version).await?,
        None => quilt::get_latest_profile(mc_version).await?,
    };
    let mut warnings = Vec::new();

    set_progress_monotonic(app, progress_floor, 74, 100, "Résolution des dépendances des mods...");
    match deps::resolve_and_install_deps(mc_version, "quilt", mods_dir, app, avoid_beta, progress_floor).await {
        Ok(failed) => warnings.extend(
            failed.into_iter().map(|id| format!("Dépendance de mod manquante : {id}")),
        ),
        Err(e) => {
            tracing::warn!("Résolution des dépendances échouée: {}", e);
            warnings.push(format!("Résolution des dépendances de mods échouée : {e}"));
        }
    }

    let total = profile.libraries.len() as u64;
    let quilt_sem = Arc::new(Semaphore::new(16));
    let mut quilt_tasks: JoinSet<(String, Option<PathBuf>)> = JoinSet::new();
    for lib in profile.libraries {
        let sem = quilt_sem.clone();
        let libraries_dir = libraries_dir.to_path_buf();
        let client = client.clone();
        quilt_tasks.spawn(async move {
            let _permit = sem.acquire().await.unwrap();
            let name = lib.name.clone();
            // Réutilise fabric::download_library : même format de lib
            // ({name, url}), aucune logique spécifique à "Fabric" dedans.
            let path = fabric::download_library(&lib, &libraries_dir, &client).await;
            (name, path)
        });
    }

    let mut quilt_cp = Vec::new();
    let mut done = 0u64;
    while let Some(result) = quilt_tasks.join_next().await {
        let (name, path) = result.map_err(|e| anyhow!("Tâche lib Quilt : {}", e))?;
        match path {
            Some(path) => quilt_cp.push(path.to_string_lossy().to_string()),
            None => warnings.push(format!("Bibliothèque Quilt manquante : {}", name)),
        }
        done += 1;
        if done.is_multiple_of(5) || done == total {
            set_progress_monotonic(app, progress_floor, 72 + done * 20 / total.max(1), 100, &format!("Quilt libs {}/{}", done, total));
        }
    }

    let extra_jvm: Vec<String> = profile
        .arguments
        .as_ref()
        .and_then(|a| a.jvm.as_ref())
        .map(|jvm| extract_conditional_args(jvm))
        .unwrap_or_default();
    let extra_game: Vec<String> = profile
        .arguments
        .as_ref()
        .and_then(|a| a.game.as_ref())
        .map(|game| extract_conditional_args(game))
        .unwrap_or_default();

    Ok(LoaderSetup {
        main_class: profile.main_class,
        classpath: quilt_cp,
        extra_game_args: extra_game,
        extra_jvm_args: extra_jvm,
        warnings,
    })
}

pub(super) async fn setup_forge(
    mc_version: &str,
    mc_dir: &Path,
    libraries_dir: &Path,
    java: &str,
    app: &tauri::AppHandle,
    progress_floor: &ProgressFloor,
    client: &reqwest::Client,
    // Version de loader épinglée sur l'instance, `None` = la plus récente
    // compatible (le comportement historique).
    pinned: Option<&str>,
) -> Result<LoaderSetup> {
    set_progress_monotonic(app, progress_floor, 70, 100, "Recherche de la version Forge...");

    let mut warnings = Vec::new();

    // Repli HORS LIGNE : la résolution de la dernière version Forge passe par
    // le réseau, mais une instance déjà installée n'en a pas besoin pour se
    // lancer. Avant, cet échec était fatal AVANT même de regarder ce qui est
    // sur le disque — une instance parfaitement jouable refusait de démarrer
    // sans connexion.
    // Une version épinglée court-circuite la résolution : il n'y a plus rien
    // à choisir, donc rien à demander au serveur Forge. Le repli hors ligne
    // ci-dessous reste utile tel quel, pour le cas non épinglé.
    let resolved = match pinned {
        Some(version) => Ok(version.to_string()),
        None => forge::fetch_latest_version(mc_version).await,
    };
    let version_id = match resolved {
        Ok(forge_ver) => {
            tracing::info!("Forge {} pour MC {}", forge_ver, mc_version);
            match forge::find_installed(mc_version, &forge_ver, mc_dir) {
                Some(id) => id,
                None => {
                    set_progress_monotonic(app, progress_floor, 72, 100, "Téléchargement de l'installeur Forge...");
                    forge::install(mc_version, &forge_ver, mc_dir, libraries_dir, java, client).await?
                }
            }
        }
        Err(e) => {
            tracing::warn!("Serveur Forge injoignable ({}) — recherche d'une installation locale", e);
            match forge::find_any_installed(mc_version, mc_dir) {
                Some(id) => {
                    warnings.push("Serveur Forge injoignable — lancement avec la version déjà installée".to_string());
                    id
                }
                None => return Err(anyhow!(
                    "Serveur Forge injoignable et aucune installation Forge locale pour MC {} — une première installation en ligne est nécessaire ({})",
                    mc_version, e
                )),
            }
        }
    };

    let mut forge_json = forge::read_version_json(&version_id, mc_dir)?;
    let mut forge_cp = Vec::new();

    // 16 téléchargements simultanés — même limite que les libs vanilla et
    // Fabric plus haut. Forge a couramment 50-150+ libs : les télécharger une
    // par une (comme avant) pouvait dominer le temps de lancement à froid.
    if let Some(libs) = forge_json.libraries.take() {
        let total = libs.len() as u64;
        let forge_sem = Arc::new(Semaphore::new(16));
        let mut forge_tasks: JoinSet<(String, Option<PathBuf>)> = JoinSet::new();
        for lib in libs {
            let sem = forge_sem.clone();
            let libraries_dir = libraries_dir.to_path_buf();
            let client = client.clone();
            forge_tasks.spawn(async move {
                let _permit = sem.acquire().await.unwrap();
                let name = lib.name.clone();
                let path = forge::download_library(&lib, &libraries_dir, &client).await;
                (name, path)
            });
        }

        let mut done = 0u64;
        while let Some(result) = forge_tasks.join_next().await {
            let (name, path) = result.map_err(|e| anyhow!("Tâche lib Forge : {}", e))?;
            match path {
                Some(path) => forge_cp.push(path.to_string_lossy().to_string()),
                None => warnings.push(format!("Bibliothèque Forge manquante : {}", name)),
            }
            done += 1;
            if done.is_multiple_of(5) || done == total {
                set_progress_monotonic(app, progress_floor, 80 + done * 12 / total.max(1), 100, &format!("Forge libs {}/{}", done, total));
            }
        }
    }

    let extra_game: Vec<String> = forge_json
        .arguments.as_ref().and_then(|a| a.game.as_ref())
        .map(|g| substitute_forge_placeholders(extract_conditional_args(g), &version_id, libraries_dir))
        .unwrap_or_else(|| {
            // Legacy Forge (pré-1.13) : pas de bloc "arguments", seulement une
            // "minecraftArguments" à plat dont on extrait juste --tweakClass
            // (le reste duplique les args vanilla déjà posés ailleurs).
            forge_json.minecraft_arguments.as_deref().map(extract_tweak_class_args).unwrap_or_default()
        });

    let mut extra_jvm: Vec<String> = forge_json
        .arguments.as_ref().and_then(|a| a.jvm.as_ref())
        .map(|j| substitute_forge_placeholders(extract_conditional_args(j), &version_id, libraries_dir))
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

/// Miroir de `setup_forge`, mais NeoForge n'a jamais eu de format legacy
/// (toujours "moderne", jamais de `minecraftArguments`) donc pas besoin de la
/// branche de repli tweakClass. Le version json produit par l'installeur
/// NeoForge a la MÊME forme que celui du Forge moderne (vérifié sur un
/// installeur réel : même `mainClass: cpw.mods.bootstraplauncher.BootstrapLauncher`,
/// même bloc `libraries[].downloads.artifact`, mêmes placeholders
/// `${library_directory}`/`${classpath_separator}`/`${version_name}`) — on
/// réutilise donc directement `forge::read_version_json`/`forge::download_library`
/// (aucune logique spécifique au nom "Forge" dedans) et `substitute_forge_placeholders`
/// plutôt que de dupliquer ce code.
pub(super) async fn setup_neoforge(
    mc_version: &str,
    mc_dir: &Path,
    libraries_dir: &Path,
    java: &str,
    app: &tauri::AppHandle,
    progress_floor: &ProgressFloor,
    client: &reqwest::Client,
    // Version de loader épinglée sur l'instance, `None` = la plus récente
    // compatible (le comportement historique).
    pinned: Option<&str>,
) -> Result<LoaderSetup> {
    set_progress_monotonic(app, progress_floor, 70, 100, "Recherche de la version NeoForge...");

    let mut warnings = Vec::new();

    // Repli HORS LIGNE — même raisonnement que `setup_forge` ci-dessus.
    // Même raisonnement que pour Forge juste au-dessus.
    let resolved = match pinned {
        Some(version) => Ok(version.to_string()),
        None => neoforge::fetch_latest_version(mc_version).await,
    };
    let version_id = match resolved {
        Ok(neoforge_ver) => {
            tracing::info!("NeoForge {} pour MC {}", neoforge_ver, mc_version);
            match neoforge::find_installed(&neoforge_ver, mc_dir) {
                Some(id) => id,
                None => {
                    set_progress_monotonic(app, progress_floor, 72, 100, "Téléchargement de l'installeur NeoForge...");
                    neoforge::install(&neoforge_ver, mc_dir, java, client).await?
                }
            }
        }
        Err(e) => {
            tracing::warn!("Serveur NeoForge injoignable ({}) — recherche d'une installation locale", e);
            match neoforge::find_any_installed(mc_version, mc_dir) {
                Some(id) => {
                    warnings.push("Serveur NeoForge injoignable — lancement avec la version déjà installée".to_string());
                    id
                }
                None => return Err(anyhow!(
                    "Serveur NeoForge injoignable et aucune installation NeoForge locale pour MC {} — une première installation en ligne est nécessaire ({})",
                    mc_version, e
                )),
            }
        }
    };

    let mut neoforge_json = forge::read_version_json(&version_id, mc_dir)?;
    let mut neoforge_cp = Vec::new();

    if let Some(libs) = neoforge_json.libraries.take() {
        let total = libs.len() as u64;
        let neoforge_sem = Arc::new(Semaphore::new(16));
        let mut neoforge_tasks: JoinSet<(String, Option<PathBuf>)> = JoinSet::new();
        for lib in libs {
            let sem = neoforge_sem.clone();
            let libraries_dir = libraries_dir.to_path_buf();
            let client = client.clone();
            neoforge_tasks.spawn(async move {
                let _permit = sem.acquire().await.unwrap();
                let name = lib.name.clone();
                let path = forge::download_library(&lib, &libraries_dir, &client).await;
                (name, path)
            });
        }

        let mut done = 0u64;
        while let Some(result) = neoforge_tasks.join_next().await {
            let (name, path) = result.map_err(|e| anyhow!("Tâche lib NeoForge : {}", e))?;
            match path {
                Some(path) => neoforge_cp.push(path.to_string_lossy().to_string()),
                None => warnings.push(format!("Bibliothèque NeoForge manquante : {}", name)),
            }
            done += 1;
            if done.is_multiple_of(5) || done == total {
                set_progress_monotonic(app, progress_floor, 80 + done * 12 / total.max(1), 100, &format!("NeoForge libs {}/{}", done, total));
            }
        }
    }

    let extra_game: Vec<String> = neoforge_json
        .arguments.as_ref().and_then(|a| a.game.as_ref())
        .map(|g| substitute_forge_placeholders(extract_conditional_args(g), &version_id, libraries_dir))
        .unwrap_or_default();

    let extra_jvm: Vec<String> = neoforge_json
        .arguments.as_ref().and_then(|a| a.jvm.as_ref())
        .map(|j| substitute_forge_placeholders(extract_conditional_args(j), &version_id, libraries_dir))
        .unwrap_or_default();

    Ok(LoaderSetup {
        main_class: neoforge_json.main_class,
        classpath: neoforge_cp,
        extra_game_args: extra_game,
        extra_jvm_args: extra_jvm,
        warnings,
    })
}
