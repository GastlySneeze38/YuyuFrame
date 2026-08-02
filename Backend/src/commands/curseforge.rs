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
