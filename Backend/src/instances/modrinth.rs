use serde::{Deserialize, Serialize};

const MODRINTH_API: &str = "https://api.modrinth.com/v2";

/// Filtres de recherche avancée Modrinth — miroir backend de ce que fait déjà
/// `fetchModrinthSearch` côté frontend (facets `project_type`/`versions`/
/// `categories` pour le loader), étendu avec les vrais tags de contenu
/// (`categories`, distincts du loader), l'environnement (`client_side`/
/// `server_side`), la licence, le filtre "open source uniquement" et le tri.
/// Le frontend n'est pas encore branché dessus (prévu dans un futur travail) —
/// cette commande est conçue pour rester utilisable telle quelle à ce moment-là.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModrinthAdvancedSearchInput {
    pub query: String,
    #[serde(default)]
    pub game_version: Option<String>,
    /// Loader ("fabric"/"forge"/"quilt"/"neoforge"/"vanilla") — "vanilla"
    /// bascule `project_type` sur "plugin" (même heuristique que le frontend
    /// aujourd'hui : un serveur vanilla ne prend que des plugins, pas des mods).
    #[serde(default)]
    pub loader: Option<String>,
    /// Vrais tags de contenu Modrinth (technology, magic, adventure,
    /// decoration, equipment, food, library, storage, utility, worldgen...) —
    /// PAS le loader, déjà géré séparément par `loader` ci-dessus. Combinés en
    /// OR entre eux (pack qui a au moins un des tags), en AND avec le reste.
    #[serde(default)]
    pub categories: Vec<String>,
    /// "client" | "server" — filtre sur `client_side`/`server_side` du projet
    /// (accepte "required" ET "optional", exclut seulement "unsupported").
    /// `None`/autre valeur = pas de filtre d'environnement.
    #[serde(default)]
    pub environment: Option<String>,
    /// Identifiant de licence SPDX Modrinth (ex: "MIT", "LGPL-3.0-only"...).
    #[serde(default)]
    pub license: Option<String>,
    #[serde(default)]
    pub open_source_only: bool,
    /// "relevance" | "downloads" | "follows" | "newest" | "updated" — passé
    /// tel quel en paramètre `index` ; absent ou invalide = défaut Modrinth
    /// ("relevance"), l'API l'ignore silencieusement si la valeur est inconnue.
    #[serde(default)]
    pub sort: Option<String>,
    #[serde(default)]
    pub limit: Option<u32>,
    #[serde(default)]
    pub offset: Option<u32>,
    /// project_type explicite ("mod"|"plugin"|"resourcepack"|"shader"|
    /// "modpack"|"datapack") — si absent, déduit de `loader` comme aujourd'hui.
    #[serde(default)]
    pub project_type: Option<String>,
}

/// Enveloppe de réponse Modrinth `/v2/search`. Les hits eux-mêmes restent en
/// JSON brut (`serde_json::Value`) plutôt qu'un struct Rust strict : le
/// frontend n'étant pas encore branché, on évite de figer un sous-ensemble de
/// champs maintenant et de devoir re-toucher ce fichier dès que le frontend
/// aura besoin d'un champ qu'on n'aurait pas anticipé (Modrinth expose bien
/// plus que `ModrinthHit` actuellement utilisé côté frontend : client_side,
/// server_side, license, date_modified, gallery...).
#[derive(Serialize, Deserialize)]
pub struct ModrinthSearchResponse {
    pub hits: Vec<serde_json::Value>,
    pub offset: u32,
    pub limit: u32,
    pub total_hits: u64,
}

#[tauri::command]
pub async fn mods_search_advanced(input: ModrinthAdvancedSearchInput) -> Result<ModrinthSearchResponse, String> {
    let is_vanilla = input.loader.as_deref() == Some("vanilla");
    let project_type = input
        .project_type
        .clone()
        .unwrap_or_else(|| if is_vanilla { "plugin" } else { "mod" }.to_string());

    // Facets Modrinth : tableau de groupes AND, chaque groupe étant lui-même
    // un OR de ses éléments — même format que `deps.rs`/`fabric.rs` ailleurs
    // dans le backend pour les requêtes Modrinth.
    let mut facets: Vec<Vec<String>> = vec![vec![format!("project_type:{}", project_type)]];

    if let Some(gv) = &input.game_version {
        if !gv.is_empty() {
            facets.push(vec![format!("versions:{}", gv)]);
        }
    }
    if let Some(loader) = &input.loader {
        if !is_vanilla && !loader.is_empty() {
            facets.push(vec![format!("categories:{}", loader)]);
        }
    }
    if !input.categories.is_empty() {
        facets.push(input.categories.iter().map(|c| format!("categories:{}", c)).collect());
    }
    match input.environment.as_deref() {
        Some("client") => facets.push(vec!["client_side:required".to_string(), "client_side:optional".to_string()]),
        Some("server") => facets.push(vec!["server_side:required".to_string(), "server_side:optional".to_string()]),
        _ => {}
    }
    if let Some(license) = &input.license {
        if !license.is_empty() {
            facets.push(vec![format!("license:{}", license)]);
        }
    }
    if input.open_source_only {
        facets.push(vec!["open_source:true".to_string()]);
    }

    let facets_json = serde_json::to_string(&facets).map_err(|e| e.to_string())?;
    let mut params: Vec<(String, String)> = vec![
        ("query".to_string(), input.query),
        ("facets".to_string(), facets_json),
        ("limit".to_string(), input.limit.unwrap_or(20).to_string()),
        ("offset".to_string(), input.offset.unwrap_or(0).to_string()),
    ];
    if let Some(sort) = &input.sort {
        if !sort.is_empty() {
            params.push(("index".to_string(), sort.clone()));
        }
    }

    let client = reqwest::Client::builder()
        .user_agent("YuyuFrame/1.0")
        .build()
        .map_err(|e| e.to_string())?;
    let resp = client
        .get(format!("{}/search", MODRINTH_API))
        .query(&params)
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("Modrinth: {}", resp.status()));
    }
    resp.json::<ModrinthSearchResponse>().await.map_err(|e| e.to_string())
}
