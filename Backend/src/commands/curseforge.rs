// Passe par le proxy LauncherAPI (`Server/LauncherAPI/src/routes/curseforge.rs`)
// — la clé CurseForge n'existe que côté serveur, jamais ici. Seules les
// métadonnées (recherche, détails, liste des fichiers) transitent par ce
// proxy ; le téléchargement du fichier lui-même se fait ensuite en direct
// vers le CDN CurseForge (`downloadUrl` dans la réponse de
// `curseforge_mod_files`), sans repasser par le serveur.
use super::{api_base, network_err};
use crate::state::SharedState;

/// Récupère le token YuyuFrame courant — toutes les routes /curseforge/*
/// exigent une session valide côté serveur (juste pour éviter qu'un appelant
/// anonyme cram le quota partagé, pas une histoire de plan payant).
async fn require_token(state: &tauri::State<'_, SharedState>) -> Result<(String, reqwest::Client), String> {
    let s = state.read().await;
    let token = s
        .yuyu_session
        .as_ref()
        .ok_or_else(|| "Non connecté à YuyuFrame".to_string())?
        .token
        .clone();
    Ok((token, s.http.clone()))
}

async fn get_json(
    client: &reqwest::Client,
    token: &str,
    url: String,
    query: &[(&str, String)],
) -> Result<serde_json::Value, String> {
    let resp = client
        .get(url)
        .bearer_auth(token)
        .query(query)
        .send()
        .await
        .map_err(network_err)?;

    if !resp.status().is_success() {
        return Err(crate::commands::bearer_call_error(resp).await);
    }

    resp.json::<serde_json::Value>().await.map_err(|e| e.to_string())
}

async fn post_json(
    client: &reqwest::Client,
    token: &str,
    url: String,
    body: &serde_json::Value,
) -> Result<serde_json::Value, String> {
    let resp = client
        .post(url)
        .bearer_auth(token)
        .json(body)
        .send()
        .await
        .map_err(network_err)?;

    if !resp.status().is_success() {
        return Err(crate::commands::bearer_call_error(resp).await);
    }

    resp.json::<serde_json::Value>().await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn curseforge_search(
    state: tauri::State<'_, SharedState>,
    query: String,
    game_version: Option<String>,
    class_id: Option<String>,
    page_size: Option<u32>,
    index: Option<u32>,
    category_id: Option<String>,
    sort_field: Option<String>,
    sort_order: Option<String>,
    mod_loader_type: Option<String>,
) -> Result<serde_json::Value, String> {
    let (token, client) = require_token(&state).await?;

    let mut params: Vec<(&str, String)> = vec![("query", query)];
    if let Some(gv) = game_version { params.push(("game_version", gv)); }
    if let Some(cid) = class_id { params.push(("class_id", cid)); }
    if let Some(ps) = page_size { params.push(("page_size", ps.to_string())); }
    if let Some(i) = index { params.push(("index", i.to_string())); }
    if let Some(cat) = category_id { params.push(("category_id", cat)); }
    if let Some(sf) = sort_field { params.push(("sort_field", sf)); }
    if let Some(so) = sort_order { params.push(("sort_order", so)); }
    if let Some(mlt) = mod_loader_type { params.push(("mod_loader_type", mlt)); }

    get_json(&client, &token, format!("{}/curseforge/search", api_base()), &params).await
}

#[tauri::command]
pub async fn curseforge_mod_details(
    state: tauri::State<'_, SharedState>,
    mod_id: u64,
) -> Result<serde_json::Value, String> {
    let (token, client) = require_token(&state).await?;
    get_json(&client, &token, format!("{}/curseforge/mods/{}", api_base(), mod_id), &[]).await
}

#[tauri::command]
pub async fn curseforge_mod_files(
    state: tauri::State<'_, SharedState>,
    mod_id: u64,
    game_version: Option<String>,
) -> Result<serde_json::Value, String> {
    let (token, client) = require_token(&state).await?;
    let mut params: Vec<(&str, String)> = vec![];
    if let Some(gv) = game_version { params.push(("game_version", gv)); }
    get_json(&client, &token, format!("{}/curseforge/mods/{}/files", api_base(), mod_id), &params).await
}

#[tauri::command]
pub async fn curseforge_categories(state: tauri::State<'_, SharedState>) -> Result<serde_json::Value, String> {
    let (token, client) = require_token(&state).await?;
    get_json(&client, &token, format!("{}/curseforge/categories", api_base()), &[]).await
}

/// Fait correspondre des fingerprints locaux à des mods/fichiers CurseForge
/// connus — sert de "déjà installé" (CurseForge n'a pas d'équivalent simple
/// à la recherche par sha1 de Modrinth, voir `curseforge_local_fingerprints`
/// ci-dessous pour le calcul du fingerprint lui-même).
#[tauri::command]
pub async fn curseforge_fingerprint_matches(
    state: tauri::State<'_, SharedState>,
    fingerprints: Vec<u32>,
) -> Result<serde_json::Value, String> {
    let (token, client) = require_token(&state).await?;
    let body = serde_json::json!({ "fingerprints": fingerprints });
    post_json(&client, &token, format!("{}/curseforge/fingerprints", api_base()), &body).await
}

// ── Fingerprint CurseForge (murmur2, seed=1, whitespace filtré) ────────────────
// Algorithme propre à CurseForge — différent du sha1 utilisé par Modrinth.
// Nécessaire pour matcher un fichier local avec leur endpoint /v1/fingerprints.
// Implémentation MurmurHash2 32-bit standard (Austin Appleby), appliquée aux
// octets du fichier après avoir retiré ceux valant 9/10/13/32 (tab/LF/CR/espace)
// — c'est la convention CurseForge, pas un choix arbitraire de ce code.

fn murmur2_32(data: &[u8], seed: u32) -> u32 {
    const M: u32 = 0x5bd1e995;
    const R: u32 = 24;

    let mut h: u32 = seed ^ (data.len() as u32);
    let mut chunks = data.chunks_exact(4);
    for chunk in &mut chunks {
        let mut k = u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
        k = k.wrapping_mul(M);
        k ^= k >> R;
        k = k.wrapping_mul(M);
        h = h.wrapping_mul(M);
        h ^= k;
    }

    let rem = chunks.remainder();
    if !rem.is_empty() {
        if rem.len() >= 3 { h ^= (rem[2] as u32) << 16; }
        if rem.len() >= 2 { h ^= (rem[1] as u32) << 8; }
        h ^= rem[0] as u32;
        h = h.wrapping_mul(M);
    }

    h ^= h >> 13;
    h = h.wrapping_mul(M);
    h ^= h >> 15;
    h
}

fn curseforge_fingerprint(data: &[u8]) -> u32 {
    let filtered: Vec<u8> = data.iter().copied().filter(|&b| b != 9 && b != 10 && b != 13 && b != 32).collect();
    murmur2_32(&filtered, 1)
}

#[derive(serde::Serialize)]
pub struct LocalFingerprint {
    pub name: String,
    pub fingerprint: u32,
}

/// Calcule le fingerprint CurseForge de chaque .jar (actif ou désactivé) du
/// dossier mods/ de l'instance — pas de cache contrairement à `sha1_cached`,
/// appelé seulement à l'ouverture de l'onglet CurseForge, pas à chaque rendu.
#[tauri::command]
pub async fn curseforge_local_fingerprints(instance_id: String) -> Result<Vec<LocalFingerprint>, String> {
    use super::instance::crud::instance_mods_dir;
    use crate::minecraft::mod_files::{is_disabled_jar, is_enabled_jar};

    let dir = instance_mods_dir(&instance_id);
    let mut out = Vec::new();

    if let Ok(mut entries) = tokio::fs::read_dir(&dir).await {
        while let Ok(Some(entry)) = entries.next_entry().await {
            let path = entry.path();
            let name = path.file_name().unwrap_or_default().to_string_lossy().to_string();
            if !is_enabled_jar(&name) && !is_disabled_jar(&name) {
                continue;
            }
            let fingerprint = tokio::task::spawn_blocking(move || {
                std::fs::read(&path).map(|data| curseforge_fingerprint(&data)).unwrap_or(0)
            })
            .await
            .unwrap_or(0);
            if fingerprint != 0 {
                out.push(LocalFingerprint { name, fingerprint });
            }
        }
    }

    Ok(out)
}

/// Téléchargement direct depuis le CDN CurseForge (`downloadUrl` renvoyé par
/// `curseforge_mod_files`) — ne repasse PAS par LauncherAPI, ce domaine ne
/// nécessite pas la clé API. Miroir de `instance::mods::mods_install`
/// (même découpage streaming + event de progression) avec une whitelist de
/// domaines CurseForge à la place de celle de Modrinth.
#[tauri::command]
pub async fn curseforge_mod_install(
    app: tauri::AppHandle,
    instance_id: String,
    url: String,
    filename: String,
) -> Result<super::instance::mods::ModInfo, String> {
    use futures::StreamExt;
    use tauri::Emitter;

    use super::instance::crud::instance_mods_dir;
    use super::instance::mods::{sha1_cached, ModInfo};
    use crate::minecraft::mod_files::is_enabled_jar;

    const ALLOWED_HOSTS: [&str; 3] = [
        "https://edge.forgecdn.net/",
        "https://media.forgecdn.net/",
        "https://mediafilez.forgecdn.net/",
    ];
    if !ALLOWED_HOSTS.iter().any(|prefix| url.starts_with(prefix)) {
        return Err("URL non autorisée".into());
    }

    let safe_name = std::path::Path::new(&filename)
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "mod.jar".to_string());

    if !is_enabled_jar(&safe_name) {
        return Err("Seuls les fichiers .jar sont acceptés".into());
    }

    let dir = instance_mods_dir(&instance_id);
    tokio::fs::create_dir_all(&dir).await.map_err(|e| e.to_string())?;

    let client = reqwest::Client::builder()
        .user_agent("YuyuFrame/1.0")
        .build()
        .map_err(|e| e.to_string())?;

    let resp = client.get(&url).send().await.map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("Téléchargement échoué : {}", resp.status()));
    }

    let total = resp.content_length().unwrap_or(0);
    let mut downloaded: u64 = 0;
    let mut bytes: Vec<u8> = Vec::new();
    let mut stream = resp.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| e.to_string())?;
        downloaded += chunk.len() as u64;
        bytes.extend_from_slice(&chunk);
        let _ = app.emit("mod_install_progress", serde_json::json!({
            "filename": &safe_name,
            "downloaded": downloaded,
            "total": total,
        }));
    }

    let dest = dir.join(&safe_name);
    tokio::fs::write(&dest, &bytes).await.map_err(|e| e.to_string())?;
    let sha1 = tokio::task::spawn_blocking(move || sha1_cached(&dest))
        .await
        .unwrap_or_default();

    crate::integrations::analytics::capture("curseforge_mod_install_succeeded", serde_json::json!({
        "instance_id": &instance_id,
    }));
    Ok(ModInfo { name: safe_name, size: bytes.len() as u64, enabled: true, sha1 })
}
