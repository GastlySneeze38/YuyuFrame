//! Ce qui se rattrape au démarrage : la base restée à l'ancien emplacement,
//! et les instances encore nommées à l'ancienne façon. Les deux sont jouées
//! une fois par `crate::run`, avant que quoi que ce soit ne lise la base.

use crate::{db, paths};

/// Renomme une seule fois les instances créées avant l'introduction du nouvel
/// id lisible (`<nom-slugifié>-<code>`, voir `instances::crud::gen_id`)
/// — l'id sert à la fois de clé primaire DB et de nom de dossier disque
/// (`instance_dir()`), donc renommer l'un sans l'autre laisserait l'instance
/// introuvable. Best-effort et sûr : un dossier verrouillé/permissions
/// refusées laisse l'instance sur son ancien id, retentée au prochain
/// démarrage plutôt que de risquer une instance perdue. Retourne les paires
/// (ancien id, nouvel id) — le frontend les récupère via la commande
/// `instance_id_migrations` pour remapper ses propres clés persistées
/// (serveurs favoris, mods épinglés...) qui référencent encore l'ancien id.
pub fn migrate_legacy_instance_ids(conn: &rusqlite::Connection) -> Vec<(String, String)> {
    let legacy = match db::instance_legacy_ids(conn) {
        Ok(v) => v,
        Err(e) => {
            tracing::warn!("Migration ids instances : lecture échouée : {}", e);
            return Vec::new();
        }
    };
    if legacy.is_empty() {
        return Vec::new();
    }

    let instances_root = paths::root().join(".minecraft").join("instances");
    let mut migrated = Vec::new();

    for (old_id, name) in legacy {
        let new_id = crate::instances::crud::gen_id(&name);
        let old_dir = instances_root.join(&old_id);
        let new_dir = instances_root.join(&new_id);

        if !old_dir.is_dir() || new_dir.exists() {
            continue;
        }
        if let Err(e) = std::fs::rename(&old_dir, &new_dir) {
            tracing::warn!("Migration instance {} → {} : renommage du dossier échoué : {}", old_id, new_id, e);
            continue;
        }
        if let Err(e) = db::instance_rename_id(conn, &old_id, &new_id) {
            tracing::warn!("Migration instance {} → {} : mise à jour DB échouée, restauration du dossier : {}", old_id, new_id, e);
            let _ = std::fs::rename(&new_dir, &old_dir);
            continue;
        }

        // meta.json embarque aussi l'id (repli "disk_wins" de instance_startup_sync)
        // — best-effort, une erreur ici ne remet pas en cause le renommage déjà validé en DB.
        let meta_path = new_dir.join("meta.json");
        if let Ok(json) = std::fs::read_to_string(&meta_path) {
            if let Ok(mut v) = serde_json::from_str::<serde_json::Value>(&json) {
                v["id"] = serde_json::Value::String(new_id.clone());
                if let Ok(pretty) = serde_json::to_string_pretty(&v) {
                    let _ = std::fs::write(&meta_path, pretty);
                }
            }
        }

        tracing::info!("Instance renommée : {} → {}", old_id, new_id);
        migrated.push((old_id, new_id));
    }

    migrated
}

/// Récupère la base restée à côté de l'exécutable par les versions ≤ 0.1.0-27.
///
/// Copie plutôt que déplace : si quelque chose se passe mal pendant la
/// migration, l'original est toujours là. L'ancienne base n'est pas effacée —
/// elle sera emportée par la prochaine réinstallation, ce qui est justement
/// la raison de ce déménagement.
///
/// Ne fait rien si la nouvelle existe déjà : elle fait autorité, et écraser
/// une base en service par une vieille copie serait pire que le bug d'origine.
pub fn migrate_db_from_exe_dir(target: &std::path::Path) {
    if target.exists() {
        return;
    }
    let Some(old) = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.join("yuyu.db")))
    else {
        return;
    };
    if !old.is_file() {
        return;
    }
    if let Some(parent) = target.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    match std::fs::copy(&old, target) {
        Ok(_) => tracing::info!(
            "Base de données récupérée depuis {} vers {}",
            old.display(),
            target.display()
        ),
        Err(e) => tracing::error!(
            "Récupération de la base {} impossible : {} — le launcher démarre sur une base neuve",
            old.display(),
            e
        ),
    }
}
