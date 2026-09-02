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
    let s = state.read().await;
    let uid = user_id(&s);
    let db = s.db.lock().await;
    db::instance_insert(&db, &id, uid, &name, &mc_version, &loader, ram_mb, &description, &jvm_vendor, jvm_custom_path.as_deref(), &gc_policy, &jvm_extra_args, &jvm_args_mode)
        .map_err(|e| e.to_string())?;
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

/// Copie `options.txt` de l'instance vers un template global dans le dossier
/// YuyuFrame — ce template sera appliqué aux nouvelles instances si le réglage
/// "sync paramètres" est actif côté launcher.
#[tauri::command]
pub async fn instance_export_settings(instance_id: String) -> Result<(), String> {
    let src = instance_dir(&instance_id).join("options.txt");
    if !src.exists() {
        return Err("Aucun fichier options.txt dans cette instance — lance le jeu au moins une fois pour le générer".into());
    }
    let dest = minecraft_dir().join("shared_options.txt");
    tokio::fs::copy(&src, &dest).await.map_err(|e| e.to_string())?;
    Ok(())
}

/// Applique le template global `shared_options.txt` à une instance.
/// Retourne `true` si le template existait et a été copié, `false` s'il est absent.
#[tauri::command]
pub async fn instance_apply_settings(instance_id: String) -> Result<bool, String> {
    let src = minecraft_dir().join("shared_options.txt");
    if !src.exists() {
        return Ok(false);
    }
    tokio::fs::create_dir_all(instance_dir(&instance_id)).await.map_err(|e| e.to_string())?;
    let dest = instance_dir(&instance_id).join("options.txt");
    tokio::fs::copy(&src, &dest).await.map_err(|e| e.to_string())?;
    Ok(true)
}

/// Ouvre le dossier de l'instance dans l'explorateur Windows — le crée
/// d'abord si l'instance n'a encore jamais été lancée (ex: juste après
/// import), sinon `explorer.exe` échoue silencieusement sur un chemin absent.
#[tauri::command]
pub async fn instance_open_folder(instance_id: String) -> Result<(), String> {
    crate::paths::open_in_explorer(&instance_dir(&instance_id))
}

/// Synchronise la DB avec les dossiers réels au démarrage.
/// mode = "db_wins"   → supprime les dossiers orphelins sur le disque
/// mode = "disk_wins" → importe en DB les dossiers qui ont un meta.json
#[tauri::command]
pub async fn instance_startup_sync(
    state: tauri::State<'_, SharedState>,
    mode: String,
) -> Result<(), String> {
    use std::collections::HashSet;

    let s = state.read().await;
    let uid = user_id(&s);
    let db = s.db.lock().await;

    let db_rows = db::instance_list(&db, uid).map_err(|e| e.to_string())?;
    let db_ids: HashSet<String> = db_rows.iter().map(|r| r.id.clone()).collect();

    let instances_root = minecraft_dir().join("instances");
    if !instances_root.is_dir() {
        let _ = std::fs::create_dir_all(&instances_root);
        return Ok(());
    }

    let disk_ids: HashSet<String> = std::fs::read_dir(&instances_root)
        .map_err(|e| e.to_string())?
        .filter_map(|e| e.ok())
        .filter(|e| e.path().is_dir())
        .filter_map(|e| e.file_name().into_string().ok())
        .collect();

    // DB entry existe mais le dossier a disparu → retirer de la DB
    for id in db_ids.difference(&disk_ids) {
        db::instance_delete(&db, id, uid).ok();
    }

    // Dossier présent mais pas en DB
    let orphan_ids: Vec<String> = disk_ids.difference(&db_ids).cloned().collect();
    match mode.as_str() {
        "db_wins" => {
            for id in orphan_ids {
                let _ = std::fs::remove_dir_all(instances_root.join(&id));
            }
        }
        "disk_wins" => {
            for id in orphan_ids {
                let meta_path = instances_root.join(&id).join("meta.json");
                if let Ok(json) = std::fs::read_to_string(&meta_path) {
                    if let Ok(meta) = serde_json::from_str::<InstanceMeta>(&json) {
                        db::instance_insert(&db, &meta.id, uid, &meta.name, &meta.mc_version, &meta.loader, meta.ram_mb, &meta.description, &meta.jvm_vendor, meta.jvm_custom_path.as_deref(), &meta.gc_policy, &meta.jvm_extra_args, &meta.jvm_args_mode).ok();
                    }
                }
                // Pas de meta.json → on laisse le dossier, impossible d'importer
            }
        }
        _ => {}
    }

    Ok(())
}
