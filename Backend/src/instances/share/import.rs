//! Import : création de l'instance, téléchargements vérifiés, fusion des options.

use super::*;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ShareImport {
    pub instance: Instance,
    /// Fichiers qui n'ont pas pu être installés (réseau, empreinte fausse).
    pub failed: Vec<String>,
}

/// Le contenu correspond-il à ce que le pack annonce ? La plus forte des
/// empreintes données fait foi ; sans aucune, on accepte (le format les rend
/// obligatoires, mais un pack fait à la main peut les omettre).
pub(super) fn matches_hashes(data: &[u8], sha1: Option<&str>, sha512: Option<&str>) -> bool {
    if let Some(expected) = sha512 {
        return format!("{:x}", Sha512::digest(data)) == expected.to_lowercase();
    }
    if let Some(expected) = sha1 {
        return format!("{:x}", Sha1::digest(data)) == expected.to_lowercase();
    }
    true
}

pub(super) async fn download(client: &reqwest::Client, file: &PackFile, dest: &Path) -> Result<(), String> {
    let resp = client.get(&file.url).send().await.map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("HTTP {}", resp.status()));
    }
    let data = resp.bytes().await.map_err(|e| e.to_string())?;
    if !matches_hashes(&data, file.sha1.as_deref(), file.sha512.as_deref()) {
        return Err("empreinte différente de celle annoncée".into());
    }
    if let Some(parent) = dest.parent() {
        tokio::fs::create_dir_all(parent).await.map_err(|e| e.to_string())?;
    }
    tokio::fs::write(dest, &data).await.map_err(|e| e.to_string())
}

pub(super) fn extract_overrides(archive_path: &Path, overrides: &[(String, String, u64)], dir: &Path) -> Vec<String> {
    let mut failed = Vec::new();
    let Ok(file) = std::fs::File::open(archive_path) else {
        return overrides.iter().map(|(_, rel, _)| rel.clone()).collect();
    };
    let Ok(mut archive) = zip::ZipArchive::new(file) else {
        return overrides.iter().map(|(_, rel, _)| rel.clone()).collect();
    };
    for (name, rel, _) in overrides {
        let dest = join_relative(dir, rel);
        let result = (|| -> std::io::Result<()> {
            let mut entry = archive.by_name(name).map_err(std::io::Error::other)?;
            if let Some(parent) = dest.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let mut out = std::fs::File::create(&dest)?;
            std::io::copy(&mut entry, &mut out)?;
            Ok(())
        })();
        if let Err(e) = result {
            tracing::warn!("[Partage] extraction de {} échouée : {}", rel, e);
            failed.push(rel.clone());
        }
    }
    failed
}

#[tauri::command]
pub async fn instance_share_import(
    app: tauri::AppHandle,
    state: tauri::State<'_, SharedState>,
    source: ShareSource,
    name: String,
    ram_mb: u32,
    apply_jvm: bool,
) -> Result<ShareImport, String> {
    use futures::StreamExt;
    use tauri::Emitter;

    let pack = load_pack(&source).await?;
    let name = Some(name.trim().to_string())
        .filter(|n| !n.is_empty())
        .or_else(|| Some(pack.name.trim().to_string()).filter(|n| !n.is_empty()))
        .unwrap_or_else(|| "Instance partagée".to_string());

    // La configuration Java reçue a déjà passé le filtre (`received_jvm`) ;
    // elle ne s'applique que si l'utilisateur l'a gardée cochée. Sinon les
    // réglages par défaut du launcher, et la RAM choisie de ce côté-ci.
    let jvm = pack.jvm.clone().filter(|_| apply_jvm);
    let mut instance = crate::instances::crud::instance_create(
        state.clone(),
        name,
        pack.mc_version.clone(),
        pack.loader.clone(),
        jvm.as_ref().map_or(ram_mb, |j| j.ram_mb),
        Some(pack.summary.clone()),
        jvm.as_ref().map(|j| j.vendor.clone()),
        None,
        jvm.as_ref().map(|j| j.gc_policy.clone()),
        jvm.as_ref().map(|j| j.args.join("\n")),
        jvm.as_ref().map(|j| j.args_mode.clone()),
    )
    .await?;

    // Le même loader que l'expéditeur, épinglé : sans ça, le destinataire
    // prendrait « le plus récent », qui n'est peut-être plus celui avec lequel
    // les mods ont été testés.
    if !pack.loader_version.is_empty() && loader_dependency(&pack.loader).is_some() {
        let s = state.read().await;
        let uid = user_id(&s);
        let db = s.db.lock().await;
        db::instance_set_loader_version(&db, &instance.id, uid, &pack.loader_version).map_err(|e| e.to_string())?;
        instance.loader_version = pack.loader_version.clone();
    }

    let dir = instance_dir(&instance.id);
    let client = http()?;
    let total = pack.files.len();
    let mut failed: Vec<String> = Vec::new();

    // Données possédées par chaque téléchargement : un flux de futures qui
    // emprunteraient la liste ne passe pas la vérification `Send` des
    // commandes Tauri. Le client se clone sans rien recopier (il est partagé).
    let jobs: Vec<(PackFile, PathBuf, reqwest::Client)> = pack
        .files
        .into_iter()
        .map(|file| {
            let dest = join_relative(&dir, &file.path);
            (file, dest, client.clone())
        })
        .collect();
    let mut downloads = futures::stream::iter(jobs.into_iter().map(|(file, dest, client)| async move {
        let result = download(&client, &file, &dest).await;
        (file.path, result)
    }))
    .buffer_unordered(PARALLEL_DOWNLOADS);

    let mut done = 0usize;
    while let Some((path, result)) = downloads.next().await {
        done += 1;
        let label = path.rsplit('/').next().unwrap_or(&path).to_string();
        let _ = app.emit("share_import_progress", serde_json::json!({ "current": done, "total": total, "label": label }));
        if let Err(e) = result {
            tracing::warn!("[Partage] {} : {}", path, e);
            failed.push(path);
        }
    }
    drop(downloads);

    // Après les téléchargements : un fichier embarqué prime sur la version
    // publique du même nom, c'est celui que l'expéditeur utilisait.
    if let Some(archive) = pack.archive.clone() {
        let overrides = pack.overrides.clone();
        let dir = dir.clone();
        let extract_failed = tokio::task::spawn_blocking(move || extract_overrides(&archive, &overrides, &dir))
            .await
            .map_err(|e| e.to_string())?;
        failed.extend(extract_failed);
    }

    // Ce qu'un lien porte en plus des téléchargements. Les chemins ont été
    // vérifiés à la lecture (`safe_relative`) ; les options et les serveurs
    // sont **fusionnés**, comme le partage d'options.
    for (rel, content) in &pack.inline {
        let dest = join_relative(&dir, rel);
        let written = match dest.parent() {
            Some(parent) => tokio::fs::create_dir_all(parent).await.and(tokio::fs::write(&dest, content).await),
            None => tokio::fs::write(&dest, content).await,
        };
        if let Err(e) = written {
            tracing::warn!("[Partage] écriture de {} échouée : {}", rel, e);
            failed.push(rel.clone());
        }
    }
    if !pack.options.is_empty() {
        if let Err(e) = mc_options_write(instance.id.clone(), pack.options.clone()).await {
            tracing::warn!("[Partage] options du jeu : {}", e);
            failed.push(OPTIONS_FILE.into());
        }
    }
    if !pack.client.is_empty() {
        if let Err(e) = agent_options_write(instance.id.clone(), pack.client.clone()).await {
            tracing::warn!("[Partage] options du client : {}", e);
            failed.push("options du client YuyuFrame".into());
        }
    }
    if !pack.servers.is_empty() {
        let servers: Vec<crate::minecraft::launcher::SavedServer> = pack
            .servers
            .iter()
            .map(|(name, ip)| crate::minecraft::launcher::SavedServer { name: name.clone(), ip: ip.clone() })
            .collect();
        let dir = dir.clone();
        let merged = tokio::task::spawn_blocking(move || crate::minecraft::launcher::merge_saved_servers(&dir, &servers))
            .await
            .map_err(|e| e.to_string())?;
        if let Err(e) = merged {
            tracing::warn!("[Partage] serveurs : {}", e);
            failed.push("servers.dat".into());
        }
    }

    crate::integrations::analytics::capture("instance_share_imported", serde_json::json!({
        "from": match source { ShareSource::File { .. } => "file", ShareSource::Link { .. } => "link" },
        "files": total,
        "failed": failed.len(),
    }));

    Ok(ShareImport { instance, failed })
}
