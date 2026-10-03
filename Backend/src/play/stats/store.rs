//! Sessions de jeu : écriture pendant la partie, lecture pour les stats.
//!
//! Le principe a changé le 2026-09-21. Avant, une session n'existait pour les
//! stats qu'une fois terminée proprement, et une fin propre supposait que le
//! launcher soit encore là pour l'observer. Maintenant une session est écrite
//! au lancement, tenue en vie par un battement de cœur, et close soit par
//! l'observation directe, soit — si le launcher a disparu entre-temps — par la
//! réparation au démarrage suivant.
//!
//! Aucune agrégation ici : ce module rend des lignes, le calcul est dans
//! `crate::play::stats`. Découper une session à minuit ou compter un taux de
//! plantage en SQL donnerait des requêtes illisibles et intestables, pour un
//! volume de données qui tient de toute façon en mémoire.

use anyhow::Result;
use rusqlite::{params, Connection, OptionalExtension};

/// Une session telle qu'elle est en base. `ended_at` absent = encore en cours
/// (ou orpheline, ce que seul `last_seen_at` permet de distinguer).
#[derive(Clone, Debug)]
pub struct SessionRow {
    pub id: i64,
    pub instance_id: String,
    pub instance_name: String,
    pub mc_version: String,
    pub loader: String,
    pub started_at: i64,
    pub ended_at: Option<i64>,
    pub duration_secs: Option<i64>,
    pub last_seen_at: Option<i64>,
    pub end_reason: Option<String>,
    pub crashed: bool,
    pub crash_report_id: Option<String>,
}

const COLUMNS: &str = "id, instance_id, instance_name, mc_version, loader, started_at, ended_at, \
     duration_secs, last_seen_at, end_reason, crashed, crash_report_id";

fn row(r: &rusqlite::Row) -> rusqlite::Result<SessionRow> {
    Ok(SessionRow {
        id: r.get(0)?,
        instance_id: r.get(1)?,
        instance_name: r.get(2)?,
        mc_version: r.get(3)?,
        loader: r.get(4)?,
        started_at: r.get(5)?,
        ended_at: r.get(6)?,
        duration_secs: r.get(7)?,
        last_seen_at: r.get(8)?,
        end_reason: r.get(9)?,
        crashed: r.get::<_, i64>(10)? != 0,
        crash_report_id: r.get(11)?,
    })
}

// ── Écriture pendant la partie ───────────────────────────────────────────────

/// Ouvre une session. `end_reason = 'running'` dès la première seconde : une
/// ligne sans raison est une session d'avant la refonte, pas une partie en
/// cours, et les deux ne se lisent pas pareil.
pub fn session_start(
    conn: &Connection,
    user_id: i64,
    instance_id: &str,
    instance_name: &str,
    mc_version: &str,
    loader: &str,
) -> Result<i64> {
    let now = chrono::Utc::now().timestamp();
    conn.execute(
        "INSERT INTO play_sessions (yuyu_user_id, instance_id, instance_name, mc_version, loader, started_at, last_seen_at, end_reason)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?6, 'running')",
        params![user_id, instance_id, instance_name, mc_version, loader, now],
    )?;
    Ok(conn.last_insert_rowid())
}

/// Complète la session avec ce que seul le lancement connaît : le processus à
/// surveiller et la configuration JVM réellement appliquée. Ces deux-là sont
/// ce qui permet de reconstruire un rapport de plantage quand le launcher
/// n'était plus là pour le faire lui-même.
pub fn session_attach_launch(
    conn: &Connection,
    session_id: i64,
    pid: Option<u32>,
    java_version: &str,
    jvm_args: &str,
    ram_mb: u32,
) -> Result<()> {
    conn.execute(
        "UPDATE play_sessions SET pid = ?2, java_version = ?3, jvm_args = ?4, ram_mb = ?5 WHERE id = ?1",
        params![session_id, pid.map(i64::from), java_version, jvm_args, ram_mb],
    )?;
    Ok(())
}

/// Battement de cœur. Une ligne, un entier : c'est volontairement la plus
/// petite écriture possible, parce qu'elle revient toutes les trente secondes
/// pour chaque partie en cours.
pub fn session_heartbeat(conn: &Connection, session_id: i64) -> Result<()> {
    conn.execute(
        "UPDATE play_sessions SET last_seen_at = ?2 WHERE id = ?1",
        params![session_id, chrono::Utc::now().timestamp()],
    )?;
    Ok(())
}

/// Ferme la session. `reason` : `normal` quand la fin a été observée,
/// `recovered` quand elle a été déduite au démarrage suivant.
pub fn session_end(
    conn: &Connection,
    session_id: i64,
    ended_at: i64,
    duration_secs: i64,
    reason: &str,
    crash_report_id: Option<&str>,
) -> Result<()> {
    conn.execute(
        "UPDATE play_sessions SET ended_at = ?2, duration_secs = ?3, end_reason = ?4, \
            crashed = ?5, crash_report_id = ?6 WHERE id = ?1",
        params![
            session_id,
            ended_at,
            duration_secs.max(0),
            reason,
            crash_report_id.is_some() as i64,
            crash_report_id,
        ],
    )?;
    Ok(())
}

/// Ferme la session seulement si elle est encore ouverte.
///
/// Le filet de `play::launch` : la fin est normalement écrite par
/// l'orchestrateur, qui seul sait s'il y a eu plantage. Mais un lancement peut
/// échouer avant même que la JVM démarre, et la session doit se fermer quand
/// même. La condition `duration_secs IS NULL` garantit qu'on n'écrase jamais
/// une fin déjà écrite, plus précise que celle-ci.
pub fn session_end_if_open(conn: &Connection, session_id: i64, ended_at: i64, duration_secs: i64) -> Result<()> {
    conn.execute(
        "UPDATE play_sessions SET ended_at = ?2, duration_secs = ?3, end_reason = 'normal' \
         WHERE id = ?1 AND duration_secs IS NULL",
        params![session_id, ended_at, duration_secs.max(0)],
    )?;
    Ok(())
}

/// Nombre de sessions déjà enregistrées pour cette instance — 0 signifie
/// qu'un lancement en cours sera le tout premier (voir capture analytics
/// `instance_first_launch` dans play/launch.rs).
pub fn instance_session_count(conn: &Connection, instance_id: &str) -> Result<i64> {
    conn.query_row("SELECT COUNT(*) FROM play_sessions WHERE instance_id = ?1", [instance_id], |r| r.get(0))
        .map_err(Into::into)
}

// ── Réparation au démarrage ──────────────────────────────────────────────────

/// Ce qu'il faut savoir d'une session restée ouverte pour décider quoi en
/// faire : est-elle encore en train de tourner, et si non, quand s'est-elle
/// arrêtée ?
#[derive(Clone, Debug)]
pub struct Orphan {
    pub id: i64,
    pub instance_id: String,
    pub instance_name: String,
    pub mc_version: String,
    pub loader: String,
    pub started_at: i64,
    pub last_seen_at: Option<i64>,
    pub pid: Option<i64>,
    pub java_version: Option<String>,
    pub jvm_args: Option<String>,
    pub ram_mb: Option<i64>,
}

/// Les sessions laissées ouvertes par un launcher qui n'est plus là.
pub fn orphan_sessions(conn: &Connection) -> Result<Vec<Orphan>> {
    let mut stmt = conn.prepare(
        "SELECT id, instance_id, instance_name, mc_version, loader, started_at, last_seen_at, pid, java_version, jvm_args, ram_mb
         FROM play_sessions WHERE duration_secs IS NULL ORDER BY started_at",
    )?;
    let rows = stmt
        .query_map([], |r| {
            Ok(Orphan {
                id: r.get(0)?,
                instance_id: r.get(1)?,
                instance_name: r.get(2)?,
                mc_version: r.get(3)?,
                loader: r.get(4)?,
                started_at: r.get(5)?,
                last_seen_at: r.get(6)?,
                pid: r.get(7)?,
                java_version: r.get(8)?,
                jvm_args: r.get(9)?,
                ram_mb: r.get(10)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

// ── Lecture ──────────────────────────────────────────────────────────────────

/// Les sessions qui touchent la plage `[from, to]`, en cours comprises.
///
/// Le chevauchement est calculé sur l'intervalle, pas sur la seule date de
/// début : une partie commencée avant la plage mais qui déborde dedans en
/// fait partie, et c'est exactement le cas d'une soirée à cheval sur minuit.
/// Les sessions sont rendues telles quelles ; le découpage par jour se fait
/// dans `crate::play::stats`, qui connaît le fuseau de la personne.
pub fn sessions_in_range(conn: &Connection, from: i64, to: i64) -> Result<Vec<SessionRow>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT {COLUMNS} FROM play_sessions \
         WHERE started_at <= ?2 AND COALESCE(ended_at, last_seen_at, started_at) >= ?1 \
         ORDER BY started_at DESC"
    ))?;
    let rows = stmt.query_map(params![from, to], row)?.collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

/// La toute première session enregistrée — sert à proposer « depuis le début »
/// sans balayer la table.
pub fn first_session_at(conn: &Connection) -> Result<Option<i64>> {
    conn.query_row("SELECT MIN(started_at) FROM play_sessions", [], |r| r.get::<_, Option<i64>>(0))
        .optional()
        .map(Option::flatten)
        .map_err(Into::into)
}

/// Efface tout l'historique de jeu. Les stats sont locales : les effacer ne
/// regarde que la personne qui le demande.
pub fn sessions_clear(conn: &Connection) -> Result<usize> {
    Ok(conn.execute("DELETE FROM play_sessions", [])?)
}
