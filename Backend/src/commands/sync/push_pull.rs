use tauri::Emitter;

use crate::state::SharedState;
use super::super::instance::crud::instance_dir;
use super::archive::{
    api_base, build_instance_zip_with_progress, dir_size, extract_zip_to_instance, get_token,
    list_mods_raw, modrinth_lookup_batch, read_modpack_ref, require_premium, ModManifest,
    ModManifestEntry, SaveInfo, SyncInstance, SyncProgressEvent,
};

#[tauri::command]
pub async fn sync_list_saves(instance_id: String) -> Result<Vec<SaveInfo>, String> {
    let saves_dir = instance_dir(&instance_id).join("saves");
    if !saves_dir.is_dir() {
        return Ok(vec![]);
    }

    let mut saves: Vec<SaveInfo> = std::fs::read_dir(&saves_dir)
        .map_err(|e| e.to_string())?
        .filter_map(|e| e.ok())
        .filter(|e| e.path().is_dir())
        .filter_map(|e| {
            let path = e.path();
            let name = path.file_name()?.to_str()?.to_string();
            let meta = std::fs::metadata(&path).ok()?;
            let updated_at = meta
                .modified()
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_secs() as i64)
                .unwrap_or(0);
            let size_bytes = dir_size(&path);
            Some(SaveInfo { name, updated_at, size_bytes })
        })
        .collect();

    saves.sort_by(|a, b| b.updated_at.cmp(&a.updated_at));
    Ok(saves)
}

#[tauri::command]
pub async fn sync_list_instances(
    state: tauri::State<'_, SharedState>,
) -> Result<Vec<SyncInstance>, String> {
    let token = {
        let s = state.read().await;
        require_premium(&s)?;
        get_token(&s)?
    };

    let client = reqwest::Client::new();
    let resp = client
        .get(format!("{}/sync/instances", api_base()))
        .bearer_auth(&token)
        .send()
        .await
        .map_err(|e| format!("Serveur inaccessible : {e}"))?;

    if !resp.status().is_success() {
        return Err(resp.text().await.unwrap_or_default());
    }

    resp.json::<Vec<SyncInstance>>().await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn sync_push_instance(
    state: tauri::State<'_, SharedState>,
    app: tauri::AppHandle,
    instance_id: String,
    save_names: Vec<String>,
) -> Result<SyncInstance, String> {
    use crate::db;

    let (token, instance) = {
        let s = state.read().await;
        require_premium(&s)?;
        let token = get_token(&s)?;
        let user_id = s.current_yuyu_user_id().unwrap_or(0);
        let conn = s.db.lock().await;
        let row = db::instance_get(&conn, &instance_id, user_id)
            .map_err(|e| e.to_string())?
            .ok_or("Instance introuvable")?;
        (token, row)
    };

    let client = reqwest::Client::builder()
        .user_agent("YuyuFrame/1.0")
        .build()
        .map_err(|e| e.to_string())?;

    // ── Étape 1 : Construire le manifest des mods ─────────────────────────────
    app.emit("sync_progress", SyncProgressEvent {
        phase: "resolving_mods".into(),
        percent: 0,
        label: "Analyse des mods...".into(),
    }).ok();

    let inst_dir = instance_dir(&instance_id);
    let mods_dir = inst_dir.join("mods");

    let mods_raw = tokio::task::spawn_blocking({
        let mods_dir = mods_dir.clone();
        move || list_mods_raw(&mods_dir)
    }).await.unwrap_or_default();

    app.emit("sync_progress", SyncProgressEvent {
        phase: "resolving_mods".into(),
        percent: 5,
        label: format!("Recherche de {} mods sur Modrinth...", mods_raw.len()),
    }).ok();

    let sha1s: Vec<String> = mods_raw.iter().map(|(_, sha1, _)| sha1.clone()).collect();
    let modrinth_map = modrinth_lookup_batch(&client, &sha1s).await;

    let modpack = read_modpack_ref(&inst_dir);

    let manifest_entries: Vec<ModManifestEntry> = mods_raw.iter().map(|(clean_name, sha1, enabled)| {
        let modrinth = modrinth_map.get(sha1).map(|(pid, vid, url)| super::archive::ModrinthRef {
            project_id: pid.clone(),
            version_id: vid.clone(),
            download_url: url.clone(),
        });
        ModManifestEntry {
            filename: clean_name.clone(),
            sha1: sha1.clone(),
            enabled: *enabled,
            modrinth,
        }
    }).collect();

    let modrinth_count = manifest_entries.iter().filter(|m| m.modrinth.is_some()).count();
    let manual_count = manifest_entries.len() - modrinth_count;

    app.emit("sync_progress", SyncProgressEvent {
        phase: "resolving_mods".into(),
        percent: 10,
        label: format!("{} mods Modrinth · {} inclus dans le ZIP", modrinth_count, manual_count),
    }).ok();

    let manifest = ModManifest {
        format_version: 1,
        mc_version: instance.mc_version.clone(),
        loader: instance.loader.clone(),
        modpack,
        mods: manifest_entries,
    };

    // ── Étape 2 : Enregistrer les métadonnées sur le serveur ──────────────────
    let meta_resp = client
        .post(format!("{}/sync/instances", api_base()))
        .bearer_auth(&token)
        .json(&serde_json::json!({
            "instance_name": instance.name,
            "mc_version":    instance.mc_version,
            "loader":        instance.loader,
            "ram_mb":        instance.ram_mb,
            "save_count":    save_names.len() as u32,
            "save_names":    save_names,
        }))
        .send()
        .await
        .map_err(|e| format!("Serveur inaccessible : {e}"))?;

    if !meta_resp.status().is_success() {
        return Err(meta_resp.text().await.unwrap_or_default());
    }

    let sync_inst: SyncInstance = meta_resp.json().await.map_err(|e| e.to_string())?;
    let sync_id = sync_inst.id;

    // ── Étape 3 : Compression avec progression ────────────────────────────────
    let (tx, mut rx) = tokio::sync::mpsc::channel::<SyncProgressEvent>(128);
    let dir = inst_dir.clone();
    let save_names_clone = save_names.clone();
    let manifest_clone = manifest.clone();

    let zip_task = tokio::task::spawn_blocking(move || {
        build_instance_zip_with_progress(dir, save_names_clone, manifest_clone, tx)
    });

    let app_progress = app.clone();
    let forward = tokio::spawn(async move {
        while let Some(ev) = rx.recv().await {
            app_progress.emit("sync_progress", ev).ok();
        }
    });

    let zip_bytes = zip_task.await.map_err(|e| e.to_string())??;
    forward.await.ok();

    // ── Étape 4 : Upload ──────────────────────────────────────────────────────
    app.emit("sync_progress", SyncProgressEvent {
        phase: "uploading".into(),
        percent: 55,
        label: "Envoi vers le cloud...".into(),
    }).ok();

    let data_resp = client
        .post(format!("{}/sync/instances/{}/data", api_base(), sync_id))
        .bearer_auth(&token)
        .header("Content-Type", "application/octet-stream")
        .body(zip_bytes)
        .send()
        .await
        .map_err(|e| format!("Serveur inaccessible : {e}"))?;

    if !data_resp.status().is_success() {
        return Err(data_resp.text().await.unwrap_or_default());
    }

    app.emit("sync_progress", SyncProgressEvent {
        phase: "done".into(),
        percent: 100,
        label: "Synchronisé !".into(),
    }).ok();

    data_resp.json::<SyncInstance>().await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn sync_pull_instance(
    state: tauri::State<'_, SharedState>,
    app: tauri::AppHandle,
    sync_id: i64,
    instance_id: String,
) -> Result<(), String> {
    let token = {
        let s = state.read().await;
        require_premium(&s)?;
        get_token(&s)?
    };

    // ── Étape 1 : Télécharger le ZIP ─────────────────────────────────────────
    app.emit("sync_progress", SyncProgressEvent {
        phase: "downloading".into(),
        percent: 5,
        label: "Téléchargement depuis le cloud...".into(),
    }).ok();

    let client = reqwest::Client::builder()
        .user_agent("YuyuFrame/1.0")
        .build()
        .map_err(|e| e.to_string())?;

    let resp = client
        .get(format!("{}/sync/instances/{}/data", api_base(), sync_id))
        .bearer_auth(&token)
        .send()
        .await
        .map_err(|e| format!("Serveur inaccessible : {e}"))?;

    if !resp.status().is_success() {
        return Err(resp.text().await.unwrap_or_default());
    }

    let zip_bytes = resp.bytes().await.map_err(|e| e.to_string())?.to_vec();

    // ── Étape 2 : Extraction ──────────────────────────────────────────────────
    app.emit("sync_progress", SyncProgressEvent {
        phase: "downloading".into(),
        percent: 35,
        label: "Extraction des fichiers...".into(),
    }).ok();

    let dir = instance_dir(&instance_id);
    let dir_clone = dir.clone();

    tokio::task::spawn_blocking(move || extract_zip_to_instance(zip_bytes, dir_clone))
        .await
        .map_err(|e| e.to_string())??;

    // ── Étape 3 : Installer les mods depuis le manifest ───────────────────────
    let manifest_path = dir.join("mods.json");
    if manifest_path.exists() {
        let manifest_json = tokio::fs::read_to_string(&manifest_path)
            .await
            .map_err(|e| e.to_string())?;
        tokio::fs::remove_file(&manifest_path).await.ok();

        let manifest: ModManifest = serde_json::from_str(&manifest_json)
            .map_err(|e| format!("Manifest invalide : {e}"))?;

        let modrinth_mods: Vec<&ModManifestEntry> = manifest.mods.iter()
            .filter(|m| m.modrinth.is_some())
            .collect();

        let total = modrinth_mods.len();
        if total > 0 {
            let mods_dir = dir.join("mods");
            tokio::fs::create_dir_all(&mods_dir).await.ok();

            for (i, entry) in modrinth_mods.iter().enumerate() {
                let modrinth = entry.modrinth.as_ref().unwrap();
                let percent = 45u8.saturating_add((i * 50 / total.max(1)) as u8);

                app.emit("sync_progress", SyncProgressEvent {
                    phase: "installing_mods".into(),
                    percent,
                    label: format!("Mods {}/{} — {}", i + 1, total, entry.filename),
                }).ok();

                // Validation URL (CDN Modrinth uniquement)
                if !modrinth.download_url.starts_with("https://cdn.modrinth.com/") {
                    continue;
                }

                let Ok(dl_resp) = client.get(&modrinth.download_url).send().await else { continue };
                if !dl_resp.status().is_success() { continue; }
                let Ok(bytes) = dl_resp.bytes().await else { continue };

                let dest_name = if entry.enabled {
                    entry.filename.clone()
                } else {
                    format!("{}.disabled", entry.filename)
                };
                tokio::fs::write(mods_dir.join(&dest_name), &bytes).await.ok();
            }
        }
    }

    app.emit("sync_progress", SyncProgressEvent {
        phase: "done".into(),
        percent: 100,
        label: "Restauré !".into(),
    }).ok();

    Ok(())
}

#[tauri::command]
pub async fn sync_delete_instance(
    state: tauri::State<'_, SharedState>,
    sync_id: i64,
) -> Result<(), String> {
    let token = {
        let s = state.read().await;
        require_premium(&s)?;
        get_token(&s)?
    };

    let client = reqwest::Client::new();
    let resp = client
        .delete(format!("{}/sync/instances/{}", api_base(), sync_id))
        .bearer_auth(&token)
        .send()
        .await
        .map_err(|e| format!("Serveur inaccessible : {e}"))?;

    if !resp.status().is_success() {
        return Err(resp.text().await.unwrap_or_default());
    }

    Ok(())
}
