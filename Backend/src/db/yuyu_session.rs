use anyhow::Result;
use rusqlite::{params, Connection};

use crate::state::YuyuSession;

// ── Session YuyuFrame, gardée localement pour la retrouver au démarrage ──────
//
// Depuis la refonte /v1 : jeton d'accès court + refresh token à rotation.
// C'est le refresh token qui porte la session ; l'ancienne colonne `jwt` le
// contient désormais (migration silencieuse : les sessions d'avant la refonte
// ne sont plus valables de toute façon, le joueur se reconnecte une fois).

pub fn save_yuyu_session(conn: &Connection, s: &YuyuSession) -> Result<()> {
    let now = chrono::Utc::now().timestamp();
    conn.execute(
        "INSERT INTO yuyu_session (id, jwt, user_id, username, plan, plan_expires_at, saved_at,
                                   refresh_token, access_token, access_expires_at, email, license,
                                   password_reset_required)
         VALUES (1, ?1, ?2, ?3, ?4, ?5, ?6, ?1, ?7, ?8, ?9, ?10, ?11)
         ON CONFLICT(id) DO UPDATE SET
             jwt                     = excluded.jwt,
             user_id                 = excluded.user_id,
             username                = excluded.username,
             plan                    = excluded.plan,
             plan_expires_at         = excluded.plan_expires_at,
             saved_at                = excluded.saved_at,
             refresh_token           = excluded.refresh_token,
             access_token            = excluded.access_token,
             access_expires_at       = excluded.access_expires_at,
             email                   = excluded.email,
             license                 = excluded.license,
             password_reset_required = excluded.password_reset_required",
        params![
            s.refresh_token,
            s.user_id,
            s.username,
            s.plan,
            s.plan_expires_at,
            now,
            s.access_token,
            s.access_expires_at,
            s.email,
            s.license,
            s.password_reset_required as i64,
        ],
    )?;
    Ok(())
}

pub fn load_yuyu_session(conn: &Connection) -> Result<Option<YuyuSession>> {
    match conn.query_row(
        "SELECT user_id, username, email, plan, plan_expires_at, password_reset_required,
                license, access_token, access_expires_at, refresh_token
         FROM yuyu_session WHERE id = 1",
        [],
        |r| {
            Ok(YuyuSession {
                user_id: r.get(0)?,
                username: r.get(1)?,
                email: r.get(2).ok(),
                plan: r.get::<_, String>(3).unwrap_or_else(|_| "free".into()),
                plan_expires_at: r.get(4)?,
                password_reset_required: r.get::<_, i64>(5).unwrap_or(0) == 1,
                license: r.get(6).ok(),
                access_token: r.get::<_, String>(7).unwrap_or_default(),
                access_expires_at: r.get::<_, i64>(8).unwrap_or(0),
                refresh_token: r.get::<_, String>(9).unwrap_or_default(),
            })
        },
    ) {
        // Une session d'avant la refonte n'a pas de refresh token : elle ne
        // vaut plus rien côté serveur, autant repartir de l'écran de
        // connexion plutôt que d'enchaîner les 401.
        Ok(row) if row.refresh_token.is_empty() => Ok(None),
        Ok(row) => Ok(Some(row)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(e.into()),
    }
}

pub fn delete_yuyu_session(conn: &Connection) -> Result<()> {
    conn.execute("DELETE FROM yuyu_session WHERE id = 1", [])?;
    Ok(())
}

/// Cache d'affichage du plan (après `GET /v1/me` ou vérification de licence).
pub fn update_yuyu_plan(conn: &Connection, plan: &str, plan_expires_at: Option<i64>) -> Result<()> {
    conn.execute(
        "UPDATE yuyu_session SET plan = ?1, plan_expires_at = ?2 WHERE id = 1",
        params![plan, plan_expires_at],
    )?;
    Ok(())
}
