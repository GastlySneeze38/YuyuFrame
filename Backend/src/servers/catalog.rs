//! Le catalogue de serveurs : celui de Modrinth (`project_types =
//! minecraft_java_server`, API v3).
//!
//! Modrinth y range des fiches de serveurs comme il range des mods : recherche,
//! catégories, région, langues, versions acceptées, et surtout deux choses
//! qu'aucun autre annuaire ouvert ne donne — un **ping déjà fait** (joueurs en
//! ligne, version) et, pour un serveur moddé, **le modpack exact** à installer
//! (`content.kind = "modpack"` + `version_id`). Le launcher n'a donc pas à
//! pinguer 20 adresses pour afficher une page, ni à deviner les mods.
//!
//! Les grands réseaux PvP (Hypixel…) n'y sont pas : une liste à nous pour les
//! ajouter est remise à plus tard.
//!
//! Les fiches sont **normalisées** ici plutôt que rendues brutes : le format
//! v3 change encore (`minecraft_java_server_ping` existe à côté de
//! `minecraft_java_server.ping`), et l'interface ne doit connaître qu'une forme.

use serde::{Deserialize, Serialize};
use serde_json::Value;

const MODRINTH_V3: &str = "https://api.modrinth.com/v3";
const MODRINTH_V2: &str = "https://api.modrinth.com/v2";
const TIMEOUT: std::time::Duration = std::time::Duration::from_secs(15);

/// Serveurs par page quand l'interface n'en demande pas un nombre précis.
pub const PAGE_SIZE: u32 = 20;

/// L'interface demande autant de serveurs qu'il en tient à l'écran ; Modrinth
/// n'en rend pas plus de 100 par requête, et au-delà de 50 une page n'est
/// plus lisible.
const PAGE_SIZE_MAX: u32 = 50;

fn page_size(asked: Option<u32>) -> u32 {
    asked.unwrap_or(PAGE_SIZE).clamp(1, PAGE_SIZE_MAX)
}

/// Tris proposés. Les autres valeurs de Modrinth n'ont pas de sens pour un
/// serveur (`downloads` compte les téléchargements du modpack) ou n'existent
/// pas (aucun tri par joueurs en ligne : Modrinth l'ignore en silence).
const SORTS: [&str; 4] = ["relevance", "follows", "newest", "updated"];

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogQuery {
    #[serde(default)]
    pub query: String,
    /// Étiquettes Modrinth (`pvp`, `smp`, `minigames`…) : un serveur qui en a
    /// **au moins une** passe.
    #[serde(default)]
    pub categories: Vec<String>,
    /// `europe`, `us_east`, `us_west`, `south_america`, `asia`, `australia`…
    pub region: Option<String>,
    /// Code de langue (`fr`, `en`…).
    pub language: Option<String>,
    /// Version du jeu que le serveur doit accepter.
    pub game_version: Option<String>,
    /// `vanilla` ou `modpack`.
    pub kind: Option<String>,
    /// Voir `SORTS` ; autre valeur = pertinence.
    pub sort: Option<String>,
    /// Page, à partir de 1.
    #[serde(default)]
    pub page: u32,
    /// Serveurs par page : ce qui tient à l'écran (voir `page_size`).
    pub page_size: Option<u32>,
}

/// Le contenu à installer pour jouer sur le serveur.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum ServerContent {
    /// Le jeu tel quel (un loader de mods optionnel côté client ne change rien).
    Vanilla,
    /// Un modpack Modrinth précis : `server_catalog_modpack(version_id)` donne
    /// de quoi l'installer.
    Modpack {
        project_id: String,
        version_id: String,
        name: Option<String>,
        icon_url: Option<String>,
    },
}

/// Le dernier ping fait par Modrinth.
#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct CatalogPing {
    pub players_online: i64,
    pub players_max: i64,
    pub version_name: Option<String>,
    /// ISO 8601 : un ping vieux de plusieurs heures se dit comme tel.
    pub at: Option<String>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct CatalogServer {
    /// Identifiant du projet Modrinth.
    pub id: String,
    pub slug: String,
    pub name: String,
    pub summary: String,
    pub author: Option<String>,
    pub icon_url: Option<String>,
    /// Image d'en-tête : l'image mise en avant de la galerie, sinon la première.
    pub banner_url: Option<String>,
    /// Couleur dominante de l'icône, `#rrggbb`.
    pub color: Option<String>,
    pub categories: Vec<String>,
    pub address: String,
    pub region: Option<String>,
    pub languages: Vec<String>,
    pub game_versions: Vec<String>,
    pub recommended_version: Option<String>,
    pub content: ServerContent,
    pub ping: Option<CatalogPing>,
    /// Parties vérifiées par Modrinth sur deux semaines.
    pub plays_2w: Option<i64>,
    pub follows: i64,
}

#[derive(Debug, Serialize)]
pub struct CatalogPage {
    pub servers: Vec<CatalogServer>,
    pub total: u64,
    pub page: u32,
    pub page_size: u32,
}

#[derive(Debug, Serialize)]
pub struct CatalogLink {
    pub platform: String,
    pub url: String,
}

#[derive(Debug, Serialize)]
pub struct CatalogServerDetail {
    #[serde(flatten)]
    pub server: CatalogServer,
    /// Markdown de Modrinth (peut contenir du HTML : à assainir à l'affichage).
    pub description: String,
    pub gallery: Vec<String>,
    pub links: Vec<CatalogLink>,
}

#[derive(Debug, Serialize)]
pub struct CatalogCategory {
    pub name: String,
    /// Regroupement de Modrinth : `minecraft_server_gameplay`, `_features`,
    /// `_community`, `_meta`.
    pub header: String,
}

/// De quoi remplir `ModpackInstallInput` pour le modpack d'un serveur.
#[derive(Debug, Serialize)]
pub struct ServerModpack {
    pub project_id: String,
    pub version_id: String,
    pub version_number: String,
    pub file_url: String,
    pub name: String,
    pub summary: String,
    pub icon_url: Option<String>,
    pub downloads: u64,
    pub date_modified: Option<String>,
    pub categories: Vec<String>,
    pub game_versions: Vec<String>,
    pub loaders: Vec<String>,
}

// ── Construction de la requête ───────────────────────────────────────────────

/// Une valeur glissée dans le filtre de Modrinth. Le filtre est un texte
/// (`categories IN [pvp, smp]`) : une valeur à espaces, crochets ou guillemets
/// pourrait en changer le sens, donc on n'accepte que l'alphabet des
/// étiquettes, des régions et des versions.
fn safe_value(value: &str) -> Option<&str> {
    let v = value.trim();
    (!v.is_empty() && v.len() <= 40 && v.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | '.')))
        .then_some(v)
}

fn build_filters(q: &CatalogQuery) -> String {
    let mut parts = vec!["project_types = minecraft_java_server".to_string()];
    let categories: Vec<&str> = q.categories.iter().filter_map(|c| safe_value(c)).take(10).collect();
    if !categories.is_empty() {
        parts.push(format!("categories IN [{}]", categories.join(", ")));
    }
    if let Some(r) = q.region.as_deref().and_then(safe_value) {
        parts.push(format!("minecraft_server.region = {r}"));
    }
    if let Some(l) = q.language.as_deref().and_then(safe_value) {
        parts.push(format!("minecraft_server.languages = {l}"));
    }
    if let Some(v) = q.game_version.as_deref().and_then(safe_value) {
        parts.push(format!("minecraft_java_server.content.supported_game_versions = {v}"));
    }
    if let Some(k) = q.kind.as_deref().filter(|k| matches!(*k, "vanilla" | "modpack")) {
        parts.push(format!("minecraft_java_server.content.kind = {k}"));
    }
    parts.join(" AND ")
}

fn sort_index(sort: Option<&str>) -> &'static str {
    sort.and_then(|s| SORTS.iter().find(|x| **x == s).copied()).unwrap_or("relevance")
}

// ── Lecture d'une fiche ──────────────────────────────────────────────────────

fn s(v: &Value, key: &str) -> Option<String> {
    v.get(key).and_then(Value::as_str).map(str::to_string).filter(|s| !s.is_empty())
}

fn strings(v: &Value, key: &str) -> Vec<String> {
    v.get(key)
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect())
        .unwrap_or_default()
}

/// Seules les images du CDN de Modrinth sont gardées : une fiche est écrite
/// par le propriétaire du serveur, et l'interface ne doit pas aller chercher
/// une adresse quelconque.
fn cdn(url: Option<String>) -> Option<String> {
    url.filter(|u| u.starts_with("https://cdn.modrinth.com/"))
}

/// Le ping, sous l'une ou l'autre des deux formes de la v3.
fn read_ping(java: &Value, root: &Value) -> Option<CatalogPing> {
    let ping = java.get("ping").filter(|p| !p.is_null()).or_else(|| root.get("minecraft_java_server_ping").filter(|p| !p.is_null()))?;
    let data = ping.get("data")?;
    Some(CatalogPing {
        players_online: data.get("players_online").and_then(Value::as_i64)?,
        players_max: data.get("players_max").and_then(Value::as_i64).unwrap_or(0),
        version_name: s(data, "version_name"),
        at: s(ping, "when"),
    })
}

/// Une fiche de Modrinth (résultat de recherche ou projet complet) mise à
/// plat. `None` pour une fiche sans adresse : on ne saurait pas la rejoindre.
fn normalize(v: &Value) -> Option<CatalogServer> {
    let java = v.get("minecraft_java_server")?;
    let address = s(java, "address")?;
    let server = v.get("minecraft_server").cloned().unwrap_or(Value::Null);
    let content = java.get("content").cloned().unwrap_or(Value::Null);

    let kind = match s(&content, "kind").as_deref() {
        Some("modpack") => match (s(&content, "project_id"), s(&content, "version_id")) {
            (Some(project_id), Some(version_id)) => ServerContent::Modpack {
                project_id,
                version_id,
                name: s(&content, "project_name"),
                icon_url: cdn(s(&content, "project_icon")),
            },
            // Un modpack sans version désignée ne s'installe pas : on le
            // présente comme vanilla plutôt que de promettre l'impossible.
            _ => ServerContent::Vanilla,
        },
        _ => ServerContent::Vanilla,
    };

    // Recherche : `featured_gallery` + `gallery` (liste d'adresses).
    // Projet : `gallery` (liste d'objets `{ url, featured }`).
    let gallery_urls: Vec<(String, bool)> = v
        .get("gallery")
        .and_then(Value::as_array)
        .map(|a| {
            a.iter()
                .filter_map(|g| match g {
                    Value::String(u) => Some((u.clone(), false)),
                    o => Some((s(o, "url")?, o.get("featured").and_then(Value::as_bool).unwrap_or(false))),
                })
                .collect()
        })
        .unwrap_or_default();
    let banner = cdn(s(v, "featured_gallery"))
        .or_else(|| cdn(gallery_urls.iter().find(|(_, f)| *f).map(|(u, _)| u.clone())))
        .or_else(|| cdn(gallery_urls.first().map(|(u, _)| u.clone())));

    Some(CatalogServer {
        id: s(v, "project_id").or_else(|| s(v, "id"))?,
        slug: s(v, "slug").unwrap_or_default(),
        name: s(v, "name").unwrap_or_else(|| address.clone()),
        summary: s(v, "summary").unwrap_or_default(),
        author: s(v, "author"),
        icon_url: cdn(s(v, "icon_url")),
        banner_url: banner,
        color: v.get("color").and_then(Value::as_u64).map(|c| format!("#{:06x}", c & 0xFF_FFFF)),
        categories: strings(v, "categories"),
        region: s(&server, "region"),
        languages: strings(&server, "languages"),
        game_versions: strings(&content, "supported_game_versions"),
        recommended_version: s(&content, "recommended_game_version"),
        content: kind,
        ping: read_ping(java, v),
        plays_2w: java.get("verified_plays_2w").and_then(Value::as_i64),
        follows: v.get("follows").or_else(|| v.get("followers")).and_then(Value::as_i64).unwrap_or(0),
        address,
    })
}

// ── Appels ───────────────────────────────────────────────────────────────────

async fn get_json(url: &str, query: &[(&str, String)]) -> Result<Value, String> {
    // Client partagé (`crate::net`) : avant, un client neuf par appel refaisait
    // la connexion TLS à chaque page du catalogue.
    let resp = crate::net::api().get(url).timeout(TIMEOUT).query(query).send().await.map_err(|e| format!("Modrinth injoignable : {e}"))?;
    if resp.status() == reqwest::StatusCode::NOT_FOUND {
        return Err("Serveur introuvable sur Modrinth".into());
    }
    if !resp.status().is_success() {
        return Err(format!("Modrinth : {}", resp.status()));
    }
    resp.json().await.map_err(|e| format!("Réponse de Modrinth illisible : {e}"))
}

/// Un identifiant ou un slug Modrinth, tel qu'il peut finir dans une adresse.
fn safe_id(id: &str) -> Result<&str, String> {
    safe_value(id).filter(|v| !v.contains('.')).ok_or_else(|| "Identifiant invalide".to_string())
}

#[tauri::command]
pub async fn server_catalog_search(query: CatalogQuery) -> Result<CatalogPage, String> {
    let page = query.page.max(1);
    let size = page_size(query.page_size);
    let params = [
        ("query", query.query.trim().chars().take(100).collect::<String>()),
        ("new_filters", build_filters(&query)),
        ("index", sort_index(query.sort.as_deref()).to_string()),
        ("limit", size.to_string()),
        ("offset", ((page - 1) * size).to_string()),
    ];
    let body = get_json(&format!("{MODRINTH_V3}/search"), &params).await?;
    let servers = body
        .get("hits")
        .and_then(Value::as_array)
        .map(|hits| hits.iter().filter_map(normalize).collect())
        .unwrap_or_default();
    Ok(CatalogPage {
        servers,
        total: body.get("total_hits").and_then(Value::as_u64).unwrap_or(0),
        page,
        page_size: size,
    })
}

/// La fiche complète d'un serveur : description, galerie, liens.
#[tauri::command]
pub async fn server_catalog_get(id: String) -> Result<CatalogServerDetail, String> {
    let id = safe_id(&id)?;
    let v = get_json(&format!("{MODRINTH_V3}/project/{id}"), &[]).await?;
    let server = normalize(&v).ok_or("Ce projet n'est pas un serveur")?;
    let gallery = v
        .get("gallery")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(|g| cdn(s(g, "url"))).collect())
        .unwrap_or_default();
    let links = v
        .get("link_urls")
        .and_then(Value::as_object)
        .map(|m| {
            m.values()
                .filter_map(|l| {
                    let url = s(l, "url").filter(|u| u.starts_with("https://"))?;
                    Some(CatalogLink { platform: s(l, "platform").unwrap_or_default(), url })
                })
                .collect()
        })
        .unwrap_or_default();
    Ok(CatalogServerDetail { server, description: s(&v, "description").unwrap_or_default(), gallery, links })
}

/// Les étiquettes de serveurs de Modrinth, pour les filtres. Liste vide si
/// Modrinth ne répond pas : l'écran garde sa recherche par texte.
#[tauri::command]
pub async fn server_catalog_categories() -> Result<Vec<CatalogCategory>, String> {
    let body = get_json(&format!("{MODRINTH_V3}/tag/category"), &[]).await?;
    Ok(body
        .as_array()
        .map(|a| {
            a.iter()
                .filter(|c| c.get("project_type").and_then(Value::as_str) == Some("minecraft_java_server"))
                .filter_map(|c| Some(CatalogCategory { name: s(c, "name")?, header: s(c, "header").unwrap_or_default() }))
                .collect()
        })
        .unwrap_or_default())
}

/// Le modpack d'un serveur : son fichier `.mrpack` et ce qu'il faut pour le
/// présenter puis l'installer avec `modpack_install`.
#[tauri::command]
pub async fn server_catalog_modpack(version_id: String) -> Result<ServerModpack, String> {
    let version_id = safe_id(&version_id)?;
    let version = get_json(&format!("{MODRINTH_V2}/version/{version_id}"), &[]).await?;
    let files = version.get("files").and_then(Value::as_array).cloned().unwrap_or_default();
    let file = files
        .iter()
        .find(|f| f.get("primary").and_then(Value::as_bool) == Some(true))
        .or_else(|| files.first())
        .ok_or("Ce modpack n'a pas de fichier")?;
    let file_url = s(file, "url")
        .filter(|u| u.starts_with("https://cdn.modrinth.com/"))
        .ok_or("Fichier du modpack hors de Modrinth")?;
    let project_id = s(&version, "project_id").ok_or("Modpack sans projet")?;
    let project = get_json(&format!("{MODRINTH_V2}/project/{}", safe_id(&project_id)?), &[]).await?;
    Ok(ServerModpack {
        version_id: version_id.to_string(),
        version_number: s(&version, "version_number").unwrap_or_default(),
        file_url,
        name: s(&project, "title").unwrap_or_else(|| project_id.clone()),
        summary: s(&project, "description").unwrap_or_default(),
        icon_url: cdn(s(&project, "icon_url")),
        downloads: project.get("downloads").and_then(Value::as_u64).unwrap_or(0),
        date_modified: s(&project, "updated"),
        categories: strings(&project, "categories"),
        game_versions: strings(&version, "game_versions"),
        loaders: strings(&version, "loaders"),
        project_id,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn le_filtre_ne_laisse_passer_que_des_valeurs_simples() {
        let q = CatalogQuery {
            categories: vec!["pvp".into(), "smp] OR project_types = mod".into(), "minigames".into()],
            region: Some("europe".into()),
            language: Some("fr".into()),
            game_version: Some("1.21.11".into()),
            kind: Some("modpack".into()),
            ..Default::default()
        };
        assert_eq!(
            build_filters(&q),
            "project_types = minecraft_java_server AND categories IN [pvp, minigames] \
             AND minecraft_server.region = europe AND minecraft_server.languages = fr \
             AND minecraft_java_server.content.supported_game_versions = 1.21.11 \
             AND minecraft_java_server.content.kind = modpack"
        );
        let vide = CatalogQuery { kind: Some("autre".into()), region: Some("a b".into()), ..Default::default() };
        assert_eq!(build_filters(&vide), "project_types = minecraft_java_server");
        assert_eq!(sort_index(Some("follows")), "follows");
        assert_eq!(sort_index(Some("downloads")), "relevance");
        assert!(safe_id("../x").is_err());
        assert_eq!((page_size(None), page_size(Some(0)), page_size(Some(12)), page_size(Some(500))), (20, 1, 12, 50));
    }

    #[test]
    fn une_fiche_de_recherche_se_lit() {
        let hit = json!({
            "project_id": "nuPdyMgK", "slug": "donutsmp", "name": "DonutSMP", "summary": "PvP",
            "author": "x", "icon_url": "https://cdn.modrinth.com/i.webp", "color": 355319,
            "categories": ["pvp", "smp"], "follows": 186,
            "gallery": ["https://cdn.modrinth.com/a.webp"], "featured_gallery": null,
            "minecraft_server": { "region": "us_east", "languages": ["en"] },
            "minecraft_java_server": {
                "address": "donutsmp.net",
                "content": { "kind": "vanilla", "supported_game_versions": ["1.21.11"], "recommended_game_version": "1.21.11" },
                "ping": { "when": "2026-10-31T18:13:07Z", "data": { "players_online": 33138, "players_max": 50000, "version_name": "Velocity" } },
                "verified_plays_2w": 10819
            }
        });
        let s = normalize(&hit).unwrap();
        assert_eq!(s.address, "donutsmp.net");
        assert_eq!(s.color.as_deref(), Some("#056bf7"));
        assert_eq!(s.banner_url.as_deref(), Some("https://cdn.modrinth.com/a.webp"));
        assert_eq!(s.content, ServerContent::Vanilla);
        assert_eq!(s.ping.as_ref().map(|p| p.players_online), Some(33138));
        assert_eq!(s.plays_2w, Some(10819));
    }

    #[test]
    fn un_modpack_sans_version_ne_promet_rien() {
        let mut hit = json!({
            "project_id": "p", "name": "Pack",
            "icon_url": "https://ailleurs.example/i.png",
            "minecraft_java_server": { "address": "a.net", "content": { "kind": "modpack", "version_id": "v", "project_id": "q" } }
        });
        let s = normalize(&hit).unwrap();
        assert!(matches!(s.content, ServerContent::Modpack { ref version_id, .. } if version_id == "v"));
        assert_eq!(s.icon_url, None, "image hors du CDN de Modrinth écartée");

        hit["minecraft_java_server"]["content"] = json!({ "kind": "modpack" });
        assert_eq!(normalize(&hit).unwrap().content, ServerContent::Vanilla);
        hit["minecraft_java_server"] = json!({ "content": {} });
        assert!(normalize(&hit).is_none(), "sans adresse, pas de serveur");
    }
}
