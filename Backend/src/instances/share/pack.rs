//! Lecture d'un pack reçu — fichier ou lien. Un pack reçu est étranger : rien
//! de ce qu'il nomme n'est cru sur parole.

use super::*;

pub(super) fn allowed_download(url: &str) -> bool {
    ALLOWED_DOWNLOADS.iter().any(|p| url.starts_with(p))
}

#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum ShareSource {
    File { path: String },
    Link { link: String },
}

pub(super) struct PackFile {
    pub(super) path: String,
    pub(super) url: String,
    pub(super) sha1: Option<String>,
    pub(super) sha512: Option<String>,
    pub(super) size: u64,
}

pub(super) struct Pack {
    pub(super) name: String,
    pub(super) summary: String,
    pub(super) mc_version: String,
    pub(super) loader: String,
    pub(super) loader_version: String,
    pub(super) files: Vec<PackFile>,
    /// (nom dans l'archive, chemin dans l'instance, taille).
    pub(super) overrides: Vec<(String, String, u64)>,
    pub(super) archive: Option<PathBuf>,
    /// Fichiers ignorés : chemin refusé, adresse hors des plateformes, version
    /// introuvable.
    pub(super) rejected: Vec<String>,
    /// Configuration Java jointe, déjà filtrée, et les arguments écartés.
    pub(super) jvm: Option<JvmShare>,
    pub(super) jvm_rejected: Vec<String>,
    /// `options.txt` reçu en réglages (lien), fusionné à l'arrivée.
    pub(super) options: Vec<McOption>,
    /// Options du client intégré, filtrées.
    pub(super) client: Vec<McOption>,
    /// Serveurs (nom, adresse), ajoutés à la liste s'ils n'y sont pas.
    pub(super) servers: Vec<(String, String)>,
    /// Fichiers copiés dans un lien : (chemin vérifié, contenu).
    pub(super) inline: Vec<(String, Vec<u8>)>,
}

/// Options du client reçues : mêmes règles que le partage d'options.
pub(super) fn received_client(pairs: Vec<(String, String)>) -> Vec<McOption> {
    pairs
        .into_iter()
        .map(|(key, value)| McOption { key, value })
        .filter(|o| keep_client(o, true))
        .collect()
}

/// Filtre une configuration reçue ; rend aussi ce qui a été écarté.
pub(super) fn received_jvm(raw: Option<JvmShare>) -> (Option<JvmShare>, Vec<String>) {
    match raw {
        Some(raw) => {
            let (clean, rejected) = sanitize_jvm(raw);
            (Some(clean), rejected)
        }
        None => (None, Vec::new()),
    }
}

pub(super) fn read_mrpack(path: &Path) -> Result<Pack, String> {
    let file = std::fs::File::open(path).map_err(|e| format!("Lecture du fichier : {e}"))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|_| "Ce fichier n'est pas un pack valide".to_string())?;
    let index: MrIndex = {
        let mut entry = archive
            .by_name("modrinth.index.json")
            .map_err(|_| "Ce fichier n'est pas un pack Modrinth (.mrpack)".to_string())?;
        let mut content = String::new();
        entry.read_to_string(&mut content).map_err(|e| e.to_string())?;
        serde_json::from_str(&content).map_err(|e| format!("Pack illisible : {e}"))?
    };
    if index.game != "minecraft" {
        return Err("Ce pack n'est pas pour Minecraft".into());
    }
    let mc_version = index
        .dependencies
        .get("minecraft")
        .cloned()
        .ok_or("Ce pack n'indique pas sa version de Minecraft")?;
    let (loader, loader_version) = loader_from_dependencies(&index.dependencies);

    // Fichier propre à YuyuFrame, facultatif : un pack venu d'ailleurs n'en a
    // pas, et un fichier illisible vaut « pas de configuration Java ».
    let extras: Option<YuyuExtras> = archive.by_name(YUYU_FILE).ok().and_then(|mut entry| {
        let mut content = String::new();
        entry.read_to_string(&mut content).ok()?;
        serde_json::from_str(&content).ok()
    });
    let (extras_jvm, extras_client) = extras.map(|e| (e.jvm, e.client)).unwrap_or_default();
    let (jvm, jvm_rejected) = received_jvm(extras_jvm);
    let client = received_client(extras_client.unwrap_or_default());

    let mut rejected = Vec::new();
    let mut files = Vec::new();
    for f in index.files {
        if f.env.as_ref().is_some_and(|e| e.client == "unsupported") {
            continue;
        }
        let Some(path) = safe_relative(&f.path) else {
            rejected.push(f.path);
            continue;
        };
        let Some(url) = f.downloads.iter().find(|u| allowed_download(u)) else {
            rejected.push(path);
            continue;
        };
        files.push(PackFile {
            path,
            url: url.clone(),
            sha1: f.hashes.get("sha1").map(|h| h.to_lowercase()),
            sha512: f.hashes.get("sha512").map(|h| h.to_lowercase()),
            size: f.file_size,
        });
    }

    // `client-overrides/` après `overrides/` : il a la priorité (format Modrinth).
    let mut overrides: Vec<(String, String, u64)> = Vec::new();
    for prefix in ["overrides/", "client-overrides/"] {
        for i in 0..archive.len() {
            let Ok(entry) = archive.by_index(i) else { continue };
            let name = entry.name().to_string();
            if entry.is_dir() {
                continue;
            }
            let Some(rel) = name.strip_prefix(prefix) else { continue };
            match safe_relative(rel) {
                Some(rel) => {
                    overrides.retain(|(_, r, _)| r != &rel);
                    overrides.push((name.clone(), rel, entry.size()));
                }
                None => rejected.push(name.clone()),
            }
        }
    }

    Ok(Pack {
        name: index.name,
        summary: index.summary.unwrap_or_default(),
        mc_version,
        loader,
        loader_version,
        files,
        overrides,
        archive: Some(path.to_path_buf()),
        rejected,
        jvm,
        jvm_rejected,
        // `options.txt` et la liste des serveurs voyagent dans `overrides/`,
        // comme le format Modrinth le veut (les autres launchers les lisent).
        options: Vec::new(),
        client,
        servers: Vec::new(),
        inline: Vec::new(),
    })
}

/// Contenu d'un enregistrement texte du lien.
pub(super) fn record_text(body: &[u8]) -> Result<&str, String> {
    std::str::from_utf8(body).map_err(|_| "Lien de partage abîmé".to_string())
}

pub(super) async fn read_link(link: &str) -> Result<Pack, String> {
    let broken = || "Lien de partage abîmé".to_string();
    let data = crate::share_link::read(link, LINK_KIND)?;
    let records = records(&data).ok_or_else(broken)?;

    let header = records.iter().find(|(tag, _)| *tag == REC_HEADER).ok_or_else(broken)?;
    let lines: Vec<&str> = record_text(header.1)?.split('\n').collect();
    let [name, mc_version, loader, loader_version, tokens] = lines.as_slice() else {
        return Err(broken());
    };
    if mc_version.is_empty() || !matches!(*loader, "vanilla" | "fabric" | "quilt" | "forge" | "neoforge") {
        return Err(broken());
    }
    let (name, mc_version, loader, loader_version) =
        (name.to_string(), mc_version.to_string(), loader.to_string(), loader_version.to_string());
    let tokens = decode_tokens(tokens)?;

    let mut raw_jvm = None;
    let mut options = Vec::new();
    let mut client = Vec::new();
    let mut servers = Vec::new();
    let mut inline = Vec::new();
    let mut remote_files = Vec::new();
    let mut rejected = Vec::new();
    for (tag, body) in &records {
        match *tag {
            // « RAM JVM GC mode », puis les arguments.
            REC_JVM => {
                let text = record_text(body)?;
                let (head, args) = text.split_once('\n').unwrap_or((text, ""));
                let mut words = head.split(' ');
                raw_jvm = (|| {
                    Some(JvmShare {
                        ram_mb: words.next()?.parse().ok()?,
                        vendor: words.next()?.to_string(),
                        gc_policy: words.next()?.to_string(),
                        args_mode: words.next()?.to_string(),
                        args: args.split_whitespace().map(str::to_string).collect(),
                    })
                })();
            }
            REC_OPTIONS => options = from_text(record_text(body)?, ':').into_iter().filter(keep_game).collect(),
            REC_CLIENT => {
                client = from_text(record_text(body)?, '=').into_iter().filter(|o| keep_client(o, true)).collect()
            }
            REC_SERVERS => {
                servers = record_text(body)?
                    .lines()
                    .filter_map(|l| l.split_once('\t'))
                    .filter(|(name, ip)| !ip.is_empty() && name.len() <= 200 && ip.len() <= 255)
                    .map(|(name, ip)| (name.to_string(), ip.to_string()))
                    .collect()
            }
            REC_REMOTE => {
                let mut fields = record_text(body)?.split('\0');
                let (Some(path), Some(url), Some(sha1)) = (fields.next(), fields.next(), fields.next()) else {
                    return Err(broken());
                };
                match safe_relative(path) {
                    Some(path) if allowed_download(url) => remote_files.push(PackFile {
                        path,
                        url: url.to_string(),
                        sha1: Some(sha1.to_lowercase()),
                        sha512: None,
                        size: 0,
                    }),
                    _ => rejected.push(path.to_string()),
                }
            }
            REC_FILE => {
                let split = body.iter().position(|b| *b == 0).ok_or_else(broken)?;
                let path = std::str::from_utf8(&body[..split]).map_err(|_| broken())?;
                match safe_relative(path) {
                    Some(path) => inline.push((path, body[split + 1..].to_vec())),
                    None => rejected.push(path.to_string()),
                }
            }
            // Une étiquette inconnue vient d'une version plus récente : on
            // prend ce qu'on comprend.
            _ => {}
        }
    }
    let (jvm, jvm_rejected) = received_jvm(raw_jvm);

    let mut versions: HashMap<String, MrVersion> = HashMap::new();
    if !tokens.is_empty() {
        let ids: Vec<&str> = tokens.iter().map(|t| t.version_id.as_str()).collect::<HashSet<_>>().into_iter().collect();
        let resp = http()?
            .get("https://api.modrinth.com/v2/versions")
            .query(&[("ids", serde_json::to_string(&ids).map_err(|e| e.to_string())?)])
            .send()
            .await
            .map_err(|_| "Modrinth est injoignable : réessaie dans un instant".to_string())?;
        if !resp.status().is_success() {
            return Err(format!("Modrinth a refusé la demande (HTTP {})", resp.status()));
        }
        let list: Vec<MrVersion> = resp.json().await.map_err(|e| e.to_string())?;
        versions = list.into_iter().map(|v| (v.id.clone(), v)).collect();
    }

    let mut files = remote_files;
    for token in tokens {
        let (dir, disabled) = dir_of(token.kind).expect("vérifié au décodage");
        // Sans début d'empreinte, la version n'avait qu'un fichier quand le
        // lien a été fait : le principal (ou le seul).
        let file = versions.get(&token.version_id).and_then(|v| {
            if token.hash_prefix.is_empty() {
                v.files.iter().find(|f| f.primary).or_else(|| v.files.first())
            } else {
                v.files.iter().find(|f| f.hashes.sha1.to_lowercase().starts_with(&token.hash_prefix))
            }
        });
        let Some(file) = file else {
            rejected.push(token.version_id);
            continue;
        };
        // Le nom vient de Modrinth : on n'en garde que le dernier segment.
        let name = file.filename.rsplit(['/', '\\']).next().unwrap_or_default();
        let Some(path) = safe_relative(&format!("{dir}/{name}{}", if disabled { ".disabled" } else { "" })) else {
            rejected.push(file.filename.clone());
            continue;
        };
        if !allowed_download(&file.url) {
            rejected.push(path);
            continue;
        }
        files.push(PackFile {
            path,
            url: file.url.clone(),
            sha1: Some(file.hashes.sha1.to_lowercase()),
            sha512: file.hashes.sha512.clone(),
            size: file.size,
        });
    }

    Ok(Pack {
        name,
        summary: String::new(),
        mc_version,
        loader,
        loader_version,
        files,
        overrides: Vec::new(),
        archive: None,
        rejected,
        jvm,
        jvm_rejected,
        options,
        client,
        servers,
        inline,
    })
}

pub(super) async fn load_pack(source: &ShareSource) -> Result<Pack, String> {
    match source {
        ShareSource::File { path } => {
            let path = PathBuf::from(path);
            tokio::task::spawn_blocking(move || read_mrpack(&path)).await.map_err(|e| e.to_string())?
        }
        ShareSource::Link { link } => read_link(link).await,
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PreviewFile {
    pub path: String,
    pub size: u64,
    /// Domaine d'origine (« modrinth », « curseforge », « github »…) ; vide
    /// pour un fichier embarqué.
    pub source: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SharePreview {
    pub name: String,
    pub summary: String,
    pub mc_version: String,
    pub loader: String,
    pub loader_version: String,
    pub downloads: Vec<PreviewFile>,
    pub embedded: Vec<PreviewFile>,
    pub rejected: Vec<String>,
    /// Configuration Java jointe (déjà filtrée), à appliquer ou non.
    pub jvm: Option<JvmShare>,
    /// Arguments JVM du pack écartés par le filtre de sécurité.
    pub jvm_rejected: Vec<String>,
    /// Réglages d'`options.txt` reçus par lien (0 : aucun).
    pub options: u32,
    /// Options du client intégré reçues (0 : aucune).
    pub client: u32,
    /// Noms des serveurs reçus par lien.
    pub servers: Vec<String>,
}

pub(super) fn source_of(url: &str) -> &'static str {
    if url.starts_with("https://cdn.modrinth.com/") {
        "modrinth"
    } else if CURSEFORGE_CDN.iter().any(|p| url.starts_with(p)) {
        "curseforge"
    } else if url.starts_with("https://gitlab.com/") {
        "gitlab"
    } else {
        "github"
    }
}

#[tauri::command]
pub async fn instance_share_preview(source: ShareSource) -> Result<SharePreview, String> {
    let pack = load_pack(&source).await?;
    Ok(SharePreview {
        downloads: pack
            .files
            .iter()
            .map(|f| PreviewFile { path: f.path.clone(), size: f.size, source: source_of(&f.url).into() })
            .collect(),
        embedded: pack
            .overrides
            .iter()
            .map(|(_, rel, size)| PreviewFile { path: rel.clone(), size: *size, source: String::new() })
            .chain(pack.inline.iter().map(|(rel, content)| PreviewFile {
                path: rel.clone(),
                size: content.len() as u64,
                source: String::new(),
            }))
            .collect(),
        options: pack.options.len() as u32,
        client: pack.client.len() as u32,
        servers: pack.servers.iter().map(|(name, _)| name.clone()).collect(),
        name: pack.name,
        summary: pack.summary,
        mc_version: pack.mc_version,
        loader: pack.loader,
        loader_version: pack.loader_version,
        rejected: pack.rejected,
        jvm: pack.jvm,
        jvm_rejected: pack.jvm_rejected,
    })
}
