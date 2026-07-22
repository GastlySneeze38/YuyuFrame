use anyhow::Result;
use rusqlite::{params, Connection};

pub struct SessionRow {
    #[allow(dead_code)]
    pub id: i64,
    pub instance_name: String,
    pub mc_version: String,
    pub loader: String,
    pub started_at: i64,
    pub duration_secs: Option<i64>,
}

pub struct InstanceStatsRow {
    pub instance_id: String,
    pub instance_name: String,
    pub mc_version: String,
    pub loader: String,
    pub sessions: i64,
    pub total_secs: i64,
}

pub struct DailyStatsRow {
    pub date: String,
    pub secs: i64,
}

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
        "INSERT INTO play_sessions (yuyu_user_id, instance_id, instance_name, mc_version, loader, started_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![user_id, instance_id, instance_name, mc_version, loader, now],
    )?;
    Ok(conn.last_insert_rowid())
}

/// Nombre de sessions déjà enregistrées pour cette instance — 0 signifie
/// qu'un lancement en cours sera le tout premier (voir capture analytics
/// `instance_first_launch` dans commands/launch.rs).
pub fn instance_session_count(conn: &Connection, instance_id: &str) -> Result<i64> {
    conn.query_row(
        "SELECT COUNT(*) FROM play_sessions WHERE instance_id = ?1",
        [instance_id],
        |r| r.get(0),
    ).map_err(Into::into)
}

pub fn session_end(conn: &Connection, session_id: i64, duration_secs: i64) -> Result<()> {
    let now = chrono::Utc::now().timestamp();
    conn.execute(
        "UPDATE play_sessions SET ended_at = ?1, duration_secs = ?2 WHERE id = ?3",
        params![now, duration_secs, session_id],
    )?;
    Ok(())
}

pub fn stats_totals(conn: &Connection, user_id: i64) -> Result<(i64, i64)> {
    let row = conn.query_row(
        "SELECT COUNT(*), COALESCE(SUM(duration_secs), 0) FROM play_sessions
         WHERE yuyu_user_id = ?1 AND duration_secs IS NOT NULL",
        params![user_id],
        |r| Ok((r.get::<_, i64>(0)?, r.get::<_, i64>(1)?)),
    )?;
    Ok(row)
}

pub fn stats_per_instance(conn: &Connection, user_id: i64) -> Result<Vec<InstanceStatsRow>> {
    let mut stmt = conn.prepare(
        "SELECT instance_id, instance_name, mc_version, loader,
                COUNT(*) as sessions, COALESCE(SUM(duration_secs), 0) as total_secs
         FROM play_sessions
         WHERE yuyu_user_id = ?1 AND duration_secs IS NOT NULL
         GROUP BY instance_id ORDER BY total_secs DESC",
    )?;
    let rows = stmt
        .query_map(params![user_id], |r| {
            Ok(InstanceStatsRow {
                instance_id: r.get(0)?,
                instance_name: r.get(1)?,
                mc_version: r.get(2)?,
                loader: r.get(3)?,
                sessions: r.get(4)?,
                total_secs: r.get(5)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

pub fn stats_recent_sessions(conn: &Connection, user_id: i64, limit: i64) -> Result<Vec<SessionRow>> {
    let mut stmt = conn.prepare(
        "SELECT id, instance_name, mc_version, loader, started_at, duration_secs
         FROM play_sessions
         WHERE yuyu_user_id = ?1 AND duration_secs IS NOT NULL
         ORDER BY started_at DESC LIMIT ?2",
    )?;
    let rows = stmt
        .query_map(params![user_id, limit], |r| {
            Ok(SessionRow {
                id: r.get(0)?,
                instance_name: r.get(1)?,
                mc_version: r.get(2)?,
                loader: r.get(3)?,
                started_at: r.get(4)?,
                duration_secs: r.get(5)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

pub fn stats_daily(conn: &Connection, user_id: i64, since: i64) -> Result<Vec<DailyStatsRow>> {
    let mut stmt = conn.prepare(
        "SELECT date(started_at, 'unixepoch') as day, COALESCE(SUM(duration_secs), 0)
         FROM play_sessions
         WHERE yuyu_user_id = ?1 AND started_at >= ?2 AND duration_secs IS NOT NULL
         GROUP BY day ORDER BY day ASC",
    )?;
    let rows = stmt
        .query_map(params![user_id, since], |r| {
            Ok(DailyStatsRow {
                date: r.get(0)?,
                secs: r.get(1)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}
