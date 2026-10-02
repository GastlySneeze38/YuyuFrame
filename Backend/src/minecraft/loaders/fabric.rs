use anyhow::{anyhow, Result};
use serde::Deserialize;
use std::path::{Path, PathBuf};

use crate::minecraft::maven::MavenCoord;
use crate::minecraft::mod_files::is_jar_file;
use super::{mark_recommended, LoaderVersion};

const FABRIC_META: &str = "https://meta.fabricmc.net/v2";
const MODRINTH_API: &str = "https://api.modrinth.com/v2";

/// Une version du jeu connue d'un loader — `/versions/game`, chez Fabric
/// comme chez Quilt.
#[derive(Deserialize)]
pub(super) struct GameVersion {
    pub version: String,
}

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
    pub game: Option<Vec<serde_json::Value>>,
}

// ── Cache disque des profils de loader (mode hors ligne) ─────────────────────

/// Emplacement du profil résolu mis en cache — voir [`profile_with_cache`].
fn profile_cache_path(loader: &str, mc_version: &str) -> PathBuf {
    crate::minecraft::launcher::minecraft_dir()
        .join("loaders")
        .join(format!("{}-{}.json", loader, mc_version))
}

/// Résout un profil de loader (Fabric/Quilt) RÉSEAU D'ABORD, avec repli sur
/// le dernier profil connu mis en cache sur disque.
///
/// Sans ce repli, une instance déjà installée et parfaitement jouable
/// refusait de se lancer hors ligne : contrairement au JSON de version
/// vanilla (mis en cache de longue date, voir `orchestrator`), le profil
/// Fabric/Quilt était re-téléchargé à CHAQUE lancement et son échec était
/// fatal — d'où « error sending request for url (meta.fabricmc.net/...) »
/// sans aucune connexion, alors que toutes les libs étaient déjà là.
///
/// Volontairement réseau-d'abord et non cache-d'abord : le profil, lui,
/// n'est PAS immuable (une nouvelle version de loader sort régulièrement),
/// donc servir le cache en priorité épinglerait l'utilisateur sur un loader
/// périmé pour toujours. Le cache n'est qu'un filet de sécurité.
async fn profile_with_cache<F, Fut>(loader: &str, mc_version: &str, fetch: F) -> Result<FabricProfile>
where
    F: FnOnce() -> Fut,
    Fut: std::future::Future<Output = Result<String>>,
{
    let online = match fetch().await {
        Ok(raw) => serde_json::from_str::<FabricProfile>(&raw)
            .map(|p| (p, raw))
            .map_err(|e| anyhow!("Profil {} invalide: {}", loader, e)),
        Err(e) => Err(e),
    };

    match online {
        Ok((profile, raw)) => {
            let path = profile_cache_path(loader, mc_version);
            if let Some(parent) = path.parent() {
                let _ = tokio::fs::create_dir_all(parent).await;
            }
            if let Err(e) = tokio::fs::write(&path, &raw).await {
                tracing::warn!("Mise en cache du profil {} échouée ({}) — le lancement hors ligne ne sera pas possible", loader, e);
            }
            Ok(profile)
        }
        Err(e) => {
            let path = profile_cache_path(loader, mc_version);
            match tokio::fs::read_to_string(&path).await {
                Ok(raw) => match serde_json::from_str::<FabricProfile>(&raw) {
                    Ok(profile) => {
                        tracing::warn!("{} injoignable ({}) — repli sur le profil en cache ({})", loader, e, path.display());
                        Ok(profile)
                    }
                    Err(parse_err) => Err(anyhow!(
                        "{} injoignable ({}) et profil en cache illisible ({})", loader, e, parse_err
                    )),
                },
                Err(_) => Err(anyhow!(
                    "{} injoignable ({}) et aucun profil en cache pour MC {} — une première installation en ligne est nécessaire",
                    loader, e, mc_version
                )),
            }
        }
    }
}

/// Fetch the Fabric profile for the latest stable loader compatible with `mc_version`.
/// Repli hors ligne sur le dernier profil connu — voir [`profile_with_cache`].
pub async fn get_latest_profile(mc_version: &str) -> Result<FabricProfile> {
    profile_with_cache("fabric", mc_version, || fetch_profile_online(mc_version)).await
}

/// Les versions de loader disponibles pour ce MC, la plus récente d'abord.
///
/// Fabric Meta rend déjà la liste triée du plus récent au plus ancien : on la
/// garde telle quelle plutôt que de retrier, le tri d'origine étant le leur.
///
/// Elle est **la même pour toutes les versions de Minecraft** (vérifié : 253
/// entrées identiques pour 1.16.5 et 1.21.4), le loader Fabric étant
/// indépendant du jeu. Le `stable` qu'ils publient est donc la seule
/// indication utile, et ils n'en marquent qu'une seule — c'est la recommandée.
pub async fn list_versions(mc_version: &str) -> Result<Vec<LoaderVersion>> {
    let client = crate::minecraft::http::short_lived_client();
    let entries: Vec<LoaderEntry> = client
        .get(format!("{FABRIC_META}/versions/loader/{mc_version}"))
        .send()
        .await?
        .json()
        .await
        .map_err(|_| anyhow!("Fabric non disponible pour Minecraft {}", mc_version))?;

    let mut versions: Vec<LoaderVersion> = entries
        .into_iter()
        .map(|e| LoaderVersion::new(e.loader.version, e.loader.stable))
        .collect();
    mark_recommended(&mut versions, None);
    Ok(versions)
}

/// Les versions du jeu que Fabric connaît.
///
/// La question inverse de [`supports`], et la même route : elle sert là où le
/// loader est imposé et où c'est la liste des versions qu'il faut réduire
/// (duplication d'une instance, modpack qui impose son loader).
pub async fn game_versions() -> Result<Vec<String>> {
    let client = crate::minecraft::http::short_lived_client();
    let games: Vec<GameVersion> = client
        .get(format!("{FABRIC_META}/versions/game"))
        .send()
        .await?
        .json()
        .await?;
    Ok(games.into_iter().map(|g| g.version).collect())
}

/// Fabric connaît-il cette version du jeu ?
///
/// Par `/versions/game` et non par la liste des loaders : la première tient en
/// quelques centaines d'entrées `{version, stable}`, la seconde rend tout le
/// profil de chaque loader. Pour une question binaire posée à chaque
/// changement de version dans un menu, la différence se voit.
///
/// `Err` veut dire « on ne sait pas » (réseau), jamais « non » — c'est à
/// l'appelant de décider, et il propose le loader plutôt que de le retirer
/// sur une panne passagère.
pub async fn supports(mc_version: &str) -> Result<bool> {
    let client = crate::minecraft::http::short_lived_client();
    let games: Vec<GameVersion> = client
        .get(format!("{FABRIC_META}/versions/game"))
        .send()
        .await?
        .json()
        .await?;
    Ok(games.iter().any(|g| g.version == mc_version))
}

/// Le profil d'une version de loader **précise**, choisie par l'utilisateur.
///
/// Clé de cache distincte par version épinglée (`fabric-0.16.5`), sinon le
/// profil d'une version remplacerait celui d'une autre et le repli hors ligne
/// servirait le mauvais loader.
pub async fn get_profile(mc_version: &str, loader_version: &str) -> Result<FabricProfile> {
    let key = format!("fabric-{loader_version}");
    profile_with_cache(&key, mc_version, || {
        fetch_pinned_profile(FABRIC_META, "Fabric", mc_version, loader_version)
    })
    .await
}

/// Le JSON de profil pour un couple (MC, version de loader) — Fabric et Quilt
/// exposent exactement la même route, d'où le paramètre `meta`.
pub(super) async fn fetch_pinned_profile(
    meta: &str,
    loader: &str,
    mc_version: &str,
    loader_version: &str,
) -> Result<String> {
    let client = crate::minecraft::http::short_lived_client();
    let url = format!("{meta}/versions/loader/{mc_version}/{loader_version}/profile/json");
    let resp = client.get(&url).send().await?;
    if !resp.status().is_success() {
        return Err(anyhow!(
            "{} {} n'existe pas pour Minecraft {}",
            loader,
            loader_version,
            mc_version
        ));
    }
    Ok(resp.text().await?)
}

async fn fetch_profile_online(mc_version: &str) -> Result<String> {
    let client = crate::minecraft::http::short_lived_client();

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

    Ok(client.get(&profile_url).send().await?.text().await?)
}

/// Variante de [`profile_with_cache`] exposée à `quilt.rs` — même mécanique,
/// clé de cache différente.
pub(super) async fn profile_with_cache_for<F, Fut>(loader: &str, mc_version: &str, fetch: F) -> Result<FabricProfile>
where
    F: FnOnce() -> Fut,
    Fut: std::future::Future<Output = Result<String>>,
{
    profile_with_cache(loader, mc_version, fetch).await
}

/// Download a Fabric library and return its local path (None if unavailable
/// — voir les `tracing::warn!` pour la raison précise, remontée par
/// l'appelant comme avertissement de lancement, pas comme détail technique).
pub async fn download_library(lib: &FabricLibrary, libraries_dir: &Path, client: &reqwest::Client) -> Option<PathBuf> {
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
        match client.get(&url).send().await {
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
        .connect_timeout(std::time::Duration::from_secs(10))
        .timeout(std::time::Duration::from_secs(60))
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
