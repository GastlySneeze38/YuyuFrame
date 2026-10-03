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
             jvm_args_mode   TEXT    NOT NULL DEFAULT 'append',
             icon            TEXT    NOT NULL DEFAULT '',
             loader_version  TEXT    NOT NULL DEFAULT '',
             window_custom     INTEGER NOT NULL DEFAULT 0,
             window_fullscreen INTEGER NOT NULL DEFAULT 0,
             window_width      INTEGER NOT NULL DEFAULT 854,
             window_height     INTEGER NOT NULL DEFAULT 480
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

         -- Historique des skins portés par un compte.
         --
         -- Il est à NOUS, et il ne peut pas être autrement : Mojang ne sert que
         -- le skin actuel d'un profil, jamais les précédents (l'historique des
         -- noms lui-même a été retiré de leur API en 2022). Les sites qui
         -- affichent d'anciens skins les ont collectés par sondage pendant des
         -- années. Donc un seul mécanisme, le même pour un compte Microsoft et
         -- pour un compte hors ligne : on enregistre ce qui a été appliqué.
         --
         -- `kind` : 'url' (skin hébergé ailleurs, repartageable) ou 'local'
         -- (PNG rangé sur CE PC, réservé aux comptes hors ligne — voir
         -- l'avertissement affiché à l'import). `source` est l'URL ou le
         -- chemin relatif du fichier selon le cas.
         CREATE TABLE IF NOT EXISTS skin_history (
             id            INTEGER PRIMARY KEY AUTOINCREMENT,
             mc_uuid       TEXT    NOT NULL,
             kind          TEXT    NOT NULL,
             source        TEXT    NOT NULL,
             variant       TEXT    NOT NULL,
             origin        TEXT    NOT NULL,
             first_seen_at INTEGER NOT NULL,
             last_used_at  INTEGER NOT NULL,
             UNIQUE(mc_uuid, source, variant)
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
    // mot de passe provisoire (voir crate::server). Les sessions d'avant n'ont
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
    // Icône de l'instance, en data URI (`data:image/png;base64,…`), vide pour
    // l'icône par défaut. Les octets sont en base plutôt que sur le disque
    // pour que la liste des instances les ait déjà : un fichier par instance
    // voudrait dire une lecture par carte à chaque affichage, et un chemin à
    // réparer dès que l'image d'origine est déplacée. La contrepartie est une
    // taille bornée à l'import (voir `instances::icon`).
    let _ = conn.execute("ALTER TABLE instances ADD COLUMN icon TEXT NOT NULL DEFAULT ''", []);
    // Version du loader épinglée par l'utilisateur. Vide = « la plus récente
    // compatible », c'est-à-dire exactement ce que le launcher a toujours fait
    // et ce qu'il continue de faire par défaut : la colonne n'ajoute un
    // comportement que lorsqu'elle est renseignée.
    let _ = conn.execute("ALTER TABLE instances ADD COLUMN loader_version TEXT NOT NULL DEFAULT ''", []);
    // Fenêtre de jeu par instance. `window_custom` à 0 = le launcher ne touche
    // à rien, ce qu'il a toujours fait : les trois autres colonnes n'ont alors
    // aucun effet, et surtout le `fullscreen` d'`options.txt` reste celui que
    // le joueur a choisi en jeu. 854×480 est la taille par défaut de Minecraft.
    let _ = conn.execute("ALTER TABLE instances ADD COLUMN window_custom INTEGER NOT NULL DEFAULT 0", []);
    let _ = conn.execute("ALTER TABLE instances ADD COLUMN window_fullscreen INTEGER NOT NULL DEFAULT 0", []);
    let _ = conn.execute("ALTER TABLE instances ADD COLUMN window_width INTEGER NOT NULL DEFAULT 854", []);
    let _ = conn.execute("ALTER TABLE instances ADD COLUMN window_height INTEGER NOT NULL DEFAULT 480", []);
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
    // ── Skin : une référence, jamais un fichier ─────────────────────────────
    // Le skin d'un compte est désormais une URL publique + son modèle, pas un
    // PNG rangé chez nous. La raison est simple : un skin doit pouvoir être vu
    // par les AUTRES joueurs, ce qui demande qu'il soit hébergé quelque part.
    // Nous n'avons pas de stockage pour ça, alors on ne stocke rien et on ne
    // manipule que des skins qui sont déjà hébergés — ceux de Mojang (skin
    // d'un compte premium, servi par textures.minecraft.net) ou une URL
    // fournie par l'utilisateur.
    //
    // `skin_origin` retient d'où la référence vient (`player:Notch`, `url`)
    // pour pouvoir le réafficher ; c'est du confort, pas une donnée dont le
    // comportement dépend.
    //
    // Les anciens PNG de %APPDATA%\YuyuFrame\skins\<uuid>.png ne sont pas
    // convertis — ils n'ont pas d'URL, c'est précisément ce qui leur manque.
    // Ils restent sur le disque sans être lus : on n'efface pas les fichiers
    // de quelqu'un sans qu'il le demande.
    let _ = conn.execute("ALTER TABLE mc_sessions ADD COLUMN skin_url TEXT", []);
    let _ = conn.execute("ALTER TABLE mc_sessions ADD COLUMN skin_variant TEXT", []);
    let _ = conn.execute("ALTER TABLE mc_sessions ADD COLUMN skin_origin TEXT", []);
    // `url` (hébergé ailleurs) ou `local` (PNG importé, rangé sur ce PC).
    //
    // Le cas `local` a été rouvert le 2026-09-30, mais il ne concerne que les
    // comptes hors ligne : pour un compte Microsoft, un fichier importé est
    // ENVOYÉ à Mojang, qui l'héberge et nous rend une URL — la référence
    // enregistrée redevient donc un `url`, partageable. C'est seulement pour un
    // compte hors ligne qu'il n'y a personne à qui l'envoyer, et l'interface
    // avertit alors que le skin ne vit que dans cette base.
    let _ = conn.execute("ALTER TABLE mc_sessions ADD COLUMN skin_kind TEXT", []);

    // Comptes Minecraft rattachés au PC plutôt qu'au compte YuyuFrame.
    crate::account::minecraft::store::migrate_to_pc_scope(&conn)?;

    // Préférences dont le backend a besoin pour décider seul (voir prefs.rs).
    super::prefs::init(&conn)?;

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
