use anyhow::{anyhow, Result};
use serde::Deserialize;
use std::path::{Path, PathBuf};

use crate::minecraft::maven::MavenCoord;
use crate::minecraft::mod_files::is_jar_file;

const FABRIC_META: &str = "https://meta.fabricmc.net/v2";
const MODRINTH_API: &str = "https://api.modrinth.com/v2";

#[derive(Deserialize)]
struct LoaderEntry {
    loader: LoaderInfo,
}

#[derive(Deserialize)]
struct LoaderInfo {
    version: String,
    #[serde(default)]
    stable: bool,
}

#[derive(Deserialize)]
pub struct FabricProfile {
    #[serde(rename = "mainClass")]
    pub main_class: String,
    pub libraries: Vec<FabricLibrary>,
    pub arguments: Option<FabricArguments>,
}

#[derive(Deserialize)]
pub struct FabricLibrary {
    pub name: String,
    pub url: Option<String>,
}

#[derive(Deserialize)]
pub struct FabricArguments {
    pub jvm: Option<Vec<serde_json::Value>>,
    #[allow(dead_code)]
    pub game: Option<Vec<serde_json::Value>>,
}

/// Fetch the Fabric profile for the latest stable loader compatible with `mc_version`.
pub async fn get_latest_profile(mc_version: &str) -> Result<FabricProfile> {
    let client = reqwest::Client::new();

    let url = format!("{}/versions/loader/{}", FABRIC_META, mc_version);
    let entries: Vec<LoaderEntry> = client
        .get(&url)
        .send()
        .await?
        .json()
        .await
        .map_err(|_| anyhow!("Fabric non disponible pour Minecraft {}", mc_version))?;

    // Prefer a stable loader; fall back to the first available (latest) one
    let loader_ver = {
        let stable = entries.iter().find(|e| e.loader.stable);
        let chosen = stable.or_else(|| entries.first());
        chosen
            .map(|e| e.loader.version.clone())
            .ok_or_else(|| anyhow!("Aucun loader Fabric disponible pour {}", mc_version))?
    };

    tracing::info!("Fabric loader {} pour MC {}", loader_ver, mc_version);

    let profile_url = format!(
        "{}/versions/loader/{}/{}/profile/json",
        FABRIC_META, mc_version, loader_ver
    );

    client
        .get(&profile_url)
        .send()
        .await?
        .json()
        .await
        .map_err(|e| anyhow!("Profil Fabric invalide: {}", e))
}

/// Download a Fabric library and return its local path (None if unavailable
/// — voir les `tracing::warn!` pour la raison précise, remontée par
/// l'appelant comme avertissement de lancement, pas comme détail technique).
pub async fn download_library(lib: &FabricLibrary, libraries_dir: &Path) -> Option<PathBuf> {
    let base_url = lib.url.as_deref().unwrap_or("https://libraries.minecraft.net/");

    // Fabric ne fournit jamais de classifier sur ses libs de loader — on ignore
    // volontairement `coord.classifier` (contrairement à Forge) pour garder le
    // même nom de fichier `{artifact}-{version}.jar` qu'avant ce refacto.
    let Some(coord) = MavenCoord::parse(&lib.name) else {
        tracing::warn!("[Fabric] coordonnée maven invalide, lib ignorée : {}", lib.name);
        return None;
    };
    let filename = format!("{}-{}.jar", coord.artifact, coord.version);

    let url = format!(
        "{}{}/{}/{}/{}",
        base_url, coord.group_path, coord.artifact, coord.version, filename
    );

    let local_path = libraries_dir
        .join(&coord.group_path)
        .join(coord.artifact)
        .join(coord.version)
        .join(&filename);

    if let Some(parent) = local_path.parent() {
        if let Err(e) = tokio::fs::create_dir_all(parent).await {
            tracing::warn!("[Fabric] création du dossier pour {} échouée : {}", lib.name, e);
            return None;
        }
    }

    if !local_path.exists() {
        match reqwest::Client::new().get(&url).send().await {
            Ok(resp) if resp.status().is_success() => match resp.bytes().await {
                Ok(bytes) => {
                    if let Err(e) = tokio::fs::write(&local_path, &bytes).await {
                        tracing::warn!("[Fabric] écriture de {} échouée : {}", lib.name, e);
                    }
                }
                Err(e) => tracing::warn!("[Fabric] lecture du corps de réponse pour {} échouée : {}", lib.name, e),
            },
            Ok(resp) => tracing::warn!("[Fabric] téléchargement de {} échoué : HTTP {}", lib.name, resp.status()),
            Err(e) => tracing::warn!("[Fabric] téléchargement de {} échoué : {}", lib.name, e),
        }
    }

    if local_path.exists() { Some(local_path) } else { None }
}

// ── Fabric API auto-install ───────────────────────────────────────────────────

#[derive(Deserialize)]
struct ModrinthVersion {
    files: Vec<ModrinthFile>,
}

#[derive(Deserialize)]
struct ModrinthFile {
    url: String,
    filename: String,
    primary: bool,
}

/// Ensure Fabric API is present in the mods folder for `mc_version`.
/// Downloads the latest version from Modrinth if not already installed.
pub async fn ensure_fabric_api(mc_version: &str, mods_dir: &Path) -> Result<()> {
    tokio::fs::create_dir_all(mods_dir).await?;

    // Already installed if any fabric-api JAR exists for this MC version
    let prefix = "fabric-api-".to_string();
    if let Ok(mut entries) = tokio::fs::read_dir(mods_dir).await {
        while let Ok(Some(entry)) = entries.next_entry().await {
            let name = entry.file_name().to_string_lossy().to_string();
            if name.to_ascii_lowercase().starts_with(&prefix) && is_jar_file(&name) {
                tracing::info!("Fabric API déjà présente: {}", name);
                return Ok(());
            }
        }
    }

    tracing::info!("Téléchargement de Fabric API pour MC {}...", mc_version);

    let client = reqwest::Client::builder()
        .user_agent("YuyuFrame/1.0")
        .build()?;

    let url = format!(
        "{}/project/fabric-api/version?game_versions=[\"{}\"]&loaders=[\"fabric\"]",
        MODRINTH_API, mc_version
    );

    let versions: Vec<ModrinthVersion> = client
        .get(&url)
        .send()
        .await?
        .json()
        .await
        .map_err(|_| anyhow!("Impossible de trouver Fabric API pour MC {}", mc_version))?;

    let latest = versions
        .into_iter()
        .next()
        .ok_or_else(|| anyhow!("Aucune version de Fabric API pour MC {}", mc_version))?;

    let file = latest
        .files
        .into_iter()
        .find(|f| f.primary)
        .ok_or_else(|| anyhow!("Aucun fichier principal pour Fabric API"))?;

    let bytes = client.get(&file.url).send().await?.bytes().await?;
    let dest = mods_dir.join(&file.filename);
    tokio::fs::write(&dest, &bytes).await?;

    tracing::info!("Fabric API installée: {}", file.filename);
    Ok(())
}
