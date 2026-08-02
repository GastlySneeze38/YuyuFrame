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

#[tauri::command]
pub async fn curseforge_search(
    state: tauri::State<'_, SharedState>,
    query: String,
    game_version: Option<String>,
    class_id: Option<String>,
    page_size: Option<u32>,
    index: Option<u32>,
) -> Result<serde_json::Value, String> {
    let (token, client) = require_token(&state).await?;

    let mut params: Vec<(&str, String)> = vec![("query", query)];
    if let Some(gv) = game_version { params.push(("game_version", gv)); }
    if let Some(cid) = class_id { params.push(("class_id", cid)); }
    if let Some(ps) = page_size { params.push(("page_size", ps.to_string())); }
    if let Some(i) = index { params.push(("index", i.to_string())); }

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
