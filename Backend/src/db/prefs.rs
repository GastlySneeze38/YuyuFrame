//! Préférences que le Rust doit connaître par lui-même.
//!
//! La plupart des réglages du launcher vivent côté frontend (Zustand, écrits
//! dans le stockage local) : ils ne concernent que l'affichage, et le Rust
//! n'a aucune raison de les lire.
//!
//! Certains, en revanche, décident de ce que fait le backend. Ceux-là ne
//! peuvent pas rester dans le frontend, sinon la règle n'existe qu'à
//! l'endroit qui l'appelle : chaque nouveau chemin de création doit penser à
//! la rappeler, et le premier qui l'oublie crée un comportement qui marche
//! « la plupart du temps ». C'était précisément le cas de la synchronisation
//! des réglages Minecraft, appliquée depuis l'écran des instances mais pas
//! depuis la restauration d'une sauvegarde cloud.
//!
//! Table volontairement générique (clé/valeur) : ce besoin se représentera.

use anyhow::Result;
use rusqlite::{params, Connection};

/// Appliquer `shared_options.txt` aux instances nouvellement créées.
pub const SYNC_GAME_SETTINGS: &str = "sync_game_settings";

pub fn init(conn: &Connection) -> Result<()> {
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS app_prefs (
             key   TEXT PRIMARY KEY,
             value TEXT NOT NULL
         );",
    )?;
    Ok(())
}

pub fn get(conn: &Connection, key: &str) -> Option<String> {
    conn.query_row("SELECT value FROM app_prefs WHERE key = ?1", params![key], |r| r.get(0))
        .ok()
}

pub fn set(conn: &Connection, key: &str, value: &str) -> Result<()> {
    conn.execute(
        "INSERT INTO app_prefs (key, value) VALUES (?1, ?2)
         ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        params![key, value],
    )?;
    Ok(())
}

/// Lecture d'un drapeau, avec sa valeur par défaut tant que le frontend ne
/// l'a jamais poussé. Une valeur illisible vaut le défaut plutôt qu'une
/// erreur : une préférence ne doit jamais empêcher une instance de se créer.
pub fn get_bool(conn: &Connection, key: &str, default: bool) -> bool {
    match get(conn, key).as_deref() {
        Some("1") | Some("true") => true,
        Some("0") | Some("false") => false,
        _ => default,
    }
}

pub fn set_bool(conn: &Connection, key: &str, value: bool) -> Result<()> {
    set(conn, key, if value { "1" } else { "0" })
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
    fn une_preference_jamais_ecrite_vaut_son_defaut() {
        let conn = db();
        assert!(!get_bool(&conn, SYNC_GAME_SETTINGS, false));
        assert!(get_bool(&conn, SYNC_GAME_SETTINGS, true));
    }

    #[test]
    fn un_drapeau_survit_a_l_ecriture() {
        let conn = db();
        set_bool(&conn, SYNC_GAME_SETTINGS, true).unwrap();
        assert!(get_bool(&conn, SYNC_GAME_SETTINGS, false));
        set_bool(&conn, SYNC_GAME_SETTINGS, false).unwrap();
        assert!(!get_bool(&conn, SYNC_GAME_SETTINGS, true));
    }

    #[test]
    fn une_valeur_illisible_ne_fait_pas_echouer_la_lecture() {
        // Une préférence corrompue ne doit jamais empêcher la création d'une
        // instance : elle retombe sur le défaut, silencieusement.
        let conn = db();
        set(&conn, SYNC_GAME_SETTINGS, "peut-être").unwrap();
        assert!(get_bool(&conn, SYNC_GAME_SETTINGS, true));
        assert!(!get_bool(&conn, SYNC_GAME_SETTINGS, false));
    }

    #[test]
    fn ecrire_deux_fois_la_meme_cle_la_remplace() {
        let conn = db();
        set(&conn, "x", "a").unwrap();
        set(&conn, "x", "b").unwrap();
        assert_eq!(get(&conn, "x").as_deref(), Some("b"));
    }
}
