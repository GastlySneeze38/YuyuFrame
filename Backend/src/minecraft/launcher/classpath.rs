use anyhow::{anyhow, Result};
use std::path::{Path, PathBuf};
use tokio::io::AsyncWriteExt;

use crate::minecraft::maven::MavenCoord;
use crate::minecraft::versions::{Artifact, Library};

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
    let os_name = if cfg!(target_os = "windows") { "windows" } else if cfg!(target_os = "macos") { "osx" } else { "linux" };
    let Some(rules) = &lib.rules else { return true };
    let mut allowed = true;
    for rule in rules {
        let action = rule.get("action").and_then(|a| a.as_str()).unwrap_or("allow");
        if let Some(os) = rule.get("os") {
            if let Some(name) = os.get("name").and_then(|n| n.as_str()) {
                if name == os_name { allowed = action == "allow"; } else if action == "allow" { allowed = false; }
            }
        } else {
            allowed = action == "allow";
        }
    }
    allowed
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
            if !out_path.exists() {
                let mut out = std::fs::File::create(&out_path)?;
                std::io::copy(&mut entry, &mut out)?;
            }
        }
        Ok::<(), anyhow::Error>(())
    })
    .await
    .map_err(|e| anyhow!("Tâche extraction natives : {}", e))?
}

pub(super) async fn download_file(client: &reqwest::Client, url: &str, path: &Path) -> Result<()> {
    let resp = client.get(url).send().await?;
    if !resp.status().is_success() {
        return Err(anyhow!("Download failed {}: {}", url, resp.status()));
    }
    let bytes = resp.bytes().await?;
    let mut file = tokio::fs::File::create(path).await?;
    file.write_all(&bytes).await?;
    Ok(())
}
