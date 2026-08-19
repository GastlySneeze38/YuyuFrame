use std::path::{Path, PathBuf};

use sha1::{Digest, Sha1};

/// Dossier où vivent les archives AppCDS de cette instance — un fichier par
/// combinaison (version, loader, classpath, mods) rencontrée. L'ancien
/// fichier est nettoyé dès qu'une nouvelle combinaison apparaît (voir
/// `appcds_jvm_args`), pas d'accumulation illimitée de `.jsa`.
fn appcds_dir(game_dir: &Path) -> PathBuf {
    game_dir.join(".appcds")
}

/// Empreinte de la liste des mods (noms + tailles, triés) — capture les
/// ajouts/suppressions de mods même quand le classpath JVM lui-même (qui ne
/// contient jamais les jars de mods, chargés dynamiquement par le loader à
/// l'exécution) ne change pas. Best-effort : un dossier `mods/` absent ou
/// illisible donne juste une empreinte vide, jamais une erreur.
async fn mods_fingerprint(game_dir: &Path) -> String {
    let mods_dir = game_dir.join("mods");
    let mut entries: Vec<(String, u64)> = Vec::new();
    if let Ok(mut dir) = tokio::fs::read_dir(&mods_dir).await {
        while let Ok(Some(entry)) = dir.next_entry().await {
            let name = entry.file_name().to_string_lossy().to_string();
            let size = entry.metadata().await.map(|m| m.len()).unwrap_or(0);
            entries.push((name, size));
        }
    }
    entries.sort();
    entries.iter().map(|(n, s)| format!("{n}:{s}")).collect::<Vec<_>>().join(",")
}

fn hash_key(parts: &[&str]) -> String {
    let mut hasher = Sha1::new();
    for p in parts {
        hasher.update(p.as_bytes());
        hasher.update(b"\0");
    }
    format!("{:x}", hasher.finalize())
}

/// P1-7 (audit launcher) : génère l'archive AppCDS au premier lancement d'un
/// profil et la réutilise aux lancements suivants — le gain de temps de
/// démarrage le plus rentable et le moins risqué disponible d'après l'audit,
/// qu'aucun launcher concurrent (Prism, MultiMC, ATLauncher) ne fait.
///
/// Java 17+ seulement : le CDS dynamique (`-XX:ArchiveClassesAtExit`) existe
/// depuis Java 13, mais son comportement est nettement plus fiable à partir
/// des LTS récentes — pas la peine de prendre de risque sur des builds
/// Java 13-16 marginales pour un gain de confort, pas de correction.
///
/// Invalidation automatique (version/loader/mods, voir doc jvm-config) : la
/// clé de cache inclut tout ce qui peut changer le jeu de classes chargées —
/// si elle change, c'est un tout nouveau fichier `.jsa`, jamais réutilisé
/// par erreur contre un classpath différent. Les archives d'une combinaison
/// précédente pour CETTE instance sont supprimées au passage (best-effort).
pub(super) async fn appcds_jvm_args(
    java_major: u32,
    game_dir: &Path,
    version_id: &str,
    loader: Option<&str>,
    classpath_str: &str,
) -> Vec<String> {
    if java_major < 17 {
        return Vec::new();
    }

    let dir = appcds_dir(game_dir);
    if tokio::fs::create_dir_all(&dir).await.is_err() {
        return Vec::new();
    }

    let mods_fp = mods_fingerprint(game_dir).await;
    let key = hash_key(&[version_id, loader.unwrap_or("vanilla"), classpath_str, &mods_fp]);
    let archive_path = dir.join(format!("{key}.jsa"));

    if let Ok(mut entries) = tokio::fs::read_dir(&dir).await {
        while let Ok(Some(entry)) = entries.next_entry().await {
            if entry.path() != archive_path {
                let _ = tokio::fs::remove_file(entry.path()).await;
            }
        }
    }

    if archive_path.exists() {
        vec![format!("-XX:SharedArchiveFile={}", archive_path.display())]
    } else {
        vec![format!("-XX:ArchiveClassesAtExit={}", archive_path.display())]
    }
}
