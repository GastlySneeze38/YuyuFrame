//! Réglages de sauvegarde.
//!
//! En base et pas dans le magasin du frontend, parce que les déclenchements
//! automatiques ont lieu dans le Rust : avant un lancement, et une fois par
//! jour. Un réglage que seule l'interface connaîtrait serait invisible au
//! moment où il sert.
//!
//! Deux niveaux : une ligne globale (`instance_id = ''`) qui sert de défaut,
//! et une ligne par instance qui la remplace. Une instance qu'on n'a jamais
//! réglée suit donc le réglage général, y compris quand on le change ensuite.

use anyhow::Result;
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};

/// Clé de la ligne globale. Une chaîne vide plutôt qu'une table à part : les
/// deux niveaux ont exactement les mêmes champs, et les séparer obligerait à
/// écrire deux fois chaque lecture.
pub const GLOBAL: &str = "";

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct BackupSettings {
    /// Rien n'est sauvegardé quand c'est faux, déclenchements compris.
    pub enabled: bool,
    /// Les mondes. C'est la raison d'être de la fonctionnalité.
    pub include_worlds: bool,
    pub include_configs: bool,
    /// Lourd : un dossier de mods pèse souvent plus que les mondes. Utile
    /// quand même pour revenir à une instance qui démarrait.
    pub include_mods: bool,
    /// Instantané juste avant de démarrer le jeu.
    pub on_launch: bool,
    /// Une fois par jour, au démarrage du launcher si l'échéance est passée.
    pub daily: bool,
    /// Sauvegardes gardées par instance. `0` = pas de limite.
    pub keep: u32,
    /// Pousser aussi sur nos serveurs (abonnés) : protège d'un disque mort,
    /// là où le local ne protège que d'une fausse manœuvre.
    pub cloud: bool,
}

impl Default for BackupSettings {
    fn default() -> Self {
        Self {
            enabled: true,
            // Les mondes seuls par défaut : c'est ce qui est irremplaçable, et
            // sauvegarder les mods d'office remplirait le disque sans qu'on
            // l'ait demandé.
            include_worlds: true,
            include_configs: false,
            include_mods: false,
            on_launch: true,
            daily: false,
            keep: 10,
            cloud: false,
        }
    }
}

impl BackupSettings {
    /// Dossiers d'instance à prendre, dans l'ordre où on les lit.
    pub fn included_dirs(&self) -> Vec<String> {
        if !self.enabled {
            return Vec::new();
        }
        let mut dirs = Vec::new();
        if self.include_worlds {
            dirs.push("saves".to_string());
        }
        if self.include_configs {
            dirs.push("config".to_string());
        }
        if self.include_mods {
            dirs.push("mods".to_string());
        }
        dirs
    }
}

pub fn init(conn: &Connection) -> Result<()> {
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS backup_settings (
             instance_id     TEXT PRIMARY KEY,
             enabled         INTEGER NOT NULL DEFAULT 1,
             include_worlds  INTEGER NOT NULL DEFAULT 1,
             include_configs INTEGER NOT NULL DEFAULT 0,
             include_mods    INTEGER NOT NULL DEFAULT 0,
             on_launch       INTEGER NOT NULL DEFAULT 1,
             daily           INTEGER NOT NULL DEFAULT 0,
             keep            INTEGER NOT NULL DEFAULT 10,
             cloud           INTEGER NOT NULL DEFAULT 0
         );",
    )?;
    // Dernière sauvegarde quotidienne, pour savoir si l'échéance est passée.
    conn.execute(
        "CREATE TABLE IF NOT EXISTS backup_runs (instance_id TEXT PRIMARY KEY, last_daily_at INTEGER NOT NULL)",
        [],
    )?;
    Ok(())
}

fn read_row(conn: &Connection, instance_id: &str) -> Result<Option<BackupSettings>> {
    conn.query_row(
        "SELECT enabled, include_worlds, include_configs, include_mods, on_launch, daily, keep, cloud
         FROM backup_settings WHERE instance_id = ?1",
        [instance_id],
        |r| {
            Ok(BackupSettings {
                enabled: r.get::<_, i64>(0)? != 0,
                include_worlds: r.get::<_, i64>(1)? != 0,
                include_configs: r.get::<_, i64>(2)? != 0,
                include_mods: r.get::<_, i64>(3)? != 0,
                on_launch: r.get::<_, i64>(4)? != 0,
                daily: r.get::<_, i64>(5)? != 0,
                keep: r.get::<_, i64>(6)?.clamp(0, 1000) as u32,
                cloud: r.get::<_, i64>(7)? != 0,
            })
        },
    )
    .optional()
    .map_err(Into::into)
}

/// Réglage global, ou les valeurs par défaut s'il n'a jamais été touché.
pub fn global(conn: &Connection) -> BackupSettings {
    read_row(conn, GLOBAL).ok().flatten().unwrap_or_default()
}

/// Réglage effectif d'une instance : le sien s'il existe, sinon le global.
pub fn effective(conn: &Connection, instance_id: &str) -> BackupSettings {
    read_row(conn, instance_id).ok().flatten().unwrap_or_else(|| global(conn))
}

/// Cette instance a-t-elle son propre réglage, ou suit-elle le général ?
pub fn is_custom(conn: &Connection, instance_id: &str) -> bool {
    read_row(conn, instance_id).ok().flatten().is_some()
}

pub fn save(conn: &Connection, instance_id: &str, s: &BackupSettings) -> Result<()> {
    conn.execute(
        "INSERT INTO backup_settings (instance_id, enabled, include_worlds, include_configs, include_mods, on_launch, daily, keep, cloud)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
         ON CONFLICT (instance_id) DO UPDATE SET
             enabled = excluded.enabled, include_worlds = excluded.include_worlds,
             include_configs = excluded.include_configs, include_mods = excluded.include_mods,
             on_launch = excluded.on_launch, daily = excluded.daily,
             keep = excluded.keep, cloud = excluded.cloud",
        params![
            instance_id,
            s.enabled as i64,
            s.include_worlds as i64,
            s.include_configs as i64,
            s.include_mods as i64,
            s.on_launch as i64,
            s.daily as i64,
            i64::from(s.keep.min(1000)),
            s.cloud as i64,
        ],
    )?;
    Ok(())
}

/// Rend une instance au réglage général.
pub fn reset(conn: &Connection, instance_id: &str) -> Result<()> {
    conn.execute("DELETE FROM backup_settings WHERE instance_id = ?1", [instance_id])?;
    Ok(())
}

// ── Échéance quotidienne ─────────────────────────────────────────────────────

pub fn last_daily(conn: &Connection, instance_id: &str) -> i64 {
    conn.query_row("SELECT last_daily_at FROM backup_runs WHERE instance_id = ?1", [instance_id], |r| r.get(0))
        .optional()
        .ok()
        .flatten()
        .unwrap_or(0)
}

pub fn mark_daily(conn: &Connection, instance_id: &str, at: i64) -> Result<()> {
    conn.execute(
        "INSERT INTO backup_runs (instance_id, last_daily_at) VALUES (?1, ?2)
         ON CONFLICT (instance_id) DO UPDATE SET last_daily_at = excluded.last_daily_at",
        params![instance_id, at],
    )?;
    Ok(())
}

/// L'échéance quotidienne est-elle passée ? Vingt heures plutôt que vingt-quatre :
/// quelqu'un qui joue tous les soirs à la même heure ne doit pas voir sa
/// sauvegarde glisser d'un jour à l'autre puis sauter une journée entière.
pub fn daily_due(last: i64, now: i64) -> bool {
    now - last >= 20 * 3600
}

#[cfg(test)]
mod tests {
    use super::*;

    fn db() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        init(&conn).unwrap();
        conn
    }

    #[test]
    fn worlds_are_the_default_and_mods_are_not() {
        let d = BackupSettings::default();
        assert_eq!(d.included_dirs(), vec!["saves"]);
        assert!(!d.include_mods, "un dossier de mods pèse plus que les mondes : jamais d'office");
    }

    #[test]
    fn disabling_backups_empties_the_selection() {
        // L'interrupteur coupe la sélection elle-même, pas seulement les
        // déclencheurs : tout ce qui sauvegarde part de `included_dirs`, donc
        // une seule vérification suffit à garantir que rien ne se fait.
        let s = BackupSettings { enabled: false, include_worlds: true, on_launch: true, daily: true, ..Default::default() };
        assert!(s.included_dirs().is_empty());
    }

    #[test]
    fn an_instance_follows_the_global_setting_until_it_has_its_own() {
        let conn = db();
        save(&conn, GLOBAL, &BackupSettings { keep: 3, ..Default::default() }).unwrap();
        assert_eq!(effective(&conn, "coco").keep, 3);
        assert!(!is_custom(&conn, "coco"));

        save(&conn, "coco", &BackupSettings { keep: 25, ..Default::default() }).unwrap();
        assert_eq!(effective(&conn, "coco").keep, 25);
        assert!(is_custom(&conn, "coco"));
        assert_eq!(global(&conn).keep, 3, "régler une instance ne touche pas au général");

        reset(&conn, "coco").unwrap();
        assert_eq!(effective(&conn, "coco").keep, 3, "revenue au général");
    }

    #[test]
    fn changing_the_global_setting_moves_every_instance_that_follows_it() {
        let conn = db();
        save(&conn, GLOBAL, &BackupSettings { include_mods: true, ..Default::default() }).unwrap();
        assert!(effective(&conn, "jamais-reglee").include_mods);
    }

    #[test]
    fn the_daily_deadline_does_not_drift() {
        let now = 1_700_000_000;
        assert!(!daily_due(now - 10 * 3600, now));
        assert!(daily_due(now - 21 * 3600, now), "20 h suffisent : sinon une session quotidienne saute un jour sur deux");
        assert!(daily_due(0, now), "jamais sauvegardé");
    }

    #[test]
    fn settings_survive_a_round_trip() {
        let conn = db();
        let wanted = BackupSettings { enabled: true, include_worlds: true, include_configs: true, include_mods: true, on_launch: false, daily: true, keep: 0, cloud: true };
        save(&conn, "coco", &wanted).unwrap();
        assert_eq!(effective(&conn, "coco"), wanted);
    }
}
