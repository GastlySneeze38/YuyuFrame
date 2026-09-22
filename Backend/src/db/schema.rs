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
             args_jit        TEXT    NOT NULL DEFAULT '',
             base_presets    TEXT    NOT NULL DEFAULT ''
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
    // Refonte LauncherAPI /v1 : jeton d'accès de 15 min + refresh token à
    // rotation, e-mail du compte, licence signée vérifiable hors ligne et
    // mot de passe provisoire (voir crate::api). Les sessions d'avant n'ont
    // pas de refresh token : `load_yuyu_session` les ignore.
    let _ = conn.execute("ALTER TABLE yuyu_session ADD COLUMN refresh_token TEXT NOT NULL DEFAULT ''", []);
    let _ = conn.execute("ALTER TABLE yuyu_session ADD COLUMN access_token TEXT NOT NULL DEFAULT ''", []);
    let _ = conn.execute("ALTER TABLE yuyu_session ADD COLUMN access_expires_at INTEGER NOT NULL DEFAULT 0", []);
    let _ = conn.execute("ALTER TABLE yuyu_session ADD COLUMN email TEXT", []);
    let _ = conn.execute("ALTER TABLE yuyu_session ADD COLUMN license TEXT", []);
    let _ = conn.execute("ALTER TABLE yuyu_session ADD COLUMN password_reset_required INTEGER NOT NULL DEFAULT 0", []);
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
    // Jeu de drapeaux dont chaque catégorie est issue, en JSON
    // (`{"gc":"gc-brucethemoose"}`) : purement informatif côté launcher, c'est
    // l'interface qui s'en sert pour montrer les écarts introduits depuis.
    let _ = conn.execute("ALTER TABLE jvm_profiles ADD COLUMN base_presets TEXT NOT NULL DEFAULT ''", []);
    let _ = conn.execute("ALTER TABLE mc_sessions ADD COLUMN is_offline INTEGER NOT NULL DEFAULT 0", []);
    // Backfill pour les comptes hors ligne créés avant l'ajout de la colonne
    // ci-dessus (feature déjà là depuis 2 jours, cf. mc_add_offline) : sans
    // ça, l'ALTER TABLE les remet tous à `is_offline = 0` (perte du badge +
    // du rappel d'achat, mais rien de fonctionnel puisque le refresh token
    // reste de toute façon sauté via `expires_at` = NEVER_EXPIRES). Le
    // littéral "offline" posé par mc_add_offline comme access_token n'est
    // jamais celui d'un vrai token Microsoft, donc marqueur fiable à 100%.
    let _ = conn.execute("UPDATE mc_sessions SET is_offline = 1 WHERE access_token = 'offline' AND is_offline = 0", []);
    // Comptes Minecraft rattachés au PC plutôt qu'au compte YuyuFrame.
    super::mc_account::migrate_to_pc_scope(&conn)?;

    // Réglages de sauvegarde : en base parce que les déclenchements
    // automatiques ont lieu côté Rust (voir backup::settings).
    crate::backup::settings::init(&conn)?;

    // ── Sessions de jeu : survivre à la fermeture du launcher ───────────────
    // Jusqu'ici la durée n'était écrite qu'à la fin de la partie, par une
    // tâche vivant DANS le launcher. Fermer le launcher pendant qu'on joue
    // laissait `duration_secs` à NULL, et comme toutes les requêtes de stats
    // filtrent dessus, la session n'était pas mal comptée : elle disparaissait.
    //
    // `last_seen_at` est le battement de cœur écrit pendant la partie : au
    // démarrage suivant, une session restée ouverte est close à sa dernière
    // trace de vie. On perd au pire l'intervalle du battement, jamais tout.
    let _ = conn.execute("ALTER TABLE play_sessions ADD COLUMN last_seen_at INTEGER", []);
    // Identifiant du processus Java, et l'instant où il a démarré. Les deux
    // ensemble : un PID seul est réattribué par Windows, et on retomberait
    // sur un processus sans rapport.
    let _ = conn.execute("ALTER TABLE play_sessions ADD COLUMN pid INTEGER", []);
    // normal : fin observée · recovered : réparée au démarrage suivant ·
    // running : partie en cours.
    let _ = conn.execute("ALTER TABLE play_sessions ADD COLUMN end_reason TEXT", []);
    let _ = conn.execute("ALTER TABLE play_sessions ADD COLUMN crashed INTEGER NOT NULL DEFAULT 0", []);
    let _ = conn.execute("ALTER TABLE play_sessions ADD COLUMN crash_report_id TEXT", []);
    // Recopiés du lancement : ils permettent de reconstruire un rapport de
    // plantage même quand le launcher n'était plus là pour les capturer.
    let _ = conn.execute("ALTER TABLE play_sessions ADD COLUMN java_version TEXT", []);
    let _ = conn.execute("ALTER TABLE play_sessions ADD COLUMN jvm_args TEXT", []);
    let _ = conn.execute("ALTER TABLE play_sessions ADD COLUMN ram_mb INTEGER", []);
    // Les sessions d'avant cette migration ont une durée mais pas de raison :
    // sans ça elles apparaîtraient comme « en cours » dans les nouveaux écrans.
    let _ = conn.execute("UPDATE play_sessions SET end_reason = 'normal' WHERE end_reason IS NULL AND duration_secs IS NOT NULL", []);
    // Toutes les lectures de stats partent d'une plage de dates.
    let _ = conn.execute("CREATE INDEX IF NOT EXISTS idx_play_sessions_started ON play_sessions (started_at)", []);

    Ok(conn)
}
