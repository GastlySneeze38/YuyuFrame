//! Rattrapage des sessions de jeu que le launcher n'a pas vues finir.
//!
//! Le cas normal, ce n'est pas un bug : on ferme le launcher pour libérer de
//! la mémoire pendant qu'on joue. Le launcher sait désormais rester en vie
//! sans sa fenêtre (voir `lib.rs`), mais ça ne couvre pas tout — arrêt de
//! Windows, coupure de courant, processus tué dans le gestionnaire des
//! tâches. Ce module est le filet en dessous.
//!
//! Il tourne une fois au démarrage, sur un thread bloquant, et décide de trois
//! choses pour chaque session restée ouverte :
//!
//! 1. **Le jeu tourne encore** → on la ré-adopte : un surveillant attend la
//!    fin du processus et fera le ménage. C'est le cas fréquent de « je ferme
//!    le launcher, je le rouvre plus tard ».
//! 2. **Le jeu a planté** → on reconstruit un rapport partiel depuis le
//!    fichier que Minecraft a écrit lui-même.
//! 3. **Le jeu s'est arrêté** → on ferme la session à sa dernière trace de vie.

use std::sync::Arc;

use chrono::{TimeZone, Utc};
use rusqlite::Connection;
use tokio::sync::Mutex;

use crate::db;
use crate::minecraft::crash;

/// Intervalle entre deux battements de cœur. Trente secondes : c'est ce qu'on
/// accepte de perdre au pire sur une session, et c'est assez rare pour que
/// l'écriture (une ligne, un entier) ne se voie nulle part.
pub const HEARTBEAT_SECS: u64 = 30;

/// Le processus `pid` est-il toujours celui de NOTRE partie ?
///
/// Le PID seul ne suffit pas : Windows les réattribue, et on tomberait sur un
/// processus sans rapport qu'on se mettrait à surveiller. On vérifie donc
/// aussi que c'est bien une JVM, et qu'elle a démarré avant la session (une
/// JVM lancée après ne peut pas être celle-là).
fn is_our_java(pid: u32, started_at: i64) -> bool {
    use sysinfo::{ProcessRefreshKind, ProcessesToUpdate, System};
    let mut sys = System::new();
    let target = sysinfo::Pid::from_u32(pid);
    sys.refresh_processes_specifics(ProcessesToUpdate::Some(&[target]), ProcessRefreshKind::everything());
    let Some(process) = sys.process(target) else { return false };
    let name = process.name().to_string_lossy().to_ascii_lowercase();
    if !name.contains("java") {
        return false;
    }
    // `start_time` est en secondes Unix. Une marge de deux minutes couvre
    // l'écart entre l'insertion en base et le démarrage réel de la JVM.
    process.start_time() as i64 <= started_at + 120
}

/// Passe en revue les sessions ouvertes. Appelée une fois au démarrage.
pub fn run(db: Arc<Mutex<Connection>>, app: tauri::AppHandle) {
    tauri::async_runtime::spawn(async move {
        let orphans = {
            let conn = db.lock().await;
            match db::orphan_sessions(&conn) {
                Ok(v) => v,
                Err(e) => {
                    tracing::warn!("[Reprise] lecture des sessions ouvertes impossible : {e}");
                    return;
                }
            }
        };
        if orphans.is_empty() {
            return;
        }
        tracing::info!("[Reprise] {} session(s) laissée(s) ouverte(s)", orphans.len());

        let version = app.package_info().version.to_string();
        for orphan in orphans {
            // Toujours une JVM vivante : on la reprend en charge plutôt que
            // de clore une partie qui est encore en train d'être jouée.
            if let Some(pid) = orphan.pid.and_then(|p| u32::try_from(p).ok()) {
                let alive = tokio::task::spawn_blocking(move || is_our_java(pid, orphan.started_at)).await.unwrap_or(false);
                if alive {
                    tracing::info!("[Reprise] session {} : le jeu tourne toujours (pid {pid}), reprise en charge", orphan.id);
                    adopt(db.clone(), orphan.id, pid, orphan.started_at);
                    continue;
                }
            }
            close(&db, orphan, &version).await;
        }
    });
}

/// Surveille une partie dont on a perdu les tubes : on ne peut plus lire son
/// journal, mais on peut encore savoir quand elle s'arrête, et c'est ce qui
/// manquait aux statistiques.
fn adopt(db: Arc<Mutex<Connection>>, session_id: i64, pid: u32, started_at: i64) {
    tauri::async_runtime::spawn(async move {
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(HEARTBEAT_SECS)).await;
            let alive = tokio::task::spawn_blocking(move || is_our_java(pid, started_at)).await.unwrap_or(false);
            let conn = db.lock().await;
            if alive {
                let _ = db::session_heartbeat(&conn, session_id);
                continue;
            }
            let now = Utc::now().timestamp();
            let _ = db::session_end(&conn, session_id, now, now - started_at, "normal", None);
            tracing::info!("[Reprise] session {session_id} terminée et enregistrée");
            return;
        }
    });
}

/// Clôt une session dont le jeu n'est plus là, et regarde au passage s'il est
/// parti en plantant.
async fn close(db: &Arc<Mutex<Connection>>, orphan: db::Orphan, launcher_version: &str) {
    // La dernière trace de vie est la seule durée qu'on puisse affirmer. À
    // défaut, la session vaut zéro : mieux vaut une partie comptée sans durée
    // qu'une durée inventée qui fausserait toutes les moyennes.
    let ended_at = orphan.last_seen_at.unwrap_or(orphan.started_at).max(orphan.started_at);

    let info = crash::Recovered {
        instance_id: orphan.instance_id.clone(),
        instance_name: orphan.instance_name.clone(),
        mc_version: orphan.mc_version.clone(),
        loader: orphan.loader.clone(),
        java_version: orphan.java_version.clone(),
        jvm_args: orphan.jvm_args.as_deref().map(|a| a.lines().map(str::to_string).collect()).unwrap_or_default(),
        ram_alloc_mb: orphan.ram_mb.and_then(|v| i32::try_from(v).ok()),
        started_at: Utc.timestamp_opt(orphan.started_at, 0).earliest().unwrap_or_else(Utc::now),
        ended_at: Utc.timestamp_opt(ended_at, 0).earliest().unwrap_or_else(Utc::now),
    };
    let version = launcher_version.to_string();
    let report = tokio::task::spawn_blocking(move || {
        let report = crash::build_recovered(info, &version)?;
        if let Err(e) = crash::store(&report) {
            tracing::warn!("[Reprise] rapport de plantage non enregistré : {e}");
        }
        Some(report)
    })
    .await
    .ok()
    .flatten();

    if let Some(r) = &report {
        tracing::warn!("[Reprise] plantage retrouvé sur {} : {}", r.instance_id, r.title);
    }

    let conn = db.lock().await;
    if let Err(e) = db::session_end(
        &conn,
        orphan.id,
        ended_at,
        ended_at - orphan.started_at,
        "recovered",
        report.as_ref().map(|r| r.id.as_str()),
    ) {
        tracing::warn!("[Reprise] session {} non close : {e}", orphan.id);
    }
}
