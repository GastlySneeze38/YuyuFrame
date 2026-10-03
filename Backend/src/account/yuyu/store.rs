use anyhow::Result;
use rusqlite::{params, Connection};

use crate::state::YuyuSession;

// ── Session YuyuFrame, gardée localement pour la retrouver au démarrage ──────
//
// Depuis la refonte /v1 : jeton d'accès court + refresh token à rotation.
// C'est le refresh token qui porte la session.
//
// Les jetons ne sont plus dans cette table (2026-10-03). Le refresh token vit
// dans le coffre du système (`crate::security::vault`) ; le jeton d'accès n'est plus
// gardé du tout — il dure 15 minutes, le launcher en redemande un au
// démarrage. La table ne garde que de quoi afficher le compte (pseudo, plan,
// licence). Les colonnes `jwt` / `refresh_token` / `access_token` restent,
// vides : seul un système sans coffre y range encore le refresh token, faute
// de mieux, pour que la session survive quand même au redémarrage.

/// Vide les colonnes de jetons, et fait en sorte que SQLite n'en garde pas la
/// trace : `secure_delete` écrase l'espace libéré au lieu de le laisser tel
/// quel dans le fichier, et le journal (WAL) — qui contient encore l'ancienne
/// page — est replié puis tronqué.
fn wipe_tokens(conn: &Connection) -> Result<()> {
    conn.execute_batch(
        "PRAGMA secure_delete = ON;
         UPDATE yuyu_session SET jwt = '', refresh_token = '', access_token = '', access_expires_at = 0 WHERE id = 1;",
    )?;
    // Sans journal WAL, il n'y a rien à replier : l'échec n'en est pas un.
    let _ = conn.query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |_| Ok(()));
    Ok(())
}

pub fn save_yuyu_session(conn: &Connection, s: &YuyuSession) -> Result<()> {
    let now = chrono::Utc::now().timestamp();
    // Dans le coffre si possible ; sinon dans la table, comme avant.
    let in_vault = crate::security::vault::store(s.user_id, &s.refresh_token);
    let kept_here = if in_vault { "" } else { s.refresh_token.as_str() };
    conn.execute_batch("PRAGMA secure_delete = ON;")?;
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
            kept_here,
            s.user_id,
            s.username,
            s.plan,
            s.plan_expires_at,
            now,
            // Le jeton d'accès n'est jamais écrit : voir l'en-tête.
            "",
            0_i64,
            s.email,
            s.license,
            s.password_reset_required as i64,
        ],
    )?;
    Ok(())
}

pub fn load_yuyu_session(conn: &Connection) -> Result<Option<YuyuSession>> {
    let row = match conn.query_row(
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
        Ok(row) => row,
        Err(rusqlite::Error::QueryReturnedNoRows) => return Ok(None),
        Err(e) => return Err(e.into()),
    };

    let in_table = !row.refresh_token.is_empty() || !row.access_token.is_empty();
    let refresh_token = match crate::security::vault::load(row.user_id) {
        // Cas normal. Un reste dans la table (écriture de repli d'un jour où
        // le coffre ne répondait pas) est effacé au passage.
        Some(token) => {
            if in_table {
                wipe_tokens(conn)?;
            }
            token
        }
        // Session d'avant le coffre : le jeton est encore dans la table. On
        // le déménage, et on ne l'efface d'ici qu'une fois rangé là-bas —
        // sans coffre, il reste où il est et la session continue.
        None if !row.refresh_token.is_empty() => {
            if crate::security::vault::store(row.user_id, &row.refresh_token) {
                wipe_tokens(conn)?;
                tracing::info!("refresh token déménagé de la base vers le coffre du système");
            }
            row.refresh_token
        }
        // Ni dans le coffre, ni dans la table : session d'avant la refonte
        // /v1, entrée du coffre supprimée, ou base copiée depuis une autre
        // machine. Elle ne vaut rien : retour à l'écran de connexion plutôt
        // que d'enchaîner les 401.
        None => return Ok(None),
    };

    // Jamais de jeton d'accès repris du disque : le premier appel en demande
    // un neuf, ce qui fait aussi tourner le refresh token.
    Ok(Some(YuyuSession { refresh_token, access_token: String::new(), access_expires_at: 0, ..row }))
}

pub fn delete_yuyu_session(conn: &Connection) -> Result<()> {
    crate::security::vault::clear();
    conn.execute_batch("PRAGMA secure_delete = ON;")?;
    conn.execute("DELETE FROM yuyu_session WHERE id = 1", [])?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    // En test, `crate::security::vault` voit un système sans coffre : c'est le repli
    // sur la table qui est exercé ici, jamais le coffre de la machine.

    fn db(name: &str) -> (Connection, std::path::PathBuf) {
        let path = std::env::temp_dir().join(format!("yuyu-session-{name}-{}.db", uuid::Uuid::new_v4()));
        (crate::db::init_db(&path).unwrap(), path)
    }

    fn session() -> YuyuSession {
        YuyuSession {
            user_id: 7,
            username: "Steve".into(),
            email: Some("steve@exemple.fr".into()),
            plan: "premium".into(),
            plan_expires_at: None,
            password_reset_required: false,
            license: None,
            access_token: "jeton-d-acces".into(),
            access_expires_at: 4_000_000_000,
            refresh_token: "yfr_secret".into(),
        }
    }

    fn column(conn: &Connection, name: &str) -> String {
        conn.query_row(&format!("SELECT {name} FROM yuyu_session WHERE id = 1"), [], |r| r.get(0)).unwrap()
    }

    #[test]
    fn le_jeton_d_acces_n_est_jamais_ecrit() {
        let (conn, path) = db("acces");
        save_yuyu_session(&conn, &session()).unwrap();
        assert_eq!(column(&conn, "access_token"), "");
        let loaded = load_yuyu_session(&conn).unwrap().unwrap();
        assert_eq!(loaded.access_token, "");
        assert_eq!(loaded.access_expires_at, 0, "donc rafraîchi au premier appel");
        drop(conn);
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn sans_coffre_la_session_survit_dans_la_table() {
        let (conn, path) = db("repli");
        save_yuyu_session(&conn, &session()).unwrap();
        let loaded = load_yuyu_session(&conn).unwrap().unwrap();
        assert_eq!(loaded.refresh_token, "yfr_secret");
        assert_eq!((loaded.user_id, loaded.username.as_str(), loaded.plan.as_str()), (7, "Steve", "premium"));
        drop(conn);
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn une_ligne_sans_jeton_n_est_pas_une_session() {
        let (conn, path) = db("vide");
        save_yuyu_session(&conn, &session()).unwrap();
        wipe_tokens(&conn).unwrap();
        assert_eq!(column(&conn, "refresh_token"), "");
        assert_eq!(column(&conn, "jwt"), "");
        assert!(load_yuyu_session(&conn).unwrap().is_none(), "ni coffre ni table : écran de connexion");
        delete_yuyu_session(&conn).unwrap();
        assert!(load_yuyu_session(&conn).unwrap().is_none());
        drop(conn);
        let _ = std::fs::remove_file(path);
    }
}

/// Cache d'affichage du plan (après `GET /v1/me` ou vérification de licence).
pub fn update_yuyu_plan(conn: &Connection, plan: &str, plan_expires_at: Option<i64>) -> Result<()> {
    conn.execute(
        "UPDATE yuyu_session SET plan = ?1, plan_expires_at = ?2 WHERE id = 1",
        params![plan, plan_expires_at],
    )?;
    Ok(())
}
