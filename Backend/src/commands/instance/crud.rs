use serde::{Deserialize, Serialize};
use std::path::PathBuf;

use crate::db;
use crate::minecraft::launcher::minecraft_dir;
use crate::state::SharedState;

#[derive(Serialize, Deserialize, Clone)]
pub struct Instance {
    pub id: String,
    pub name: String,
    pub mc_version: String,
    pub loader: String,
    pub ram_mb: u32,
    pub favorite: bool,
    pub description: String,
    /// P1-6 (audit launcher, Phase 6) — "temurin" (défaut) | "openj9" | "graal" | "custom".
    pub jvm_vendor: String,
    pub jvm_custom_path: Option<String>,
    /// "auto" (défaut) ou une policy explicite — voir `build_jvm_args`.
    pub gc_policy: String,
    /// Drapeaux JVM saisis à la main (écran "Configuration JVM"), texte brut.
    pub jvm_extra_args: String,
    /// "append" (défaut) | "replace" — voir `merge_jvm_args`.
    pub jvm_args_mode: String,
    /// Config JVM reliée (`db::jvm_profile`) — quand elle est là, elle
    /// remplace les trois champs ci-dessus au lancement.
    ///
    /// Volontairement absent de `meta.json` : les configs elles-mêmes ne
    /// vivent qu'en base, un `meta.json` restauré sur une DB neuve pointerait
    /// donc vers une config qui n'existe plus. Le repli "disk_wins" retombe
    /// sur les colonnes `jvm_*` de l'instance, ce qui est le comportement par
    /// défaut du launcher — sûr, jamais un lancement avec des drapeaux
    /// inattendus.
    pub jvm_profile_id: Option<String>,
}

#[derive(Serialize, Deserialize)]
struct InstanceMeta {
    id: String,
    name: String,
    mc_version: String,
    loader: String,
    ram_mb: u32,
    #[serde(default)]
    description: String,
    #[serde(default = "default_jvm_vendor")]
    jvm_vendor: String,
    #[serde(default)]
    jvm_custom_path: Option<String>,
    #[serde(default = "default_gc_policy")]
    gc_policy: String,
    #[serde(default)]
    jvm_extra_args: String,
    #[serde(default = "default_jvm_args_mode")]
    jvm_args_mode: String,
}

fn default_jvm_vendor() -> String { "auto".to_string() }
fn default_gc_policy() -> String { "auto".to_string() }
fn default_jvm_args_mode() -> String { "append".to_string() }

#[allow(clippy::too_many_arguments)]
fn write_meta(id: &str, name: &str, mc_version: &str, loader: &str, ram_mb: u32, description: &str, jvm_vendor: &str, jvm_custom_path: Option<&str>, gc_policy: &str, jvm_extra_args: &str, jvm_args_mode: &str) {
    let meta = InstanceMeta {
        id: id.to_string(),
        name: name.to_string(),
        mc_version: mc_version.to_string(),
        loader: loader.to_string(),
        ram_mb,
        description: description.to_string(),
        jvm_vendor: jvm_vendor.to_string(),
        jvm_custom_path: jvm_custom_path.map(str::to_string),
        gc_policy: gc_policy.to_string(),
        jvm_extra_args: jvm_extra_args.to_string(),
        jvm_args_mode: jvm_args_mode.to_string(),
    };
    match serde_json::to_string_pretty(&meta) {
        Ok(json) => {
            // Best-effort : la DB reste la source de vérité pour cette instance
            // (voir instance_list/get/update), meta.json ne sert qu'au repli
            // "disk_wins" de instance_startup_sync si la DB est perdue — un
            // échec ici ne doit pas faire échouer la commande appelante, mais
            // doit au moins être visible dans les logs plutôt que muet.
            if let Err(e) = std::fs::write(instance_dir(id).join("meta.json"), json) {
                tracing::warn!("Écriture de meta.json pour l'instance {} échouée : {}", id, e);
            }
        }
        Err(e) => tracing::warn!("Sérialisation de meta.json pour l'instance {} échouée : {}", id, e),
    }
}

pub fn instance_dir(id: &str) -> PathBuf {
    minecraft_dir().join("instances").join(id)
}

pub fn instance_mods_dir(id: &str) -> PathBuf {
    instance_dir(id).join("mods")
}

/// Réduit un nom d'instance à un slug de dossier lisible (minuscules,
/// alphanumérique uniquement, tirets comme séparateurs, tronqué). Les accents
/// et autres caractères non-ASCII sont abandonnés plutôt que translittérés —
/// suffisant pour un nom de dossier lisible, pas la peine d'une vraie
/// translittération pour ce seul usage.
fn slugify(name: &str) -> String {
    let mut slug = String::new();
    for c in name.trim().to_lowercase().chars() {
        if c.is_ascii_alphanumeric() {
            slug.push(c);
        } else if !slug.ends_with('-') && !slug.is_empty() {
            slug.push('-');
        }
    }
    while slug.ends_with('-') {
        slug.pop();
    }
    slug.chars().take(24).collect()
}

/// Nom de dossier lisible : `<nom-slugifié>-<code>` plutôt qu'un code
/// opaque seul — le suffixe aléatoire (8 caractères, largement suffisant vu
/// qu'il est déjà désambiguïsé par le nom) garantit l'unicité même entre deux
/// instances slugifiées à l'identique. Voir aussi `commands/launch.rs` qui
/// utilise la FIN de l'id (le suffixe, jamais le nom) comme label de fenêtre
/// console — ne jamais dépendre du début de l'id pour l'unicité.
pub(crate) fn gen_id(name: &str) -> String {
    use rand::Rng;
    let suffix: String = rand::thread_rng()
        .sample_iter(rand::distributions::Alphanumeric)
        .take(8)
        .map(char::from)
        .collect::<String>()
        .to_lowercase();
    let slug = slugify(name);
    if slug.is_empty() {
        suffix
    } else {
        format!("{}-{}", slug, suffix)
    }
}

fn row_to_instance(r: db::InstanceRow) -> Instance {
    Instance {
        id: r.id, name: r.name, mc_version: r.mc_version, loader: r.loader, ram_mb: r.ram_mb,
        favorite: r.favorite, description: r.description,
        jvm_vendor: r.jvm_vendor, jvm_custom_path: r.jvm_custom_path, gc_policy: r.gc_policy,
        jvm_extra_args: r.jvm_extra_args, jvm_args_mode: r.jvm_args_mode,
        jvm_profile_id: r.jvm_profile_id,
    }
}

fn user_id(s: &crate::state::AppState) -> i64 {
    s.current_yuyu_user_id().unwrap_or(0)
}

/// Migration one-shot des ids legacy vers `<nom-slugifié>-<code>` (voir
/// `migrate_legacy_instance_ids` dans lib.rs) — calculée une fois au démarrage
/// et stockée dans l'état partagé, consommée par le frontend pour remapper ses
/// propres clés persistées (serveurs favoris, mods épinglés...) qui référencent
/// encore l'ancien id.
#[tauri::command]
pub async fn instance_id_migrations(state: tauri::State<'_, SharedState>) -> Result<Vec<(String, String)>, String> {
    Ok(state.read().await.instance_id_migrations.clone())
}

#[tauri::command]
pub async fn instance_list(state: tauri::State<'_, SharedState>) -> Result<Vec<Instance>, String> {
    let s = state.read().await;
    let uid = user_id(&s);
    let db = s.db.lock().await;
    db::instance_list(&db, uid)
        .map(|rows| rows.into_iter().map(row_to_instance).collect())
        .map_err(|e| e.to_string())
}

#[tauri::command]
#[allow(clippy::too_many_arguments)]
pub async fn instance_create(
    state: tauri::State<'_, SharedState>,
    name: String,
    mc_version: String,
    loader: String,
    ram_mb: u32,
    description: Option<String>,
    jvm_vendor: Option<String>,
    jvm_custom_path: Option<String>,
    gc_policy: Option<String>,
    jvm_extra_args: Option<String>,
    jvm_args_mode: Option<String>,
) -> Result<Instance, String> {
    if name.trim().is_empty() {
        return Err("Le nom de l'instance est requis".into());
    }
    let description = description.unwrap_or_default().trim().to_string();
    let name = name.trim().to_string();
    let jvm_vendor = jvm_vendor.unwrap_or_else(default_jvm_vendor);
    let gc_policy = gc_policy.unwrap_or_else(default_gc_policy);
    let jvm_extra_args = jvm_extra_args.unwrap_or_default();
    let jvm_args_mode = jvm_args_mode.unwrap_or_else(default_jvm_args_mode);
    let id = gen_id(&name);
    tokio::fs::create_dir_all(instance_dir(&id))
        .await
        .map_err(|e| e.to_string())?;
    write_meta(&id, &name, &mc_version, &loader, ram_mb, &description, &jvm_vendor, jvm_custom_path.as_deref(), &gc_policy, &jvm_extra_args, &jvm_args_mode);
    let sync_settings = {
        let s = state.read().await;
        let uid = user_id(&s);
        let db = s.db.lock().await;
        db::instance_insert(&db, &id, uid, &name, &mc_version, &loader, ram_mb, &description, &jvm_vendor, jvm_custom_path.as_deref(), &gc_policy, &jvm_extra_args, &jvm_args_mode)
            .map_err(|e| e.to_string())?;
        db::prefs::get_bool(&db, db::prefs::SYNC_GAME_SETTINGS, false)
    };
    // Le verrou de la base est relâché avant de toucher au disque : la copie
    // du modèle n'a rien à faire sous un verrou que toutes les autres
    // commandes attendent.
    super::options::apply_template_on_create(sync_settings, &id).await;
    crate::integrations::analytics::capture("instance_created", serde_json::json!({
        "mc_version": &mc_version,
        "loader": &loader,
    }));
    Ok(Instance { id, name, mc_version, loader, ram_mb, favorite: false, description, jvm_vendor, jvm_custom_path, gc_policy, jvm_extra_args, jvm_args_mode, jvm_profile_id: None })
}

#[tauri::command]
pub async fn instance_delete(
    state: tauri::State<'_, SharedState>,
    id: String,
) -> Result<(), String> {
    {
        let s = state.read().await;
        let uid = user_id(&s);
        let db = s.db.lock().await;
        db::instance_delete(&db, &id, uid).map_err(|e| e.to_string())?;
    }
    let dir = instance_dir(&id);
    if dir.exists() {
        tokio::fs::remove_dir_all(&dir).await.map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[tauri::command]
pub async fn instance_toggle_favorite(
    state: tauri::State<'_, SharedState>,
    id: String,
) -> Result<Instance, String> {
    let s = state.read().await;
    let uid = user_id(&s);
    let db = s.db.lock().await;
    let row = db::instance_get(&db, &id, uid)
        .map_err(|e| e.to_string())?
        .ok_or("Instance introuvable")?;
    db::instance_set_favorite(&db, &id, uid, !row.favorite).map_err(|e| e.to_string())?;
    let updated = db::instance_get(&db, &id, uid)
        .map_err(|e| e.to_string())?
        .ok_or("Instance introuvable")?;
    Ok(row_to_instance(updated))
}

#[tauri::command]
#[allow(clippy::too_many_arguments)]
pub async fn instance_update(
    state: tauri::State<'_, SharedState>,
    id: String,
    name: String,
    mc_version: String,
    loader: String,
    ram_mb: u32,
    description: Option<String>,
    jvm_vendor: Option<String>,
    jvm_custom_path: Option<String>,
    gc_policy: Option<String>,
    jvm_extra_args: Option<String>,
    jvm_args_mode: Option<String>,
) -> Result<Instance, String> {
    let name = name.trim().to_string();
    let description = description.unwrap_or_default().trim().to_string();
    let jvm_vendor = jvm_vendor.unwrap_or_else(default_jvm_vendor);
    let gc_policy = gc_policy.unwrap_or_else(default_gc_policy);
    let jvm_extra_args = jvm_extra_args.unwrap_or_default();
    let jvm_args_mode = jvm_args_mode.unwrap_or_else(default_jvm_args_mode);
    let s = state.read().await;
    let uid = user_id(&s);
    let db = s.db.lock().await;
    db::instance_update(&db, &id, uid, &name, &mc_version, &loader, ram_mb, &description, &jvm_vendor, jvm_custom_path.as_deref(), &gc_policy, &jvm_extra_args, &jvm_args_mode)
        .map_err(|e| e.to_string())?;
    write_meta(&id, &name, &mc_version, &loader, ram_mb, &description, &jvm_vendor, jvm_custom_path.as_deref(), &gc_policy, &jvm_extra_args, &jvm_args_mode);
    let row = db::instance_get(&db, &id, uid)
        .map_err(|e| e.to_string())?
        .ok_or("Instance introuvable")?;
    Ok(row_to_instance(row))
}

#[tauri::command]
pub async fn instance_duplicate(
    state: tauri::State<'_, SharedState>,
    source_id: String,
    name: String,
    mc_version: String,
    ram_mb: u32,
) -> Result<Instance, String> {
    if name.trim().is_empty() {
        return Err("Le nom de l'instance est requis".into());
    }
    let name = name.trim().to_string();

    let (loader, jvm_vendor, jvm_custom_path, gc_policy, jvm_extra_args, jvm_args_mode) = {
        let s = state.read().await;
        let uid = user_id(&s);
        let db = s.db.lock().await;
        let src = db::instance_get(&db, &source_id, uid)
            .map_err(|e| e.to_string())?
            .ok_or("Instance source introuvable")?;
        (src.loader, src.jvm_vendor, src.jvm_custom_path, src.gc_policy, src.jvm_extra_args, src.jvm_args_mode)
    };

    let new_id = gen_id(&name);
    tokio::fs::create_dir_all(instance_dir(&new_id)).await.map_err(|e| e.to_string())?;

    let src_mods = instance_mods_dir(&source_id);
    if src_mods.exists() {
        let dst_mods = instance_mods_dir(&new_id);
        tokio::fs::create_dir_all(&dst_mods).await.map_err(|e| e.to_string())?;
        let mut dir = tokio::fs::read_dir(&src_mods).await.map_err(|e| e.to_string())?;
        while let Some(entry) = dir.next_entry().await.map_err(|e| e.to_string())? {
            let src_path = entry.path();
            if src_path.is_file() {
                if let Some(filename) = src_path.file_name() {
                    tokio::fs::copy(&src_path, dst_mods.join(filename)).await.map_err(|e| e.to_string())?;
                }
            }
        }
    }

    // Les réglages Minecraft de la source suivent la copie.
    //
    // Seule la duplication les prend chez la source plutôt que dans le modèle
    // global : dupliquer, c'est demander la même chose, y compris les
    // touches et la distance d'affichage. Sans ça, une instance dupliquée
    // repartait avec les réglages d'usine alors que ses mods étaient bien là
    // — un écart d'autant plus déroutant qu'il ne concernait qu'un fichier.
    let src_options = instance_dir(&source_id).join("options.txt");
    if src_options.exists() {
        if let Err(e) = tokio::fs::copy(&src_options, instance_dir(&new_id).join("options.txt")).await {
            // Best-effort : une instance dupliquée sans son options.txt reste
            // parfaitement jouable.
            tracing::warn!("Copie des réglages Minecraft vers la copie {} échouée : {}", new_id, e);
        }
    }

    write_meta(&new_id, &name, &mc_version, &loader, ram_mb, "", &jvm_vendor, jvm_custom_path.as_deref(), &gc_policy, &jvm_extra_args, &jvm_args_mode);

    let s = state.read().await;
    let uid = user_id(&s);
    let db = s.db.lock().await;
    db::instance_insert(&db, &new_id, uid, &name, &mc_version, &loader, ram_mb, "", &jvm_vendor, jvm_custom_path.as_deref(), &gc_policy, &jvm_extra_args, &jvm_args_mode)
        .map_err(|e| e.to_string())?;

    // La config JVM reliée n'est PAS dupliquée : une config est justement
    // faite pour être partagée entre instances, la copie repart donc déliée
    // plutôt que d'hériter d'un lien que l'utilisateur n'a pas demandé.
    Ok(Instance { id: new_id, name, mc_version, loader, ram_mb, favorite: false, description: String::new(), jvm_vendor, jvm_custom_path, gc_policy, jvm_extra_args, jvm_args_mode, jvm_profile_id: None })
}

// Le modèle `shared_options.txt` (export, application, état) vit désormais
// dans `options.rs`, avec tout ce qui touche aux réglages Minecraft.

/// Ouvre le dossier de l'instance dans l'explorateur Windows — le crée
/// d'abord si l'instance n'a encore jamais été lancée (ex: juste après
/// import), sinon `explorer.exe` échoue silencieusement sur un chemin absent.
#[tauri::command]
pub async fn instance_open_folder(instance_id: String) -> Result<(), String> {
    crate::paths::open_in_explorer(&instance_dir(&instance_id))
}

/// Ce que la synchronisation de démarrage a le droit de faire.
///
/// Séparé de la commande pour être testable : c'est du calcul d'ensembles, et
/// c'est exactement là que s'est produite la perte de données du 2026-09-27
/// (voir la javadoc de `instance_startup_sync`).
struct StartupSyncPlan {
    /// Lignes du compte courant dont le dossier a disparu — à retirer de la
    /// liste, le dossier n'existe plus de toute façon.
    rows_to_forget: Vec<String>,
    /// Dossiers qu'aucune ligne de la base ne revendique, tous comptes
    /// confondus. Candidats à l'import — **jamais** à la suppression.
    folders_unknown: Vec<String>,
}

fn plan_startup_sync(
    disk_ids: &[String],
    all_db_ids: &[String],
    own_db_ids: &[String],
) -> StartupSyncPlan {
    use std::collections::HashSet;
    let disk: HashSet<&String> = disk_ids.iter().collect();
    let all: HashSet<&String> = all_db_ids.iter().collect();

    let mut rows_to_forget: Vec<String> = own_db_ids
        .iter()
        .filter(|id| !disk.contains(id))
        .cloned()
        .collect();
    let mut folders_unknown: Vec<String> = disk_ids
        .iter()
        .filter(|id| !all.contains(id))
        .cloned()
        .collect();
    rows_to_forget.sort();
    folders_unknown.sort();

    StartupSyncPlan { rows_to_forget, folders_unknown }
}

/// Réconcilie la base et les dossiers réels au démarrage.
///
/// ── Ce que cette fonction ne fait plus, et pourquoi ───────────────────────
/// Elle supprimait les dossiers « orphelins » avec `remove_dir_all` quand le
/// mode valait `db_wins` (le défaut). Trois défauts qui se sont additionnés
/// le 2026-09-27 et ont effacé les instances — mondes compris — de tous ceux
/// qui ont installé la nouvelle version :
///
///  1. la liste de référence était **celle du compte connecté**
///     (`instance_list(db, uid)`). Déconnecté, `uid` vaut 0 et ne correspond
///     à aucune ligne : toutes les instances devenaient orphelines d'un coup.
///     Connecté sur un second compte, celles du premier subissaient le même
///     sort ;
///  2. la base vivait **à côté de l'exécutable** (voir `lib.rs`) : une
///     réinstallation la laissait derrière elle, et le launcher redémarrait
///     donc sur une base vide, face à un disque plein d'instances ;
///  3. la suppression était **définitive et silencieuse** — pas de corbeille,
///     pas de confirmation, pas de log.
///
/// Un launcher n'a aucune raison d'effacer des mondes tout seul. Le mode ne
/// décide donc plus que du sort des dossiers **inconnus de toute la base** :
/// les importer dans la liste, ou les laisser tranquilles. Dans les deux cas
/// ils restent sur le disque.
#[tauri::command]
pub async fn instance_startup_sync(
    state: tauri::State<'_, SharedState>,
    mode: String,
) -> Result<(), String> {
    let s = state.read().await;
    let uid = user_id(&s);
    let db = s.db.lock().await;

    let instances_root = minecraft_dir().join("instances");
    if !instances_root.is_dir() {
        let _ = std::fs::create_dir_all(&instances_root);
        return Ok(());
    }

    let disk_ids: Vec<String> = std::fs::read_dir(&instances_root)
        .map_err(|e| e.to_string())?
        .filter_map(|e| e.ok())
        .filter(|e| e.path().is_dir())
        .filter_map(|e| e.file_name().into_string().ok())
        .collect();

    // Tous comptes confondus pour juger un dossier inconnu, le compte courant
    // pour nettoyer ses propres lignes — voir `plan_startup_sync`.
    let all_db_ids = db::instance_all_ids(&db).map_err(|e| e.to_string())?;
    let own_db_ids: Vec<String> = db::instance_list(&db, uid)
        .map_err(|e| e.to_string())?
        .into_iter()
        .map(|r| r.id)
        .collect();

    let plan = plan_startup_sync(&disk_ids, &all_db_ids, &own_db_ids);

    for id in &plan.rows_to_forget {
        tracing::info!("Instance {} retirée de la liste : son dossier n'existe plus", id);
        db::instance_delete(&db, id, uid).ok();
    }

    for id in &plan.folders_unknown {
        // `meta.json` est écrit à chaque création/modification (voir
        // `write_meta`) : c'est ce qui permet de retrouver une instance dont
        // la base a été perdue. Sans lui on ne sait pas quoi inscrire — le
        // dossier reste sur le disque, intact, en attendant mieux.
        let meta_path = instances_root.join(id).join("meta.json");
        let Ok(json) = std::fs::read_to_string(&meta_path) else {
            tracing::info!("Dossier {} inconnu de la base et sans meta.json — laissé tel quel", id);
            continue;
        };
        if mode != "disk_wins" {
            tracing::info!("Dossier {} inconnu de la base — non importé (réglage), laissé tel quel", id);
            continue;
        }
        match serde_json::from_str::<InstanceMeta>(&json) {
            Ok(meta) => {
                tracing::info!("Instance {} réimportée depuis son meta.json", id);
                db::instance_insert(&db, &meta.id, uid, &meta.name, &meta.mc_version, &meta.loader, meta.ram_mb, &meta.description, &meta.jvm_vendor, meta.jvm_custom_path.as_deref(), &meta.gc_policy, &meta.jvm_extra_args, &meta.jvm_args_mode).ok();
            }
            Err(e) => tracing::warn!("meta.json illisible pour {} : {} — dossier laissé tel quel", id, e),
        }
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ids(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn un_dossier_appartenant_a_un_autre_compte_n_est_pas_orphelin() {
        // Le cœur de la perte de données : `own` est vide (déconnecté, ou
        // connecté sur un autre compte) alors que la base connaît bien ces
        // instances. Rien ne doit être considéré comme inconnu.
        let plan = plan_startup_sync(&ids(&["a", "b"]), &ids(&["a", "b"]), &ids(&[]));
        assert!(plan.folders_unknown.is_empty());
        assert!(plan.rows_to_forget.is_empty());
    }

    #[test]
    fn une_base_vide_ne_condamne_rien() {
        // Base perdue (réinstallation) : tout le disque est « inconnu », mais
        // inconnu ne veut plus dire supprimable — seulement importable.
        let plan = plan_startup_sync(&ids(&["a", "b"]), &ids(&[]), &ids(&[]));
        assert_eq!(plan.folders_unknown, ids(&["a", "b"]));
        assert!(plan.rows_to_forget.is_empty());
    }

    #[test]
    fn une_ligne_sans_dossier_est_oubliee() {
        let plan = plan_startup_sync(&ids(&["a"]), &ids(&["a", "b"]), &ids(&["a", "b"]));
        assert_eq!(plan.rows_to_forget, ids(&["b"]));
        assert!(plan.folders_unknown.is_empty());
    }

    #[test]
    fn seules_les_lignes_du_compte_courant_sont_oubliees() {
        // `b` appartient à un autre compte et son dossier a disparu : ce n'est
        // pas à la session courante de faire le ménage chez lui.
        let plan = plan_startup_sync(&ids(&["a"]), &ids(&["a", "b"]), &ids(&["a"]));
        assert!(plan.rows_to_forget.is_empty());
    }
}
