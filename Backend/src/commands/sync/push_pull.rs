//! Synchronisation d'une instance entre plusieurs PC.
//!
//! Réécrite le 2026-09-21 sur le protocole par morceaux de `/v1/sync`. Avant,
//! le launcher compressait toute l'instance dans un zip et l'envoyait d'un
//! bloc : la moindre coupure repartait de zéro, et un modpack de 2 Gio
//! saturait le serveur à chaque petit changement. Maintenant seuls les
//! morceaux que le serveur n'a pas encore partent — changer un mod envoie ce
//! mod, pas l'instance.
//!
//! **Les mondes ne sont pas synchronisés.** C'est une décision, pas un oubli :
//! deux PC sur lesquels on a joué donnent deux versions d'un même monde, et
//! aucune fusion n'a de sens. Les mondes relèvent du backup (voir
//! `crate::backup`), qui empile des versions datées au lieu de prétendre
//! réconcilier.
//!
//! Aucun jeton n'est manipulé ici : tout passe par `crate::api`, qui
//! rafraîchit de lui-même — indispensable sur un envoi qui dure plus de
//! quinze minutes.

use serde_json::{json, Value};
use tauri::Emitter;

use super::super::instance::crud::instance_dir;
use super::archive::{dir_size, SaveInfo, SyncInstance, SyncProgressEvent};
use crate::api;
use crate::state::SharedState;
use crate::sync::chunks::{self, FileEntry, Manifest, Progress};

/// Ce qui part dans la sync. Volontairement court : la configuration d'un
/// modpack et ses mods, rien d'autre. `.yuyuframe` porte le document des mods
/// référencés (voir `crate::sync::mods`).
const SYNCED: [&str; 5] = ["mods", "config", "resourcepacks", "shaderpacks", ".yuyuframe"];

/// Jamais synchronisé, même à l'intérieur d'un dossier ci-dessus : ce sont
/// des fichiers que chaque PC régénère, et qui changent à chaque lancement.
const NEVER: [&str; 5] = ["logs", "crash-reports", "cache", ".git", "natives"];

fn progress(app: &tauri::AppHandle, phase: &str, percent: u8, label: String) {
    let _ = app.emit("sync_progress", SyncProgressEvent { phase: phase.into(), percent, label });
}

/// Pourcentage d'un transfert, borné à 99 : le 100 est réservé à la fin
/// réelle, pas à la fin de l'envoi des octets.
fn percent_of(p: Progress) -> u8 {
    if p.total_bytes <= 0 {
        return 99;
    }
    ((p.done_bytes * 99) / p.total_bytes).clamp(0, 99) as u8
}

fn human(bytes: i64) -> String {
    const UNITS: [&str; 4] = ["o", "Ko", "Mo", "Go"];
    let mut value = bytes as f64;
    let mut unit = 0;
    while value >= 1024.0 && unit < UNITS.len() - 1 {
        value /= 1024.0;
        unit += 1;
    }
    format!("{value:.1} {}", UNITS[unit])
}

// ── Lecture ──────────────────────────────────────────────────────────────────

/// Les mondes de l'instance. Toujours là malgré la sortie des mondes du
/// périmètre : c'est le backup qui s'en sert désormais pour proposer quoi
/// sauvegarder.
#[tauri::command]
pub async fn sync_list_saves(instance_id: String) -> Result<Vec<SaveInfo>, String> {
    let saves_dir = instance_dir(&instance_id).join("saves");
    tokio::task::spawn_blocking(move || {
        if !saves_dir.is_dir() {
            return Ok(Vec::new());
        }
        let mut saves: Vec<SaveInfo> = std::fs::read_dir(&saves_dir)
            .map_err(|e| e.to_string())?
            .flatten()
            .filter(|e| e.path().is_dir())
            .filter_map(|e| {
                let path = e.path();
                let name = path.file_name()?.to_str()?.to_string();
                let updated_at = std::fs::metadata(&path)
                    .ok()?
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                    .map(|d| d.as_secs() as i64)
                    .unwrap_or(0);
                Some(SaveInfo { name, updated_at, size_bytes: dir_size(&path) })
            })
            .collect();
        saves.sort_by_key(|s| std::cmp::Reverse(s.updated_at));
        Ok(saves)
    })
    .await
    .map_err(|e| e.to_string())?
}

#[tauri::command]
pub async fn sync_list_instances(state: tauri::State<'_, SharedState>) -> Result<Vec<SyncInstance>, String> {
    let value = api::get(&state, "/sync/instances", &[]).await.map_err(String::from)?;
    serde_json::from_value(value).map_err(|e| e.to_string())
}

// ── Envoi ────────────────────────────────────────────────────────────────────

#[tauri::command]
pub async fn sync_push_instance(
    state: tauri::State<'_, SharedState>,
    app: tauri::AppHandle,
    instance_id: String,
) -> Result<SyncInstance, String> {
    let (name, mc_version, loader, ram_mb) = instance_meta(&state, &instance_id).await?;
    let root = instance_dir(&instance_id);

    // 1. Les mods reconnus par Modrinth partent en référence, pas en contenu :
    //    un dossier de modpack de 2 Gio devient quelques dizaines de kilo-
    //    octets. Ce qui n'est reconnu nulle part reste envoyé tel quel — sinon
    //    on le perdrait (voir `crate::sync::mods`).
    progress(&app, "scanning", 2, "Identification des mods…".into());
    let http = state.read().await.http.clone();
    let (doc, referenced) = crate::sync::mods::resolve(&http, &root).await;
    let referenced_count = doc.mods.len();
    {
        let (root, doc) = (root.clone(), doc.clone());
        tokio::task::spawn_blocking(move || crate::sync::mods::write_doc(&root, &doc))
            .await
            .map_err(|e| e.to_string())?
            .map_err(|e| format!("Écriture du document des mods impossible : {e}"))?;
    }

    // 2. Le manifeste local, moins les mods référencés. Lire et hacher toute
    //    une instance prend du temps : sur un fil bloquant, jamais sur le
    //    runtime.
    progress(&app, "scanning", 4, "Analyse des fichiers…".into());
    let scan_root = root.clone();
    let files: Vec<FileEntry> = tokio::task::spawn_blocking(move || {
        chunks::scan(&scan_root, &SYNCED.map(String::from), &NEVER)
    })
    .await
    .map_err(|e| e.to_string())?
    .map_err(|e| format!("Lecture de l'instance impossible : {e}"))?
    .into_iter()
    .filter(|f| !referenced.contains(&f.path))
    .collect();

    if files.is_empty() {
        return Err("Rien à synchroniser : cette instance n'a ni mods ni configuration.".into());
    }
    let total: i64 = files.iter().map(|f| f.size).sum();

    // 3. L'instance côté serveur (quotas vérifiés là-bas). Zéro sauvegarde :
    //    les mondes ne passent pas par ici.
    let created = api::post(
        &state,
        "/sync/instances",
        json!({
            "instance_name": name,
            "mc_version": mc_version,
            "loader": loader,
            "ram_mb": ram_mb,
            "save_count": 0,
            "save_names": [],
        }),
    )
    .await
    .map_err(String::from)?;
    let remote: SyncInstance = serde_json::from_value(created).map_err(|e| e.to_string())?;

    // 4. Ce qui manque au serveur.
    progress(&app, "comparing", 6, format!("{} fichiers, {} au total", files.len(), human(total)));
    let missing_resp = api::post(&state, &format!("/sync/instances/{}/missing", remote.id), json!({ "files": files }))
        .await
        .map_err(String::from)?;
    let missing: Vec<String> = serde_json::from_value(missing_resp.get("missing").cloned().unwrap_or_default()).map_err(|e| e.to_string())?;

    // 5. Les morceaux manquants seulement.
    if missing.is_empty() {
        progress(&app, "uploading", 90, "Déjà à jour sur le serveur".into());
    } else {
        let app_progress = app.clone();
        let report = move |p: Progress| {
            progress(
                &app_progress,
                "uploading",
                percent_of(p),
                format!("Envoi — {} / {} · {} fichiers", human(p.done_bytes), human(p.total_bytes), p.total_files),
            );
        };
        chunks::upload_missing(&state, &root, &files, &missing, &report).await.map_err(String::from)?;
    }

    // 6. Valider. `base_revision` : si un autre PC a envoyé entre-temps, le
    //    serveur refuse (409) plutôt que d'écraser son travail.
    progress(&app, "uploading", 99, "Finalisation…".into());
    let committed = api::put(
        &state,
        &format!("/sync/instances/{}/manifest", remote.id),
        json!({ "base_revision": remote.revision, "files": files }),
    )
    .await
    .map_err(String::from)?;

    progress(
        &app,
        "done",
        100,
        if referenced_count > 0 {
            format!("Instance synchronisée — {} envoyés, {referenced_count} mods référencés", human(total))
        } else {
            format!("Instance synchronisée ({})", human(total))
        },
    );
    serde_json::from_value(committed).map_err(|e| e.to_string())
}

// ── Récupération ─────────────────────────────────────────────────────────────

#[tauri::command]
pub async fn sync_pull_instance(
    state: tauri::State<'_, SharedState>,
    app: tauri::AppHandle,
    sync_id: i64,
    instance_id: String,
) -> Result<(), String> {
    let root = instance_dir(&instance_id);
    tokio::fs::create_dir_all(&root).await.map_err(|e| e.to_string())?;

    progress(&app, "comparing", 4, "Lecture du manifeste…".into());
    let value = api::get(&state, &format!("/sync/instances/{sync_id}/manifest"), &[]).await.map_err(String::from)?;
    let manifest: Manifest = serde_json::from_value(value).map_err(|e| e.to_string())?;
    if manifest.files.is_empty() {
        return Err("Cette instance n'a encore rien de synchronisé.".into());
    }

    let app_progress = app.clone();
    let report = move |p: Progress| {
        progress(
            &app_progress,
            "downloading",
            percent_of(p),
            format!(
                "Téléchargement — {} / {} · fichier {}/{}",
                human(p.done_bytes),
                human(p.total_bytes),
                p.done_files + 1,
                p.total_files
            ),
        );
    };
    chunks::download(&state, &root, &manifest.files, &report).await.map_err(String::from)?;

    // Les mods référencés se retéléchargent depuis leur source au lieu d'avoir
    // occupé le quota. Le document vient d'arriver avec le manifeste.
    let doc = {
        let root = root.clone();
        tokio::task::spawn_blocking(move || crate::sync::mods::read_doc(&root)).await.map_err(|e| e.to_string())?
    };
    let mut installed = 0;
    if let Some(doc) = &doc {
        if !doc.mods.is_empty() {
            let http = state.read().await.http.clone();
            let app_mods = app.clone();
            let report = move |done: usize, total: usize, file: &str| {
                let percent = if total > 0 { ((done * 99) / total).clamp(0, 99) as u8 } else { 99 };
                progress(&app_mods, "installing_mods", percent, format!("Installation des mods — {done}/{total} · {file}"));
            };
            installed = crate::sync::mods::install(&http, &root, doc, &report).await.map_err(String::from)?;
        }
    }

    // Ce qui n'est plus dans le manifeste part : sans ça, un mod retiré sur
    // l'autre PC resterait ici et continuerait de casser le jeu. Limité aux
    // dossiers synchronisés — on ne touche jamais aux mondes.
    let removed = tokio::task::spawn_blocking({
        let root = root.clone();
        let mut kept: std::collections::HashSet<String> = manifest.files.iter().map(|f| f.path.clone()).collect();
        // Les mods référencés ne sont PAS dans le manifeste : sans cette
        // ligne, le ménage effacerait exactement ce qu'on vient d'installer.
        kept.extend(doc.iter().flat_map(|d| d.mods.iter().map(|m| format!("mods/{}", m.file))));
        move || prune(&root, &kept)
    })
    .await
    .map_err(|e| e.to_string())?;

    progress(
        &app,
        "done",
        100,
        // La révision est affichée : c'est elle qu'on compare entre deux PC
        // quand quelqu'un se demande lequel est en retard.
        {
            let mut parts = vec![format!("{} fichiers récupérés", manifest.files.len())];
            if installed > 0 {
                parts.push(format!("{installed} mods installés"));
            }
            if removed > 0 {
                parts.push(format!("{removed} retiré(s)"));
            }
            format!("{} — révision {}", parts.join(", "), manifest.revision)
        },
    );
    Ok(())
}

/// Supprime, dans les dossiers synchronisés, ce qui n'est plus au manifeste.
fn prune(root: &std::path::Path, kept: &std::collections::HashSet<String>) -> usize {
    let Ok(local) = chunks::scan(root, &SYNCED.map(String::from), &NEVER) else { return 0 };
    let mut removed = 0;
    for file in local {
        if kept.contains(&file.path) {
            continue;
        }
        let path = root.join(file.path.replace('/', std::path::MAIN_SEPARATOR_STR));
        if std::fs::remove_file(&path).is_ok() {
            removed += 1;
        }
    }
    removed
}

// ── Ce qui est réellement sauvegardé ─────────────────────────────────────────

/// Le contenu exact d'une instance synchronisée : chaque fichier, sa taille,
/// et la révision à laquelle il appartient.
///
/// Sans ça, la sync est une boîte noire : on clique « Envoyer », un chiffre
/// change, et personne ne sait ce qu'il y a dedans. Le manifeste existe déjà
/// côté serveur — le montrer ne coûte qu'un appel.
#[tauri::command]
pub async fn sync_manifest(state: tauri::State<'_, SharedState>, sync_id: i64) -> Result<Value, String> {
    api::get(&state, &format!("/sync/instances/{sync_id}/manifest"), &[]).await.map_err(String::from)
}

/// Un mod référencé, tel que la page de détail l'affiche.
#[derive(serde::Serialize)]
pub struct ReferencedMod {
    pub file: String,
    pub project_id: String,
    pub size: i64,
    pub enabled: bool,
}

/// Les mods que l'instance synchronisée référence sans les stocker.
///
/// Indispensable à la page de détail : depuis qu'ils voyagent en référence,
/// ils ne sont plus dans l'arborescence des fichiers. Une page qui prétend
/// montrer « tout ce qui est sauvegardé » en les cachant serait pire que
/// l'ancien panneau.
#[tauri::command]
pub async fn sync_referenced_mods(state: tauri::State<'_, SharedState>, sync_id: i64) -> Result<Vec<ReferencedMod>, String> {
    let value = api::get(&state, &format!("/sync/instances/{sync_id}/manifest"), &[]).await.map_err(String::from)?;
    let manifest: Manifest = serde_json::from_value(value).map_err(|e| e.to_string())?;

    let Some(entry) = manifest.files.iter().find(|f| f.path == crate::sync::mods::DOC_PATH) else {
        // Instance envoyée par un launcher d'avant le référencement : ses mods
        // sont dans le manifeste, la page les montrera dans l'arborescence.
        return Ok(Vec::new());
    };
    let Some(sha) = entry.chunks.first() else { return Ok(Vec::new()) };

    let bytes = api::get_bytes(&state, &format!("/sync/chunks/{sha}"), std::time::Duration::from_secs(60))
        .await
        .map_err(String::from)?;
    let doc: crate::sync::mods::ModsDoc = serde_json::from_slice(&bytes).map_err(|e| e.to_string())?;
    Ok(doc
        .mods
        .iter()
        .map(|m| ReferencedMod { file: m.file.clone(), project_id: m.project_id.clone(), size: m.size, enabled: m.enabled() })
        .collect())
}

/// Un fichier qui diffère entre le PC et le serveur.
#[derive(serde::Serialize)]
pub struct DiffEntry {
    pub path: String,
    /// added | modified | removed
    pub kind: String,
    /// Taille locale pour un ajout ou une modification, taille distante pour
    /// une suppression.
    pub size: i64,
}

/// Ce qui changerait si on envoyait maintenant.
#[derive(serde::Serialize)]
pub struct SyncDiff {
    pub revision: i64,
    pub entries: Vec<DiffEntry>,
    pub unchanged: usize,
    /// Octets réellement à téléverser — pas la taille des fichiers modifiés,
    /// mais celle des morceaux que le serveur n'a pas encore. Un mod remis à
    /// sa place à l'identique ne pèse rien.
    pub upload_bytes: i64,
    pub local_files: usize,
    pub local_bytes: i64,
}

/// Compare le dossier local au manifeste du serveur, sans rien envoyer.
///
/// C'est ce que le protocole par morceaux permet et que personne ne montre :
/// annoncer « 3 mods ajoutés, 1 modifié, 12 Mo à envoyer » **avant** de
/// cliquer, au lieu de lancer un transfert en espérant.
#[tauri::command]
pub async fn sync_diff(state: tauri::State<'_, SharedState>, sync_id: i64, instance_id: String) -> Result<SyncDiff, String> {
    let root = instance_dir(&instance_id);
    let scan_root = root.clone();
    let local: Vec<FileEntry> = tokio::task::spawn_blocking(move || chunks::scan(&scan_root, &SYNCED.map(String::from), &NEVER))
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| format!("Lecture de l'instance impossible : {e}"))?;

    let value = api::get(&state, &format!("/sync/instances/{sync_id}/manifest"), &[]).await.map_err(String::from)?;
    let remote: Manifest = serde_json::from_value(value).map_err(|e| e.to_string())?;

    let remote_by_path: std::collections::HashMap<&str, &FileEntry> = remote.files.iter().map(|f| (f.path.as_str(), f)).collect();
    let local_by_path: std::collections::HashMap<&str, &FileEntry> = local.iter().map(|f| (f.path.as_str(), f)).collect();

    let mut entries = Vec::new();
    let mut unchanged = 0;
    for file in &local {
        match remote_by_path.get(file.path.as_str()) {
            None => entries.push(DiffEntry { path: file.path.clone(), kind: "added".into(), size: file.size }),
            // Les empreintes, pas la taille : deux fichiers de même longueur
            // au contenu différent sont bien une modification.
            Some(r) if r.chunks != file.chunks => entries.push(DiffEntry { path: file.path.clone(), kind: "modified".into(), size: file.size }),
            Some(_) => unchanged += 1,
        }
    }
    for file in &remote.files {
        if !local_by_path.contains_key(file.path.as_str()) {
            entries.push(DiffEntry { path: file.path.clone(), kind: "removed".into(), size: file.size });
        }
    }
    entries.sort_by(|a, b| a.kind.cmp(&b.kind).then(a.path.cmp(&b.path)));

    // Le serveur seul sait ce qu'il a déjà : on le lui demande plutôt que de
    // l'estimer.
    let missing = api::post(&state, &format!("/sync/instances/{sync_id}/missing"), json!({ "files": local }))
        .await
        .map_err(String::from)?;
    let upload_bytes = missing.get("total_bytes").and_then(Value::as_i64).unwrap_or(0);

    Ok(SyncDiff {
        revision: remote.revision,
        entries,
        unchanged,
        upload_bytes,
        local_files: local.len(),
        local_bytes: local.iter().map(|f| f.size).sum(),
    })
}

#[tauri::command]
pub async fn sync_delete_instance(state: tauri::State<'_, SharedState>, sync_id: i64) -> Result<(), String> {
    api::delete(&state, &format!("/sync/instances/{sync_id}")).await.map(|_| ()).map_err(String::from)
}

// ── Détails de l'instance locale ─────────────────────────────────────────────

async fn instance_meta(state: &tauri::State<'_, SharedState>, instance_id: &str) -> Result<(String, String, String, u32), String> {
    let s = state.read().await;
    let user_id = s.current_yuyu_user_id().unwrap_or(0);
    let db = s.db.lock().await;
    let row = crate::db::instance_get(&db, instance_id, user_id)
        .map_err(|e| e.to_string())?
        .ok_or("Instance introuvable")?;
    Ok((row.name, row.mc_version, row.loader, row.ram_mb))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn worlds_are_never_part_of_the_sync() {
        assert!(!SYNCED.contains(&"saves"), "les mondes relèvent du backup, pas de la sync");
    }

    #[test]
    fn regenerated_folders_are_left_out() {
        for noise in ["logs", "crash-reports", "cache"] {
            assert!(NEVER.contains(&noise), "{noise} change à chaque lancement et n'a rien à faire dans un manifeste");
        }
    }

    #[test]
    fn sizes_are_readable() {
        assert_eq!(human(512), "512.0 o");
        assert_eq!(human(1536), "1.5 Ko");
        assert_eq!(human(3 * 1024 * 1024 * 1024), "3.0 Go");
    }

    #[test]
    fn the_bar_never_claims_to_be_finished_early() {
        let full = Progress { done_bytes: 100, total_bytes: 100, done_files: 1, total_files: 1 };
        assert_eq!(percent_of(full), 99, "100 % est réservé à la fin réelle");
        assert_eq!(percent_of(Progress { done_bytes: 0, total_bytes: 0, done_files: 0, total_files: 0 }), 99);
    }
}
