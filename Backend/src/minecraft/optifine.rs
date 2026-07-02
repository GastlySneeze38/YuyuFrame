use anyhow::{anyhow, Result};
use std::path::{Path, PathBuf};

use crate::minecraft::launcher::minecraft_dir;

/// Bytecode PRÉCOMPILÉ (pas la source) embarqué directement dans le binaire
/// Rust — voir `optifine_patcher_main.java` à côté pour le code source
/// lisible/le contexte complet. Choix DÉLIBÉRÉ de ne PAS compiler à la
/// volée chez l'utilisateur final : `ensure_java` (voir launcher.rs) résout
/// un JRE pour LANCER Minecraft, qui n'inclut souvent PAS `javac` (le
/// runtime Mojang bundlé — "jre-legacy" — est historiquement un JRE pur,
/// sans compilateur) — on ne peut donc pas supposer `javac` disponible sur
/// la machine d'un joueur. Précompilé une seule fois en dev (JDK 24, cible
/// `--release 8` pour rester compatible Java 8, requis par MC 1.8.9) via
/// `javac --release 8 optifine_patcher_main.java`, à refaire manuellement
/// si jamais ce fichier source est modifié.
const PATCHER_MAIN_CLASS: &[u8] = include_bytes!("optifine_patcher_classes/com/yuyuframe/optifinepatcher/Main.class");

fn patcher_cache_dir() -> PathBuf {
    minecraft_dir().join("optifine-patcher-cache")
}

fn patcher_class_path(cache_dir: &Path) -> PathBuf {
    cache_dir.join("com").join("yuyuframe").join("optifinepatcher").join("Main.class")
}

/// Écrit le {@code .class} précompilé sur disque (même cache réutilisé pour
/// toutes les instances/lancements) — AUCUNE compilation à l'exécution,
/// juste une écriture de bytes déjà prêts. Toujours réécrit (coût
/// négligeable, quelques Ko) plutôt que de sauter l'écriture si le fichier
/// existe déjà : sinon une future correction de ce pont resterait invisible
/// pour un utilisateur qui l'a déjà exécuté une fois (cache figé sur une
/// vieille version potentiellement buguée).
async fn ensure_patcher_extracted() -> Result<PathBuf> {
    let cache_dir = patcher_cache_dir();
    let class_path = patcher_class_path(&cache_dir);
    if let Some(parent) = class_path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    tokio::fs::write(&class_path, PATCHER_MAIN_CLASS).await?;
    Ok(cache_dir)
}

/// Cherche le fichier OptiFine_*.jar le plus récent dans le dossier
/// Téléchargements de l'utilisateur, et le copie dans un dossier stable
/// propre à l'instance — jamais référencé directement dans Téléchargements
/// (dossier géré par l'utilisateur, pourrait être vidé/déplacé n'importe
/// quand). OptiFine n'autorise aucune redistribution sans permission écrite
/// de son auteur (cf. https://optifine.net/copyright) — on ne télécharge
/// donc jamais le jar nous-mêmes, l'utilisateur passe par le vrai site
/// officiel (bouton "Ouvrir OptiFine" côté Frontend) puis reclique pour
/// déclencher cette détection.
pub async fn import_from_downloads(instance_optifine_dir: &Path) -> Result<PathBuf> {
    let downloads = dirs::download_dir().ok_or_else(|| anyhow!("Dossier Téléchargements introuvable"))?;

    let mut candidates: Vec<(std::time::SystemTime, PathBuf)> = Vec::new();
    let mut entries = tokio::fs::read_dir(&downloads).await?;
    while let Ok(Some(entry)) = entries.next_entry().await {
        let path = entry.path();
        let name = path.file_name().unwrap_or_default().to_string_lossy().to_lowercase();
        if name.starts_with("optifine") && name.ends_with(".jar") {
            if let Ok(meta) = entry.metadata().await {
                if let Ok(modified) = meta.modified() {
                    candidates.push((modified, path));
                }
            }
        }
    }

    candidates.sort_by_key(|(t, _)| *t);
    let (_, source) = candidates.pop().ok_or_else(|| {
        anyhow!("Aucun fichier OptiFine_*.jar trouvé dans Téléchargements — télécharge-le d'abord depuis le site officiel")
    })?;

    tokio::fs::create_dir_all(instance_optifine_dir).await?;
    let safe_name = source.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| "OptiFine.jar".to_string());
    let dest = instance_optifine_dir.join(&safe_name);
    tokio::fs::copy(&source, &dest).await?;

    Ok(dest)
}

/// Produit (ou réutilise, si déjà fait pour ce même jar OptiFine + cette
/// même version MC) le jar client patché — fusion des classes OptiFine dans
/// une copie du jar vanilla, via le pont Java ci-dessus. Retourne le chemin
/// du jar patché, à utiliser comme jar client effectif au lancement (voir
/// launcher.rs, cas "optifine" du match loader).
///
/// `optifine.Patcher.process()` ne produit PAS un jar client complet : en
/// désassemblant son bytecode (javap) et en inspectant le jar produit, il
/// n'écrit QUE les classes propres à OptiFine + les classes vanilla qu'il
/// patche explicitement (ex: 218 classes obfusquées sur les 2446 du jar
/// vanilla 1.8.9) — le reste des classes vanilla (dont
/// `net.minecraft.client.main.Main`, le point d'entrée !) est absent,
/// provoquant "Error: Could not find or load main class" au lancement (testé
/// en conditions réelles). Merger ce jar "delta" avec le jar vanilla est donc
/// une étape à NOUS, pas fournie par OptiFine — voir `merge_with_vanilla`.
pub async fn ensure_patched(
    instance_optifine_dir: &Path,
    mc_version: &str,
    vanilla_jar: &Path,
    optifine_jar: &Path,
    java: &str,
) -> Result<PathBuf> {
    let output_jar = instance_optifine_dir.join(format!("{}-optifine-patched.jar", mc_version));
    if output_jar.exists() {
        return Ok(output_jar);
    }

    let classes_dir = ensure_patcher_extracted().await?;
    let delta_jar = instance_optifine_dir.join(format!("{}-optifine-delta.jar", mc_version));

    let patch_output = tokio::process::Command::new(java)
        .arg("-cp")
        .arg(&classes_dir)
        .arg("com.yuyuframe.optifinepatcher.Main")
        .arg(vanilla_jar)
        .arg(&delta_jar)
        .arg(optifine_jar)
        .output()
        .await?;

    if !patch_output.status.success() {
        let stderr = String::from_utf8_lossy(&patch_output.stderr);
        let _ = tokio::fs::remove_file(&delta_jar).await;
        return Err(anyhow!("Patch OptiFine échoué:\n{}", stderr));
    }

    if !delta_jar.exists() {
        let _ = tokio::fs::remove_file(&delta_jar).await;
        return Err(anyhow!("Le patch OptiFine s'est terminé sans erreur mais n'a produit aucun jar — signature optifine.Patcher.process probablement différente sur cette version."));
    }

    let vanilla_jar = vanilla_jar.to_path_buf();
    let delta_jar_for_merge = delta_jar.clone();
    let output_jar_for_merge = output_jar.clone();
    let merge_result = tokio::task::spawn_blocking(move || {
        merge_with_vanilla(&vanilla_jar, &delta_jar_for_merge, &output_jar_for_merge)
    })
    .await
    .map_err(|e| anyhow!("Fusion du jar OptiFine interrompue : {}", e))?;

    let _ = tokio::fs::remove_file(&delta_jar).await;

    if let Err(e) = merge_result {
        let _ = tokio::fs::remove_file(&output_jar).await;
        return Err(anyhow!("Fusion du jar OptiFine avec le jar vanilla échouée : {}", e));
    }

    if !output_jar.exists() {
        return Err(anyhow!("La fusion du jar OptiFine s'est terminée sans erreur mais n'a produit aucun jar."));
    }

    Ok(output_jar)
}

/// Combine le jar "delta" produit par OptiFine (ses propres classes + les
/// classes vanilla patchées) avec le RESTE du jar vanilla non touché par
/// OptiFine, en un seul jar client complet et utilisable. Les entrées du
/// delta ont priorité (écrites en premier, les doublons du vanilla sont
/// ensuite ignorés). Copie brute (raw_copy_file, sans décompresser/
/// recompresser) — rapide et bit-exact.
///
/// Les fichiers de signature (`META-INF/MANIFEST.MF`, `*.SF`, `*.RSA`,
/// `*.DSA`) sont explicitement exclus des DEUX sources : le jar vanilla est
/// signé par Mojang (empreintes SHA par classe dans son MANIFEST.MF) mais le
/// jar delta ne l'est pas — les mélanger tel quel lève
/// `SecurityException: signer information does not match` au chargement de
/// la première classe vanilla non patchée (confirmé en conditions réelles).
/// Aucun manifeste n'est nécessaire ici : le jar est utilisé via `-cp`, pas
/// `-jar` (pas besoin de Main-Class dans un manifeste).
fn merge_with_vanilla(vanilla_jar: &Path, delta_jar: &Path, output_jar: &Path) -> Result<()> {
    use std::collections::HashSet;
    use std::fs::File;
    use std::io::BufWriter;

    fn is_signature_entry(name: &str) -> bool {
        let upper = name.to_ascii_uppercase();
        upper == "META-INF/MANIFEST.MF"
            || upper.ends_with(".SF")
            || upper.ends_with(".RSA")
            || upper.ends_with(".DSA")
    }

    let mut delta_archive = zip::ZipArchive::new(File::open(delta_jar)?)?;
    let mut writer = zip::ZipWriter::new(BufWriter::new(File::create(output_jar)?));
    let mut written: HashSet<String> = HashSet::new();

    for i in 0..delta_archive.len() {
        let file = delta_archive.by_index_raw(i)?;
        let name = file.name().to_string();
        if is_signature_entry(&name) {
            continue;
        }
        written.insert(name);
        writer.raw_copy_file(file)?;
    }

    let mut vanilla_archive = zip::ZipArchive::new(File::open(vanilla_jar)?)?;
    for i in 0..vanilla_archive.len() {
        let file = vanilla_archive.by_index_raw(i)?;
        let name = file.name().to_string();
        if written.contains(&name) || is_signature_entry(&name) {
            continue;
        }
        writer.raw_copy_file(file)?;
    }

    writer.finish()?;
    Ok(())
}
