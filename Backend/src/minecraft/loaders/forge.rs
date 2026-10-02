use anyhow::{anyhow, Result};
use serde::Deserialize;
use std::collections::HashMap;
use std::path::{Path, PathBuf};

use crate::minecraft::maven::MavenCoord;
use crate::minecraft::versions::predicate::{cmp_core, version_core};
use super::{mark_recommended, LoaderVersion};

const FORGE_PROMOTIONS: &str =
    "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
const FORGE_MAVEN: &str =
    "https://maven.minecraftforge.net/net/minecraftforge/forge/";

#[derive(Deserialize)]
struct ForgePromos {
    promos: HashMap<String, String>,
}

/// Subset of the Forge installed version JSON (similar structure to vanilla).
#[derive(Deserialize)]
pub struct ForgeVersionJson {
    #[serde(rename = "mainClass")]
    pub main_class: String,
    pub libraries: Option<Vec<ForgeLibrary>>,
    pub arguments: Option<ForgeArguments>,
    /// Présent uniquement sur le format legacy (pré-1.13) à la place de
    /// `arguments.game` — contient notamment `--tweakClass ...`, indispensable
    /// pour que Forge s'injecte dans le launchwrapper.
    #[serde(rename = "minecraftArguments")]
    pub minecraft_arguments: Option<String>,
}

#[derive(Deserialize)]
pub struct ForgeLibrary {
    pub name: String,
    pub downloads: Option<ForgeLibDownloads>,
    /// Dépôt maven de base — uniquement présent sur le format legacy (pré-1.13)
    /// qui n'a pas de bloc `downloads.artifact.url`.
    #[serde(default)]
    pub url: Option<String>,
}

#[derive(Deserialize)]
pub struct ForgeLibDownloads {
    pub artifact: Option<ForgeArtifact>,
}

#[derive(Deserialize, Clone)]
pub struct ForgeArtifact {
    pub url: String,
    pub path: Option<String>,
}

#[derive(Deserialize)]
pub struct ForgeArguments {
    pub game: Option<Vec<serde_json::Value>>,
    pub jvm: Option<Vec<serde_json::Value>>,
}

/// Returns the Forge version string (e.g. "54.0.1") for a given MC version.
pub async fn fetch_latest_version(mc_version: &str) -> Result<String> {
    let client = crate::minecraft::http::short_lived_client();
    let promos: ForgePromos = client
        .get(FORGE_PROMOTIONS)
        .send()
        .await?
        .json()
        .await
        .map_err(|_| anyhow!("Impossible de contacter le serveur Forge"))?;

    let recommended = format!("{}-recommended", mc_version);
    let latest = format!("{}-latest", mc_version);

    promos
        .promos
        .get(&recommended)
        .or_else(|| promos.promos.get(&latest))
        .cloned()
        .ok_or_else(|| anyhow!("Aucune version Forge pour Minecraft {}", mc_version))
}

/// Les versions du jeu pour lesquelles Forge publie quelque chose.
///
/// Lues dans les clés des promotions (`1.21.4-recommended`), donc exactement
/// les versions que le launcher sait installer — pas une liste reconstituée.
pub async fn game_versions() -> Result<Vec<String>> {
    let client = crate::minecraft::http::short_lived_client();
    let promos: ForgePromos = client.get(FORGE_PROMOTIONS).send().await?.json().await?;
    let mut versions: Vec<String> = promos
        .promos
        .keys()
        .filter_map(|k| k.rsplit_once('-').map(|(mc, _)| mc.to_string()))
        .collect();
    versions.sort();
    versions.dedup();
    Ok(versions)
}

/// Forge a-t-il une version pour ce MC ?
///
/// Par les promotions et non par le maven : le premier fait quelques dizaines
/// de kilo-octets, le second plus d'un méga-octet pour la même réponse
/// binaire. Et une version promue est précisément ce que le launcher
/// installerait — s'il n'y en a pas, il n'y a rien à proposer.
///
/// `Err` = « on ne sait pas » (réseau), jamais « non ».
pub async fn supports(mc_version: &str) -> Result<bool> {
    let client = crate::minecraft::http::short_lived_client();
    let promos: ForgePromos = client.get(FORGE_PROMOTIONS).send().await?.json().await?;
    Ok(promos.promos.contains_key(&format!("{mc_version}-recommended"))
        || promos.promos.contains_key(&format!("{mc_version}-latest")))
}

/// Les builds Forge disponibles pour ce MC, le plus récent d'abord.
///
/// `promotions_slim.json` ne connaît que « recommended » et « latest » : pour
/// proposer un choix il faut la liste complète, qui n'existe que dans le
/// `maven-metadata.xml` du dépôt. Pas de dépendance XML pour autant — on ne
/// cherche qu'une suite de `<version>…</version>`, et un analyseur complet
/// pour ça serait disproportionné (voir `builds_for`, testé).
///
/// Deux requêtes et non une : le maven donne la liste, les promotions disent
/// laquelle Forge recommande. C'est leur propre désignation, et c'est
/// exactement ce que le launcher installe quand rien n'est épinglé — la
/// recommandation ne doit pas dire autre chose que ce qui se passerait sans
/// elle. Promotions injoignable n'est pas fatal : on retombe sur le build le
/// plus récent.
pub async fn list_versions(mc_version: &str) -> Result<Vec<LoaderVersion>> {
    let client = crate::minecraft::http::short_lived_client();
    let xml = client
        .get(format!("{FORGE_MAVEN}maven-metadata.xml"))
        .send()
        .await?
        .text()
        .await
        .map_err(|_| anyhow!("Impossible de contacter le serveur Forge"))?;

    let mut versions: Vec<LoaderVersion> = builds_for(&xml, mc_version)
        .into_iter()
        // Forge ne publie pas de pré-versions par canal : tout ce qui est au
        // maven est publié. La distinction utile est « recommandée ou non ».
        .map(|b| LoaderVersion::new(b, true))
        .collect();

    let promoted = fetch_latest_version(mc_version).await.ok();
    mark_recommended(&mut versions, promoted.as_deref());
    Ok(versions)
}

/// Les builds Forge de cette version de MC, le plus récent d'abord.
///
/// Deux pièges, tous deux vérifiés sur le vrai `maven-metadata.xml` :
///
///   - **le document n'est pas trié** (`1.21.4-54.1.6` y précède
///     `1.21.4-54.1.18`), donc l'ordre d'apparition ne veut rien dire — on
///     trie numériquement, comme ailleurs dans le launcher ;
///   - **le pré-1.13 répète la version MC en suffixe** (`1.7.10-10.13.4.1614-1.7.10`).
///     Il faut l'enlever, parce que le reste du code manipule le build au
///     format des promotions (`10.13.4.1614`) — c'est lui que `install`
///     attend, et il sait déjà reconstruire l'URL legacy.
fn builds_for(xml: &str, mc_version: &str) -> Vec<String> {
    let prefix = format!("{mc_version}-");
    let suffix = format!("-{mc_version}");
    let mut builds: Vec<String> = xml
        .split("<version>")
        .skip(1)
        .filter_map(|rest| rest.split_once("</version>"))
        .map(|(v, _)| v.trim())
        .filter(|v| !v.is_empty())
        .filter_map(|v| v.strip_prefix(&prefix))
        .map(|b| b.strip_suffix(&suffix).unwrap_or(b).to_string())
        .collect();
    builds.sort_by(|a, b| cmp_core(&version_core(b), &version_core(a)));
    builds.dedup();
    builds
}

/// Trouve un dossier de version déjà installé correspondant à ce MC+build Forge.
/// On ne déduit pas l'id depuis un format fixe : les vieux Forge pré-1.13
/// (1.7.x à ~1.12) utilisent des ids irréguliers selon la version (casse,
/// suffixe dupliqué...), donc on recherche plutôt un dossier existant dont le
/// nom contient à la fois la version MC et le build Forge.
/// N'importe quelle version Forge déjà installée pour ce MC, sans connaître
/// le build — repli HORS LIGNE quand `fetch_latest_version` ne peut pas
/// joindre les serveurs Forge (voir `setup_forge`). Même heuristique de nom
/// de dossier que [`find_installed`], sans la contrainte sur le build ;
/// `neoforge-*` est exclu explicitement (dossiers distincts, voir
/// `neoforge::find_any_installed`).
pub fn find_any_installed(mc_version: &str, mc_dir: &Path) -> Option<String> {
    let versions_dir = mc_dir.join("versions");
    let entries = std::fs::read_dir(&versions_dir).ok()?;
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        let lower = name.to_ascii_lowercase();
        if lower.starts_with("neoforge") || !lower.contains("forge") {
            continue;
        }
        if name.contains(mc_version) && entry.path().join(format!("{}.json", name)).exists() {
            return Some(name);
        }
    }
    None
}

pub fn find_installed(mc_version: &str, forge_ver: &str, mc_dir: &Path) -> Option<String> {
    let versions_dir = mc_dir.join("versions");
    let entries = std::fs::read_dir(&versions_dir).ok()?;
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if name.contains(forge_ver) && name.contains(mc_version) && entry.path().join(format!("{}.json", name)).exists() {
            return Some(name);
        }
    }
    None
}

enum InstallerProfile {
    /// Installeur récent (>= ~1.13) : peut s'installer en ligne de commande
    /// via `--installClient`.
    Modern { id: String },
    /// Installeur pré-1.13 : son `SimpleInstaller` n'a **aucun** mode
    /// headless pour le client (il ouvre toujours une fenêtre Swing — testé
    /// empiriquement, `--installClient` n'existe même pas dans son parseur
    /// d'options). On reproduit donc l'installation nous-mêmes à partir du
    /// profil embarqué.
    Legacy { id: String, profile: serde_json::Value },
}

/// Inspecte l'installeur téléchargé pour déterminer son format et l'id réel
/// de version qu'il va produire, sans deviner via un pattern de chaîne (les
/// vieux Forge ont des ids irréguliers selon la version : casse, suffixe
/// dupliqué...).
fn inspect_installer(installer_path: &Path) -> Result<InstallerProfile> {
    use std::io::Read;
    let bytes = std::fs::read(installer_path)?;
    let cursor = std::io::Cursor::new(bytes);
    let mut archive = zip::ZipArchive::new(cursor)?;

    if let Ok(mut entry) = archive.by_name("version.json") {
        let mut content = String::new();
        entry.read_to_string(&mut content)?;
        let v: serde_json::Value = serde_json::from_str(&content)?;
        if let Some(id) = v.get("id").and_then(|x| x.as_str()) {
            return Ok(InstallerProfile::Modern { id: id.to_string() });
        }
    }

    let mut entry = archive
        .by_name("install_profile.json")
        .map_err(|_| anyhow!("Installeur Forge invalide (profil introuvable)"))?;
    let mut content = String::new();
    entry.read_to_string(&mut content)?;
    let profile: serde_json::Value = serde_json::from_str(&content)?;
    let id = profile
        .get("versionInfo")
        .and_then(|vi| vi.get("id"))
        .and_then(|x| x.as_str())
        .ok_or_else(|| anyhow!("Impossible de déterminer l'ID de version Forge"))?
        .to_string();
    Ok(InstallerProfile::Legacy { id, profile })
}

/// Écrit le version json et télécharge les libs (incluant le universal jar
/// Forge) décrits par le profil legacy — équivalent du travail que ferait le
/// `SimpleInstaller` GUI pour un client.
async fn install_legacy(version_id: &str, profile: &serde_json::Value, mc_dir: &Path, libraries_dir: &Path, client: &reqwest::Client) -> Result<()> {
    let version_info = profile
        .get("versionInfo")
        .ok_or_else(|| anyhow!("Profil Forge legacy invalide"))?;

    let version_dir = mc_dir.join("versions").join(version_id);
    tokio::fs::create_dir_all(&version_dir).await?;
    tokio::fs::write(
        version_dir.join(format!("{}.json", version_id)),
        serde_json::to_vec_pretty(version_info)?,
    )
    .await?;

    // L'artefact Forge lui-même est publié sous un nom de fichier différent
    // (`install.filePath`, ex: "...-universal.jar") de son nom maven standard
    // — on le récupère sous ce nom distant mais on le range localement sous
    // le nom standard pour que la résolution générique des libs le retrouve.
    let forge_artifact_name = profile.pointer("/install/path").and_then(|v| v.as_str());
    let forge_file_name = profile.pointer("/install/filePath").and_then(|v| v.as_str());

    let libraries = version_info
        .get("libraries")
        .and_then(|l| l.as_array())
        .cloned()
        .unwrap_or_default();

    for lib in libraries {
        let Some(name) = lib.get("name").and_then(|v| v.as_str()) else { continue };
        let Some(coord) = MavenCoord::parse(name) else { continue };
        let (group, art, ver) = (coord.group_path, coord.artifact, coord.version);
        let local_path = libraries_dir.join(&group).join(art).join(ver).join(format!("{}-{}.jar", art, ver));

        if let Some(parent) = local_path.parent() {
            let _ = tokio::fs::create_dir_all(parent).await;
        }
        if local_path.exists() {
            continue;
        }

        let base = lib.get("url").and_then(|v| v.as_str()).unwrap_or("https://libraries.minecraft.net/");
        let remote_file = if Some(name) == forge_artifact_name {
            forge_file_name.map(|s| s.to_string()).unwrap_or_else(|| format!("{}-{}.jar", art, ver))
        } else {
            format!("{}-{}.jar", art, ver)
        };
        let url = format!("{}{}/{}/{}/{}", base, group, art, ver, remote_file);

        if let Ok(resp) = client.get(&url).send().await {
            if resp.status().is_success() {
                if let Ok(bytes) = resp.bytes().await {
                    let _ = tokio::fs::write(&local_path, &bytes).await;
                }
            }
        }
    }

    Ok(())
}

/// Download the Forge installer and run it targeting `mc_dir`. Returns the
/// real installed version id (cf. [`inspect_installer`]).
pub async fn install(
    mc_version: &str,
    forge_ver: &str,
    mc_dir: &Path,
    libraries_dir: &Path,
    java: &str,
    client: &reqwest::Client,
) -> Result<String> {
    let installer_name = format!("forge-{}-{}-installer.jar", mc_version, forge_ver);
    // Forge moderne (>= ~1.13) range ses installeurs sous "{mc}-{forge}/".
    // Forge pré-1.13 a régulièrement répété la version MC dans le dossier :
    // "{mc}-{forge}-{mc}/". On essaie le format moderne puis on retombe sur
    // l'ancien si le serveur répond 404.
    let modern_url = format!("{}{}-{}/{}", FORGE_MAVEN, mc_version, forge_ver, installer_name);
    let legacy_installer_name = format!("forge-{}-{}-{}-installer.jar", mc_version, forge_ver, mc_version);
    let legacy_url = format!("{}{}-{}-{}/{}", FORGE_MAVEN, mc_version, forge_ver, mc_version, legacy_installer_name);

    let temp = mc_dir.join(".forge-installer");
    tokio::fs::create_dir_all(&temp).await?;
    let installer_path = temp.join(&installer_name);

    if !installer_path.exists() {
        tracing::info!("Téléchargement installeur Forge depuis {}", modern_url);
        let mut resp = client.get(&modern_url).send().await?;
        if !resp.status().is_success() {
            tracing::info!("Format moderne introuvable ({}), essai du format legacy {}", resp.status(), legacy_url);
            resp = client.get(&legacy_url).send().await?;
            if !resp.status().is_success() {
                return Err(anyhow!(
                    "Téléchargement installeur Forge échoué: {}",
                    resp.status()
                ));
            }
        }
        let bytes = resp.bytes().await?;
        tokio::fs::write(&installer_path, &bytes).await?;
    }

    let profile = inspect_installer(&installer_path)?;

    let result = match profile {
        InstallerProfile::Modern { id } => {
            tracing::info!("Lancement installeur Forge...");
            let output = crate::process::hidden_command(java)
                .args([
                    "-jar",
                    &installer_path.to_string_lossy(),
                    "--installClient",
                    &mc_dir.to_string_lossy(),
                ])
                .current_dir(mc_dir)
                .output()
                .await?;

            if !output.status.success() {
                // L'installeur Forge écrit l'essentiel de son diagnostic sur
                // stdout, pas stderr — sans les deux, le message remonté à
                // l'utilisateur est souvent vide ou inutile.
                let stdout = String::from_utf8_lossy(&output.stdout);
                let stderr = String::from_utf8_lossy(&output.stderr);
                Err(anyhow!("Installeur Forge échoué:\n{}\n{}", stdout, stderr))
            } else {
                Ok(id)
            }
        }
        InstallerProfile::Legacy { id, profile } => {
            tracing::info!("Installation manuelle du profil Forge legacy {}...", id);
            install_legacy(&id, &profile, mc_dir, libraries_dir, client).await.map(|_| id)
        }
    };

    // Clean up installer temp dir regardless of outcome
    let _ = tokio::fs::remove_dir_all(&temp).await;

    result
}

/// Read and parse the installed Forge version JSON, given its resolved id
/// (cf. [`find_installed`] / [`install`]).
pub fn read_version_json(version_id: &str, mc_dir: &Path) -> Result<ForgeVersionJson> {
    let path = mc_dir
        .join("versions")
        .join(version_id)
        .join(format!("{}.json", version_id));
    let content = std::fs::read_to_string(&path)
        .map_err(|_| anyhow!("Forge non installé: {}", version_id))?;
    serde_json::from_str(&content).map_err(|e| anyhow!("Version Forge invalide: {}", e))
}

/// Download a Forge-specific library and return its local path. Handles both
/// the modern `downloads.artifact` shape and the legacy (pré-1.13) shape
/// where a library only has `name` + an optional base maven `url`.
/// None si indisponible — voir les `tracing::warn!` pour la raison précise,
/// remontée par l'appelant comme avertissement de lancement.
pub async fn download_library(lib: &ForgeLibrary, libraries_dir: &Path, client: &reqwest::Client) -> Option<PathBuf> {
    let Some(coord) = MavenCoord::parse(&lib.name) else {
        tracing::warn!("[Forge] coordonnée maven invalide, lib ignorée : {}", lib.name);
        return None;
    };
    let group = &coord.group_path;
    let (art, ver) = (coord.artifact, coord.version);
    let fname = coord.filename();

    let (local_path, url) = if let Some(downloads) = lib.downloads.as_ref() {
        let Some(artifact) = downloads.artifact.as_ref() else {
            tracing::warn!("[Forge] pas d'artifact de téléchargement pour {}", lib.name);
            return None;
        };
        let local_path = match &artifact.path {
            Some(rel_path) => libraries_dir.join(rel_path),
            None => libraries_dir.join(group).join(art).join(ver).join(&fname),
        };
        (local_path, artifact.url.clone())
    } else {
        let local_path = libraries_dir.join(group).join(art).join(ver).join(&fname);
        let base = lib.url.clone().unwrap_or_else(|| "https://libraries.minecraft.net/".to_string());
        (local_path, format!("{}{}/{}/{}/{}", base, group, art, ver, fname))
    };

    if let Some(parent) = local_path.parent() {
        if let Err(e) = tokio::fs::create_dir_all(parent).await {
            tracing::warn!("[Forge] création du dossier pour {} échouée : {}", lib.name, e);
            return None;
        }
    }

    if !local_path.exists() && !url.is_empty() {
        match client.get(&url).send().await {
            Ok(resp) if resp.status().is_success() => match resp.bytes().await {
                Ok(bytes) => {
                    if let Err(e) = tokio::fs::write(&local_path, &bytes).await {
                        tracing::warn!("[Forge] écriture de {} échouée : {}", lib.name, e);
                    }
                }
                Err(e) => tracing::warn!("[Forge] lecture du corps de réponse pour {} échouée : {}", lib.name, e),
            },
            Ok(resp) => tracing::warn!("[Forge] téléchargement de {} échoué : HTTP {}", lib.name, resp.status()),
            Err(e) => tracing::warn!("[Forge] téléchargement de {} échoué : {}", lib.name, e),
        }
    }

    if local_path.exists() { Some(local_path) } else { None }
}

#[cfg(test)]
mod tests {
    use super::builds_for;

    /// Un extrait fidèle du vrai maven : non trié, et le pré-1.13 qui répète
    /// la version MC en suffixe.
    const XML: &str = r#"<metadata><versioning><versions>
      <version>1.21.4-54.1.6</version>
      <version>1.21.4-54.1.18</version>
      <version>1.21.4-54.1.5</version>
      <version>1.20.1-47.2.0</version>
      <version>1.7.10-10.13.4.1614-1.7.10</version>
      <version>1.7.10-10.13.0.1150</version>
    </versions></versioning></metadata>"#;

    /// Le document n'est pas ordonné : c'est le tri numérique qui décide, et
    /// `54.1.18` passe donc devant `54.1.6` (un tri de texte ferait l'inverse).
    #[test]
    fn la_plus_recente_d_abord_quel_que_soit_l_ordre_du_document() {
        assert_eq!(builds_for(XML, "1.21.4"), vec!["54.1.18", "54.1.6", "54.1.5"]);
    }

    /// Le suffixe du pré-1.13 est retiré : le reste du launcher manipule le
    /// build au format des promotions, et c'est lui que l'installeur attend.
    #[test]
    fn le_suffixe_repete_du_pre_1_13_est_retire() {
        assert_eq!(builds_for(XML, "1.7.10"), vec!["10.13.4.1614", "10.13.0.1150"]);
    }

    /// Une version de MC voisine ne doit pas déteindre : `1.21.4-` ne prend
    /// pas les builds de `1.20.1`.
    #[test]
    fn ne_prend_que_les_builds_de_ce_minecraft() {
        assert_eq!(builds_for(XML, "1.20.1"), vec!["47.2.0"]);
        assert!(builds_for(XML, "1.99").is_empty());
        assert!(builds_for("<metadata></metadata>", "1.21.4").is_empty());
    }
}
