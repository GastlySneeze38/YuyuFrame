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
    /// P1-6 (audit launcher, Phase 6) — "temurin" (défaut) | "openj9" | "graal" | "custom".
    pub jvm_vendor: String,
    /// Chemin JVM fourni par l'utilisateur — requis si `jvm_vendor` vaut
    /// "custom", optionnel pour "graal" (repli best-effort sinon, voir `ensure_java`).
    pub jvm_custom_path: Option<String>,
    /// "auto" (défaut, grille RAM/loader) ou une policy explicite — le jeu
    /// de valeurs valides dépend de `jvm_vendor` (voir `build_jvm_args`).
    pub gc_policy: String,
    /// Drapeaux JVM saisis à la main dans l'écran "Configuration JVM" (texte
    /// brut, tel que tapé). Fusionnés aux drapeaux générés selon
    /// `jvm_args_mode` — voir `merge_jvm_args`.
    pub jvm_extra_args: String,
    /// "append" (défaut) : les drapeaux manuels s'ajoutent aux générés.
    /// "replace" : seule la base obligatoire (heap + library path) est gardée.
    pub jvm_args_mode: String,
    /// Config JVM reliée (`settings::jvm_profile_store`). Quand elle est renseignée, elle
    /// remplace intégralement les champs `jvm_*` ci-dessus au lancement —
    /// ceux-ci ne servent plus que de repli pour une instance sans config.
    pub jvm_profile_id: Option<String>,
    /// Icône choisie par l'utilisateur, en data URI. Vide = icône par défaut.
    /// Les octets eux-mêmes sont ici (voir la migration dans `schema.rs`), ce
    /// qui évite une lecture de fichier par carte à chaque affichage de la
    /// liste.
    pub icon: String,
    /// Version du loader épinglée. Vide = la plus récente compatible, le
    /// comportement historique et toujours celui par défaut.
    pub loader_version: String,
    /// Le launcher impose-t-il la fenêtre de jeu ? Faux = il n'y touche pas,
    /// et les trois champs suivants n'ont aucun effet.
    pub window_custom: bool,
    pub window_fullscreen: bool,
    pub window_width: u32,
    pub window_height: u32,
}

const INSTANCE_COLUMNS: &str =
    "id, name, mc_version, loader, ram_mb, favorite, description, jvm_vendor, jvm_custom_path, gc_policy, jvm_extra_args, jvm_args_mode, jvm_profile_id, icon, loader_version, window_custom, window_fullscreen, window_width, window_height";

fn row_to_instance(r: &rusqlite::Row) -> rusqlite::Result<InstanceRow> {
    Ok(InstanceRow {
        id: r.get(0)?,
        name: r.get(1)?,
        mc_version: r.get(2)?,
        loader: r.get(3)?,
        ram_mb: r.get::<_, u32>(4)?,
        favorite: r.get::<_, i64>(5)? != 0,
        description: r.get(6)?,
        jvm_vendor: r.get(7)?,
        jvm_custom_path: r.get(8)?,
        gc_policy: r.get(9)?,
        jvm_extra_args: r.get(10)?,
        jvm_args_mode: r.get(11)?,
        jvm_profile_id: r.get(12)?,
        icon: r.get(13)?,
        loader_version: r.get(14)?,
        window_custom: r.get::<_, i64>(15)? != 0,
        window_fullscreen: r.get::<_, i64>(16)? != 0,
        window_width: r.get::<_, u32>(17)?,
        window_height: r.get::<_, u32>(18)?,
    })
}

pub fn instance_list(conn: &Connection, user_id: i64) -> Result<Vec<InstanceRow>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT {INSTANCE_COLUMNS} FROM instances WHERE yuyu_user_id = ?1 ORDER BY created_at ASC",
    ))?;
    let rows = stmt
        .query_map(params![user_id], row_to_instance)?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

pub fn instance_get(conn: &Connection, id: &str, user_id: i64) -> Result<Option<InstanceRow>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT {INSTANCE_COLUMNS} FROM instances WHERE id = ?1 AND yuyu_user_id = ?2",
    ))?;
    match stmt.query_row(params![id, user_id], row_to_instance) {
        Ok(row) => Ok(Some(row)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(e.into()),
    }
}

/// Pose ou retire l'icône (chaîne vide = retour à l'icône par défaut).
///
/// Une mise à jour à part, et non un paramètre de plus sur `instance_update` :
/// l'icône se change d'un geste isolé, qui n'a pas à repasser le nom, la
/// version et les six champs JVM — ni à être repassée par chaque appelant de
/// `instance_update`, qui l'écraserait en l'oubliant.
pub fn instance_set_icon(conn: &Connection, id: &str, user_id: i64, icon: &str) -> Result<()> {
    let n = conn.execute(
        "UPDATE instances SET icon = ?1 WHERE id = ?2 AND yuyu_user_id = ?3",
        params![icon, id, user_id],
    )?;
    if n == 0 {
        return Err(anyhow::anyhow!("Instance introuvable"));
    }
    Ok(())
}

/// Enregistre les réglages de fenêtre d'une instance.
///
/// Les quatre ensemble, et non un par un : ils ne veulent rien dire séparés.
/// `custom` à faux rend les trois autres sans effet au lancement, mais on les
/// garde quand même en base — décocher puis recocher doit retrouver ses
/// valeurs, pas en réinventer.
pub fn instance_set_window(
    conn: &Connection,
    id: &str,
    user_id: i64,
    custom: bool,
    fullscreen: bool,
    width: u32,
    height: u32,
) -> Result<()> {
    let n = conn.execute(
        "UPDATE instances SET window_custom = ?1, window_fullscreen = ?2, window_width = ?3, window_height = ?4
         WHERE id = ?5 AND yuyu_user_id = ?6",
        params![custom as i64, fullscreen as i64, width, height, id, user_id],
    )?;
    if n == 0 {
        return Err(anyhow::anyhow!("Instance introuvable"));
    }
    Ok(())
}

/// Désigne la JVM d'une instance, ou revient à la résolution automatique.
///
/// Le vendeur suit le chemin et n'est pas un réglage séparé ici : `ensure_java`
/// refuse un vendeur « custom » sans chemin, et un chemin posé sans vendeur
/// « custom » serait ignoré. Les laisser se régler indépendamment ne produit
/// donc que des états qui ne lancent pas.
pub fn instance_set_java_path(conn: &Connection, id: &str, user_id: i64, path: Option<&str>) -> Result<()> {
    let vendor = if path.is_some() { "custom" } else { "auto" };
    let n = conn.execute(
        "UPDATE instances SET jvm_custom_path = ?1, jvm_vendor = ?2 WHERE id = ?3 AND yuyu_user_id = ?4",
        params![path, vendor, id, user_id],
    )?;
    if n == 0 {
        return Err(anyhow::anyhow!("Instance introuvable"));
    }
    Ok(())
}

/// Épingle une version de loader (chaîne vide = la plus récente compatible).
pub fn instance_set_loader_version(conn: &Connection, id: &str, user_id: i64, version: &str) -> Result<()> {
    conn.execute(
        "UPDATE instances SET loader_version = ?1 WHERE id = ?2 AND yuyu_user_id = ?3",
        params![version, id, user_id],
    )?;
    Ok(())
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
    jvm_vendor: &str,
    jvm_custom_path: Option<&str>,
    gc_policy: &str,
    jvm_extra_args: &str,
    jvm_args_mode: &str,
) -> Result<()> {
    let now = chrono::Utc::now().timestamp();
    conn.execute(
        "INSERT INTO instances (id, yuyu_user_id, name, mc_version, loader, ram_mb, created_at, description, jvm_vendor, jvm_custom_path, gc_policy, jvm_extra_args, jvm_args_mode)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)",
        params![id, user_id, name, mc_version, loader, ram_mb, now, description, jvm_vendor, jvm_custom_path, gc_policy, jvm_extra_args, jvm_args_mode],
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
    jvm_vendor: &str,
    jvm_custom_path: Option<&str>,
    gc_policy: &str,
    jvm_extra_args: &str,
    jvm_args_mode: &str,
    loader_version: &str,
) -> Result<()> {
    let n = conn.execute(
        "UPDATE instances SET name=?1, mc_version=?2, loader=?3, ram_mb=?4, description=?5, jvm_vendor=?6, jvm_custom_path=?7, gc_policy=?8, jvm_extra_args=?9, jvm_args_mode=?10, loader_version=?11
         WHERE id=?12 AND yuyu_user_id=?13",
        params![name, mc_version, loader, ram_mb, description, jvm_vendor, jvm_custom_path, gc_policy, jvm_extra_args, jvm_args_mode, loader_version, id, user_id],
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

/// Identifiants de **toutes** les instances connues, tous comptes confondus.
///
/// Sert uniquement à la synchronisation de démarrage, et c'est délibéré : un
/// dossier n'est « inconnu » que s'il n'appartient à personne. Comparer à la
/// seule liste du compte courant faisait passer pour orphelines les instances
/// d'un autre compte — et celles de tout le monde quand on était déconnecté,
/// puisque `yuyu_user_id` valait alors 0 et ne correspondait plus à rien.
pub fn instance_all_ids(conn: &Connection) -> Result<Vec<String>> {
    let mut stmt = conn.prepare("SELECT id FROM instances")?;
    let rows = stmt
        .query_map([], |r| r.get::<_, String>(0))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

/// Reassigne les instances et les configurations JVM sans compte
/// (yuyu_user_id = 0) à l'utilisateur qui vient de se connecter.
pub fn instance_claim_unclaimed(conn: &Connection, user_id: i64) -> Result<()> {
    conn.execute(
        "UPDATE instances SET yuyu_user_id = ?1 WHERE yuyu_user_id = 0",
        params![user_id],
    )?;
    conn.execute(
        "UPDATE jvm_profiles SET yuyu_user_id = ?1 WHERE yuyu_user_id = 0",
        params![user_id],
    )?;
    Ok(())
}

/// Le pendant : quand plus personne n'est connecté, tout ce qui est sur ce PC
/// redevient visible (yuyu_user_id = 0).
///
/// Les instances sont des dossiers de ce PC ; le compte YuyuFrame ne sert
/// qu'à l'abonnement et à la synchronisation. Sans ça, une déconnexion — et à
/// plus forte raison la déconnexion générale de la 0.1.4 — présentait une
/// liste vide à quelqu'un dont les mondes étaient intacts, ce qui se lit
/// exactement comme une perte de données. Rien n'est effacé ni déplacé : seul
/// change le compte auquel les lignes sont rattachées, et la prochaine
/// connexion les reprend (`instance_claim_unclaimed`).
///
/// TOUTES les lignes, pas seulement celles du compte qui part : appelée aussi
/// au démarrage sans session, elle rattrape les installations déjà
/// déconnectées, dont les lignes portent encore un ancien numéro de compte.
pub fn instance_release_all(conn: &Connection) -> Result<()> {
    conn.execute("UPDATE instances SET yuyu_user_id = 0 WHERE yuyu_user_id <> 0", [])?;
    conn.execute("UPDATE jvm_profiles SET yuyu_user_id = 0 WHERE yuyu_user_id <> 0", [])?;
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

#[cfg(test)]
mod tests {
    use super::*;

    fn db(name: &str) -> (Connection, std::path::PathBuf) {
        let path = std::env::temp_dir().join(format!("yuyu-instances-{name}-{}.db", uuid::Uuid::new_v4()));
        (crate::db::init_db(&path).unwrap(), path)
    }

    fn add(conn: &Connection, id: &str, user_id: i64) {
        instance_insert(conn, id, user_id, id, "1.21.4", "fabric", 4096, "", "auto", None, "auto", "", "append").unwrap();
    }

    /// La déconnexion ne doit jamais présenter une liste vide à quelqu'un
    /// dont les instances sont sur le disque.
    #[test]
    fn les_instances_restent_visibles_deconnecte_et_reviennent_a_la_connexion() {
        let (conn, path) = db("release");
        add(&conn, "survie", 7);
        add(&conn, "autre-compte", 9);
        add(&conn, "sans-compte", 0);
        assert!(instance_list(&conn, 0).unwrap().len() == 1, "avant : déconnecté, on ne voyait que l'instance sans compte");

        instance_release_all(&conn).unwrap();
        assert_eq!(instance_list(&conn, 0).unwrap().len(), 3, "déconnecté : tout ce qui est sur le PC");
        assert_eq!(instance_all_ids(&conn).unwrap().len(), 3, "rien n'est effacé");

        instance_claim_unclaimed(&conn, 7).unwrap();
        assert_eq!(instance_list(&conn, 7).unwrap().len(), 3, "reconnecté : tout revient au compte");
        assert!(instance_list(&conn, 0).unwrap().is_empty());

        drop(conn);
        let _ = std::fs::remove_file(path);
    }
}
