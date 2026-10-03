use anyhow::Result;
use rusqlite::{params, Connection};

/// Les comptes Minecraft appartiennent au PC, pas au compte YuyuFrame : on les
/// ajoute, change et lance sans être connecté à YuyuFrame, et une
/// (dé)connexion YuyuFrame ne les touche pas. La colonne `yuyu_user_id` des
/// tables `mc_sessions`/`active_mc` est conservée (schéma existant) mais vaut
/// toujours cette constante — voir la migration dans `schema.rs`.
const PC_SCOPE: i64 = 0;

#[derive(Clone)]
pub struct McSessionRow {
    pub mc_username: String,
    pub mc_uuid: String,
    pub access_token: String,
    pub ms_refresh_token: String,
    pub expires_at: i64,
    pub is_offline: bool,
}

fn row_from(r: &rusqlite::Row<'_>) -> rusqlite::Result<McSessionRow> {
    Ok(McSessionRow {
        mc_username: r.get(0)?,
        mc_uuid: r.get(1)?,
        access_token: r.get(2)?,
        ms_refresh_token: r.get(3)?,
        expires_at: r.get(4)?,
        is_offline: r.get::<_, i32>(5)? != 0,
    })
}

pub fn upsert_mc_session(
    conn: &Connection,
    mc_username: &str,
    mc_uuid: &str,
    access_token: &str,
    ms_refresh_token: &str,
    expires_at: i64,
    is_offline: bool,
) -> Result<()> {
    let now = chrono::Utc::now().timestamp();
    conn.execute(
        "INSERT INTO mc_sessions
             (yuyu_user_id, mc_username, mc_uuid, access_token, ms_refresh_token, expires_at, is_offline, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
         ON CONFLICT(yuyu_user_id, mc_uuid) DO UPDATE SET
             mc_username      = excluded.mc_username,
             access_token     = excluded.access_token,
             ms_refresh_token = excluded.ms_refresh_token,
             expires_at       = excluded.expires_at,
             is_offline       = excluded.is_offline,
             updated_at       = excluded.updated_at",
        params![PC_SCOPE, mc_username, mc_uuid, access_token, ms_refresh_token, expires_at, is_offline as i32, now],
    )?;
    Ok(())
}

/// Du plus ancien au plus récent ajout — ordre stable pour l'affichage et pour
/// choisir le compte qui reprend la main quand l'actif est supprimé.
pub fn list_mc_sessions(conn: &Connection) -> Result<Vec<McSessionRow>> {
    let mut stmt = conn.prepare(
        "SELECT mc_username, mc_uuid, access_token, ms_refresh_token, expires_at, is_offline
         FROM mc_sessions WHERE yuyu_user_id = ?1 ORDER BY id ASC",
    )?;
    let rows = stmt
        .query_map(params![PC_SCOPE], |r| row_from(r))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

pub fn get_mc_session(conn: &Connection, mc_uuid: &str) -> Result<Option<McSessionRow>> {
    let mut stmt = conn.prepare(
        "SELECT mc_username, mc_uuid, access_token, ms_refresh_token, expires_at, is_offline
         FROM mc_sessions WHERE yuyu_user_id = ?1 AND mc_uuid = ?2",
    )?;
    match stmt.query_row(params![PC_SCOPE, mc_uuid], |r| row_from(r)) {
        Ok(r) => Ok(Some(r)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(e.into()),
    }
}

pub fn delete_mc_session(conn: &Connection, mc_uuid: &str) -> Result<()> {
    conn.execute(
        "DELETE FROM mc_sessions WHERE yuyu_user_id = ?1 AND mc_uuid = ?2",
        params![PC_SCOPE, mc_uuid],
    )?;
    Ok(())
}

pub fn update_mc_tokens(
    conn: &Connection,
    mc_uuid: &str,
    access_token: &str,
    ms_refresh_token: &str,
    expires_at: i64,
) -> Result<()> {
    let now = chrono::Utc::now().timestamp();
    conn.execute(
        "UPDATE mc_sessions
         SET access_token=?1, ms_refresh_token=?2, expires_at=?3, updated_at=?4
         WHERE yuyu_user_id=?5 AND mc_uuid=?6",
        params![access_token, ms_refresh_token, expires_at, now, PC_SCOPE, mc_uuid],
    )?;
    Ok(())
}

// ── Skin : référence, pas fichier ─────────────────────────────────────────────
//
// Voir le commentaire de la migration dans `schema.rs` : on ne garde qu'une URL
// déjà hébergée ailleurs et le modèle à utiliser, jamais les pixels.

#[derive(Clone, serde::Serialize)]
pub struct SkinRef {
    /// `url` (hébergé ailleurs, repartageable) ou `local` (PNG sur ce PC —
    /// comptes hors ligne seulement, voir la migration dans `schema.rs`).
    pub kind: String,
    /// URL, ou nom du fichier dans le dossier des skins importés selon `kind`.
    pub source: String,
    /// `classic` (bras de 4 px) ou `slim` (3 px, modèle « Alex »).
    pub variant: String,
    /// D'où vient la référence : `player:<pseudo>`, `url`, `file` ou `mojang`.
    pub origin: String,
}

pub fn set_skin_ref(conn: &Connection, mc_uuid: &str, skin: &SkinRef) -> Result<()> {
    conn.execute(
        "UPDATE mc_sessions SET skin_url=?1, skin_variant=?2, skin_origin=?3, skin_kind=?4, updated_at=?5
         WHERE yuyu_user_id=?6 AND mc_uuid=?7",
        params![
            skin.source,
            skin.variant,
            skin.origin,
            skin.kind,
            chrono::Utc::now().timestamp(),
            PC_SCOPE,
            mc_uuid
        ],
    )?;
    Ok(())
}

pub fn clear_skin_ref(conn: &Connection, mc_uuid: &str) -> Result<()> {
    conn.execute(
        "UPDATE mc_sessions
         SET skin_url=NULL, skin_variant=NULL, skin_origin=NULL, skin_kind=NULL, updated_at=?1
         WHERE yuyu_user_id=?2 AND mc_uuid=?3",
        params![chrono::Utc::now().timestamp(), PC_SCOPE, mc_uuid],
    )?;
    Ok(())
}

/// `None` = aucun skin choisi pour ce compte (ou compte inconnu). Seule la
/// source décide de la présence d'un skin : un modèle ou un genre absent est lu
/// avec sa valeur par défaut plutôt que de faire disparaître le skin. Les
/// lignes écrites avant l'ouverture du cas `local` n'ont pas de `skin_kind`, et
/// étaient toutes des URL.
pub fn get_skin_ref(conn: &Connection, mc_uuid: &str) -> Result<Option<SkinRef>> {
    let row = conn.query_row(
        "SELECT skin_url, skin_variant, skin_origin, skin_kind FROM mc_sessions
         WHERE yuyu_user_id = ?1 AND mc_uuid = ?2",
        params![PC_SCOPE, mc_uuid],
        |r| {
            Ok((
                r.get::<_, Option<String>>(0)?,
                r.get::<_, Option<String>>(1)?,
                r.get::<_, Option<String>>(2)?,
                r.get::<_, Option<String>>(3)?,
            ))
        },
    );
    match row {
        Ok((Some(source), variant, origin, kind)) => Ok(Some(SkinRef {
            kind: kind.unwrap_or_else(|| "url".to_string()),
            source,
            variant: variant.unwrap_or_else(|| "classic".to_string()),
            origin: origin.unwrap_or_else(|| "url".to_string()),
        })),
        Ok(_) => Ok(None),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(e.into()),
    }
}

// ── Active MC session ──────────────────────────────────────────────────────────

pub fn set_active_mc(conn: &Connection, mc_uuid: &str) -> Result<()> {
    conn.execute(
        "INSERT INTO active_mc (yuyu_user_id, mc_uuid) VALUES (?1, ?2)
         ON CONFLICT(yuyu_user_id) DO UPDATE SET mc_uuid = excluded.mc_uuid",
        params![PC_SCOPE, mc_uuid],
    )?;
    Ok(())
}

pub fn get_active_mc_uuid(conn: &Connection) -> Result<Option<String>> {
    match conn.query_row(
        "SELECT mc_uuid FROM active_mc WHERE yuyu_user_id = ?1",
        params![PC_SCOPE],
        |r| r.get(0),
    ) {
        Ok(uuid) => Ok(Some(uuid)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(e.into()),
    }
}

pub fn clear_active_mc(conn: &Connection) -> Result<()> {
    conn.execute("DELETE FROM active_mc WHERE yuyu_user_id = ?1", params![PC_SCOPE])?;
    Ok(())
}

/// Les comptes étaient rangés par compte YuyuFrame. Tout passe sous
/// `PC_SCOPE` : un même compte présent sous plusieurs utilisateurs ne garde
/// que sa ligne la plus récente (tokens les plus frais), et le compte actif est
/// celui de la session YuyuFrame enregistrée, à défaut n'importe quel actif
/// encore existant. Idempotente : ne fait plus rien une fois migrée.
pub fn migrate_to_pc_scope(conn: &Connection) -> Result<()> {
    conn.execute_batch(
        "BEGIN;
         DELETE FROM mc_sessions WHERE EXISTS (
             SELECT 1 FROM mc_sessions newer
             WHERE newer.mc_uuid = mc_sessions.mc_uuid
               AND (newer.updated_at > mc_sessions.updated_at
                    OR (newer.updated_at = mc_sessions.updated_at AND newer.id > mc_sessions.id))
         );
         UPDATE mc_sessions SET yuyu_user_id = 0 WHERE yuyu_user_id <> 0;
         INSERT OR IGNORE INTO active_mc (yuyu_user_id, mc_uuid)
             SELECT 0, mc_uuid FROM active_mc
             WHERE yuyu_user_id = (SELECT user_id FROM yuyu_session WHERE id = 1)
               AND mc_uuid IN (SELECT mc_uuid FROM mc_sessions);
         INSERT OR IGNORE INTO active_mc (yuyu_user_id, mc_uuid)
             SELECT 0, mc_uuid FROM active_mc
             WHERE yuyu_user_id <> 0 AND mc_uuid IN (SELECT mc_uuid FROM mc_sessions)
             LIMIT 1;
         DELETE FROM active_mc WHERE yuyu_user_id <> 0;
         COMMIT;",
    )?;
    Ok(())
}
