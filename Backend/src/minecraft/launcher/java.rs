use anyhow::{anyhow, Result};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::sync::Semaphore;
use tokio::task::JoinSet;

use std::sync::atomic::AtomicU64;
use super::classpath::download_file;
use super::progress::set_progress_monotonic;

pub(super) async fn detect_java_major_version(java: &str) -> Option<u32> {
    let out = tokio::process::Command::new(java)
        .arg("-version")
        .output()
        .await
        .ok()?;
    // java -version écrit sur stderr
    let text = String::from_utf8_lossy(&out.stderr);
    for line in text.lines() {
        if line.contains("version") {
            // Formats : `"21.0.2"` ou `"1.8.0_xxx"`
            if let (Some(s), Some(e)) = (line.find('"'), line.rfind('"')) {
                if s < e {
                    let ver = &line[s + 1..e];
                    let parts: Vec<&str> = ver.split('.').collect();
                    let major: u32 = parts[0].parse().ok()?;
                    return Some(if major == 1 {
                        parts.get(1)?.parse().ok()?
                    } else {
                        major
                    });
                }
            }
        }
    }
    None
}

const MOJANG_JAVA_MANIFEST: &str =
    "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

/// Retourne un exécutable Java prêt à l'emploi pour `required_major`, ainsi
/// que sa version majeure exacte — connue avec certitude pour tous les
/// chemins de résolution sauf JAVA_HOME (composants Mojang et Temurin ciblés
/// par version majeure par construction), ce qui évite à l'appelant de
/// relancer `java -version` juste après pour la redécouvrir.
/// Ordre de priorité : JAVA_HOME → install système → runtime Mojang en cache → téléchargement Mojang.
pub(super) async fn ensure_java(
    component: &str,
    required_major: u32,
    mc_dir: &Path,
    client: &reqwest::Client,
    app: &tauri::AppHandle,
    progress_floor: &AtomicU64,
) -> Result<(String, u32)> {
    // 1. JAVA_HOME — version EXACTE requise, même contrainte que
    // find_system_java (Java 8 : LaunchWrapper incompatible Java 9+ ; Java 9+ :
    // une version plus récente que celle requise par MC fait planter LWJGL en
    // présence d'un agent JVM, voir find_system_java). Un JAVA_HOME moderne
    // pointant sur un JDK 21 utilisé pour lancer du 1.8.9 plantait sinon.
    if let Ok(home) = std::env::var("JAVA_HOME") {
        let exe = PathBuf::from(&home).join("bin").join(java_exe_name());
        if exe.exists() {
            if let Some(v) = detect_java_major_version(&exe.to_string_lossy()).await {
                if v == required_major {
                    return Ok((exe.to_string_lossy().to_string(), v));
                }
            }
        }
    }

    // 2. Installation système
    if let Some(java) = find_system_java(required_major) {
        return Ok((java, required_major));
    }

    // 3. Java 8 : Mojang fige son propre runtime à la build 8u51 depuis des années
    // (vérifié sur son manifeste officiel — seuls les certificats racine sont
    // rafraîchis, jamais le JDK). On préfère un Eclipse Temurin récent (8u4xx+)
    // si on peut le récupérer, avant de retomber sur le runtime Mojang figé.
    if component == "jre-legacy" {
        let temurin_dir = mc_dir.join("runtime").join("jre-legacy-temurin");
        let temurin_exe = temurin_dir.join("bin").join(java_exe_name());
        if temurin_exe.exists() {
            return Ok((temurin_exe.to_string_lossy().to_string(), required_major));
        }
        if cfg!(target_os = "windows") {
            tracing::info!("Java 8 Mojang figé à 8u51 — tentative de téléchargement d'un Temurin récent");
            set_progress_monotonic(app, progress_floor, 10, 100, "Téléchargement Java 8 récent (Eclipse Temurin)...");
            match download_adoptium_jre8(&temurin_dir, client).await {
                Ok(()) if temurin_exe.exists() => return Ok((temurin_exe.to_string_lossy().to_string(), required_major)),
                Ok(()) => tracing::warn!("Téléchargement Temurin terminé mais java introuvable, repli sur Mojang"),
                Err(e) => tracing::warn!("Téléchargement Temurin échoué ({}), repli sur Mojang", e),
            }
        }
    }

    // 4. Runtime Mojang déjà téléchargé
    let runtime_dir = mc_dir.join("runtime").join(component);
    let java_exe = if cfg!(target_os = "macos") {
        runtime_dir.join("jre.bundle").join("Contents").join("Home").join("bin").join("java")
    } else {
        runtime_dir.join("bin").join(java_exe_name())
    };
    if java_exe.exists() {
        return Ok((java_exe.to_string_lossy().to_string(), required_major));
    }

    // 5. Téléchargement depuis Mojang
    tracing::info!("Java {} ({}) introuvable — téléchargement depuis Mojang", required_major, component);
    set_progress_monotonic(app, progress_floor, 12, 100, &format!("Téléchargement Java {} (Mojang)...", required_major));
    download_mojang_runtime(component, &runtime_dir, client, app, progress_floor).await?;

    if java_exe.exists() {
        Ok((java_exe.to_string_lossy().to_string(), required_major))
    } else {
        Err(anyhow!("Runtime Java installé mais introuvable à {}", java_exe.display()))
    }
}

async fn download_mojang_runtime(
    component: &str,
    dest: &Path,
    client: &reqwest::Client,
    app: &tauri::AppHandle,
    progress_floor: &AtomicU64,
) -> Result<()> {
    let platform = mojang_platform_key();

    let all: serde_json::Value = client.get(MOJANG_JAVA_MANIFEST).send().await?.json().await?;

    let manifest_url = all
        .get(platform).and_then(|p| p.get(component))
        .and_then(|c| c.get(0)).and_then(|e| e.get("manifest"))
        .and_then(|m| m.get("url")).and_then(|u| u.as_str())
        .ok_or_else(|| anyhow!("Runtime Mojang '{}' indisponible pour '{}'", component, platform))?
        .to_string();

    let file_manifest: serde_json::Value = client.get(&manifest_url).send().await?.json().await?;
    let files = file_manifest["files"]
        .as_object()
        .ok_or_else(|| anyhow!("Manifest Java invalide"))?;

    // Crée d'abord tous les répertoires (pas de concurrence nécessaire)
    for (rel_path, info) in files.iter() {
        if info["type"].as_str() == Some("directory") {
            tokio::fs::create_dir_all(dest.join(rel_path)).await?;
        }
    }

    let total = files.len() as u64;
    let sem = Arc::new(Semaphore::new(24));
    let mut tasks: JoinSet<Result<()>> = JoinSet::new();

    for (rel_path, info) in files.iter() {
        match info["type"].as_str().unwrap_or("") {
            "file" => {
                let url = info["downloads"]["raw"]["url"].as_str().unwrap_or("").to_string();
                let executable = info["executable"].as_bool().unwrap_or(false);
                let file_dest = dest.join(rel_path);
                let sem = sem.clone();
                let client = client.clone();
                tasks.spawn(async move {
                    if !file_dest.exists() {
                        let _permit = sem.acquire().await.unwrap();
                        if let Some(p) = file_dest.parent() {
                            tokio::fs::create_dir_all(p).await?;
                        }
                        download_file(&client, &url, &file_dest).await?;
                    }
                    #[cfg(unix)]
                    if executable {
                        use std::os::unix::fs::PermissionsExt;
                        tokio::fs::set_permissions(&file_dest, std::fs::Permissions::from_mode(0o755)).await?;
                    }
                    let _ = executable;
                    Ok::<(), anyhow::Error>(())
                });
            }
            #[cfg(unix)]
            "link" => {
                let target = info["target"].as_str().unwrap_or("").to_string();
                let link = dest.join(rel_path);
                if !link.exists() {
                    if let Some(p) = link.parent() { tokio::fs::create_dir_all(p).await?; }
                    tokio::fs::symlink(&target, &link).await.ok();
                }
            }
            _ => {}
        }
    }

    let mut done = 0u64;
    while let Some(r) = tasks.join_next().await {
        r??;
        done += 1;
        if done.is_multiple_of(100) || done == total {
            set_progress_monotonic(app, progress_floor, 12 + done * 8 / total.max(1), 100,
                &format!("Java runtime {}/{}", done, total));
        }
    }
    Ok(())
}

/// Télécharge un JRE 8 récent (Eclipse Temurin, build "latest" — 8u4xx+ au
/// lieu du 8u51 figé par Mojang) via l'API publique Adoptium et l'extrait
/// dans `dest` (structure finale : `dest/bin/java.exe`, comme Mojang).
/// Windows uniquement pour l'instant — Adoptium sert un .zip sur Windows
/// mais un .tar.gz sur macOS/Linux, et seul le crate `zip` est disponible ici.
async fn download_adoptium_jre8(dest: &Path, client: &reqwest::Client) -> Result<()> {
    let arch = if cfg!(target_arch = "aarch64") { "aarch64" } else { "x64" };
    let url = format!(
        "https://api.adoptium.net/v3/binary/latest/8/ga/windows/{}/jre/hotspot/normal/eclipse?project=jdk",
        arch
    );

    let resp = client.get(&url).send().await?;
    if !resp.status().is_success() {
        return Err(anyhow!("Téléchargement Adoptium échoué: {}", resp.status()));
    }
    let bytes = resp.bytes().await?;

    tokio::fs::create_dir_all(dest).await?;
    let temp_zip = dest.with_extension("download.zip");
    tokio::fs::write(&temp_zip, &bytes).await?;

    let extract_result = extract_zip_flatten_root(&temp_zip, dest);
    let _ = tokio::fs::remove_file(&temp_zip).await;
    extract_result
}

/// Extrait un zip Adoptium en retirant son unique dossier racine (ex :
/// "jdk8u492-b09-jre/") pour que `dest` contienne directement `bin/`, `lib/`...
fn extract_zip_flatten_root(zip_path: &Path, dest: &Path) -> Result<()> {
    let file = std::fs::File::open(zip_path)?;
    let mut archive = zip::ZipArchive::new(file)?;

    for i in 0..archive.len() {
        let mut entry = archive.by_index(i)?;
        let entry_path = entry.mangled_name();
        let relative: PathBuf = entry_path.components().skip(1).collect();
        if relative.as_os_str().is_empty() {
            continue;
        }
        let out_path = dest.join(&relative);

        if entry.is_dir() {
            std::fs::create_dir_all(&out_path)?;
        } else {
            if let Some(parent) = out_path.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let mut out_file = std::fs::File::create(&out_path)?;
            std::io::copy(&mut entry, &mut out_file)?;
        }
    }
    Ok(())
}

fn mojang_platform_key() -> &'static str {
    if cfg!(target_os = "windows") {
        if cfg!(target_arch = "aarch64") { "windows-arm64" }
        else if cfg!(target_pointer_width = "64") { "windows-x64" }
        else { "windows-x86" }
    } else if cfg!(target_os = "macos") {
        if cfg!(target_arch = "aarch64") { "mac-os-arm64" } else { "mac-os" }
    } else {
        if cfg!(target_pointer_width = "64") { "linux" } else { "linux-i386" }
    }
}

/// Cherche un JDK système compatible avec `required_major`.
/// Java 8 : version exacte requise (LaunchWrapper incompatible Java 9+).
/// Java 9+ : version minimale (n'importe quelle version >= required_major convient).
fn find_system_java(required_major: u32) -> Option<String> {
    let roots: &[&str] = if cfg!(target_os = "windows") {
        &[
            r"C:\Program Files\Java",
            r"C:\Program Files\Eclipse Adoptium",
            r"C:\Program Files\Microsoft",
            r"C:\Program Files\BellSoft",
            r"C:\Program Files\Amazon Corretto",
            r"C:\Program Files\Semeru Runtime",
        ]
    } else if cfg!(target_os = "macos") {
        &["/Library/Java/JavaVirtualMachines"]
    } else {
        &["/usr/lib/jvm", "/usr/local/lib/jvm", "/opt/java"]
    };

    // Java 8 : version exacte (LaunchWrapper incompatible Java 9+).
    // Java 9+ : version exacte uniquement — versions plus récentes (ex : Java 24 avec MC
    // qui requiert 21) changent l'ordre d'init des classes et font crasher LWJGL 3.3.3
    // nativement en présence d'un agent JVM. ensure_java() télécharge le runtime Mojang sinon.
    let max_major = required_major;

    let mut best: Option<(u32, String)> = None;
    for root in roots {
        let Ok(entries) = std::fs::read_dir(root) else { continue };
        for entry in entries.flatten() {
            let dir_name = entry.file_name().to_string_lossy().to_lowercase();
            let Some(major) = java_major_from_dir_name(&dir_name) else { continue };
            if major < required_major || major > max_major { continue; }
            let exe = if cfg!(target_os = "macos") {
                entry.path().join("Contents").join("Home").join("bin").join("java")
            } else {
                entry.path().join("bin").join(java_exe_name())
            };
            if exe.exists() && (best.is_none() || major < best.as_ref().unwrap().0) {
                best = Some((major, exe.to_string_lossy().to_string()));
            }
        }
    }
    best.map(|(_, p)| p)
}

fn java_exe_name() -> &'static str {
    if cfg!(target_os = "windows") { "java.exe" } else { "java" }
}

/// Extrait la version majeure depuis un nom de répertoire JDK.
/// Reconnaît : "jdk-21", "jre-21", "jdk-21.0.3+9", "java-21-openjdk-amd64", "temurin-21", etc.
/// Et la forme legacy "1.X" des paquets Debian/Ubuntu/RHEL — "java-1.8.0-openjdk-amd64"
/// donnerait "1" avec un simple premier segment, jamais la vraie version majeure (8) :
/// sans ce cas, ces JDK système ne matchaient jamais `required_major` et étaient
/// silencieusement ignorés (retéléchargement inutile du runtime Mojang).
fn java_major_from_dir_name(name: &str) -> Option<u32> {
    let stripped = name
        .strip_prefix("jdk-")
        .or_else(|| name.strip_prefix("jre-"))
        .or_else(|| name.strip_prefix("java-"))
        .or_else(|| name.strip_prefix("temurin-"))
        .or_else(|| name.strip_prefix("corretto-"))
        .or_else(|| name.strip_prefix("semeru-"))?;
    let mut segments = stripped.split(['.', '+', '-', '_']);
    let first: u32 = segments.next()?.parse().ok()?;
    if first == 1 {
        segments.next()?.parse().ok()
    } else {
        Some(first)
    }
}
