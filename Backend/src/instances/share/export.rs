//! Export en fichier `.mrpack`.

use super::*;

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct MrIndex {
    pub(super) format_version: u32,
    pub(super) game: String,
    pub(super) version_id: String,
    pub(super) name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub(super) summary: Option<String>,
    pub(super) files: Vec<MrIndexFile>,
    pub(super) dependencies: BTreeMap<String, String>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct MrIndexFile {
    pub(super) path: String,
    pub(super) hashes: BTreeMap<String, String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub(super) env: Option<MrEnv>,
    pub(super) downloads: Vec<String>,
    #[serde(default)]
    pub(super) file_size: u64,
}

#[derive(Serialize, Deserialize)]
pub(super) struct MrEnv {
    #[serde(default)]
    pub(super) client: String,
    #[serde(default)]
    pub(super) server: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ShareExport {
    /// Fichiers que le destinataire téléchargera lui-même.
    pub linked: u32,
    /// Fichiers copiés dans le pack.
    pub embedded: u32,
    /// Poids du pack écrit.
    pub size: u64,
}

pub(super) fn hash_file(path: &Path) -> std::io::Result<(String, String)> {
    let mut file = std::fs::File::open(path)?;
    let mut sha1 = Sha1::new();
    let mut sha512 = Sha512::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        sha1.update(&buf[..n]);
        sha512.update(&buf[..n]);
    }
    Ok((format!("{:x}", sha1.finalize()), format!("{:x}", sha512.finalize())))
}

/// Tous les fichiers d'un élément, en chemins relatifs à l'instance.
pub(super) fn files_of(abs: &Path, rel: &str, out: &mut Vec<(PathBuf, String)>) {
    let Ok(meta) = std::fs::symlink_metadata(abs) else { return };
    if meta.file_type().is_symlink() {
        return;
    }
    if meta.is_file() {
        out.push((abs.to_path_buf(), rel.to_string()));
        return;
    }
    let Ok(entries) = std::fs::read_dir(abs) else { return };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        files_of(&entry.path(), &format!("{rel}/{name}"), out);
    }
}

/// Les éléments choisis. Un chemin envoyé par l'interface ne compte que s'il
/// figure dans l'inventaire : on n'exporte jamais un chemin arbitraire.
pub(super) fn selection<'a>(scanned: &'a Scanned, paths: &[String]) -> Vec<&'a (Entry, Option<String>)> {
    let wanted: HashSet<&str> = paths.iter().map(String::as_str).collect();
    scanned.entries.iter().filter(|(e, _)| wanted.contains(e.path.as_str())).collect()
}

#[tauri::command]
pub async fn instance_share_export(
    state: tauri::State<'_, SharedState>,
    instance_id: String,
    paths: Vec<String>,
    include_jvm: bool,
    include_client: bool,
    file_path: String,
) -> Result<ShareExport, String> {
    let scanned = scan(&state, &instance_id).await?;
    let client = Some(scanned.client_options.iter().map(|o| (o.key.clone(), o.value.clone())).collect::<Vec<_>>())
        .filter(|c| include_client && !c.is_empty());
    let jvm = include_jvm.then(|| scanned.jvm.clone());
    let extras = (jvm.is_some() || client.is_some()).then_some(YuyuExtras { format_version: 1, jvm, client });
    let loader_version = loader_version_of(&scanned.instance).await?;
    let chosen = selection(&scanned, &paths);

    let mut linked: Vec<(PathBuf, String, Remote)> = Vec::new();
    let mut embedded: Vec<(PathBuf, String)> = Vec::new();
    // Fichiers réécrits avant de partir (`options.txt` filtré).
    let mut generated: Vec<(String, Vec<u8>)> = Vec::new();
    for (entry, sha1) in chosen {
        match sha1.as_deref().and_then(cached) {
            Some(remote) => linked.push((entry.abs.clone(), entry.path.clone(), remote)),
            None if entry.path == OPTIONS_FILE => {
                if let Some(text) = shared_options_txt(&entry.abs) {
                    generated.push((entry.path.clone(), text.into_bytes()));
                }
            }
            None => files_of(&entry.abs, &entry.path, &mut embedded),
        }
    }

    let mut dependencies = BTreeMap::new();
    dependencies.insert("minecraft".to_string(), scanned.instance.mc_version.clone());
    if let Some(key) = loader_dependency(&scanned.instance.loader) {
        dependencies.insert(key.to_string(), loader_version);
    }
    let name = scanned.instance.name.clone();
    let summary = Some(scanned.instance.description.clone()).filter(|s| !s.trim().is_empty());
    let target = PathBuf::from(&file_path);

    tokio::task::spawn_blocking(move || -> Result<ShareExport, String> {
        let mut files = Vec::with_capacity(linked.len());
        for (abs, path, remote) in &linked {
            let (sha1, sha512) = hash_file(abs).map_err(|e| format!("{path} : {e}"))?;
            let size = std::fs::metadata(abs).map(|m| m.len()).unwrap_or(0);
            let mut hashes = BTreeMap::new();
            hashes.insert("sha1".to_string(), sha1);
            hashes.insert("sha512".to_string(), remote.sha512.clone().unwrap_or(sha512));
            files.push(MrIndexFile {
                path: path.clone(),
                hashes,
                env: Some(MrEnv { client: "required".into(), server: "optional".into() }),
                downloads: vec![remote.url.clone()],
                file_size: size,
            });
        }
        let index = MrIndex {
            format_version: 1,
            game: "minecraft".into(),
            version_id: chrono_stamp(),
            name,
            summary,
            files,
            dependencies,
        };

        // Écrit à côté puis renommé : un export interrompu ne laisse pas un
        // pack tronqué sous le nom choisi.
        let partial = target.with_extension("mrpack.part");
        let write = || -> Result<(), String> {
            let out = std::fs::File::create(&partial).map_err(|e| e.to_string())?;
            let mut zip = zip::ZipWriter::new(out);
            let options = zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Deflated)
                .large_file(true);
            zip.start_file("modrinth.index.json", options).map_err(|e| e.to_string())?;
            zip.write_all(&serde_json::to_vec_pretty(&index).map_err(|e| e.to_string())?)
                .map_err(|e| e.to_string())?;
            if let Some(extras) = &extras {
                zip.start_file(YUYU_FILE, options).map_err(|e| e.to_string())?;
                zip.write_all(&serde_json::to_vec_pretty(extras).map_err(|e| e.to_string())?)
                    .map_err(|e| e.to_string())?;
            }
            for (abs, rel) in &embedded {
                zip.start_file(format!("overrides/{rel}"), options).map_err(|e| e.to_string())?;
                let mut file = std::fs::File::open(abs).map_err(|e| format!("{rel} : {e}"))?;
                std::io::copy(&mut file, &mut zip).map_err(|e| format!("{rel} : {e}"))?;
            }
            for (rel, content) in &generated {
                zip.start_file(format!("overrides/{rel}"), options).map_err(|e| e.to_string())?;
                zip.write_all(content).map_err(|e| format!("{rel} : {e}"))?;
            }
            zip.finish().map_err(|e| e.to_string())?;
            Ok(())
        };
        if let Err(e) = write() {
            let _ = std::fs::remove_file(&partial);
            return Err(e);
        }
        std::fs::rename(&partial, &target).map_err(|e| e.to_string())?;
        Ok(ShareExport {
            linked: linked.len() as u32,
            embedded: (embedded.len() + generated.len()) as u32,
            size: std::fs::metadata(&target).map(|m| m.len()).unwrap_or(0),
        })
    })
    .await
    .map_err(|e| e.to_string())?
}

/// Identifiant de version du pack : sa date d'export, lisible.
pub(super) fn chrono_stamp() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    format!("share-{secs}")
}
