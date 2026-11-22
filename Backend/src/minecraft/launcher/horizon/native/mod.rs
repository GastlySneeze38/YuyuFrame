//! Les mods natifs d'Horizon : du code Rust livré en source dans un `.hmod`,
//! compilé sur la machine du joueur contre le build exact d'Horizon que joue
//! l'instance, juste avant de lancer la partie.
//!
//! Chaîne refaite chez nous (décision du 2026-11-21) d'après le launcher
//! d'Horizon, sans lui : `toolchain` installe le compilateur Rust de leurs SDK,
//! `sdk` le SDK du build, `build` compile les mods et assemble le jeu qui les
//! charge. Un jeu assemblé est repris tant que ni les mods ni le build ne
//! changent : la compilation n'a lieu qu'au premier lancement qui en a besoin.
//!
//! **Confiance** — un mod natif tourne avec tous les droits du joueur, sans bac
//! à sable. Rien n'est compilé tant que le joueur n'a pas accepté
//! l'avertissement une fois (`ALLOWED_PREF`, révocable dans les paramètres).
//!
//! Ce qui n'est pas compilable est laissé de côté avec sa raison et la partie
//! se lance quand même ; le rapport (`native-report.json` dans le dossier
//! Horizon de l'instance, événement `horizon_native_report`) dit à l'interface
//! quoi montrer, dont le build pour lequel un mod est fait quand ce n'est pas
//! celui de l'instance — on le propose, sans jamais l'imposer.

pub mod build;
pub mod engine;
pub mod sdk;
pub mod toolchain;

use anyhow::{anyhow, Result};
use serde::Serialize;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tauri::{Emitter, Manager};
use tokio::sync::watch;

use super::super::progress::{log_to_console, set_progress_monotonic, ProgressFloor};
use build::{LeftOut, Reason};

/// Préférence : le joueur a accepté que des mods natifs tournent sur sa machine.
pub const ALLOWED_PREF: &str = "horizon_native_allowed";

pub fn root() -> PathBuf {
    crate::paths::root().join("horizon").join("native")
}

/// Une seule préparation à la fois (téléchargement en arrière-plan compris) :
/// deux qui écriraient le même compilateur se marcheraient dessus.
static BUSY: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

pub async fn allowed(app: &tauri::AppHandle) -> bool {
    let state = app.state::<crate::state::SharedState>();
    let db = state.read().await.db.clone();
    let conn = db.lock().await;
    crate::db::prefs::get_bool(&conn, ALLOWED_PREF, false)
}

/// Ce que la préparation dit à l'interface.
#[derive(Serialize, Clone, Debug)]
pub struct Report {
    pub instance_id: String,
    /// La version d'Horizon de la partie.
    pub base: String,
    /// Les mods natifs compilés et chargés.
    pub mods: Vec<String>,
    pub left_out: Vec<LeftOut>,
    /// Pour chaque build visé par un mod laissé de côté, la version publiée
    /// qui est ce build, quand il en reste une : de quoi proposer d'y passer.
    pub builds: HashMap<String, String>,
    /// Les mods natifs ne sont pas encore autorisés par le joueur.
    pub not_allowed: bool,
}

fn report_path(game_dir: &Path) -> PathBuf {
    game_dir.join("horizon").join("native-report.json")
}

/// Le dernier rapport d'une instance.
pub fn last_report(game_dir: &Path) -> Option<serde_json::Value> {
    serde_json::from_slice(&std::fs::read(report_path(game_dir)).ok()?).ok()
}

fn publish(app: &tauri::AppHandle, game_dir: &Path, report: &Report) {
    if let Ok(text) = serde_json::to_vec(report) {
        let _ = std::fs::create_dir_all(game_dir.join("horizon"));
        let _ = std::fs::write(report_path(game_dir), text);
    }
    let _ = app.emit("horizon_native_report", report);
}

/// Au lancement, entre l'installation d'Horizon et la préparation de ses
/// ressources : compile les mods natifs allumés de l'instance et rend le
/// dossier du jeu assemblé à lancer, ou `None` s'il n'y a rien à charger.
#[allow(clippy::too_many_arguments)]
pub async fn prepare(
    app: &tauri::AppHandle,
    floor: &Arc<ProgressFloor>,
    client: &reqwest::Client,
    base_dir: &Path,
    base_version: &str,
    game_dir: &Path,
    instance_id: &str,
    console: &str,
    cancel: &watch::Receiver<bool>,
) -> Result<Option<PathBuf>> {
    let dir = game_dir.to_path_buf();
    let (mods, unreadable) = tokio::task::spawn_blocking(move || build::enabled_mods(&dir))
        .await
        .map_err(|e| anyhow!("{e}"))?;
    let mut report = Report {
        instance_id: instance_id.to_string(),
        base: base_version.to_string(),
        mods: Vec::new(),
        left_out: unreadable,
        builds: HashMap::new(),
        not_allowed: false,
    };
    if mods.is_empty() {
        if report.left_out.is_empty() {
            let _ = std::fs::remove_file(report_path(game_dir));
        } else {
            publish(app, game_dir, &report);
        }
        return Ok(None);
    }
    if !allowed(app).await {
        report.not_allowed = true;
        publish(app, game_dir, &report);
        log_to_console(app, console, "[Horizon] Mods natifs pas encore autorisés : partie lancée sans eux", "err");
        return Ok(None);
    }

    let _busy = BUSY.lock().await;
    let cancelled = {
        let cancel = cancel.clone();
        move || *cancel.borrow()
    };
    let commit = super::release::build_commit(client, base_dir, base_version).await?;

    // Le compilateur : ~510 Mo la toute première fois, d'où la plus grande
    // part de la barre.
    let label_rust = format!(
        "Installation du compilateur des mods natifs ({} Mo)",
        toolchain::download_bytes() / 1_000_000
    );
    let rust = {
        let (app, floor) = (app.clone(), floor.clone());
        toolchain::ensure(client, &move |f| {
            let pct = (f * 100.0) as u64;
            set_progress_monotonic(&app, &floor, 21 + (f * 33.0) as u64, 100, &format!("{label_rust} — {pct} %"));
        }, &cancelled)
        .await?
    };
    // Le reste du jeu précompilé n'est utile qu'aux mods qui le modifient.
    let wants_engine = mods.iter().any(|(_, b)| b.engine.is_some() && b.made_for == commit);
    let sdk = {
        let (app, floor) = (app.clone(), floor.clone());
        sdk::ensure(client, &commit, wants_engine, &move |f| {
            let pct = (f * 100.0) as u64;
            set_progress_monotonic(&app, &floor, 54 + (f * 6.0) as u64, 100, &format!("Téléchargement du SDK d'Horizon — {pct} %"));
        }, &cancelled)
        .await?
    };
    let prebuilt = sdk::prebuilt(&sdk.runtime())?;

    // Ce que chaque mod donne contre ce build ; `surface.json` n'est lu que si
    // un mod est fait pour un autre build.
    let needs_surface = mods.iter().any(|(_, b)| b.made_for != commit && b.engine.is_none());
    let surface = if needs_surface { sdk::surface(client, &sdk.index).await.ok() } else { None };
    let api_deps = sdk.api().join("deps");
    let mut accepted: Vec<build::Bundle> = Vec::new();
    for (path, bundle) in mods {
        match build::judge(&bundle, &commit, surface.as_ref(), &prebuilt, &api_deps) {
            Ok(()) => accepted.push(bundle),
            Err(reason) => report.left_out.push(LeftOut {
                id: bundle.id.clone(),
                file: path.file_name().unwrap_or_default().to_string_lossy().into_owned(),
                reason,
            }),
        }
    }

    // Le jeu précompilé entier, si un mod le modifie ou utilise un paquet du
    // jeu absent de la tranche `api` (serde, log…).
    let mut whole = None;
    if accepted.iter().any(|b| build::needs_whole(b, &prebuilt, &api_deps)) {
        let ready = async {
            let sdk = {
                let (app, floor) = (app.clone(), floor.clone());
                sdk::ensure(client, &commit, true, &move |f| {
                    let pct = (f * 100.0) as u64;
                    set_progress_monotonic(&app, &floor, 58, 100, &format!("Téléchargement du jeu précompilé — {pct} %"));
                }, &cancelled)
                .await?
            };
            tokio::task::spawn_blocking(move || engine::whole(&sdk)).await.map_err(|e| anyhow!("{e}"))?
        };
        match ready.await {
            Ok(dir) => whole = Some(dir),
            Err(e) => {
                if *cancel.borrow() {
                    return Err(e);
                }
                let error = e.to_string();
                accepted.retain(|b| {
                    if build::needs_whole(b, &prebuilt, &api_deps) {
                        report.left_out.push(LeftOut { id: b.id.clone(), file: format!("{}.hmod", b.id), reason: Reason::Engine { error: error.clone() } });
                        false
                    } else {
                        true
                    }
                });
            }
        }
    }

    // Les outils des mods qui modifient le jeu : une fois par build d'Horizon.
    // S'ils ne peuvent pas être préparés, ces mods restent de côté avec la
    // raison, et les autres sont chargés.
    let mut tools = None;
    if accepted.iter().any(|b| b.engine.is_some()) {
        match engine_tools(app, floor, client, &rust, &sdk, &commit, accepted.iter().any(|b| b.mixins), &cancelled).await {
            Ok(ready) => tools = Some(ready),
            Err(e) => {
                if *cancel.borrow() {
                    return Err(e);
                }
                let error = e.to_string();
                log_to_console(app, console, &format!("[Horizon] Outils des mods qui modifient le jeu indisponibles : {error}"), "err");
                accepted.retain(|b| {
                    if b.engine.is_some() {
                        report.left_out.push(LeftOut { id: b.id.clone(), file: format!("{}.hmod", b.id), reason: Reason::Engine { error: error.clone() } });
                        false
                    } else {
                        true
                    }
                });
            }
        }
    }

    set_progress_monotonic(app, floor, 66, 100, "Compilation des mods natifs...");
    let built = {
        let (app, floor) = (app.clone(), floor.clone());
        let base_dir = base_dir.to_path_buf();
        let left_out = std::mem::take(&mut report.left_out);
        let cancel = cancel.clone();
        tokio::task::spawn_blocking(move || {
            build::build(
                &rust,
                &sdk,
                &base_dir,
                accepted,
                left_out,
                whole.as_deref(),
                tools.as_ref(),
                &|id, n, total| {
                    let at = 66 + ((n - 1) * 3 / total.max(1)) as u64;
                    set_progress_monotonic(&app, &floor, at, 100, &format!("Compilation des mods natifs — {id} ({n}/{total})"));
                },
                &|| *cancel.borrow(),
            )
        })
        .await
        .map_err(|e| anyhow!("{e}"))??
    };
    report.mods = built.mods.clone();
    report.left_out = built.left_out;
    for left in &report.left_out {
        let made_for = match &left.reason {
            Reason::OtherBuild { made_for, .. } | Reason::DoesNotFit { made_for, .. } => made_for.clone(),
            _ => continue,
        };
        if !report.builds.contains_key(&made_for) {
            if let Some(version) = super::release::version_for_commit(client, &made_for).await {
                report.builds.insert(made_for, version);
            }
        }
    }
    for left in &report.left_out {
        log_to_console(app, console, &format!("[Horizon] Mod natif {} laissé de côté : {:?}", left.id, left.reason), "err");
    }
    if !report.mods.is_empty() {
        log_to_console(app, console, &format!("[Horizon] Mods natifs chargés : {}", report.mods.join(", ")), "out");
    }
    publish(app, game_dir, &report);
    Ok((!built.mods.is_empty()).then_some(built.dir))
}

/// Source du build, jeu précompilé entier, `yuyu-horizon-gen` et, si un mod
/// a des `[[native.mixin]]`, `horizon-mixins`. Chacun n'est construit qu'une
/// fois par build (ou par révision), puis repris.
#[allow(clippy::too_many_arguments)]
async fn engine_tools(
    app: &tauri::AppHandle,
    floor: &Arc<ProgressFloor>,
    client: &reqwest::Client,
    rust: &toolchain::Toolchain,
    sdk: &sdk::Sdk,
    commit: &str,
    mixins: bool,
    cancelled: &(dyn Fn() -> bool + Send + Sync),
) -> Result<build::EngineTools> {
    let source = {
        let (app, floor) = (app.clone(), floor.clone());
        engine::ensure_source(client, commit, &move |done| {
            set_progress_monotonic(&app, &floor, 60, 100, &format!("Téléchargement du source d'Horizon — {} Mo", done / 1_000_000));
        }, cancelled)
        .await?
    };
    let whole = {
        let sdk = sdk.clone();
        tokio::task::spawn_blocking(move || engine::whole(&sdk)).await.map_err(|e| anyhow!("{e}"))??
    };
    set_progress_monotonic(app, floor, 61, 100, "Construction de l'outil des mods natifs (une fois par version d'Horizon)...");
    let generator = {
        let (rust, source, commit) = (rust.clone(), source.clone(), commit.to_string());
        tokio::task::spawn_blocking(move || engine::ensure_gen(&rust, &source, &commit)).await.map_err(|e| anyhow!("{e}"))??
    };
    let mixins = if mixins {
        set_progress_monotonic(app, floor, 64, 100, "Construction de horizon-mixins (une fois)...");
        let rev = sdk.index.mixins.as_ref().map(|m| m.rev.clone()).ok_or_else(|| anyhow!("ce SDK ne dit pas quelle version de horizon-mixins utiliser"))?;
        Some(engine::ensure_mixins(client, rust, &generator, &rev, cancelled).await?)
    } else {
        None
    };
    Ok(build::EngineTools { whole, generator, source, mixins })
}

/// Télécharge en arrière-plan le compilateur et le SDK du build que jouera
/// l'instance, dès qu'un premier mod natif y est allumé : au clic sur Jouer,
/// il ne reste en général que la compilation. Silencieux ; un échec sera
/// réessayé au lancement.
pub fn prefetch(app: tauri::AppHandle, pinned: String) {
    tauri::async_runtime::spawn(async move {
        if !allowed(&app).await {
            return;
        }
        let Ok(_busy) = BUSY.try_lock() else { return };
        let client = crate::net::files();
        let never = || false;
        let emit = |stage: &str, f: f32| {
            let _ = app.emit("horizon_native_prefetch", serde_json::json!({ "stage": stage, "fraction": f }));
        };
        if let Err(e) = toolchain::ensure(client, &|f| emit("rust", f), &never).await {
            tracing::warn!("[Horizon] compilateur non préparé en arrière-plan : {e}");
            return;
        }
        let Some(base) = super::release::installed_for(&pinned) else {
            emit("done", 1.0);
            return;
        };
        let version = base.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        match super::release::build_commit(client, &base, &version).await {
            Ok(commit) => {
                if let Err(e) = sdk::ensure(client, &commit, false, &|f| emit("sdk", f), &never).await {
                    tracing::warn!("[Horizon] SDK non préparé en arrière-plan : {e}");
                }
            }
            Err(e) => tracing::warn!("[Horizon] build de {version} inconnu : {e}"),
        }
        emit("done", 1.0);
    });
}

/// Où en est la préparation des mods natifs, pour l'écran des mods.
#[derive(Serialize)]
pub struct Status {
    pub allowed: bool,
    pub toolchain: bool,
    pub download_bytes: u64,
}

#[tauri::command]
pub async fn horizon_native_status(app: tauri::AppHandle) -> Result<Status, String> {
    Ok(Status {
        allowed: allowed(&app).await,
        toolchain: toolchain::installed().is_some(),
        download_bytes: toolchain::download_bytes(),
    })
}

/// Le joueur accepte (ou retire) les mods natifs. Retirer ne supprime rien :
/// les mods restent installés et ne sont plus compilés ni chargés.
#[tauri::command]
pub async fn horizon_native_allow(app: tauri::AppHandle, allow: bool) -> Result<(), String> {
    let state = app.state::<crate::state::SharedState>();
    let db = state.read().await.db.clone();
    let conn = db.lock().await;
    crate::db::prefs::set_bool(&conn, ALLOWED_PREF, allow).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn horizon_native_prefetch(app: tauri::AppHandle, loader_version: String) -> Result<(), String> {
    prefetch(app, loader_version);
    Ok(())
}

#[tauri::command]
pub async fn horizon_native_report(instance_id: String) -> Result<Option<serde_json::Value>, String> {
    Ok(last_report(&crate::instances::crud::instance_dir(&instance_id)))
}

/// Supprime le compilateur, les SDK et les jeux assemblés (paramètres,
/// stockage) : tout se retélécharge au prochain lancement qui en a besoin.
#[tauri::command]
pub async fn horizon_native_clear() -> Result<(), String> {
    let _busy = BUSY.lock().await;
    let dir = root();
    if dir.exists() {
        let trash = dir.with_extension("removing");
        tokio::fs::rename(&dir, &trash).await.map_err(|e| e.to_string())?;
        tokio::fs::remove_dir_all(&trash).await.map_err(|e| e.to_string())?;
    }
    Ok(())
}
