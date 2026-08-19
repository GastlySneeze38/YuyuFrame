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

/// `true` si ce runtime Java a une archive CDS de base — le CDS dynamique
/// (`-XX:ArchiveClassesAtExit`) s'appuie dessus et échoue avec juste un
/// warning JVM sinon (jamais fatal, mais AppCDS ne fait alors strictement
/// rien) : "-XX:ArchiveClassesAtExit is unsupported when base CDS archive is
/// not loaded" — trouvé en test réel avec le runtime Mojang
/// `java-runtime-epsilon` (Java 25), visiblement livré sans `classes.jsa`.
/// Emplacement standard depuis JDK 12 (VM serveur, la seule que ship
/// OpenJDK 9+ sur desktop 64 bits) : `<JAVA_HOME>/lib/server/classes.jsa` —
/// `<JAVA_HOME>/lib/classes.jsa` en repli pour d'éventuels autres layouts.
fn has_base_cds_archive(java: &str) -> bool {
    let Some(java_home) = Path::new(java).parent().and_then(Path::parent) else {
        return false;
    };
    java_home.join("lib").join("server").join("classes.jsa").exists()
        || java_home.join("lib").join("classes.jsa").exists()
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
    java: &str,
    java_major: u32,
    game_dir: &Path,
    version_id: &str,
    loader: Option<&str>,
    classpath_str: &str,
) -> Vec<String> {
    if java_major < 17 || !has_base_cds_archive(java) {
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
        // Le launcher attache TOUJOURS au moins un javaagent (LauncherAgent,
        // et p2p-agent si activé) — sans ce déverrouillage explicite, le dump
        // CDS échoue systématiquement avec "Must enable
        // AllowArchivingWithJavaAgent in order to run Java agent during CDS
        // dumping" (trouvé en test réel). Seulement nécessaire pour la phase
        // de DUMP (ArchiveClassesAtExit) — jamais pour le simple chargement
        // (SharedArchiveFile, branche ci-dessus).
        vec![
            "-XX:+UnlockDiagnosticVMOptions".to_string(),
            "-XX:+AllowArchivingWithJavaAgent".to_string(),
            format!("-XX:ArchiveClassesAtExit={}", archive_path.display()),
        ]
    }
}
