use anyhow::Result;
use rusqlite::{params, Connection};

// ── JWT session YuyuFrame (stocké localement pour restauration au démarrage) ──

pub struct YuyuSessionRow {
    pub jwt: String,
    pub user_id: i64,
    pub username: String,
    pub plan: String,
    pub plan_expires_at: Option<i64>,
}

pub fn save_yuyu_jwt(
    conn: &Connection,
    user_id: i64,
    username: &str,
    jwt: &str,
    plan: &str,
    plan_expires_at: Option<i64>,
) -> Result<()> {
    let now = chrono::Utc::now().timestamp();
    conn.execute(
        "INSERT INTO yuyu_session (id, jwt, user_id, username, plan, plan_expires_at, saved_at)
         VALUES (1, ?1, ?2, ?3, ?4, ?5, ?6)
         ON CONFLICT(id) DO UPDATE SET
             jwt             = excluded.jwt,
             user_id         = excluded.user_id,
             username        = excluded.username,
             plan            = excluded.plan,
             plan_expires_at = excluded.plan_expires_at,
             saved_at        = excluded.saved_at",
        params![jwt, user_id, username, plan, plan_expires_at, now],
    )?;
    Ok(())
}

pub fn load_yuyu_jwt(conn: &Connection) -> Result<Option<YuyuSessionRow>> {
    match conn.query_row(
        "SELECT jwt, user_id, username, plan, plan_expires_at FROM yuyu_session WHERE id = 1",
        [],
        |r| Ok(YuyuSessionRow {
            jwt: r.get(0)?,
            user_id: r.get(1)?,
            username: r.get(2)?,
            plan: r.get::<_, String>(3).unwrap_or_else(|_| "free".into()),
            plan_expires_at: r.get(4)?,
        }),
    ) {
        Ok(row) => Ok(Some(row)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(e.into()),
    }
}

pub fn delete_yuyu_jwt(conn: &Connection) -> Result<()> {
    conn.execute("DELETE FROM yuyu_session WHERE id = 1", [])?;
    Ok(())
}

pub fn update_yuyu_plan(conn: &Connection, plan: &str, plan_expires_at: Option<i64>) -> Result<()> {
    conn.execute(
        "UPDATE yuyu_session SET plan = ?1, plan_expires_at = ?2 WHERE id = 1",
        params![plan, plan_expires_at],
    )?;
    Ok(())
}
