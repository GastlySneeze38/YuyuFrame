use anyhow::Result;
use rusqlite::{params, Connection};

pub struct InstanceRow {
    pub id: String,
    pub name: String,
    pub mc_version: String,
    pub loader: String,
    pub ram_mb: u32,
    pub favorite: bool,
    pub description: String,
}

pub fn instance_list(conn: &Connection, user_id: i64) -> Result<Vec<InstanceRow>> {
    let mut stmt = conn.prepare(
        "SELECT id, name, mc_version, loader, ram_mb, favorite, description FROM instances
         WHERE yuyu_user_id = ?1 ORDER BY created_at ASC",
    )?;
    let rows = stmt
        .query_map(params![user_id], |r| {
            Ok(InstanceRow {
                id: r.get(0)?,
                name: r.get(1)?,
                mc_version: r.get(2)?,
                loader: r.get(3)?,
                ram_mb: r.get::<_, u32>(4)?,
                favorite: r.get::<_, i64>(5)? != 0,
                description: r.get(6)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

pub fn instance_get(conn: &Connection, id: &str, user_id: i64) -> Result<Option<InstanceRow>> {
    let mut stmt = conn.prepare(
        "SELECT id, name, mc_version, loader, ram_mb, favorite, description FROM instances
         WHERE id = ?1 AND yuyu_user_id = ?2",
    )?;
    match stmt.query_row(params![id, user_id], |r| {
        Ok(InstanceRow {
            id: r.get(0)?,
            name: r.get(1)?,
            mc_version: r.get(2)?,
            loader: r.get(3)?,
            ram_mb: r.get::<_, u32>(4)?,
            favorite: r.get::<_, i64>(5)? != 0,
            description: r.get(6)?,
        })
    }) {
        Ok(row) => Ok(Some(row)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(e.into()),
    }
}

pub fn instance_set_favorite(conn: &Connection, id: &str, user_id: i64, favorite: bool) -> Result<()> {
    conn.execute(
        "UPDATE instances SET favorite = ?1 WHERE id = ?2 AND yuyu_user_id = ?3",
        params![favorite as i64, id, user_id],
    )?;
    Ok(())
}

#[allow(clippy::too_many_arguments)]
pub fn instance_insert(
    conn: &Connection,
    id: &str,
    user_id: i64,
    name: &str,
    mc_version: &str,
    loader: &str,
    ram_mb: u32,
    description: &str,
) -> Result<()> {
    let now = chrono::Utc::now().timestamp();
    conn.execute(
        "INSERT INTO instances (id, yuyu_user_id, name, mc_version, loader, ram_mb, created_at, description)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
        params![id, user_id, name, mc_version, loader, ram_mb, now, description],
    )?;
    Ok(())
}

#[allow(clippy::too_many_arguments)]
pub fn instance_update(
    conn: &Connection,
    id: &str,
    user_id: i64,
    name: &str,
    mc_version: &str,
    loader: &str,
    ram_mb: u32,
    description: &str,
) -> Result<()> {
    let n = conn.execute(
        "UPDATE instances SET name=?1, mc_version=?2, loader=?3, ram_mb=?4, description=?5
         WHERE id=?6 AND yuyu_user_id=?7",
        params![name, mc_version, loader, ram_mb, description, id, user_id],
    )?;
    if n == 0 {
        return Err(anyhow::anyhow!("Instance introuvable"));
    }
    Ok(())
}

pub fn instance_delete(conn: &Connection, id: &str, user_id: i64) -> Result<()> {
    conn.execute(
        "DELETE FROM instances WHERE id = ?1 AND yuyu_user_id = ?2",
        params![id, user_id],
    )?;
    Ok(())
}

/// Reassigne les instances orphelines (yuyu_user_id = 0) à l'utilisateur qui vient de se connecter.
pub fn instance_claim_unclaimed(conn: &Connection, user_id: i64) -> Result<()> {
    conn.execute(
        "UPDATE instances SET yuyu_user_id = ?1 WHERE yuyu_user_id = 0",
        params![user_id],
    )?;
    Ok(())
}

/// Ancien format d'id (avant l'introduction du slug de nom, voir
/// `crud::gen_id`) : exactement 12 caractères alphanumériques, jamais de
/// tiret. Le nouveau format contient toujours un tiret, sauf pour un nom
/// entièrement non-alphanumérique où le suffixe seul fait 8 caractères —
/// jamais 12. Distinction sans ambiguïté dans les deux sens.
fn is_legacy_id(id: &str) -> bool {
    id.len() == 12 && !id.contains('-') && id.chars().all(|c| c.is_ascii_alphanumeric())
}

/// Instances (tous users confondus) dont l'id est encore au format legacy —
/// utilisé une seule fois au démarrage pour migrer vers le nouveau format
/// lisible (voir `migrate_legacy_instance_ids` dans lib.rs).
pub fn instance_legacy_ids(conn: &Connection) -> Result<Vec<(String, String)>> {
    let mut stmt = conn.prepare("SELECT id, name FROM instances")?;
    let rows = stmt
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows.into_iter().filter(|(id, _)| is_legacy_id(id)).collect())
}

/// Répercute un renommage d'id d'instance dans la ligne `instances`
/// elle-même et dans l'historique `play_sessions` (sinon les stats de jeu
/// passées de l'instance se retrouvent orphelines, scindées de l'instance
/// renommée). Ne touche PAS au dossier disque — responsabilité de l'appelant,
/// qui a accès à `minecraft_dir()` (pas disponible depuis `db/`).
pub fn instance_rename_id(conn: &Connection, old_id: &str, new_id: &str) -> Result<()> {
    conn.execute("UPDATE instances SET id = ?1 WHERE id = ?2", params![new_id, old_id])?;
    conn.execute("UPDATE play_sessions SET instance_id = ?1 WHERE instance_id = ?2", params![new_id, old_id])?;
    Ok(())
}
