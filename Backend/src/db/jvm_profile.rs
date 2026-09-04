use anyhow::Result;
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};

/// Une configuration JVM réutilisable, reliable à plusieurs instances.
///
/// Elle porte la même chose qu'un bloc `jvm_*` d'instance, à deux différences
/// près qui viennent de son usage — comparer des jeux de drapeaux :
/// - `ram_mb` peut valoir `None` ("garde la RAM de l'instance"), parce que la
///   plupart des configs ne cherchent pas à imposer un tas, seulement des
///   drapeaux ;
/// - les drapeaux sont rangés en trois catégories (`args_jvm`, `args_gc`,
///   `args_jit`) au lieu d'un seul champ. Ce sont trois sujets indépendants —
///   on change de ramasse-miettes sans toucher au compilateur — et les mélanger
///   rendait impossible de dire quelle moitié d'un test a bougé. Au lancement,
///   les trois sont simplement concaténés (voir `all_args`).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JvmProfileRow {
    pub id: String,
    pub name: String,
    /// `None` = ne pas toucher à la RAM choisie sur l'instance.
    pub ram_mb: Option<u32>,
    pub jvm_vendor: String,
    pub jvm_custom_path: Option<String>,
    pub gc_policy: String,
    /// "append" | "replace" — voir `merge_jvm_args`.
    pub args_mode: String,
    /// Moteur/mémoire : `-XX:+AlwaysPreTouch`, `-Xss`, `-XX:+UseNUMA`...
    pub args_jvm: String,
    /// Ramasse-miettes : sélecteur (`-XX:+UseShenandoahGC`) et son réglage.
    pub args_gc: String,
    /// Compilateur : `-XX:-DontCompileHugeMethods`, JVMCI/Graal, code cache.
    pub args_jit: String,
    /// Jeu de drapeaux dont chaque catégorie est issue, en JSON
    /// (`{"gc":"gc-brucethemoose"}`). Le launcher ne l'interprète jamais : il
    /// sert uniquement à l'interface, pour rappeler d'où part la config et
    /// montrer les écarts introduits depuis. Opaque ici exprès — le catalogue
    /// des jeux vit côté frontend, la base n'a pas à en connaître les noms.
    pub base_presets: String,
}

impl JvmProfileRow {
    /// Les trois catégories telles que les attend `merge_jvm_args` — un seul
    /// texte, catégories séparées par un saut de ligne. L'ordre (moteur, GC,
    /// JIT) n'a pas d'importance pour la JVM mais en a pour la lecture d'un
    /// aperçu : il reste celui de l'écran d'édition.
    pub fn all_args(&self) -> String {
        [&self.args_jvm, &self.args_gc, &self.args_jit]
            .iter()
            .map(|s| s.trim())
            .filter(|s| !s.is_empty())
            .collect::<Vec<_>>()
            .join("\n")
    }
}

const COLUMNS: &str =
    "id, name, ram_mb, jvm_vendor, jvm_custom_path, gc_policy, args_mode, args_jvm, args_gc, args_jit, base_presets";

fn row_to_profile(r: &rusqlite::Row) -> rusqlite::Result<JvmProfileRow> {
    Ok(JvmProfileRow {
        id: r.get(0)?,
        name: r.get(1)?,
        ram_mb: r.get::<_, Option<u32>>(2)?,
        jvm_vendor: r.get(3)?,
        jvm_custom_path: r.get(4)?,
        gc_policy: r.get(5)?,
        args_mode: r.get(6)?,
        args_jvm: r.get(7)?,
        args_gc: r.get(8)?,
        args_jit: r.get(9)?,
        base_presets: r.get(10)?,
    })
}

pub fn jvm_profile_list(conn: &Connection, user_id: i64) -> Result<Vec<JvmProfileRow>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT {COLUMNS} FROM jvm_profiles WHERE yuyu_user_id = ?1 ORDER BY created_at ASC",
    ))?;
    let rows = stmt
        .query_map(params![user_id], row_to_profile)?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

pub fn jvm_profile_get(conn: &Connection, id: &str, user_id: i64) -> Result<Option<JvmProfileRow>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT {COLUMNS} FROM jvm_profiles WHERE id = ?1 AND yuyu_user_id = ?2",
    ))?;
    match stmt.query_row(params![id, user_id], row_to_profile) {
        Ok(row) => Ok(Some(row)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(e.into()),
    }
}

pub fn jvm_profile_insert(conn: &Connection, user_id: i64, p: &JvmProfileRow) -> Result<()> {
    conn.execute(
        "INSERT INTO jvm_profiles (id, yuyu_user_id, name, created_at, ram_mb, jvm_vendor, jvm_custom_path, gc_policy, args_mode, args_jvm, args_gc, args_jit, base_presets)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)",
        params![p.id, user_id, p.name, chrono::Utc::now().timestamp(), p.ram_mb, p.jvm_vendor,
                p.jvm_custom_path, p.gc_policy, p.args_mode, p.args_jvm, p.args_gc, p.args_jit, p.base_presets],
    )?;
    Ok(())
}

pub fn jvm_profile_update(conn: &Connection, user_id: i64, p: &JvmProfileRow) -> Result<()> {
    let n = conn.execute(
        "UPDATE jvm_profiles SET name=?1, ram_mb=?2, jvm_vendor=?3, jvm_custom_path=?4, gc_policy=?5,
                                 args_mode=?6, args_jvm=?7, args_gc=?8, args_jit=?9, base_presets=?10
         WHERE id=?11 AND yuyu_user_id=?12",
        params![p.name, p.ram_mb, p.jvm_vendor, p.jvm_custom_path, p.gc_policy,
                p.args_mode, p.args_jvm, p.args_gc, p.args_jit, p.base_presets, p.id, user_id],
    )?;
    if n == 0 {
        return Err(anyhow::anyhow!("Configuration JVM introuvable"));
    }
    Ok(())
}

/// Supprime la config ET délie toutes les instances qui l'utilisaient — sans
/// ça elles garderaient un `jvm_profile_id` pointant dans le vide, ce que la
/// résolution au lancement traite comme "aucune config" mais qui laisserait
/// l'interface afficher une config fantôme.
pub fn jvm_profile_delete(conn: &Connection, id: &str, user_id: i64) -> Result<()> {
    conn.execute(
        "UPDATE instances SET jvm_profile_id = NULL WHERE jvm_profile_id = ?1 AND yuyu_user_id = ?2",
        params![id, user_id],
    )?;
    conn.execute(
        "DELETE FROM jvm_profiles WHERE id = ?1 AND yuyu_user_id = ?2",
        params![id, user_id],
    )?;
    Ok(())
}

/// Relie (ou délie, avec `None`) une instance à une config.
pub fn instance_set_jvm_profile(
    conn: &Connection,
    instance_id: &str,
    user_id: i64,
    profile_id: Option<&str>,
) -> Result<()> {
    let n = conn.execute(
        "UPDATE instances SET jvm_profile_id = ?1 WHERE id = ?2 AND yuyu_user_id = ?3",
        params![profile_id, instance_id, user_id],
    )?;
    if n == 0 {
        return Err(anyhow::anyhow!("Instance introuvable"));
    }
    Ok(())
}
