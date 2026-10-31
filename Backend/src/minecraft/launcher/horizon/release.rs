//! Les builds d'Horizon : laquelle installer, et l'installer sans faire
//! confiance à ce que GitHub sert.
//!
//! Chaque version stable (`v0.2.97`…) publie, à côté de ses archives,
//! `SHA256SUMS` et `SHA256SUMS.sig` : une signature Ed25519 par ligne, en
//! base64, portant sur `horizon release <tag>\n` suivi du contenu exact de
//! `SHA256SUMS`. Le tag fait partie du message signé, donc une liste signée
//! pour une version ne peut pas passer pour une autre. C'est la règle de leur
//! propre launcher (`crates/horizon-release-sign`), reprise telle quelle : une
//! archive n'est installée que si son SHA-256 figure dans une liste signée par
//! la clé publique ci-dessous.
//!
//! Seules les versions stables sont prises : les builds quotidiens (`nightly`)
//! sont signés pour un autre canal et changent chaque jour.

use anyhow::{anyhow, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use futures::StreamExt;
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use tokio::io::AsyncWriteExt;

use super::super::progress::{set_progress_monotonic, ProgressFloor};

const REPO: &str = "horizon-rust/horizon";

/// L'archive du jeu pour Windows x64, seule plateforme proposée : le reste du
/// launcher (agent, canal du jeu) est déjà Windows seulement, et leurs
/// archives n'ont pas le même nom ailleurs.
const ASSET: &str = "horizon-windows-x64.zip";

/// Clé publique des versions d'Horizon, recopiée de
/// `crates/horizon-release-sign/release-keys.txt` (2026-10-31). Si elle
/// change chez eux, toute installation sera refusée ici : c'est voulu, une
/// clé ne se remplace pas sans qu'on l'ait vérifiée.
const RELEASE_KEY: &str = "MU4oiIQcCxkZvbwSjjXs7SySCuM6ywRQ3nGaJYZG0gw=";

/// Fichier posé en dernier dans un dossier de version : sans lui, le dossier
/// est une installation interrompue et n'est jamais lancé.
const COMPLETE: &str = ".installed";

pub(super) const CLIENT: &str = "horizon-client.exe";
pub(super) const PREPARER: &str = "horizon-prepare.exe";

/// Une version installée : son tag et son dossier.
pub(super) struct Installed {
    pub tag: String,
    pub dir: PathBuf,
}

fn versions_dir() -> PathBuf {
    crate::paths::root().join("horizon").join("versions")
}

/// `v0.2.97` → `[0, 2, 97]`, `None` pour tout ce qui n'est pas une version
/// stable (les tags `nightly…` et ceux du launcher, `launcher@…`).
fn stable_version(tag: &str) -> Option<Vec<u64>> {
    let rest = tag.strip_prefix('v')?;
    let parts: Option<Vec<u64>> = rest.split('.').map(|p| p.parse().ok()).collect();
    parts.filter(|p| !p.is_empty())
}

/// La plus récente des versions installées (complètes).
fn newest_installed() -> Option<Installed> {
    let entries = std::fs::read_dir(versions_dir()).ok()?;
    entries
        .flatten()
        .filter(|e| e.path().join(COMPLETE).is_file() && e.path().join(CLIENT).is_file())
        .filter_map(|e| {
            let tag = e.file_name().to_string_lossy().into_owned();
            stable_version(&tag).map(|v| (v, Installed { tag, dir: e.path() }))
        })
        .max_by(|a, b| a.0.cmp(&b.0))
        .map(|(_, installed)| installed)
}

/// Le SHA-256 signé de `asset` dans la version `tag`, ou pourquoi l'archive
/// doit être refusée.
fn signed_digest(tag: &str, asset: &str, sums: &[u8], signature: &str, keys: &[[u8; 32]]) -> Result<String> {
    use ed25519_dalek::{Signature, Verifier, VerifyingKey};

    let mut message = format!("horizon release {tag}\n").into_bytes();
    message.extend_from_slice(sums);
    let signed = signature
        .lines()
        .filter_map(|line| STANDARD.decode(line.trim()).ok())
        .filter_map(|bytes| Signature::from_slice(&bytes).ok())
        .any(|sig| {
            keys.iter().any(|key| {
                VerifyingKey::from_bytes(key)
                    .map(|k| k.verify(&message, &sig).is_ok())
                    .unwrap_or(false)
            })
        });
    if !signed {
        return Err(anyhow!(
            "La signature d'Horizon {tag} ne correspond pas à sa clé officielle : téléchargement refusé"
        ));
    }
    let text = std::str::from_utf8(sums).map_err(|_| anyhow!("SHA256SUMS n'est pas du texte"))?;
    text.lines()
        .filter_map(|line| {
            let (hex, name) = line.split_once(' ')?;
            let name = name.trim_start_matches([' ', '*']);
            (name == asset && hex.len() == 64 && hex.bytes().all(|b| b.is_ascii_hexdigit()))
                .then(|| hex.to_ascii_lowercase())
        })
        .next()
        .ok_or_else(|| anyhow!("{asset} n'est pas dans la liste signée d'Horizon {tag}"))
}

fn release_key() -> [u8; 32] {
    STANDARD
        .decode(RELEASE_KEY)
        .ok()
        .and_then(|b| <[u8; 32]>::try_from(b.as_slice()).ok())
        .expect("clé publique d'Horizon invalide")
}

fn asset_url(tag: &str, name: &str) -> String {
    format!("https://github.com/{REPO}/releases/download/{tag}/{name}")
}

/// Le tag de la version stable la plus récente qui publie l'archive Windows.
async fn latest_tag(client: &reqwest::Client) -> Result<String> {
    let url = format!("https://api.github.com/repos/{REPO}/releases?per_page=40");
    let list: serde_json::Value = client
        .get(&url)
        .header("Accept", "application/vnd.github+json")
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    list.as_array()
        .into_iter()
        .flatten()
        .filter(|r| !r["draft"].as_bool().unwrap_or(true) && !r["prerelease"].as_bool().unwrap_or(true))
        .filter(|r| {
            r["assets"]
                .as_array()
                .is_some_and(|assets| assets.iter().any(|a| a["name"].as_str() == Some(ASSET)))
        })
        .filter_map(|r| r["tag_name"].as_str().map(str::to_string))
        .filter_map(|tag| stable_version(&tag).map(|v| (v, tag)))
        .max_by(|a, b| a.0.cmp(&b.0))
        .map(|(_, tag)| tag)
        .ok_or_else(|| anyhow!("Aucune version stable d'Horizon pour Windows n'est publiée"))
}

async fn fetch_small(client: &reqwest::Client, url: &str) -> Result<Vec<u8>> {
    let bytes = client.get(url).send().await?.error_for_status()?.bytes().await?;
    if bytes.len() > 64 * 1024 {
        return Err(anyhow!("{url} : fichier trop gros pour une liste de sommes"));
    }
    Ok(bytes.to_vec())
}

/// La version d'Horizon à lancer : la plus récente déjà installée, sinon la
/// dernière version stable, installée maintenant.
///
/// Pas de mise à jour automatique : une version installée a ses ressources
/// préparées, et une nouvelle version peut demander de tout refaire. Le
/// projet publie plusieurs versions par jour ; rester sur une version qui
/// marche est le choix sûr.
pub(super) async fn ensure_installed(
    client: &reqwest::Client,
    app: &tauri::AppHandle,
    floor: &ProgressFloor,
) -> Result<Installed> {
    if let Some(installed) = newest_installed() {
        return Ok(installed);
    }
    set_progress_monotonic(app, floor, 2, 100, "Recherche de la dernière version d'Horizon...");
    let tag = latest_tag(client).await?;
    install(client, app, floor, &tag).await
}

async fn install(client: &reqwest::Client, app: &tauri::AppHandle, floor: &ProgressFloor, tag: &str) -> Result<Installed> {
    let sums = fetch_small(client, &asset_url(tag, "SHA256SUMS")).await?;
    let signature = fetch_small(client, &asset_url(tag, "SHA256SUMS.sig")).await?;
    let expected = signed_digest(tag, ASSET, &sums, &String::from_utf8_lossy(&signature), &[release_key()])?;

    let root = versions_dir();
    let partial = root.join(format!(".{tag}.partial"));
    let _ = tokio::fs::remove_dir_all(&partial).await;
    tokio::fs::create_dir_all(&partial).await?;
    let archive = partial.join("download.zip");

    let response = client.get(asset_url(tag, ASSET)).send().await?.error_for_status()?;
    let total = response.content_length().unwrap_or(0);
    let mut file = tokio::fs::File::create(&archive).await?;
    let mut hash = Sha256::new();
    let mut done = 0u64;
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk?;
        hash.update(&chunk);
        file.write_all(&chunk).await?;
        done += chunk.len() as u64;
        if total > 0 {
            set_progress_monotonic(app, floor, 3 + done * 17 / total, 100, &format!("Téléchargement d'Horizon {tag}..."));
        }
    }
    file.flush().await?;
    drop(file);
    let got = format!("{:x}", hash.finalize());
    if !got.eq_ignore_ascii_case(&expected) {
        let _ = tokio::fs::remove_dir_all(&partial).await;
        return Err(anyhow!("L'archive d'Horizon {tag} ne correspond pas à sa somme signée : installation refusée"));
    }

    set_progress_monotonic(app, floor, 20, 100, &format!("Installation d'Horizon {tag}..."));
    let unpacked = partial.join("unpacked");
    {
        let archive = archive.clone();
        let unpacked = unpacked.clone();
        tokio::task::spawn_blocking(move || unpack(&archive, &unpacked))
            .await
            .map_err(|e| anyhow!("Extraction d'Horizon : {e}"))??;
    }

    // L'archive contient un seul dossier (`horizon-windows-x64/…`) : le
    // dossier de version, c'est son contenu.
    let mut entries = std::fs::read_dir(&unpacked)?.flatten().collect::<Vec<_>>();
    let top = match entries.as_slice() {
        [only] if only.path().is_dir() => entries.remove(0).path(),
        _ => unpacked.clone(),
    };
    if !top.join(CLIENT).is_file() || !top.join(PREPARER).is_file() {
        let _ = tokio::fs::remove_dir_all(&partial).await;
        return Err(anyhow!("L'archive d'Horizon {tag} ne contient pas {CLIENT} et {PREPARER}"));
    }
    let target = root.join(tag);
    let _ = tokio::fs::remove_dir_all(&target).await;
    tokio::fs::rename(&top, &target).await?;
    tokio::fs::write(target.join(COMPLETE), tag).await?;
    let _ = tokio::fs::remove_dir_all(&partial).await;
    Ok(Installed { tag: tag.to_string(), dir: target })
}

/// Extrait l'archive dans `to`. Les noms d'entrées passent par
/// `enclosed_name` : une entrée qui remonterait (`../`) ou viserait un
/// lecteur est refusée, comme partout où le launcher ouvre une archive.
fn unpack(archive: &Path, to: &Path) -> Result<()> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(archive)?)?;
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i)?;
        let Some(relative) = entry.enclosed_name() else {
            return Err(anyhow!("Entrée d'archive refusée : {}", entry.name()));
        };
        let path = to.join(relative);
        if entry.is_dir() {
            std::fs::create_dir_all(&path)?;
            continue;
        }
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut out = std::fs::File::create(&path)?;
        std::io::copy(&mut entry, &mut out)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `SHA256SUMS` et `SHA256SUMS.sig` réels de la v0.2.97, relevés le
    /// 2026-10-31 : la vérification doit accepter ce qu'Horizon publie.
    const SUMS_V0_2_97: &str = "\
16e62f7a9ac95d6be788e92cd5fb50a569926d2378531a4e0ea3a07337b79c11  horizon-linux-x86_64.tar.gz
29d3cef224aecdafea314cf6a3acb5caf8228edef9a3c5d73a0b4fe3b2696fca  horizon-windows-x64.zip
587fb8a653324e6f1b3bf59f1f2227ad99201ccaaf3f31024db25ccd4f37c63e  horizon-macos-arm64.zip
57422db46533e077277ef1505dcc03805d52d15f3a213200569118c36c5f7cc9  horizon-server-linux-x86_64.tar.gz
";
    const SIG_V0_2_97: &str =
        "XDSckSAve+YAwmmuQ1wU8Wpr8rpN3U17OknKPGg3IK+mOUbFquPtS5pYd9vPA9GuxdxXmRkcD4/vAI2xrFr8Bg==\n";

    #[test]
    fn une_vraie_version_passe() {
        let digest = signed_digest("v0.2.97", ASSET, SUMS_V0_2_97.as_bytes(), SIG_V0_2_97, &[release_key()]).unwrap();
        assert_eq!(digest, "29d3cef224aecdafea314cf6a3acb5caf8228edef9a3c5d73a0b4fe3b2696fca");
    }

    #[test]
    fn une_liste_modifiee_est_refusee() {
        let tampered = SUMS_V0_2_97.replace("29d3cef2", "00000000");
        assert!(signed_digest("v0.2.97", ASSET, tampered.as_bytes(), SIG_V0_2_97, &[release_key()]).is_err());
    }

    #[test]
    fn une_liste_signee_ne_vaut_pas_pour_un_autre_tag() {
        assert!(signed_digest("v0.2.98", ASSET, SUMS_V0_2_97.as_bytes(), SIG_V0_2_97, &[release_key()]).is_err());
    }

    #[test]
    fn seules_les_versions_stables_comptent() {
        assert_eq!(stable_version("v0.2.97"), Some(vec![0, 2, 97]));
        assert_eq!(stable_version("nightly-2026-10-31"), None);
        assert_eq!(stable_version("launcher@0.2.31"), None);
        assert!(stable_version("v0.2.100") > stable_version("v0.2.97"));
    }
}
