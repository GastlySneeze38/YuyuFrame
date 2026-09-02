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
             id              TEXT    PRIMARY KEY,
             yuyu_user_id    INTEGER NOT NULL DEFAULT 0,
             name            TEXT    NOT NULL,
             mc_version      TEXT    NOT NULL,
             loader          TEXT    NOT NULL,
             ram_mb          INTEGER NOT NULL DEFAULT 4096,
             favorite        INTEGER NOT NULL DEFAULT 0,
             created_at      INTEGER NOT NULL,
             jvm_vendor      TEXT    NOT NULL DEFAULT 'auto',
             jvm_custom_path TEXT,
             gc_policy       TEXT    NOT NULL DEFAULT 'auto',
             jvm_extra_args  TEXT    NOT NULL DEFAULT '',
             jvm_args_mode   TEXT    NOT NULL DEFAULT 'append'
         );

         -- Configurations JVM réutilisables : une même config peut être reliée
         -- à plusieurs instances (c'est tout l'intérêt pour comparer deux jeux
         -- de drapeaux sur le même monde). Les drapeaux sont rangés en trois
         -- catégories parce que c'est ainsi qu'on les raisonne — le moteur
         -- (jvm/mémoire), le ramasse-miettes et le compilateur — jamais comme
         -- une seule liste plate où plus personne ne retrouve ce qu'il teste.
         CREATE TABLE IF NOT EXISTS jvm_profiles (
             id              TEXT    PRIMARY KEY,
             yuyu_user_id    INTEGER NOT NULL DEFAULT 0,
             name            TEXT    NOT NULL,
             created_at      INTEGER NOT NULL,
             ram_mb          INTEGER,
             jvm_vendor      TEXT    NOT NULL DEFAULT 'auto',
             jvm_custom_path TEXT,
             gc_policy       TEXT    NOT NULL DEFAULT 'auto',
             args_mode       TEXT    NOT NULL DEFAULT 'append',
             args_jvm        TEXT    NOT NULL DEFAULT '',
             args_gc         TEXT    NOT NULL DEFAULT '',
             args_jit        TEXT    NOT NULL DEFAULT ''
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
    // P1-6 (audit launcher, Phase 6) : vendeur JVM + policy GC par instance.
    let _ = conn.execute("ALTER TABLE instances ADD COLUMN jvm_vendor TEXT NOT NULL DEFAULT 'auto'", []);
    let _ = conn.execute("ALTER TABLE instances ADD COLUMN jvm_custom_path TEXT", []);
    let _ = conn.execute("ALTER TABLE instances ADD COLUMN gc_policy TEXT NOT NULL DEFAULT 'auto'", []);
    // Écran "Configuration JVM" : drapeaux saisis à la main + mode de fusion
    // avec ceux générés ("append" par défaut, "replace" pour ne garder que la
    // base obligatoire — voir merge_jvm_args).
    let _ = conn.execute("ALTER TABLE instances ADD COLUMN jvm_extra_args TEXT NOT NULL DEFAULT ''", []);
    let _ = conn.execute("ALTER TABLE instances ADD COLUMN jvm_args_mode TEXT NOT NULL DEFAULT 'append'", []);
    // Config JVM reliée. NULL = aucune, l'instance retombe sur ses propres
    // colonnes jvm_* ci-dessus (donc sur les drapeaux générés par défaut).
    let _ = conn.execute("ALTER TABLE instances ADD COLUMN jvm_profile_id TEXT", []);
    let _ = conn.execute("ALTER TABLE mc_sessions ADD COLUMN is_offline INTEGER NOT NULL DEFAULT 0", []);
    // Backfill pour les comptes hors ligne créés avant l'ajout de la colonne
    // ci-dessus (feature déjà là depuis 2 jours, cf. mc_add_offline) : sans
    // ça, l'ALTER TABLE les remet tous à `is_offline = 0` (perte du badge +
    // du rappel d'achat, mais rien de fonctionnel puisque le refresh token
    // reste de toute façon sauté via `expires_at` = NEVER_EXPIRES). Le
    // littéral "offline" posé par mc_add_offline comme access_token n'est
    // jamais celui d'un vrai token Microsoft, donc marqueur fiable à 100%.
    let _ = conn.execute("UPDATE mc_sessions SET is_offline = 1 WHERE access_token = 'offline' AND is_offline = 0", []);

    Ok(conn)
}
