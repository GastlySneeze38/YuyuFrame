use anyhow::Result;
use rusqlite::{params, Connection};

#[derive(Clone)]
pub struct McSessionRow {
    pub mc_username: String,
    pub mc_uuid: String,
    pub access_token: String,
    pub ms_refresh_token: String,
    pub expires_at: i64,
    pub is_offline: bool,
}

pub fn upsert_mc_session(
    conn: &Connection,
    yuyu_user_id: i64,
    mc_username: &str,
    mc_uuid: &str,
    access_token: &str,
    ms_refresh_token: &str,
    expires_at: i64,
    is_offline: bool,
) -> Result<()> {
    let now = chrono::Utc::now().timestamp();
    conn.execute(
        "INSERT INTO mc_sessions
             (yuyu_user_id, mc_username, mc_uuid, access_token, ms_refresh_token, expires_at, is_offline, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
         ON CONFLICT(yuyu_user_id, mc_uuid) DO UPDATE SET
             mc_username      = excluded.mc_username,
             access_token     = excluded.access_token,
             ms_refresh_token = excluded.ms_refresh_token,
             expires_at       = excluded.expires_at,
             is_offline       = excluded.is_offline,
             updated_at       = excluded.updated_at",
        params![yuyu_user_id, mc_username, mc_uuid, access_token, ms_refresh_token, expires_at, is_offline as i32, now],
    )?;
    Ok(())
}

pub fn list_mc_sessions(conn: &Connection, yuyu_user_id: i64) -> Result<Vec<McSessionRow>> {
    let mut stmt = conn.prepare(
        "SELECT mc_username, mc_uuid, access_token, ms_refresh_token, expires_at, is_offline
         FROM mc_sessions WHERE yuyu_user_id = ?1",
    )?;
    let rows = stmt
        .query_map(params![yuyu_user_id], |r| {
            Ok(McSessionRow {
                mc_username: r.get(0)?,
                mc_uuid: r.get(1)?,
                access_token: r.get(2)?,
                ms_refresh_token: r.get(3)?,
                expires_at: r.get(4)?,
                is_offline: r.get::<_, i32>(5)? != 0,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

pub fn get_mc_session(
    conn: &Connection,
    yuyu_user_id: i64,
    mc_uuid: &str,
) -> Result<Option<McSessionRow>> {
    let mut stmt = conn.prepare(
        "SELECT mc_username, mc_uuid, access_token, ms_refresh_token, expires_at, is_offline
         FROM mc_sessions WHERE yuyu_user_id = ?1 AND mc_uuid = ?2",
    )?;
    match stmt.query_row(params![yuyu_user_id, mc_uuid], |r| {
        Ok(McSessionRow {
            mc_username: r.get(0)?,
            mc_uuid: r.get(1)?,
            access_token: r.get(2)?,
            ms_refresh_token: r.get(3)?,
            expires_at: r.get(4)?,
            is_offline: r.get::<_, i32>(5)? != 0,
        })
    }) {
        Ok(r) => Ok(Some(r)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(e.into()),
    }
}

pub fn delete_mc_session(conn: &Connection, yuyu_user_id: i64, mc_uuid: &str) -> Result<()> {
    conn.execute(
        "DELETE FROM mc_sessions WHERE yuyu_user_id = ?1 AND mc_uuid = ?2",
        params![yuyu_user_id, mc_uuid],
    )?;
    Ok(())
}

pub fn update_mc_tokens(
    conn: &Connection,
    yuyu_user_id: i64,
    mc_uuid: &str,
    access_token: &str,
    ms_refresh_token: &str,
    expires_at: i64,
) -> Result<()> {
    let now = chrono::Utc::now().timestamp();
    conn.execute(
        "UPDATE mc_sessions
         SET access_token=?1, ms_refresh_token=?2, expires_at=?3, updated_at=?4
         WHERE yuyu_user_id=?5 AND mc_uuid=?6",
        params![access_token, ms_refresh_token, expires_at, now, yuyu_user_id, mc_uuid],
    )?;
    Ok(())
}

// ── Active MC session ──────────────────────────────────────────────────────────

pub fn set_active_mc(conn: &Connection, yuyu_user_id: i64, mc_uuid: &str) -> Result<()> {
    conn.execute(
        "INSERT INTO active_mc (yuyu_user_id, mc_uuid) VALUES (?1, ?2)
         ON CONFLICT(yuyu_user_id) DO UPDATE SET mc_uuid = excluded.mc_uuid",
        params![yuyu_user_id, mc_uuid],
    )?;
    Ok(())
}

pub fn get_active_mc_uuid(conn: &Connection, yuyu_user_id: i64) -> Result<Option<String>> {
    match conn.query_row(
        "SELECT mc_uuid FROM active_mc WHERE yuyu_user_id = ?1",
        params![yuyu_user_id],
        |r| r.get(0),
    ) {
        Ok(uuid) => Ok(Some(uuid)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(e.into()),
    }
}

pub fn clear_active_mc(conn: &Connection, yuyu_user_id: i64, mc_uuid: &str) -> Result<()> {
    conn.execute(
        "DELETE FROM active_mc WHERE yuyu_user_id = ?1 AND mc_uuid = ?2",
        params![yuyu_user_id, mc_uuid],
    )?;
    Ok(())
}
