#![allow(dead_code)]

use anyhow::Result;
use serde::Deserialize;
use std::collections::HashMap;
use std::sync::{Arc, LazyLock};
use std::time::{Duration, Instant};
use tokio::sync::{Mutex, RwLock};

const VERSION_MANIFEST: &str =
    "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";

#[derive(Debug, Deserialize)]
struct VersionManifest {
    versions: Vec<VersionInfo>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct VersionInfo {
    pub id: String,
    #[serde(rename = "type")]
    pub version_type: String,
    pub url: String,
}

/// Durée de validité du manifeste en mémoire. Mojang publie une version par
/// semaine au mieux : une demi-heure est large, et l'écran des instances
/// n'attend plus le réseau à chaque ouverture.
const MANIFEST_TTL: Duration = Duration::from_secs(30 * 60);

/// Manifeste en cache + verrou de téléchargement.
///
/// Le verrou évite le troupeau : ouvrir l'écran des instances pendant que le
/// préchargement du démarrage tourne encore lançait un second
/// téléchargement des mêmes 100 Ko. Le second appelant attend le premier et
/// repart avec son résultat.
static MANIFEST: LazyLock<RwLock<Option<(Instant, Arc<Vec<VersionInfo>>)>>> =
    LazyLock::new(|| RwLock::new(None));
static MANIFEST_FETCH: LazyLock<Mutex<()>> = LazyLock::new(|| Mutex::new(()));

/// Liste des versions jouables, depuis le cache quand il est frais.
///
/// En cas d'échec réseau, un cache périmé est préféré à une erreur : une
/// liste d'il y a deux heures reste utilisable pour créer une instance, une
/// liste vide non.
pub async fn fetch_version_list() -> Result<Vec<VersionInfo>> {
    if let Some((at, list)) = MANIFEST.read().await.as_ref() {
        if at.elapsed() < MANIFEST_TTL {
            return Ok(list.as_ref().clone());
        }
    }

    let _guard = MANIFEST_FETCH.lock().await;
    // Quelqu'un d'autre a pu le rafraîchir pendant l'attente du verrou.
    if let Some((at, list)) = MANIFEST.read().await.as_ref() {
        if at.elapsed() < MANIFEST_TTL {
            return Ok(list.as_ref().clone());
        }
    }

    match download_version_list().await {
        Ok(list) => {
            let list = Arc::new(list);
            *MANIFEST.write().await = Some((Instant::now(), list.clone()));
            Ok(list.as_ref().clone())
        }
        Err(e) => match MANIFEST.read().await.as_ref() {
            Some((_, stale)) => {
                tracing::warn!("Manifeste Mojang injoignable ({e}) — liste de versions périmée réutilisée");
                Ok(stale.as_ref().clone())
            }
            None => Err(e),
        },
    }
}

/// Réchauffe le cache au démarrage, sans jamais faire échouer quoi que ce
/// soit : quand l'écran des instances s'ouvre, la liste est déjà là.
pub async fn prefetch_version_list() {
    if let Err(e) = fetch_version_list().await {
        tracing::debug!("Préchargement du manifeste Mojang échoué : {e}");
    }
}

async fn download_version_list() -> Result<Vec<VersionInfo>> {
    let client = crate::minecraft::http::short_lived_client();
    let manifest: VersionManifest = client
        .get(VERSION_MANIFEST)
        .send()
        .await?
        .json()
        .await?;
    Ok(manifest
        .versions
        .into_iter()
        .filter(|v| v.version_type == "release" || v.version_type == "snapshot")
        .collect())
}

#[derive(Debug, Deserialize)]
pub struct JavaVersionInfo {
    pub component: String,
    #[serde(rename = "majorVersion")]
    pub major_version: u32,
}

#[derive(Debug, Deserialize)]
pub struct VersionDetails {
    pub id: String,
    #[serde(rename = "mainClass")]
    pub main_class: String,
    #[serde(rename = "minecraftArguments")]
    pub minecraft_arguments: Option<String>,
    pub arguments: Option<Arguments>,
    pub downloads: Downloads,
    pub libraries: Vec<Library>,
    #[serde(rename = "assetIndex")]
    pub asset_index: AssetIndex,
    #[serde(rename = "javaVersion")]
    pub java_version: Option<JavaVersionInfo>,
}

#[derive(Debug, Deserialize)]
pub struct Arguments {
    pub game: Vec<serde_json::Value>,
    pub jvm: Vec<serde_json::Value>,
}

#[derive(Debug, Deserialize)]
pub struct Downloads {
    pub client: Artifact,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Artifact {
    pub url: String,
    pub sha1: String,
    pub size: u64,
    /// Relative path inside the libraries directory (e.g. "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar")
    pub path: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Library {
    pub name: String,
    pub downloads: Option<LibraryDownloads>,
    pub rules: Option<Vec<serde_json::Value>>,
    /// Old format: maps OS name → classifier key (e.g. "windows" → "natives-windows")
    pub natives: Option<HashMap<String, String>>,
    pub extract: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct LibraryDownloads {
    pub artifact: Option<Artifact>,
    /// Old format native JARs keyed by classifier ("natives-windows", etc.)
    pub classifiers: Option<HashMap<String, Artifact>>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AssetIndex {
    pub id: String,
    pub url: String,
}

#[derive(Debug, Deserialize)]
pub struct AssetIndexFile {
    pub objects: HashMap<String, AssetObject>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AssetObject {
    pub hash: String,
    pub size: u64,
}

