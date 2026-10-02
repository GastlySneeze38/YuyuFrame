use anyhow::{anyhow, Result};
use serde::Deserialize;

use crate::minecraft::versions::predicate::{cmp_core, version_core};
use super::fabric::FabricProfile;
use super::{mark_recommended, LoaderVersion};

const QUILT_META: &str = "https://meta.quiltmc.org/v3";

#[derive(Deserialize)]
struct LoaderEntry {
    loader: LoaderInfo,
}

#[derive(Deserialize)]
struct LoaderInfo {
    version: String,
}

/// Fetch the Quilt profile for the latest loader compatible with `mc_version`.
/// Réutilise directement `fabric::FabricProfile` (même forme JSON exacte,
/// vérifiée sur un profil réel : `mainClass`, `libraries: [{name, url}]`,
/// `arguments.{jvm,game}`) — pas besoin d'un type dupliqué.
/// Repli hors ligne sur le dernier profil connu — voir
/// `fabric::profile_with_cache` (même mécanique, clé de cache "quilt").
pub async fn get_latest_profile(mc_version: &str) -> Result<FabricProfile> {
    super::fabric::profile_with_cache_for("quilt", mc_version, || fetch_profile_online(mc_version)).await
}

/// Les versions de loader disponibles pour ce MC, la plus récente d'abord.
///
/// Trois différences avec Fabric, et chacune coûte une ligne ici : l'API v3 ne
/// garantit pas l'ordre (on trie), elle ne publie **aucun champ `stable`** (on
/// le déduit du nom), et l'essentiel de ce qu'elle rend est en pré-version —
/// 254 des 307 entrées pour 1.21.4 portent `-beta`. Désigner la plus récente
/// tout court enverrait donc presque toujours sur une beta.
pub async fn list_versions(mc_version: &str) -> Result<Vec<LoaderVersion>> {
    let client = crate::minecraft::http::short_lived_client();
    let entries: Vec<LoaderEntry> = client
        .get(format!("{QUILT_META}/versions/loader/{mc_version}"))
        .send()
        .await?
        .json()
        .await
        .map_err(|_| anyhow!("Quilt non disponible pour Minecraft {}", mc_version))?;

    let mut versions: Vec<LoaderVersion> = entries
        .into_iter()
        .map(|e| {
            let stable = is_stable(&e.loader.version);
            LoaderVersion::new(e.loader.version, stable)
        })
        .collect();
    versions.sort_by(|a, b| cmp_core(&version_core(&b.version), &version_core(&a.version)));
    mark_recommended(&mut versions, None);
    Ok(versions)
}

/// Une version Quilt est publiée quand elle ne porte pas d'étiquette de
/// pré-version — `0.20.0-beta.9`, `0.17.0-rc.1`. Le tiret seul ne suffit pas
/// comme critère : il sert aussi à des suffixes de build ailleurs.
fn is_stable(version: &str) -> bool {
    let lower = version.to_ascii_lowercase();
    !(lower.contains("-beta") || lower.contains("-rc") || lower.contains("-alpha") || lower.contains("-pre"))
}

/// Quilt connaît-il cette version du jeu ? Même route et même convention que
/// `fabric::supports` : `Err` = « on ne sait pas », pas « non ».
pub async fn supports(mc_version: &str) -> Result<bool> {
    let client = crate::minecraft::http::short_lived_client();
    let games: Vec<super::fabric::GameVersion> = client
        .get(format!("{QUILT_META}/versions/game"))
        .send()
        .await?
        .json()
        .await?;
    Ok(games.iter().any(|g| g.version == mc_version))
}

/// Le profil d'une version de loader précise — voir `fabric::get_profile`.
pub async fn get_profile(mc_version: &str, loader_version: &str) -> Result<FabricProfile> {
    let key = format!("quilt-{loader_version}");
    super::fabric::profile_with_cache_for(&key, mc_version, || {
        super::fabric::fetch_pinned_profile(QUILT_META, "Quilt", mc_version, loader_version)
    })
    .await
}

async fn fetch_profile_online(mc_version: &str) -> Result<String> {
    let client = crate::minecraft::http::short_lived_client();

    let url = format!("{}/versions/loader/{}", QUILT_META, mc_version);
    let entries: Vec<LoaderEntry> = client
        .get(&url)
        .send()
        .await?
        .json()
        .await
        .map_err(|_| anyhow!("Quilt non disponible pour Minecraft {}", mc_version))?;

    // Contrairement à Fabric Meta v2 (champ `stable` + liste déjà triée par le
    // plus récent), l'API v3 de Quilt ne garantit ni l'un ni l'autre —
    // sélection manuelle de la version la plus haute numériquement, builds
    // -beta/-rc écartés sauf absence totale d'alternative.
    let pick_best = |list: &[&LoaderEntry]| -> Option<String> {
        list.iter()
            .max_by(|a, b| cmp_core(&version_core(&a.loader.version), &version_core(&b.loader.version)))
            .map(|e| e.loader.version.clone())
    };
    let all: Vec<&LoaderEntry> = entries.iter().collect();
    let stable: Vec<&LoaderEntry> = all
        .iter()
        .copied()
        .filter(|e| !e.loader.version.contains("-beta") && !e.loader.version.contains("-rc"))
        .collect();
    let loader_ver = pick_best(&stable)
        .or_else(|| pick_best(&all))
        .ok_or_else(|| anyhow!("Aucun loader Quilt disponible pour {}", mc_version))?;

    tracing::info!("Quilt loader {} pour MC {}", loader_ver, mc_version);

    let profile_url = format!(
        "{}/versions/loader/{}/{}/profile/json",
        QUILT_META, mc_version, loader_ver
    );

    Ok(client.get(&profile_url).send().await?.text().await?)
}
