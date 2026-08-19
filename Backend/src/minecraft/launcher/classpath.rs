use anyhow::{anyhow, Result};
use std::path::{Path, PathBuf};
use std::time::Duration;
use tokio::io::AsyncWriteExt;

use crate::minecraft::maven::MavenCoord;
use crate::minecraft::versions::{Artifact, Library};
use super::mojang_rules::rules_allow;

/// Déduplique par `group/artifact` (voir `artifact_key`), en conservant la
/// PREMIÈRE occurrence de chaque clé — PREMIER GAGNANT, l'ordre d'appel est
/// CONTRACTUEL (R-3, audit pipeline). L'appelant (orchestrator.rs) place les
/// libs du loader (Fabric/Forge/...) avant les libs vanilla précisément pour
/// que cet ordre garantisse que les premières masquent les secondes — c'est
/// ce qui permet à Forge/Fabric d'imposer leurs propres versions de libs
/// (Guava, Gson...) plutôt que celles, potentiellement incompatibles avec
/// leurs mods, du manifeste Mojang. Changer cette fonction pour garder la
/// DERNIÈRE occurrence casserait ça silencieusement — voir le test
/// `dedup_classpath_keeps_first_occurrence` ci-dessous, qui verrouille ce
/// comportement.
pub(super) fn dedup_classpath(entries: Vec<String>) -> Vec<String> {
    let mut seen = std::collections::HashSet::new();
    let mut result = Vec::with_capacity(entries.len());
    for entry in entries {
        let key = artifact_key(&entry);
        if seen.insert(key) {
            result.push(entry);
        }
    }
    result
}

fn artifact_key(path: &str) -> String {
    let normalized = path.replace('\\', "/");
    let marker = "/libraries/";
    if let Some(idx) = normalized.rfind(marker) {
        let rel = &normalized[idx + marker.len()..];
        let parts: Vec<&str> = rel.split('/').collect();
        if parts.len() >= 3 {
            let group_artifact = parts[..parts.len() - 2].join("/");
            let filename = parts.last().unwrap_or(&"");
            // Les JARs natifs ("-natives-") ont une clé unique par fichier :
            // on ne les déduplique pas entre eux (x86_64 ≠ arm64),
            // et ils ne doivent pas effacer le JAR principal du même artifact.
            if filename.contains("-natives-") {
                return format!("{}/{}", group_artifact, filename);
            }
            return group_artifact;
        }
    }
    normalized
}

pub(super) fn should_download_library(lib: &Library) -> bool {
    match &lib.rules {
        Some(rules) => rules_allow(rules),
        None => true,
    }
}

pub(super) fn artifact_path(base: &Path, artifact: &Artifact, name: &str) -> PathBuf {
    if let Some(ref p) = artifact.path { return base.join(p); }
    library_jar_path(base, name)
}

fn library_jar_path(base: &Path, name: &str) -> PathBuf {
    match MavenCoord::parse(name) {
        Some(coord) => base.join(coord.relative_path()),
        None => base.join(name),
    }
}

pub(super) async fn extract_natives(jar_path: &Path, natives_dir: &Path) -> Result<()> {
    let jar_bytes = tokio::fs::read(jar_path).await?;
    let natives_dir = natives_dir.to_path_buf();
    // La décompression zip + les écritures fichier sont synchrones (crate
    // `zip`, `std::fs`) — passées en spawn_blocking pour ne pas geler un
    // thread worker tokio pendant que ça tourne (natives_to_extract est en
    // plus maintenant traité en parallèle par l'appelant, voir orchestrator.rs).
    tokio::task::spawn_blocking(move || {
        let cursor = std::io::Cursor::new(jar_bytes);
        let mut archive = zip::ZipArchive::new(cursor)?;
        for i in 0..archive.len() {
            let mut entry = archive.by_index(i)?;
            let name = entry.name().to_string();
            if name.starts_with("META-INF") || name.ends_with('/') { continue; }
            let lower = name.to_ascii_lowercase();
            let is_native = lower.ends_with(".dll") || lower.ends_with(".so") || lower.ends_with(".dylib") || lower.ends_with(".jnilib");
            if !is_native { continue; }
            let file_name = std::path::Path::new(&name).file_name().unwrap_or_default().to_string_lossy().to_string();
            let out_path = natives_dir.join(&file_name);
            // L-5 (audit launcher) : comparer la taille décompressée attendue
            // avant de sauter l'extraction — sans ça, une DLL tronquée (coupure
            // réseau pendant le téléchargement du jar, voir L-1) ou héritée
            // d'une autre version de LWJGL reste en place indéfiniment
            // (UnsatisfiedLinkError insoluble sans suppression manuelle).
            let up_to_date = out_path.exists()
                && std::fs::metadata(&out_path).map(|m| m.len()).unwrap_or(0) == entry.size();
            if !up_to_date {
                let mut out = std::fs::File::create(&out_path)?;
                std::io::copy(&mut entry, &mut out)?;
            }
        }
        Ok::<(), anyhow::Error>(())
    })
    .await
    .map_err(|e| anyhow!("Tâche extraction natives : {}", e))?
}

/// `true` si `path` existe déjà avec le contenu attendu : présence pure sans
/// `expected_size` (aucune taille connue à ce point d'appel), ou présence +
/// taille exacte sinon — un fichier tronqué par une coupure réseau existe
/// mais fait la mauvaise taille. Moins cher qu'un SHA1 complet à chaque
/// lancement, et suffisant pour attraper une troncature (L-1, audit launcher).
pub(super) async fn file_matches(path: &Path, expected_size: Option<u64>) -> bool {
    match expected_size {
        Some(size) => tokio::fs::metadata(path).await.map(|m| m.len() == size).unwrap_or(false),
        None => tokio::fs::try_exists(path).await.unwrap_or(false),
    }
}

/// Téléchargement simple, sans vérification d'intégrité — pour les cas où
/// aucun SHA1 n'est disponible côté appelant (ex : index d'assets, runtime
/// Java Mojang). Bénéficie quand même de l'écriture atomique et du retry de
/// [`download_verified`].
pub(super) async fn download_file(client: &reqwest::Client, url: &str, path: &Path) -> Result<()> {
    download_verified(client, url, path, None).await
}

enum DownloadFailure {
    /// 4xx : la ressource n'existe pas ou l'accès est refusé — retenter ne
    /// changera rien, on abandonne immédiatement (R-2, audit pipeline).
    ClientError(reqwest::StatusCode),
    /// Erreur réseau ou 5xx — transitoire, vaut la peine d'être retenté.
    Retryable(anyhow::Error),
}

async fn try_download_once(
    client: &reqwest::Client,
    url: &str,
    tmp: &Path,
    expected_sha1: Option<&str>,
) -> Result<(), DownloadFailure> {
    let resp = client.get(url).send().await.map_err(|e| DownloadFailure::Retryable(e.into()))?;
    let status = resp.status();
    if status.is_client_error() {
        return Err(DownloadFailure::ClientError(status));
    }
    if !status.is_success() {
        return Err(DownloadFailure::Retryable(anyhow!("HTTP {}", status)));
    }
    let bytes = resp.bytes().await.map_err(|e| DownloadFailure::Retryable(e.into()))?;

    if let Some(expected) = expected_sha1 {
        use sha1::{Digest, Sha1};
        let mut hasher = Sha1::new();
        hasher.update(&bytes);
        let actual = format!("{:x}", hasher.finalize());
        if !actual.eq_ignore_ascii_case(expected) {
            return Err(DownloadFailure::Retryable(anyhow!(
                "hash SHA1 invalide pour {} (attendu {}, obtenu {})", url, expected, actual
            )));
        }
    }

    if let Some(parent) = tmp.parent() {
        tokio::fs::create_dir_all(parent).await.map_err(|e| DownloadFailure::Retryable(e.into()))?;
    }
    let mut file = tokio::fs::File::create(tmp).await.map_err(|e| DownloadFailure::Retryable(e.into()))?;
    file.write_all(&bytes).await.map_err(|e| DownloadFailure::Retryable(e.into()))?;
    Ok(())
}

/// Téléchargement atomique et (optionnellement) vérifié — L-1 et R-2 de
/// l'audit pipeline, corrigés ensemble puisqu'ils touchent le même chemin de
/// code.
///
/// - Écrit d'abord dans `<path>.part` puis `rename` vers `path` (atomique sur
///   le même volume) : une coupure réseau, l'app tuée ou un disque plein
///   pendant l'écriture ne laisse jamais de fichier tronqué au chemin final —
///   avant ce correctif, un tel fichier PASSAIT les tests `exists()` du reste
///   du pipeline et n'était donc plus jamais retéléchargé.
/// - Vérifie le SHA1 contre `expected_sha1` quand fourni (déjà disponible
///   dans le manifeste Mojang pour le client jar, les libs et les assets,
///   mais jusqu'ici jamais lu).
/// - 3 tentatives avec backoff exponentiel sur erreur réseau/5xx — pas sur
///   4xx (la ressource n'existe pas ou l'accès est refusé, insister ne sert
///   à rien).
pub(super) async fn download_verified(
    client: &reqwest::Client,
    url: &str,
    path: &Path,
    expected_sha1: Option<&str>,
) -> Result<()> {
    let mut tmp_name = path.file_name().unwrap_or_default().to_os_string();
    tmp_name.push(".part");
    let tmp = path.with_file_name(tmp_name);

    let mut last_err: Option<anyhow::Error> = None;
    for attempt in 0..3u32 {
        if attempt > 0 {
            tokio::time::sleep(Duration::from_millis(500 * (1u64 << (attempt - 1)))).await;
        }
        match try_download_once(client, url, &tmp, expected_sha1).await {
            Ok(()) => {
                tokio::fs::rename(&tmp, path).await?;
                return Ok(());
            }
            Err(DownloadFailure::ClientError(status)) => {
                let _ = tokio::fs::remove_file(&tmp).await;
                return Err(anyhow!("Téléchargement échoué {} : {}", url, status));
            }
            Err(DownloadFailure::Retryable(e)) => last_err = Some(e),
        }
    }
    let _ = tokio::fs::remove_file(&tmp).await;
    Err(last_err.unwrap_or_else(|| anyhow!("Téléchargement échoué : {}", url)))
}

#[cfg(test)]
mod tests {
    use super::dedup_classpath;

    /// Verrouille le comportement documenté sur `dedup_classpath` (R-3, audit
    /// pipeline) : premier gagnant. Une lib loader (Forge) placée avant la
    /// même lib en version vanilla doit masquer cette dernière.
    #[test]
    fn dedup_classpath_keeps_first_occurrence() {
        let forge_guava = r"C:\mc\libraries\com\google\guava\guava\32.1.2-forge\guava-32.1.2-forge.jar".to_string();
        let vanilla_guava = r"C:\mc\libraries\com\google\guava\guava\32.1.2-jre\guava-32.1.2-jre.jar".to_string();

        let result = dedup_classpath(vec![forge_guava.clone(), vanilla_guava]);

        assert_eq!(result, vec![forge_guava], "la lib loader (première occurrence) doit être conservée, pas la vanilla");
    }

    /// Les JARs natifs ne doivent jamais être dédupliqués entre eux (x86_64 ≠
    /// arm64), ni effacer le JAR principal du même artifact.
    #[test]
    fn dedup_classpath_keeps_all_native_variants() {
        let main_jar = r"C:\mc\libraries\org\lwjgl\lwjgl\3.3.3\lwjgl-3.3.3.jar".to_string();
        let native_win = r"C:\mc\libraries\org\lwjgl\lwjgl\3.3.3\lwjgl-3.3.3-natives-windows.jar".to_string();
        let native_linux = r"C:\mc\libraries\org\lwjgl\lwjgl\3.3.3\lwjgl-3.3.3-natives-linux.jar".to_string();

        let result = dedup_classpath(vec![main_jar.clone(), native_win.clone(), native_linux.clone()]);

        assert_eq!(result, vec![main_jar, native_win, native_linux]);
    }
}
