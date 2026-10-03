use anyhow::{anyhow, Result};
use serde::Deserialize;
use std::path::{Path, PathBuf};

use crate::minecraft::versions::predicate::{cmp_core, version_core};
use super::{mark_recommended, LoaderVersion};

const NEOFORGE_VERSIONS_API: &str =
    "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge";
const NEOFORGE_MAVEN: &str = "https://maven.neoforged.net/releases/net/neoforged/neoforge/";

#[derive(Deserialize)]
struct NeoForgeVersionList {
    versions: Vec<String>,
}

/// Returns the NeoForge version string (e.g. "20.2.93") for a given MC version.
/// Contrairement à Forge (fichier `promotions_slim.json` + id "mc-forge"),
/// NeoForge expose juste la liste plate de toutes ses versions publiées, dont
/// le préfixe majeur.mineur correspond toujours à celui de MC (`1.20.2` →
/// `20.2.x`, vérifié empiriquement sur `maven.neoforged.net`) — pas de patch
/// MC dans le préfixe puisque NeoForge ne cible jamais deux versions patch de
/// MC avec le même majeur.mineur différemment.
pub async fn fetch_latest_version(mc_version: &str) -> Result<String> {
    let client = crate::minecraft::http::short_lived_client();
    let resp: NeoForgeVersionList = client
        .get(NEOFORGE_VERSIONS_API)
        .send()
        .await?
        .json()
        .await
        .map_err(|_| anyhow!("Impossible de contacter le serveur NeoForge"))?;

    let prefix = format!("{}.", mc_version.strip_prefix("1.").unwrap_or(mc_version));
    let matching: Vec<String> = resp.versions.into_iter().filter(|v| v.starts_with(&prefix)).collect();

    // Tri numérique (pas lexicographique : les patchs ne sont pas zero-paddés,
    // ex. "20.2.9" vs "20.2.10") via les mêmes helpers que la résolution de
    // dépendances de mods (predicate.rs) — pas de logique de comparaison de
    // version dupliquée ici.
    let pick_best = |versions: &[String]| -> Option<String> {
        versions
            .iter()
            .max_by(|a, b| cmp_core(&version_core(a), &version_core(b)))
            .cloned()
    };

    let stable: Vec<String> = matching.iter().filter(|v| !v.contains("-beta")).cloned().collect();
    pick_best(&stable)
        .or_else(|| pick_best(&matching))
        .ok_or_else(|| anyhow!("Aucune version NeoForge pour Minecraft {}", mc_version))
}

/// Les versions du jeu pour lesquelles NeoForge publie quelque chose.
///
/// Déduites de leurs numéros, faute d'une liste publiée : `20.2.3` cible
/// `1.20.2`. Le cas particulier est la version `.0`, qui vise une version de
/// Minecraft **sans patch** — `21.0.x` est pour `1.21`, pas pour « 1.21.0 »
/// qui n'existe pas. On rend donc les deux formes, et c'est la liste des
/// versions du jeu qui tranche en ne gardant que celles qui existent.
pub async fn game_versions() -> Result<Vec<String>> {
    let client = crate::minecraft::http::short_lived_client();
    let resp: NeoForgeVersionList = client.get(NEOFORGE_VERSIONS_API).send().await?.json().await?;
    Ok(game_versions_from(&resp.versions))
}

/// Partie calculatoire de [`game_versions`], isolée pour être testable.
fn game_versions_from(versions: &[String]) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for v in versions {
        let mut parts = v.split('.');
        let (Some(major), Some(minor)) = (parts.next(), parts.next()) else { continue };
        // Les branches hors ligne principale (`0.25w14craftmine.3-beta`) ne
        // ciblent pas une version publiée : elles n'ont rien à faire ici.
        if major.parse::<u32>().is_err() || minor.parse::<u32>().is_err() {
            continue;
        }
        out.push(format!("1.{major}.{minor}"));
        if minor == "0" {
            out.push(format!("1.{major}"));
        }
    }
    out.sort();
    out.dedup();
    out
}

/// NeoForge a-t-il une version pour ce MC ?
///
/// Souvent non, et c'est normal : le dépôt `net.neoforged:neoforge` commence à
/// `20.2`, donc **rien avant Minecraft 1.20.2** (les builds 1.20.1 vivent sous
/// un autre artefact, que le launcher n'installe pas). Même liste que
/// `list_versions`, filtrée pareil.
///
/// `Err` = « on ne sait pas » (réseau), jamais « non ».
pub async fn supports(mc_version: &str) -> Result<bool> {
    let client = crate::minecraft::http::short_lived_client();
    let resp: NeoForgeVersionList = client.get(NEOFORGE_VERSIONS_API).send().await?.json().await?;
    let prefix = format!("{}.", mc_version.strip_prefix("1.").unwrap_or(mc_version));
    Ok(resp.versions.iter().any(|v| v.starts_with(&prefix)))
}

/// Les versions NeoForge disponibles pour ce MC, la plus récente d'abord.
///
/// Même filtre par préfixe que `fetch_latest_version` ci-dessus, et même tri
/// numérique — un tri lexicographique mettrait `20.2.9` après `20.2.10`.
pub async fn list_versions(mc_version: &str) -> Result<Vec<LoaderVersion>> {
    let client = crate::minecraft::http::short_lived_client();
    let resp: NeoForgeVersionList = client
        .get(NEOFORGE_VERSIONS_API)
        .send()
        .await?
        .json()
        .await
        .map_err(|_| anyhow!("Impossible de contacter le serveur NeoForge"))?;

    let prefix = format!("{}.", mc_version.strip_prefix("1.").unwrap_or(mc_version));
    let mut versions: Vec<LoaderVersion> = resp
        .versions
        .into_iter()
        .filter(|v| v.starts_with(&prefix))
        // Les beta sont la majorité (120 sur 158 pour 1.21.4) : les écarter de
        // la liste priverait d'un choix légitime, ne pas les signaler ferait
        // recommander une pré-version. On les garde, marquées.
        .map(|v| {
            let stable = !v.contains("-beta");
            LoaderVersion::new(v, stable)
        })
        .collect();
    versions.sort_by(|a, b| cmp_core(&version_core(&b.version), &version_core(&a.version)));
    mark_recommended(&mut versions, None);
    Ok(versions)
}

/// L'id de version installée est toujours exactement `neoforge-{version}`
/// (vérifié sur un installeur réel) — pas besoin du scan flou de
/// `forge::find_installed` (qui existe pour absorber les ids irréguliers du
/// Forge pré-1.13, un cas qui n'existe pas pour NeoForge).
pub fn find_installed(neoforge_ver: &str, mc_dir: &Path) -> Option<String> {
    let id = format!("neoforge-{}", neoforge_ver);
    let path = mc_dir.join("versions").join(&id).join(format!("{}.json", id));
    path.exists().then_some(id)
}

/// N'importe quelle version NeoForge déjà installée pour ce MC, sans
/// connaître le build — repli HORS LIGNE quand `fetch_latest_version` ne peut
/// pas joindre `maven.neoforged.net` (voir `setup_neoforge`). Le dossier ne
/// contient jamais la version MC telle quelle (`1.21.1` → `neoforge-21.1.x`),
/// d'où le même calcul de préfixe que `fetch_latest_version`.
pub fn find_any_installed(mc_version: &str, mc_dir: &Path) -> Option<String> {
    let prefix = format!("{}.", mc_version.strip_prefix("1.").unwrap_or(mc_version));
    let entries = std::fs::read_dir(mc_dir.join("versions")).ok()?;
    let mut best: Option<String> = None;
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        let Some(ver) = name.strip_prefix("neoforge-") else { continue };
        if !ver.starts_with(&prefix) || !entry.path().join(format!("{}.json", name)).exists() {
            continue;
        }
        // Plusieurs builds peuvent cohabiter — on garde le plus récent, même
        // tri numérique que `fetch_latest_version`.
        let keep = match &best {
            Some(current) => {
                let current_ver = current.strip_prefix("neoforge-").unwrap_or(current);
                cmp_core(&version_core(ver), &version_core(current_ver)).is_gt()
            }
            None => true,
        };
        if keep {
            best = Some(name);
        }
    }
    best
}

/// Télécharge l'installeur NeoForge et le lance en mode client headless.
/// Contrairement à Forge, NeoForge n'a jamais eu de format d'installeur
/// legacy (pas de version pré-1.13) : toujours `--installClient` en ligne de
/// commande, jamais besoin de reproduire un profil d'installation à la main.
pub async fn install(neoforge_ver: &str, mc_dir: &Path, java: &str, client: &reqwest::Client) -> Result<String> {
    let id = format!("neoforge-{}", neoforge_ver);
    let installer_name = format!("neoforge-{}-installer.jar", neoforge_ver);
    let url = format!("{}{}/{}", NEOFORGE_MAVEN, neoforge_ver, installer_name);

    let temp = mc_dir.join(".neoforge-installer");
    tokio::fs::create_dir_all(&temp).await?;
    let installer_path: PathBuf = temp.join(&installer_name);

    if !installer_path.exists() {
        tracing::info!("Téléchargement installeur NeoForge depuis {}", url);
        let resp = client.get(&url).send().await?;
        if !resp.status().is_success() {
            return Err(anyhow!("Téléchargement installeur NeoForge échoué: {}", resp.status()));
        }
        let bytes = resp.bytes().await?;
        tokio::fs::write(&installer_path, &bytes).await?;
    }

    tracing::info!("Lancement installeur NeoForge...");
    let output = crate::app::process::hidden_command(java)
        .args([
            "-jar",
            &installer_path.to_string_lossy(),
            "--installClient",
            &mc_dir.to_string_lossy(),
        ])
        .current_dir(mc_dir)
        .output()
        .await?;

    let _ = tokio::fs::remove_dir_all(&temp).await;

    if !output.status.success() {
        // Même remarque que Forge (R-1) : le diagnostic utile atterrit
        // souvent sur stdout, pas seulement stderr.
        let stdout = String::from_utf8_lossy(&output.stdout);
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(anyhow!("Installeur NeoForge échoué:\n{}\n{}", stdout, stderr));
    }

    Ok(id)
}

#[cfg(test)]
mod tests {
    use super::game_versions_from;

    fn v(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| s.to_string()).collect()
    }

    /// `20.2.3` cible `1.20.2` : c'est le numéro du loader qui porte la
    /// version du jeu, NeoForge ne publiant pas la correspondance.
    #[test]
    fn la_version_du_jeu_se_lit_dans_le_numero() {
        assert_eq!(game_versions_from(&v(&["20.2.3", "20.2.4", "21.3.1"])), v(&["1.20.2", "1.21.3"]));
    }

    /// Le patch `.0` vise une version sans patch : `21.0.x` est pour `1.21`,
    /// et « 1.21.0 » n'existe pas. On rend les deux, le manifeste du jeu
    /// écartera celle qui n'existe pas.
    #[test]
    fn le_patch_zero_vise_aussi_la_version_sans_patch() {
        assert_eq!(game_versions_from(&v(&["21.0.5"])), v(&["1.21", "1.21.0"]));
    }

    /// Les branches hors ligne principale ne ciblent aucune version publiée.
    #[test]
    fn les_branches_speciales_sont_ecartees() {
        assert!(game_versions_from(&v(&["0.25w14craftmine.3-beta"])).is_empty());
        assert!(game_versions_from(&v(&["21"])).is_empty());
        assert!(game_versions_from(&[]).is_empty());
    }
}
