use anyhow::{anyhow, Result};
use serde::Deserialize;
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tauri::Emitter;
use tokio::sync::Semaphore;
use tokio::task::JoinSet;

use crate::minecraft::mod_files::is_enabled_jar;
use crate::minecraft::versions::predicate::{
    normalize_version, parse_predicate_groups, read_fabric_mod_json,
    read_fabric_mod_json_with_nested, version_allowed,
};

const MODRINTH_API: &str = "https://api.modrinth.com/v2";

// IDs gérés par le loader ou intégrés à Minecraft — on ne tente pas de les télécharger
const BUILTIN_IDS: &[&str] = &[
    "minecraft",
    "fabricloader",
    "fabric-loader",
    "java",
    "forge",
    "neoforge",
    "quilt_loader",
    "quilt-loader",
];

#[derive(Deserialize)]
struct ModrinthVersion {
    version_number: String,
    files: Vec<ModrinthFile>,
}

#[derive(Deserialize)]
struct ModrinthFile {
    url: String,
    filename: String,
    primary: bool,
}

#[derive(Deserialize)]
struct ModrinthSearchResult {
    hits: Vec<ModrinthHit>,
}

#[derive(Deserialize)]
struct ModrinthHit {
    project_id: String,
}

/// Mod installé localement, avec sa version déclarée et son fichier jar.
struct InstalledMod {
    version: String,
    path: PathBuf,
}

async fn scan_installed(mods_dir: &Path) -> HashMap<String, InstalledMod> {
    let mut installed = HashMap::new();
    // Fabric API est gérée séparément — considérée toujours présente et compatible
    installed.insert(
        "fabric-api".to_string(),
        InstalledMod { version: String::new(), path: PathBuf::new() },
    );

    if let Ok(mut entries) = tokio::fs::read_dir(mods_dir).await {
        while let Ok(Some(entry)) = entries.next_entry().await {
            let path = entry.path();
            let name = path.file_name().unwrap_or_default().to_string_lossy().to_string();
            if !is_enabled_jar(&name) {
                continue;
            }
            if let Some((meta, nested_ids)) = read_fabric_mod_json_with_nested(&path) {
                // Jars imbriqués (jar-in-jar) : toujours considérés compatibles,
                // comme fabric-api lui-même — voir doc de `read_fabric_mod_json_with_nested`.
                for nested_id in nested_ids {
                    installed
                        .entry(nested_id)
                        .or_insert_with(|| InstalledMod { version: String::new(), path: path.clone() });
                }
                installed.insert(meta.id, InstalledMod { version: meta.version, path });
            }
        }
    }

    installed
}

/// Une contrainte de version déclarée par un mod dépendant, gardée avec son nom
/// pour pouvoir signaler quel mod bloque quoi.
struct DepConstraint {
    declaring_mod: String,
    depends_groups: Vec<Vec<String>>,
    breaks_groups: Vec<Vec<String>>,
}

struct MissingDep {
    id: String,
    /// Contraintes de TOUS les mods installés qui dépendent de `id` — une version
    /// candidate doit satisfaire chacune d'entre elles, pas seulement la première
    /// trouvée (sinon on peut réinstaller une version qui casse un autre mod).
    constraints: Vec<DepConstraint>,
    /// Jar existant mais incompatible, à supprimer avant réinstallation.
    replace_path: Option<PathBuf>,
}

async fn collect_missing_deps(
    mods_dir: &Path,
    installed: &HashMap<String, InstalledMod>,
    already_tried: &HashSet<String>,
    mc_version: &str,
    loader: &str,
) -> Vec<MissingDep> {
    // 1) Rassemble, pour chaque dep_id, la contrainte de CHAQUE mod installé qui en dépend.
    let mut constraints_by_id: HashMap<String, Vec<DepConstraint>> = HashMap::new();

    if let Ok(mut entries) = tokio::fs::read_dir(mods_dir).await {
        while let Ok(Some(entry)) = entries.next_entry().await {
            let path = entry.path();
            let name = path.file_name().unwrap_or_default().to_string_lossy().to_string();
            if !is_enabled_jar(&name) {
                continue;
            }
            let Some(meta) = read_fabric_mod_json(&path) else { continue };
            for (dep_id, predicate_value) in &meta.depends {
                if BUILTIN_IDS.contains(&dep_id.as_str()) || already_tried.contains(dep_id) {
                    continue;
                }
                let depends_groups = parse_predicate_groups(predicate_value);
                let breaks_groups = meta
                    .breaks
                    .get(dep_id)
                    .map(parse_predicate_groups)
                    .unwrap_or_default();
                constraints_by_id.entry(dep_id.clone()).or_default().push(DepConstraint {
                    declaring_mod: name.clone(),
                    depends_groups,
                    breaks_groups,
                });
            }
        }
    }

    // 2) Pour chaque dep_id, vérifie si la version installée satisfait TOUTES les contraintes.
    let mut missing: Vec<MissingDep> = Vec::new();
    for (dep_id, constraints) in constraints_by_id {
        match installed.get(&dep_id) {
            Some(found) if found.version.is_empty() => {
                // fabric-api : pas de version suivie, on suppose compatible
                continue;
            }
            Some(found) => {
                let normalized = normalize_version(&found.version, mc_version, loader);
                let all_satisfied = constraints
                    .iter()
                    .all(|c| version_allowed(&normalized, &c.depends_groups, &c.breaks_groups));
                if all_satisfied {
                    continue;
                }
                missing.push(MissingDep { id: dep_id, constraints, replace_path: Some(found.path.clone()) });
            }
            None => {
                missing.push(MissingDep { id: dep_id, constraints, replace_path: None });
            }
        }
    }

    missing
}

/// Détecte une version pré-release (beta/alpha/rc/pre/snapshot) par ses
/// segments dot/dash/plus-séparés — évite les faux positifs sur des mots qui
/// contiennent juste ces lettres ailleurs dans la chaîne.
fn is_beta_version(version: &str) -> bool {
    version.split(['.', '-', '+']).any(|seg| {
        let l = seg.to_ascii_lowercase();
        ["alpha", "beta", "rc", "pre", "snapshot"]
            .iter()
            .any(|kw| l.starts_with(kw))
    })
}

async fn fetch_versions_for_slug(
    client: &reqwest::Client,
    slug: &str,
    mc_version: &str,
    loader: &str,
) -> Option<Vec<ModrinthVersion>> {
    let url = format!(
        "{}/project/{}/version?game_versions=[\"{}\"]&loaders=[\"{}\"]",
        MODRINTH_API, slug, mc_version, loader
    );
    let resp = client.get(&url).send().await.ok()?;
    if resp.status().is_success() {
        resp.json::<Vec<ModrinthVersion>>().await.ok()
    } else {
        None
    }
}

async fn install_dep(
    client: &reqwest::Client,
    dep: &MissingDep,
    mc_version: &str,
    loader: &str,
    mods_dir: &Path,
    avoid_beta: bool,
) -> Result<String> {
    let dep_id = &dep.id;

    // Tentative directe par slug Modrinth (correspond souvent au mod ID Fabric)
    let versions = if let Some(v) = fetch_versions_for_slug(client, dep_id, mc_version, loader).await {
        v
    } else {
        // Fallback : recherche textuelle
        let search_url = format!(
            "{}/search?query={}&facets=[[\"project_type:mod\"],[\"versions:{}\"],[\"categories:{}\"]]\
&limit=3",
            MODRINTH_API, dep_id, mc_version, loader
        );
        let results: ModrinthSearchResult = client
            .get(&search_url)
            .send()
            .await?
            .json()
            .await
            .map_err(|_| anyhow!("Aucun résultat de recherche pour «{}»", dep_id))?;

        let hit = results
            .hits
            .into_iter()
            .next()
            .ok_or_else(|| anyhow!("Mod «{}» introuvable sur Modrinth", dep_id))?;

        fetch_versions_for_slug(client, &hit.project_id, mc_version, loader)
            .await
            .unwrap_or_default()
    };

    // Modrinth renvoie les versions du plus récent au plus ancien : on prend la
    // première qui satisfait réellement TOUTES les contraintes déclarées par
    // TOUS les mods dépendants (`depends`) sans tomber dans une version
    // explicitement cassée par l'un d'eux (`breaks`) — sinon on risque de
    // satisfaire un seul mod en cassant un autre (ex: Voxy exige Sodium <0.8.13
    // alors qu'un autre mod accepterait n'importe quelle 0.8.x/0.9.x).
    let compatible: Vec<ModrinthVersion> = versions
        .into_iter()
        .filter(|v| {
            let normalized = normalize_version(&v.version_number, mc_version, loader);
            dep.constraints
                .iter()
                .all(|c| version_allowed(&normalized, &c.depends_groups, &c.breaks_groups))
        })
        .collect();

    // « Éviter les dépendances beta » (réglage launcher) : une version beta
    // n'est souvent pas encore supportée par les autres mods qui en dépendent
    // (cas Sodium 0.8.13-beta + Voxy) — on ne l'installe jamais automatiquement,
    // même si elle matche la plage de versions déclarée.
    let version = if avoid_beta {
        compatible.into_iter().find(|v| {
            !is_beta_version(&normalize_version(&v.version_number, mc_version, loader))
        })
    } else {
        compatible.into_iter().next()
    }
    .ok_or_else(|| {
        let declaring_mods: Vec<&str> = dep.constraints.iter().map(|c| c.declaring_mod.as_str()).collect();
        anyhow!(
            "Aucune version {}compatible pour «{}» (MC {}, {}) satisfaisant à la fois : {:?}",
            if avoid_beta { "stable " } else { "" },
            dep_id, mc_version, loader, declaring_mods
        )
    })?;

    let file = version
        .files
        .into_iter()
        .find(|f| f.primary)
        .ok_or_else(|| anyhow!("Pas de fichier principal pour «{}»", dep_id))?;

    if let Some(old_path) = &dep.replace_path {
        let _ = tokio::fs::remove_file(old_path).await;
    }

    let bytes = client.get(&file.url).send().await?.bytes().await?;
    tokio::fs::write(mods_dir.join(&file.filename), &bytes).await?;

    Ok(file.filename)
}

/// Fenêtre pendant laquelle une dépendance ayant échoué n'est PAS retentée.
/// Sans ça, une dépendance sans version compatible sur Modrinth (contrainte
/// trop stricte, mod jamais publié pour cette combo MC/loader...) refaisait
/// sa recherche + tentative de téléchargement à CHAQUE lancement, pour
/// échouer à nouveau à chaque fois — assez court pour retenter vite si
/// l'utilisateur change de version ou qu'une version compatible sort entre
/// temps, assez long pour ne pas spammer Modrinth sur une session de test.
const DEP_FAILURE_COOLDOWN_SECS: i64 = 30 * 60;

/// Fichier caché à la racine de l'instance (PAS dans mods_dir : mods_list ne
/// liste que les .jar, mais autant rester hors de vue).
fn dep_failures_path(mods_dir: &Path) -> PathBuf {
    mods_dir.parent().unwrap_or(mods_dir).join(".dep_failures.json")
}

/// Charge les échecs récents encore dans la fenêtre de cooldown (les entrées
/// plus vieilles sont silencieusement écartées — pas besoin de les réécrire,
/// `save_recent_failures` ne persiste que ce qui a été effectivement revu
/// dans cet appel).
async fn load_recent_failures(mods_dir: &Path) -> HashMap<String, i64> {
    let Ok(json) = tokio::fs::read_to_string(dep_failures_path(mods_dir)).await else {
        return HashMap::new();
    };
    let all: HashMap<String, i64> = serde_json::from_str(&json).unwrap_or_default();
    let now = chrono::Utc::now().timestamp();
    all.into_iter().filter(|(_, ts)| now - ts < DEP_FAILURE_COOLDOWN_SECS).collect()
}

async fn save_recent_failures(mods_dir: &Path, failures: &HashMap<String, i64>) {
    if failures.is_empty() {
        let _ = tokio::fs::remove_file(dep_failures_path(mods_dir)).await;
        return;
    }
    if let Ok(json) = serde_json::to_string(failures) {
        let _ = tokio::fs::write(dep_failures_path(mods_dir), json).await;
    }
}

/// Résout et installe les dépendances manquantes ou incompatibles pour tous les
/// mods Fabric du dossier, en respectant les contraintes de version qu'ils
/// déclarent. Itère jusqu'à ce qu'il n'y ait plus rien à installer (max 10 passes).
/// Retourne la liste des dépendances qui n'ont pas pu être installées (vide si
/// tout a réussi) — l'appelant la remonte comme avertissement de lancement
/// plutôt que de la laisser silencieuse dans les logs (une dépendance
/// obligatoire manquante, ex: Fabric API pour Sodium, plante sinon le jeu au
/// démarrage sans indice). Inclut aussi les dépendances en cooldown (voir
/// `DEP_FAILURE_COOLDOWN_SECS`), non retentées mais toujours signalées.
pub async fn resolve_and_install_deps(
    mc_version: &str,
    loader: &str,
    mods_dir: &Path,
    app: &tauri::AppHandle,
    avoid_beta: bool,
) -> Result<Vec<String>> {
    if !mods_dir.exists() {
        return Ok(vec![]);
    }

    let client = reqwest::Client::builder()
        .user_agent("YuyuFrame/1.0")
        .build()?;

    let mut recent_failures = load_recent_failures(mods_dir).await;
    let mut already_tried: HashSet<String> = recent_failures.keys().cloned().collect();
    let mut failed: Vec<String> = already_tried.iter().cloned().collect();

    for _ in 0..10 {
        let installed = scan_installed(mods_dir).await;
        let missing = collect_missing_deps(mods_dir, &installed, &already_tried, mc_version, loader).await;

        if missing.is_empty() {
            break;
        }

        for dep in &missing {
            already_tried.insert(dep.id.clone());
        }

        // 4 installations simultanées — les dépendances trouvées dans une
        // même passe sont indépendantes (chacune sa propre recherche +
        // téléchargement Modrinth), donc parallélisables ; limité (contre 16
        // ailleurs) par politesse envers l'API publique Modrinth plutôt que
        // par une contrainte technique. Les dépendances-de-dépendances ne
        // sont découvertes qu'à la passe suivante (re-scan de mods_dir),
        // donc les passes elles restent séquentielles.
        let sem = Arc::new(Semaphore::new(4));
        let mut tasks: JoinSet<(String, Result<String>)> = JoinSet::new();
        for dep in missing {
            let sem = sem.clone();
            let client = client.clone();
            let mc_version = mc_version.to_string();
            let loader = loader.to_string();
            let mods_dir = mods_dir.to_path_buf();
            let dep_id = dep.id.clone();

            let _ = app.emit(
                "download_progress",
                serde_json::json!({
                    "current": 0,
                    "total": 100,
                    "message": format!("Dépendance : installation de {}…", dep_id)
                }),
            );

            tasks.spawn(async move {
                let _permit = sem.acquire().await.unwrap();
                let result = install_dep(&client, &dep, &mc_version, &loader, &mods_dir, avoid_beta).await;
                (dep_id, result)
            });
        }

        while let Some(res) = tasks.join_next().await {
            let (dep_id, result) = match res {
                Ok(r) => r,
                Err(e) => {
                    tracing::warn!("Tâche d'installation de dépendance échouée : {}", e);
                    continue;
                }
            };
            match result {
                Ok(filename) => {
                    tracing::info!("Dépendance installée : {}", filename);
                    recent_failures.remove(&dep_id);
                }
                Err(e) => {
                    tracing::warn!("Impossible d'installer «{}» : {}", dep_id, e);
                    failed.push(dep_id.clone());
                    recent_failures.insert(dep_id, chrono::Utc::now().timestamp());
                }
            }
        }
    }

    save_recent_failures(mods_dir, &recent_failures).await;
    Ok(failed)
}
