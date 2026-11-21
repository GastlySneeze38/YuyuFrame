//! Les builds d'Horizon : laquelle installer, et l'installer sans faire
//! confiance à ce que GitHub sert.
//!
//! **Depuis le 2026-10-28, Horizon ne publie plus que des Nightly** : des
//! builds tirés de leur code plusieurs fois par jour, sous le tag `nightly`
//! (le dernier, dont les fichiers sont remplacés à chaque build) et sous
//! `nightly-<date>` (le dernier de chaque jour, gardé quelques jours). La
//! v0.2.97 est la dernière version numérotée ; leur propre launcher la juge
//! trop ancienne pour servir de « Stable ». Le nouveau format de mods et le
//! retour des shaders n'existent que dans les Nightly.
//!
//! Deux canaux, au choix de l'instance (`instances.loader_version`) :
//! - vide : **Stable**, la dernière Nightly de la dernière semaine terminée
//!   (lundi → dimanche). Une version par semaine, qui a déjà tourné quelques
//!   jours chez tout le monde. Leur launcher prend la dernière Nightly de
//!   chaque semaine ; on prend celle de la semaine finie, pour ne pas changer
//!   tous les jours.
//! - `nightly` : la dernière Nightly publiée.
//! - toute autre valeur : un build précis (`nightly-2026-11-21-f037744`, ou
//!   une ancienne version `v0.2.97`), jamais mis à jour.
//!
//! Une Nightly s'installe sous le nom de sa version, tiré du titre de sa
//! publication (« Nightly 2026-11-21 · f037744 » → `nightly-2026-11-21-f037744`).
//!
//! **Signatures** — chaque publication porte `SHA256SUMS` et une signature
//! Ed25519 par ligne, en base64, sur `horizon release <nom>\n` suivi du contenu
//! exact de `SHA256SUMS`. Pour une version numérotée, le nom est son tag
//! (`SHA256SUMS.sig`). Pour une Nightly, c'est sa version complète
//! (`SHA256SUMS.v2.sig`) : une Nightly plus ancienne ne peut donc pas se faire
//! passer pour celle du jour. Règle de leur `crates/horizon-release-sign`,
//! reprise telle quelle et relue sur la Nightly du 2026-11-21 (`f037744`).

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
/// `crates/horizon-release-sign/release-keys.txt` (2026-10-31, inchangée au
/// 2026-11-21). Si elle change chez eux, toute installation sera refusée ici :
/// c'est voulu, une clé ne se remplace pas sans qu'on l'ait vérifiée.
const RELEASE_KEY: &str = "MU4oiIQcCxkZvbwSjjXs7SySCuM6ywRQ3nGaJYZG0gw=";

const SUMS: &str = "SHA256SUMS";
/// Signature d'une version numérotée, faite pour son tag.
const SIGNATURE: &str = "SHA256SUMS.sig";
/// Signature d'une Nightly, faite pour sa version complète.
const SIGNATURE_V2: &str = "SHA256SUMS.v2.sig";
/// L'index du SDK du build, que chaque Nightly porte dans ses sommes signées :
/// gardé dans le dossier de version, il donne le commit complet du build
/// (`native/`, mods natifs).
const SDK_INDEX: &str = "sdk.json";

/// Valeur de `instances.loader_version` qui suit la dernière Nightly.
pub const CHANNEL_NIGHTLY: &str = "nightly";

/// Fichier posé en dernier dans un dossier de version : sans lui, le dossier
/// est une installation interrompue et n'est jamais lancé. Il contient la date
/// de publication, qui départage deux Nightly du même jour.
const COMPLETE: &str = ".installed";

pub(super) const CLIENT: &str = "horizon-client.exe";
pub(super) const PREPARER: &str = "horizon-prepare.exe";

/// Une version installée : son nom et son dossier.
pub(super) struct Installed {
    pub tag: String,
    pub dir: PathBuf,
}

/// Une publication installable.
#[derive(Clone, Debug)]
struct Release {
    /// Le tag GitHub d'où télécharger (`nightly`, `nightly-2026-11-21`, `v0.2.97`).
    tag: String,
    /// Le nom sous lequel elle s'installe (`nightly-2026-11-21-f037744`, `v0.2.97`).
    version: String,
    /// Date de publication (RFC 3339), pour ordonner deux Nightly du même jour.
    published: String,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Channel {
    Stable,
    Nightly,
}

fn versions_dir() -> PathBuf {
    crate::paths::root().join("horizon").join("versions")
}

/// `v0.2.97` → `[0, 2, 97]`, `None` pour tout ce qui n'est pas une version
/// numérotée (les Nightly et les tags du launcher, `launcher@…`).
fn stable_version(tag: &str) -> Option<Vec<u64>> {
    let rest = tag.strip_prefix('v')?;
    let parts: Option<Vec<u64>> = rest.split('.').map(|p| p.parse().ok()).collect();
    parts.filter(|p| !p.is_empty())
}

fn is_date(text: &str) -> bool {
    let b = text.as_bytes();
    b.len() == 10
        && b.iter()
            .enumerate()
            .all(|(i, c)| if i == 4 || i == 7 { *c == b'-' } else { c.is_ascii_digit() })
}

fn is_commit(text: &str) -> bool {
    (7..=40).contains(&text.len()) && text.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// La version d'une Nightly d'après le titre de sa publication :
/// « Nightly 2026-11-21 · f037744 » → `nightly-2026-11-21-f037744`. Un titre
/// qui ne donne pas sa date et son commit est refusé : c'est pour ce nom que
/// la Nightly est signée, rien d'autre ne peut le remplacer.
fn build_version(title: &str) -> Option<String> {
    let (date, commit) = title.strip_prefix("Nightly ")?.split_once(" · ")?;
    (is_date(date) && is_commit(commit)).then(|| format!("nightly-{date}-{commit}"))
}

/// La date d'une version de Nightly (`nightly-2026-11-21-f037744` → `2026-11-21`).
pub fn build_date(version: &str) -> Option<&str> {
    let date = version.strip_prefix("nightly-")?.get(..10)?;
    is_date(date).then_some(date)
}

/// Une version complète de Nightly : `nightly-<date>-<commit>`.
fn is_build(version: &str) -> bool {
    version
        .strip_prefix("nightly-")
        .and_then(|rest| rest.split_at_checked(10))
        .is_some_and(|(date, commit)| is_date(date) && commit.strip_prefix('-').is_some_and(is_commit))
}

/// Clé d'ordre : toute Nightly passe après les versions numérotées, puis par
/// date, puis par heure de publication.
fn order_key(version: &str, published: &str) -> (u8, Vec<u64>, String) {
    if let Some(date) = build_date(version) {
        let parts = date.split('-').filter_map(|p| p.parse().ok()).collect();
        return (1, parts, published.to_string());
    }
    (0, stable_version(version).unwrap_or_default(), String::new())
}

/// Le SHA-256 signé de `asset`, ou pourquoi l'archive doit être refusée.
/// `signed_for` : le tag d'une version numérotée, la version complète d'une Nightly.
pub(super) fn signed_digest(signed_for: &str, asset: &str, sums: &[u8], signature: &str, keys: &[[u8; 32]]) -> Result<String> {
    use ed25519_dalek::{Signature, Verifier, VerifyingKey};

    let mut message = format!("horizon release {signed_for}\n").into_bytes();
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
            "La signature d'Horizon {signed_for} ne correspond pas à sa clé officielle : téléchargement refusé"
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
        .ok_or_else(|| anyhow!("{asset} n'est pas dans la liste signée d'Horizon {signed_for}"))
}

pub(super) fn release_key() -> [u8; 32] {
    STANDARD
        .decode(RELEASE_KEY)
        .ok()
        .and_then(|b| <[u8; 32]>::try_from(b.as_slice()).ok())
        .expect("clé publique d'Horizon invalide")
}

fn asset_url(tag: &str, name: &str) -> String {
    format!("https://github.com/{REPO}/releases/download/{tag}/{name}")
}

/// Le nom d'une version tel qu'on l'écrit au joueur.
pub fn display_name(version: &str) -> String {
    match (build_date(version), version.rsplit('-').next()) {
        (Some(date), Some(commit)) if is_build(version) => format!("Nightly du {date} ({commit})"),
        _ => version.to_string(),
    }
}

/// Les publications installables pour Windows, de la plus récente à la plus
/// ancienne : les versions numérotées et les Nightly signées pour leur
/// version. Une même Nightly publiée sous `nightly` et sous `nightly-<date>`
/// n'apparaît qu'une fois, sous son tag daté, dont les fichiers ne bougent plus.
async fn releases(client: &reqwest::Client) -> Result<Vec<Release>> {
    let url = format!("https://api.github.com/repos/{REPO}/releases?per_page=100");
    let list: serde_json::Value = client
        .get(&url)
        .header("Accept", "application/vnd.github+json")
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    let has = |r: &serde_json::Value, name: &str| {
        r["assets"].as_array().is_some_and(|assets| assets.iter().any(|a| a["name"].as_str() == Some(name)))
    };
    let mut out: Vec<Release> = Vec::new();
    for r in list.as_array().into_iter().flatten() {
        if r["draft"].as_bool().unwrap_or(true) || !has(r, ASSET) || !has(r, SUMS) {
            continue;
        }
        let tag = r["tag_name"].as_str().unwrap_or_default().to_string();
        let title = r["name"].as_str().unwrap_or_default();
        let published = r["published_at"].as_str().unwrap_or_default().to_string();
        let version = if stable_version(&tag).is_some() && !r["prerelease"].as_bool().unwrap_or(true) && has(r, SIGNATURE) {
            tag.clone()
        } else if (tag == CHANNEL_NIGHTLY || tag.starts_with("nightly-")) && has(r, SIGNATURE_V2) {
            match build_version(title) {
                Some(v) => v,
                None => continue,
            }
        } else {
            continue;
        };
        match out.iter_mut().find(|x| x.version == version) {
            Some(existing) if existing.tag == CHANNEL_NIGHTLY => existing.tag = tag,
            Some(_) => {}
            None => out.push(Release { tag, version, published }),
        }
    }
    out.sort_by(|a, b| order_key(&b.version, &b.published).cmp(&order_key(&a.version, &a.published)));
    Ok(out)
}

/// La version publiée d'Horizon qui est le build `commit`, s'il en reste
/// une : ce qu'on propose au joueur dont un mod natif est fait pour ce build.
pub(super) async fn version_for_commit(client: &reqwest::Client, commit: &str) -> Option<String> {
    let list = releases(client).await.ok()?;
    list.into_iter()
        .map(|r| r.version)
        .find(|v| is_build(v) && v.rsplit('-').next().is_some_and(|short| short.len() >= 7 && commit.starts_with(short)))
}

/// Le commit complet du build installé dans `dir` : celui de son `sdk.json`
/// (gardé à l'installation depuis le 2026-11-21), sinon retrouvé parmi les
/// publications `sdk-<commit>` d'après le commit court de son nom, puis gardé.
pub(super) async fn build_commit(client: &reqwest::Client, dir: &Path, version: &str) -> Result<String> {
    if let Some(commit) = std::fs::read(dir.join(SDK_INDEX))
        .ok()
        .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
        .and_then(|v| v["build"]["commit"].as_str().map(str::to_string))
    {
        return Ok(commit);
    }
    let kept = dir.join(".build-commit");
    if let Ok(text) = std::fs::read_to_string(&kept) {
        let text = text.trim();
        if text.len() == 40 {
            return Ok(text.to_string());
        }
    }
    let short = version
        .rsplit('-')
        .next()
        .filter(|s| is_build(version) && s.len() >= 7)
        .ok_or_else(|| anyhow!("{} est trop ancienne pour les mods natifs : passe sur une Nightly", display_name(version)))?;
    for page in 1..=3 {
        let url = format!("https://api.github.com/repos/{REPO}/releases?per_page=100&page={page}");
        let list: serde_json::Value = client
            .get(&url)
            .header("Accept", "application/vnd.github+json")
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        let Some(items) = list.as_array() else { break };
        if items.is_empty() {
            break;
        }
        for r in items {
            if let Some(commit) = r["tag_name"].as_str().and_then(|t| t.strip_prefix("sdk-")) {
                if commit.len() == 40 && commit.starts_with(short) {
                    let _ = std::fs::write(&kept, commit);
                    return Ok(commit.to_string());
                }
            }
        }
    }
    Err(anyhow!("Le SDK de {} n'est pas publié : les mods natifs ne peuvent pas être compilés", display_name(version)))
}

/// Le build qu'un canal désigne dans `list` (triée, la plus récente d'abord).
fn pick(channel: Channel, list: &[Release]) -> Option<&Release> {
    let nightlies = || list.iter().filter(|r| is_build(&r.version));
    match channel {
        Channel::Nightly => nightlies().next(),
        Channel::Stable => {
            // Une vraie version numérotée après la v0.2.97 l'emporte, si le
            // projet en publie de nouveau un jour.
            let last_numbered = stable_version("v0.2.97");
            if let Some(real) = list.iter().find(|r| stable_version(&r.version).is_some_and(|v| Some(v) > last_numbered)) {
                return Some(real);
            }
            let monday = this_monday();
            nightlies()
                .find(|r| build_date(&r.version).is_some_and(|d| d < monday.as_str()))
                // Rien d'avant cette semaine dans la liste : la plus récente.
                .or_else(|| nightlies().next())
        }
    }
}

/// Le lundi de la semaine en cours (UTC), `AAAA-MM-JJ`.
fn this_monday() -> String {
    use chrono::Datelike;
    let today = chrono::Utc::now().date_naive();
    (today - chrono::Duration::days(today.weekday().num_days_from_monday() as i64)).to_string()
}

/// Les versions installées complètes, la plus récente d'abord.
fn installed() -> Vec<(String, String)> {
    let mut list: Vec<(String, String)> = std::fs::read_dir(versions_dir())
        .into_iter()
        .flatten()
        .flatten()
        .filter(|e| e.path().join(COMPLETE).is_file() && e.path().join(CLIENT).is_file())
        .map(|e| {
            let name = e.file_name().to_string_lossy().into_owned();
            let published = std::fs::read_to_string(e.path().join(COMPLETE)).unwrap_or_default().trim().to_string();
            (name, published)
        })
        .filter(|(name, _)| stable_version(name).is_some() || is_build(name))
        .collect();
    list.sort_by(|a, b| order_key(&b.0, &b.1).cmp(&order_key(&a.0, &a.1)));
    list
}

fn installed_at(version: &str) -> Option<Installed> {
    let dir = versions_dir().join(version);
    (dir.join(COMPLETE).is_file() && dir.join(CLIENT).is_file()).then(|| Installed { tag: version.to_string(), dir })
}

/// Sans réseau : la plus récente installée qui convient au canal.
fn newest_installed_for(channel: Channel) -> Option<Installed> {
    let list = installed();
    let found = match channel {
        Channel::Nightly => list.iter().find(|(v, _)| is_build(v)).or_else(|| list.first()),
        Channel::Stable => list.first(),
    };
    found.and_then(|(v, _)| installed_at(v))
}

/// Sans réseau : le dossier de la version que prendrait une instance épinglée
/// sur `pinned`, si elle est déjà installée. Sert à demander au jeu ce qu'il
/// sait charger (`content.rs`), jamais à lancer.
pub(super) fn installed_for(pinned: &str) -> Option<PathBuf> {
    let found = match pinned {
        "" => newest_installed_for(Channel::Stable),
        CHANNEL_NIGHTLY => newest_installed_for(Channel::Nightly),
        exact => installed_at(exact),
    };
    found.map(|i| i.dir)
}

/// Les versions d'Horizon proposables, pour l'onglet Installation.
///
/// « Recommandée » = celle que prend le canal Stable (valeur vide), c'est-à-dire
/// ce que le lancement installerait sans épinglage. `stable` marque les versions
/// numérotées et le build que Stable désigne ; les autres Nightly sont des
/// builds de développement. Hors ligne, seules les versions installées sont listées.
pub async fn list_versions(client: &reqwest::Client) -> Vec<crate::minecraft::loaders::LoaderVersion> {
    let list = releases(client).await.unwrap_or_default();
    let recommended = pick(Channel::Stable, &list)
        .map(|r| r.version.clone())
        .or_else(|| newest_installed_for(Channel::Stable).map(|i| i.tag));
    let mut versions: Vec<(String, String)> = list.iter().map(|r| (r.version.clone(), r.published.clone())).collect();
    for (name, published) in installed() {
        if !versions.iter().any(|(v, _)| *v == name) {
            versions.push((name, published));
        }
    }
    versions.sort_by(|a, b| order_key(&b.0, &b.1).cmp(&order_key(&a.0, &a.1)));
    versions
        .into_iter()
        .map(|(version, _)| crate::minecraft::loaders::LoaderVersion {
            recommended: Some(&version) == recommended.as_ref(),
            stable: stable_version(&version).is_some() || Some(&version) == recommended.as_ref(),
            version,
        })
        .collect()
}

async fn fetch_small(client: &reqwest::Client, url: &str) -> Result<Vec<u8>> {
    let bytes = client.get(url).send().await?.error_for_status()?.bytes().await?;
    if bytes.len() > 64 * 1024 {
        return Err(anyhow!("{url} : fichier trop gros pour une liste de sommes"));
    }
    Ok(bytes.to_vec())
}

/// La version d'Horizon à lancer pour `pinned` (`instances.loader_version`) :
/// - un build précis : celui-là, installé maintenant s'il manque ;
/// - `nightly` ou vide (Stable) : le build que désigne le canal, installé
///   maintenant s'il est nouveau. Sans réseau, ou si l'installation échoue, la
///   plus récente déjà installée qui convient, pour qu'une panne de GitHub
///   n'empêche jamais de jouer.
///
/// `keep` : les versions épinglées par des instances, que le nettoyage qui
/// suit une mise à jour ne supprime jamais.
pub(super) async fn ensure_installed(
    client: &reqwest::Client,
    app: &tauri::AppHandle,
    floor: &ProgressFloor,
    pinned: &str,
    keep: &[String],
) -> Result<Installed> {
    let channel = match pinned {
        "" => Channel::Stable,
        CHANNEL_NIGHTLY => Channel::Nightly,
        exact => return ensure_exact(client, app, floor, exact).await,
    };

    set_progress_monotonic(app, floor, 2, 100, "Recherche de la dernière version d'Horizon...");
    let list = releases(client).await;
    let target = list.as_ref().ok().and_then(|l| pick(channel, l)).cloned();
    let Some(target) = target else {
        if let Err(e) = &list {
            tracing::warn!("[Horizon] liste des versions indisponible : {e}");
        }
        return newest_installed_for(channel).ok_or_else(|| {
            anyhow!("Impossible de joindre GitHub pour installer Horizon, et aucune version n'est installée")
        });
    };
    if let Some(found) = installed_at(&target.version) {
        return Ok(found);
    }

    let result = match install(client, app, floor, &target).await {
        Ok(done) => Ok(done),
        // Le tag `nightly` change de fichiers à chaque build : une liste lue
        // juste avant le remplacement ne correspond plus. On relit une fois.
        Err(first) => match releases(client).await.ok().and_then(|l| pick(channel, &l).cloned()) {
            Some(fresh) => match installed_at(&fresh.version) {
                Some(found) => Ok(found),
                None => install(client, app, floor, &fresh).await,
            },
            None => Err(first),
        },
    };
    match result {
        Ok(done) => {
            remove_stale(&done.tag, keep);
            Ok(done)
        }
        Err(e) => match newest_installed_for(channel) {
            Some(previous) => {
                tracing::warn!("[Horizon] {} non installée, on garde {} : {e}", target.version, previous.tag);
                Ok(previous)
            }
            None => Err(e),
        },
    }
}

/// Un build précis : installé s'il ne l'est pas, sinon refusé s'il n'est plus publié.
async fn ensure_exact(client: &reqwest::Client, app: &tauri::AppHandle, floor: &ProgressFloor, version: &str) -> Result<Installed> {
    if stable_version(version).is_none() && !is_build(version) {
        return Err(anyhow!("« {version} » n'est pas une version d'Horizon"));
    }
    if let Some(found) = installed_at(version) {
        return Ok(found);
    }
    let list = releases(client).await?;
    let release = list.into_iter().find(|r| r.version == version).ok_or_else(|| {
        anyhow!(
            "{} n'est plus publiée par Horizon : choisis une autre version dans l'onglet Installation",
            display_name(version)
        )
    })?;
    install(client, app, floor, &release).await
}

/// Après une mise à jour par canal : supprime les Nightly installées par la
/// mise à jour qui ne servent plus — toutes sauf celle qu'on vient
/// d'installer, la précédente (repli si la nouvelle pose problème) et celles
/// qu'une instance épingle. Les versions numérotées ne sont jamais touchées.
///
/// Le dossier est d'abord renommé : sous Windows c'est impossible tant qu'une
/// partie le fait tourner, et on passe alors son tour plutôt que de laisser un
/// dossier à moitié effacé.
fn remove_stale(current: &str, keep: &[String]) {
    let builds: Vec<String> = installed().into_iter().map(|(v, _)| v).filter(|v| is_build(v)).collect();
    let previous = builds.iter().find(|v| v.as_str() != current).cloned();
    for version in builds {
        if version == current || Some(&version) == previous.as_ref() || keep.contains(&version) {
            continue;
        }
        let dir = versions_dir().join(&version);
        let trash = versions_dir().join(format!(".{version}.removing"));
        if std::fs::rename(&dir, &trash).is_ok() {
            if let Err(e) = std::fs::remove_dir_all(&trash) {
                tracing::warn!("[Horizon] {version} pas entièrement supprimée : {e}");
            }
        }
    }
}

async fn install(client: &reqwest::Client, app: &tauri::AppHandle, floor: &ProgressFloor, release: &Release) -> Result<Installed> {
    let name = display_name(&release.version);
    let (signature_file, signed_for) = if is_build(&release.version) {
        (SIGNATURE_V2, release.version.as_str())
    } else {
        (SIGNATURE, release.tag.as_str())
    };
    let sums = fetch_small(client, &asset_url(&release.tag, SUMS)).await?;
    let signature = fetch_small(client, &asset_url(&release.tag, signature_file)).await?;
    let expected = signed_digest(signed_for, ASSET, &sums, &String::from_utf8_lossy(&signature), &[release_key()])?;

    let root = versions_dir();
    let partial = root.join(format!(".{}.partial", release.version));
    let _ = tokio::fs::remove_dir_all(&partial).await;
    tokio::fs::create_dir_all(&partial).await?;
    let archive = partial.join("download.zip");

    let response = client.get(asset_url(&release.tag, ASSET)).send().await?.error_for_status()?;
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
        if let Some(share) = (done * 17).checked_div(total) {
            set_progress_monotonic(app, floor, 3 + share, 100, &format!("Téléchargement d'Horizon — {name}..."));
        }
    }
    file.flush().await?;
    drop(file);
    let got = format!("{:x}", hash.finalize());
    if !got.eq_ignore_ascii_case(&expected) {
        let _ = tokio::fs::remove_dir_all(&partial).await;
        return Err(anyhow!("L'archive d'Horizon ({name}) ne correspond pas à sa somme signée : installation refusée"));
    }

    set_progress_monotonic(app, floor, 20, 100, &format!("Installation d'Horizon — {name}..."));
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
        return Err(anyhow!("L'archive d'Horizon ({name}) ne contient pas {CLIENT} et {PREPARER}"));
    }
    let target = root.join(&release.version);
    let _ = tokio::fs::remove_dir_all(&target).await;
    tokio::fs::rename(&top, &target).await?;
    // Le commit complet du build, pour les mods natifs ; facultatif (une
    // version numérotée n'en a pas), et vérifié par la même liste signée.
    if let Ok(digest) = signed_digest(signed_for, SDK_INDEX, &sums, &String::from_utf8_lossy(&signature), &[release_key()]) {
        if let Ok(bytes) = fetch_small(client, &asset_url(&release.tag, SDK_INDEX)).await {
            if format!("{:x}", Sha256::digest(&bytes)).eq_ignore_ascii_case(&digest) {
                let _ = tokio::fs::write(target.join(SDK_INDEX), &bytes).await;
            }
        }
    }
    tokio::fs::write(target.join(COMPLETE), &release.published).await?;
    let _ = tokio::fs::remove_dir_all(&partial).await;
    Ok(Installed { tag: release.version.clone(), dir: target })
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

    /// `SHA256SUMS`, `SHA256SUMS.sig` et `SHA256SUMS.v2.sig` réels de la
    /// Nightly « 2026-11-21 · f037744 », relevés le 2026-11-21.
    const SUMS_NIGHTLY: &str = "\
f129d35e9514175620b4b639b4cf11658d9d53b435e483b41c1a8e3369c37cd4  horizon-linux-x86_64.tar.gz
c5d3a5bcdc918b2fc5d7043b728ccc888789f38c5068e1fd0360feb52d5117c1  horizon-windows-x64.zip
0915151d7768665d70bef807fafdf602bc085cd1fe595009b5b15cb4b2479d6b  horizon-windows-arm64.zip
15794d7fb120ebcc27c772075e49b73858682c66bbedb01c1d6ee17e096a6870  horizon-linux-musl-x86_64.tar.gz
8db2d75f9f5dace5060ebce7b48111007877928a4573f394b56f342a933bec36  horizon-macos-arm64.zip
8d9c1e352eb668881dcc478d30c563da68a25939ff819408072c43a6397572de  horizon-macos-x64.zip
fc9c5696b5378a3e771e64232880bd6113eef9b5cec3ee1d85a83601cdb57cb6  horizon-server-linux-x86_64.tar.gz
687d1e015f16e2a031b9c23578a8b9e627056f0b1cf1f5312c0085cbcd88f30a  horizon-source.tar.gz
ad260eb2903c9fd1eed9bc9fddd6a8fea44dc40f4b027d74fb53aa2c6872d70a  sdk.json
";
    const SIG_NIGHTLY_CHANNEL: &str =
        "s9No/tm3cvE9vmfSnz9DXlrBTL3sZW11TM2XQHyKPUH5gv2vfM4DeW/84B2P3VySBuUerDgb4ucxTzdUp+a+AA==\n";
    const SIG_NIGHTLY_V2: &str =
        "SuBAMNxs9XJow2TB7f190YFjFfOT33KagmgVJhLtVXA6AxlNc1n8NiocoCiZFHAlz0dSd5S5j6CPf/qrNstoCw==\n";

    #[test]
    fn une_vraie_version_passe() {
        let digest = signed_digest("v0.2.97", ASSET, SUMS_V0_2_97.as_bytes(), SIG_V0_2_97, &[release_key()]).unwrap();
        assert_eq!(digest, "29d3cef224aecdafea314cf6a3acb5caf8228edef9a3c5d73a0b4fe3b2696fca");
    }

    #[test]
    fn une_vraie_nightly_passe_pour_sa_version() {
        let version = build_version("Nightly 2026-11-21 · f037744").unwrap();
        assert_eq!(version, "nightly-2026-11-21-f037744");
        let digest = signed_digest(&version, ASSET, SUMS_NIGHTLY.as_bytes(), SIG_NIGHTLY_V2, &[release_key()]).unwrap();
        assert_eq!(digest, "c5d3a5bcdc918b2fc5d7043b728ccc888789f38c5068e1fd0360feb52d5117c1");
    }

    #[test]
    fn une_nightly_ne_passe_pas_pour_une_autre() {
        // Signée pour sa version : rejouée sous un autre nom, elle est refusée.
        assert!(signed_digest("nightly-2026-11-22-f037744", ASSET, SUMS_NIGHTLY.as_bytes(), SIG_NIGHTLY_V2, &[release_key()]).is_err());
        // La signature du canal (`horizon release nightly`) ne vaut pas pour une version précise.
        assert!(signed_digest("nightly-2026-11-21-f037744", ASSET, SUMS_NIGHTLY.as_bytes(), SIG_NIGHTLY_CHANNEL, &[release_key()]).is_err());
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
    fn noms_de_versions() {
        assert_eq!(stable_version("v0.2.97"), Some(vec![0, 2, 97]));
        assert_eq!(stable_version("nightly-2026-10-31"), None);
        assert_eq!(stable_version("launcher@0.2.31"), None);
        assert!(stable_version("v0.2.100") > stable_version("v0.2.97"));
        assert!(is_build("nightly-2026-11-21-f037744"));
        assert!(!is_build("nightly-2026-11-21"));
        assert!(!is_build("nightly"));
        assert_eq!(build_version("Nightly 2026-11-21"), None);
        assert_eq!(build_version("Snapshot 2026-11-21 · f037744"), None);
        assert_eq!(display_name("nightly-2026-11-21-f037744"), "Nightly du 2026-11-21 (f037744)");
        assert_eq!(display_name("v0.2.97"), "v0.2.97");
    }

    fn rel(version: &str, published: &str) -> Release {
        Release { tag: version.to_string(), version: version.to_string(), published: published.to_string() }
    }

    #[test]
    fn une_nightly_passe_devant_les_versions_numerotees() {
        assert!(order_key("nightly-2026-11-01-aaaaaaa", "") > order_key("v0.2.97", ""));
        assert!(order_key("nightly-2026-11-21-bbbbbbb", "2026-11-21T18:00:00Z") > order_key("nightly-2026-11-21-aaaaaaa", "2026-11-21T09:00:00Z"));
    }

    #[test]
    fn stable_prend_une_semaine_terminee() {
        let monday = this_monday();
        let list = vec![
            rel(&format!("nightly-{monday}-ccccccc"), "z"),
            rel("nightly-2000-01-02-bbbbbbb", ""),
            rel("nightly-2000-01-01-aaaaaaa", ""),
            rel("v0.2.97", ""),
        ];
        assert_eq!(pick(Channel::Nightly, &list).unwrap().version, format!("nightly-{monday}-ccccccc"));
        assert_eq!(pick(Channel::Stable, &list).unwrap().version, "nightly-2000-01-02-bbbbbbb");
        // Rien d'avant cette semaine : la plus récente plutôt que rien.
        let only_this_week = vec![rel(&format!("nightly-{monday}-ccccccc"), "")];
        assert_eq!(pick(Channel::Stable, &only_this_week).unwrap().version, format!("nightly-{monday}-ccccccc"));
        // Une vraie version numérotée récente l'emporterait.
        let numbered = vec![rel("nightly-2000-01-02-bbbbbbb", ""), rel("v0.3.0", "")];
        assert_eq!(pick(Channel::Stable, &numbered).unwrap().version, "v0.3.0");
    }
}
