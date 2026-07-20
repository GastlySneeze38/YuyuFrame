use anyhow::{anyhow, Result};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::sync::Semaphore;
use tokio::task::JoinSet;

use crate::minecraft::loaders::{deps, fabric, forge};
use super::jvm_args::extract_tweak_class_args;
use super::progress::set_progress;

/// Extrait les chaînes d'un tableau JSON brut (`arguments.jvm`/`arguments.game`
/// des profils Fabric/Forge), en ignorant silencieusement les entrées non-string
/// (objets conditionnels de règles OS, non gérés ici).
fn json_str_array(values: &[serde_json::Value]) -> Vec<String> {
    values.iter().filter_map(|v| v.as_str().map(|s| s.to_string())).collect()
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
        fabric_tasks.spawn(async move {
            let _permit = sem.acquire().await.unwrap();
            let name = lib.name.clone();
            let path = fabric::download_library(&lib, &libraries_dir).await;
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
            set_progress(app, 72 + done * 20 / total.max(1), 100, &format!("Fabric libs {}/{}", done, total));
        }
    }

    let extra_jvm: Vec<String> = profile
        .arguments
        .as_ref()
        .and_then(|a| a.jvm.as_ref())
        .map(|jvm| json_str_array(jvm))
        .unwrap_or_default();
    // Rarement fourni par les profils Fabric en pratique, mais on l'applique
    // par cohérence avec le vanilla (build_game_args) et Forge (extra_game) —
    // avant, ce champ était désérialisé puis silencieusement jeté.
    let extra_game: Vec<String> = profile
        .arguments
        .as_ref()
        .and_then(|a| a.game.as_ref())
        .map(|game| json_str_array(game))
        .unwrap_or_default();

    Ok(LoaderSetup {
        main_class: profile.main_class,
        classpath: fabric_cp,
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

    let mut forge_json = forge::read_version_json(&version_id, mc_dir)?;
    let mut forge_cp = Vec::new();
    let mut warnings = Vec::new();

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
            forge_tasks.spawn(async move {
                let _permit = sem.acquire().await.unwrap();
                let name = lib.name.clone();
                let path = forge::download_library(&lib, &libraries_dir).await;
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
                set_progress(app, 80 + done * 12 / total.max(1), 100, &format!("Forge libs {}/{}", done, total));
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
