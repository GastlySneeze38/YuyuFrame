use anyhow::Result;
use rusqlite::{params, Connection};

/// Historique des skins d'un compte — voir la table dans `schema.rs` pour
/// pourquoi il est tenu localement plutôt que demandé à Mojang.
///
/// Il n'est pas cantonné aux comptes hors ligne : un compte Microsoft en
/// profite autant, puisque Mojang ne sait pas dire ce qu'il portait avant.

/// Au-delà, les entrées les plus anciennes sont oubliées. Généreux exprès : une
/// ligne pèse quelques dizaines d'octets, et perdre un skin qu'on voulait
/// retrouver est bien plus ennuyeux que d'en garder cent.
const MAX_PER_ACCOUNT: usize = 100;

#[derive(Clone, serde::Serialize)]
pub struct SkinHistoryRow {
    pub id: i64,
    /// `url` (hébergé ailleurs) ou `local` (PNG sur ce PC seulement).
    pub kind: String,
    /// URL, ou nom du fichier dans le dossier des skins locaux.
    pub source: String,
    pub variant: String,
    pub origin: String,
    pub first_seen_at: i64,
    pub last_used_at: i64,
}

fn row_from(r: &rusqlite::Row<'_>) -> rusqlite::Result<SkinHistoryRow> {
    Ok(SkinHistoryRow {
        id: r.get(0)?,
        kind: r.get(1)?,
        source: r.get(2)?,
        variant: r.get(3)?,
        origin: r.get(4)?,
        first_seen_at: r.get(5)?,
        last_used_at: r.get(6)?,
    })
}

/// Enregistre un skin porté. Le même skin réappliqué ne crée pas de doublon :
/// il remonte en tête, et sa date de première apparition est conservée — c'est
/// elle qui raconte quelque chose.
///
/// Le modèle (`variant`) fait partie de l'identité d'une entrée : le même PNG en
/// bras fins et en bras larges donne deux apparences distinctes, et pouvoir
/// revenir à l'une ou l'autre est exactement l'objet de cet écran.
pub fn remember(
    conn: &Connection,
    mc_uuid: &str,
    kind: &str,
    source: &str,
    variant: &str,
    origin: &str,
) -> Result<()> {
    let now = chrono::Utc::now().timestamp();
    conn.execute(
        "INSERT INTO skin_history (mc_uuid, kind, source, variant, origin, first_seen_at, last_used_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?6)
         ON CONFLICT(mc_uuid, source, variant) DO UPDATE SET
             last_used_at = excluded.last_used_at,
             kind         = excluded.kind,
             origin       = excluded.origin",
        params![mc_uuid, kind, source, variant, origin, now],
    )?;

    conn.execute(
        "DELETE FROM skin_history
         WHERE mc_uuid = ?1 AND id NOT IN (
             SELECT id FROM skin_history WHERE mc_uuid = ?1
             ORDER BY last_used_at DESC, id DESC LIMIT ?2
         )",
        params![mc_uuid, MAX_PER_ACCOUNT as i64],
    )?;
    Ok(())
}

/// Du plus récemment porté au plus ancien.
pub fn list(conn: &Connection, mc_uuid: &str) -> Result<Vec<SkinHistoryRow>> {
    let mut stmt = conn.prepare(
        "SELECT id, kind, source, variant, origin, first_seen_at, last_used_at
         FROM skin_history WHERE mc_uuid = ?1 ORDER BY last_used_at DESC, id DESC",
    )?;
    let rows = stmt
        .query_map(params![mc_uuid], |r| row_from(r))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

/// L'`mc_uuid` est exigé en plus de l'`id` : un identifiant venu de l'interface
/// ne doit pas pouvoir toucher l'historique d'un autre compte.
pub fn forget(conn: &Connection, mc_uuid: &str, id: i64) -> Result<()> {
    conn.execute(
        "DELETE FROM skin_history WHERE mc_uuid = ?1 AND id = ?2",
        params![mc_uuid, id],
    )?;
    Ok(())
}

/// Un fichier local n'est effaçable que s'il ne sert plus à personne — le même
/// PNG peut être l'historique de deux comptes hors ligne, son nom étant son
/// empreinte.
pub fn local_source_still_used(conn: &Connection, source: &str) -> Result<bool> {
    let count: i64 = conn.query_row(
        "SELECT COUNT(*) FROM skin_history WHERE kind = 'local' AND source = ?1",
        params![source],
        |r| r.get(0),
    )?;
    Ok(count > 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn db() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(
            "CREATE TABLE skin_history (
                 id            INTEGER PRIMARY KEY AUTOINCREMENT,
                 mc_uuid       TEXT    NOT NULL,
                 kind          TEXT    NOT NULL,
                 source        TEXT    NOT NULL,
                 variant       TEXT    NOT NULL,
                 origin        TEXT    NOT NULL,
                 first_seen_at INTEGER NOT NULL,
                 last_used_at  INTEGER NOT NULL,
                 UNIQUE(mc_uuid, source, variant)
             );",
        )
        .unwrap();
        conn
    }

    #[test]
    fn le_meme_skin_reapplique_ne_fait_pas_de_doublon() {
        let conn = db();
        remember(&conn, "u1", "url", "https://a.test/s.png", "classic", "url").unwrap();
        remember(&conn, "u1", "url", "https://a.test/s.png", "classic", "url").unwrap();
        assert_eq!(list(&conn, "u1").unwrap().len(), 1);
    }

    #[test]
    fn le_modele_distingue_deux_entrees() {
        let conn = db();
        remember(&conn, "u1", "url", "https://a.test/s.png", "classic", "url").unwrap();
        remember(&conn, "u1", "url", "https://a.test/s.png", "slim", "url").unwrap();
        assert_eq!(list(&conn, "u1").unwrap().len(), 2);
    }

    #[test]
    fn la_date_de_premiere_apparition_survit_a_une_reapplication() {
        let conn = db();
        remember(&conn, "u1", "url", "https://a.test/s.png", "classic", "url").unwrap();
        let first = list(&conn, "u1").unwrap()[0].first_seen_at;
        conn.execute(
            "UPDATE skin_history SET first_seen_at = 1, last_used_at = 1 WHERE mc_uuid = 'u1'",
            [],
        )
        .unwrap();
        remember(&conn, "u1", "url", "https://a.test/s.png", "classic", "url").unwrap();
        let after = &list(&conn, "u1").unwrap()[0];
        assert_eq!(after.first_seen_at, 1, "la première apparition ne doit pas être réécrite");
        assert!(after.last_used_at >= first, "le dernier usage doit être remonté");
    }

    #[test]
    fn les_historiques_de_deux_comptes_ne_se_melangent_pas() {
        let conn = db();
        remember(&conn, "u1", "url", "https://a.test/s.png", "classic", "url").unwrap();
        remember(&conn, "u2", "url", "https://b.test/s.png", "classic", "url").unwrap();
        assert_eq!(list(&conn, "u1").unwrap().len(), 1);
        assert_eq!(list(&conn, "u2").unwrap().len(), 1);
    }

    #[test]
    fn oublier_ne_touche_que_le_compte_vise() {
        let conn = db();
        remember(&conn, "u1", "url", "https://a.test/s.png", "classic", "url").unwrap();
        let id = list(&conn, "u1").unwrap()[0].id;
        // Même identifiant, mais réclamé au nom d'un autre compte : refusé.
        forget(&conn, "u2", id).unwrap();
        assert_eq!(list(&conn, "u1").unwrap().len(), 1);
        forget(&conn, "u1", id).unwrap();
        assert!(list(&conn, "u1").unwrap().is_empty());
    }

    #[test]
    fn un_fichier_local_partage_par_deux_comptes_reste_utilise() {
        let conn = db();
        remember(&conn, "u1", "local", "abc.png", "classic", "file").unwrap();
        remember(&conn, "u2", "local", "abc.png", "classic", "file").unwrap();
        let id = list(&conn, "u1").unwrap()[0].id;
        forget(&conn, "u1", id).unwrap();
        assert!(local_source_still_used(&conn, "abc.png").unwrap());
    }

    #[test]
    fn au_dela_du_plafond_les_plus_anciens_sont_oublies() {
        let conn = db();
        for i in 0..(MAX_PER_ACCOUNT + 10) {
            remember(&conn, "u1", "url", &format!("https://a.test/{}.png", i), "classic", "url").unwrap();
            // Les dates viennent de l'horloge à la seconde : sans écart forcé,
            // l'ordre du plafond ne serait pas déterministe dans ce test.
            conn.execute(
                "UPDATE skin_history SET last_used_at = ?1 WHERE mc_uuid = 'u1' AND source = ?2",
                params![i as i64, format!("https://a.test/{}.png", i)],
            )
            .unwrap();
        }
        let rows = list(&conn, "u1").unwrap();
        assert_eq!(rows.len(), MAX_PER_ACCOUNT);
        assert!(rows.iter().all(|r| r.source != "https://a.test/0.png"));
    }
}
