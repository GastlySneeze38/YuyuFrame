use anyhow::Result;
use rusqlite::Connection;
use std::path::Path;

pub fn init_db(path: &Path) -> Result<Connection> {
    let conn = Connection::open(path)?;
    tracing::info!("Base de données : {}", path.display());

    conn.execute_batch(
        "PRAGMA foreign_keys = ON;

         CREATE TABLE IF NOT EXISTS mc_sessions (
             id               INTEGER PRIMARY KEY AUTOINCREMENT,
             yuyu_user_id     INTEGER NOT NULL,
             mc_username      TEXT    NOT NULL,
             mc_uuid          TEXT    NOT NULL,
             access_token     TEXT    NOT NULL,
              ms_refresh_token TEXT    NOT NULL,
              expires_at       INTEGER NOT NULL,
              is_offline       INTEGER NOT NULL DEFAULT 0,
              updated_at       INTEGER NOT NULL,
              UNIQUE(yuyu_user_id, mc_uuid)
         );

         CREATE TABLE IF NOT EXISTS active_mc (
             yuyu_user_id INTEGER PRIMARY KEY,
             mc_uuid      TEXT    NOT NULL
         );

         CREATE TABLE IF NOT EXISTS yuyu_session (
             id              INTEGER PRIMARY KEY CHECK (id = 1),
             jwt             TEXT    NOT NULL,
             user_id         INTEGER NOT NULL,
             username        TEXT    NOT NULL,
             plan            TEXT    NOT NULL DEFAULT 'free',
             plan_expires_at INTEGER,
             saved_at        INTEGER NOT NULL
         );

         CREATE TABLE IF NOT EXISTS instances (
             id           TEXT    PRIMARY KEY,
             yuyu_user_id INTEGER NOT NULL DEFAULT 0,
             name         TEXT    NOT NULL,
             mc_version   TEXT    NOT NULL,
             loader       TEXT    NOT NULL,
             ram_mb       INTEGER NOT NULL DEFAULT 4096,
             favorite     INTEGER NOT NULL DEFAULT 0,
             created_at   INTEGER NOT NULL
         );

         CREATE TABLE IF NOT EXISTS play_sessions (
             id            INTEGER PRIMARY KEY AUTOINCREMENT,
             yuyu_user_id  INTEGER NOT NULL,
             instance_id   TEXT    NOT NULL,
             instance_name TEXT    NOT NULL,
             mc_version    TEXT    NOT NULL,
             loader        TEXT    NOT NULL,
             started_at    INTEGER NOT NULL,
             ended_at      INTEGER,
             duration_secs INTEGER
         );",
    )?;

    // Migrations pour les DBs existantes
    let _ = conn.execute("ALTER TABLE instances ADD COLUMN yuyu_user_id INTEGER NOT NULL DEFAULT 0", []);
    let _ = conn.execute("ALTER TABLE yuyu_session ADD COLUMN plan TEXT NOT NULL DEFAULT 'free'", []);
    let _ = conn.execute("ALTER TABLE yuyu_session ADD COLUMN plan_expires_at INTEGER", []);
    let _ = conn.execute("ALTER TABLE instances ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0", []);
    let _ = conn.execute("ALTER TABLE instances ADD COLUMN description TEXT NOT NULL DEFAULT ''", []);
    let _ = conn.execute("ALTER TABLE mc_sessions ADD COLUMN is_offline INTEGER NOT NULL DEFAULT 0", []);

    Ok(conn)
}
