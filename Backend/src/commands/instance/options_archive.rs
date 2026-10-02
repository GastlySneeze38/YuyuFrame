//! Emporter et reprendre **toutes** les options d'une instance.
//!
//! Le modèle partagé de `options.rs` ne connaît qu'`options.txt` : c'est ce
//! qu'il faut pour « mes réglages de base dans chaque nouvelle instance », et
//! c'est trop peu pour « donne-moi ta configuration ». Les réglages d'un
//! modpack vivent surtout dans `config/`, un fichier par mod — distance de
//! rendu de Sodium, touches d'un mod de minimap, options d'Iris.
//!
//! D'où une **archive** plutôt qu'un fichier : un seul objet à envoyer, à
//! ranger dans ses téléchargements, à redonner. Elle ne contient que des
//! réglages — ni mondes, ni mods, ni rien qui pèse.
//!
//! ── Ce qu'on prend, et pourquoi pas plus ─────────────────────────────────
//! `options.txt` et ses voisins connus, plus tout `config/`. Quelques mods
//! écrivent ailleurs (un dossier à leur nom à la racine de l'instance) : les
//! attraper demanderait une liste de mods à tenir à jour, qui serait fausse
//! la semaine suivante. On prend donc ce qui est universel, et on le dit.

use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use super::crud::instance_dir;

/// Fichiers de réglages posés à la racine d'une instance.
///
/// `optionsof` et `optionsshaders` sont ceux d'OptiFine et d'Iris/Oculus : ils
/// sont au même endroit, ont la même nature, et c'est précisément ce qu'on
/// perd quand on ne copie qu'`options.txt`.
const ROOT_FILES: [&str; 3] = ["options.txt", "optionsof.txt", "optionsshaders.txt"];

/// Dossier des réglages de mods, chez Fabric comme chez Forge.
const CONFIG_DIR: &str = "config";

/// Un chemin lu dans une archive est-il sûr à écrire sous `base` ?
///
/// Une entrée de zip est une chaîne quelconque, choisie par qui a fabriqué
/// l'archive : `../../../AppData/…` est un nom d'entrée parfaitement valide.
/// On n'accepte donc que des composants ordinaires, et on refuse tout le
/// reste plutôt que d'essayer de le réparer.
fn safe_join(base: &Path, entry: &str) -> Option<PathBuf> {
    let mut out = base.to_path_buf();
    let mut depth = 0usize;
    for part in entry.split(['/', '\\']) {
        if part.is_empty() || part == "." {
            continue;
        }
        if part == ".." || part.contains(':') {
            return None;
        }
        out.push(part);
        depth += 1;
    }
    (depth > 0).then_some(out)
}

/// L'entrée de l'archive concerne-t-elle bien des réglages ?
///
/// Vérifié à l'importation et pas seulement à l'exportation : l'archive peut
/// venir de n'importe où, et rien n'empêche d'y glisser un `mods/truc.jar`.
/// On n'écrit que ce qu'on sait être une option.
fn is_settings_entry(entry: &str) -> bool {
    let normalised = entry.replace('\\', "/");
    ROOT_FILES.contains(&normalised.as_str()) || normalised.starts_with(&format!("{CONFIG_DIR}/"))
}

/// Les fichiers de réglages d'une instance, en chemins relatifs.
fn collect(dir: &Path) -> Vec<String> {
    let mut found: Vec<String> = ROOT_FILES
        .iter()
        .filter(|name| dir.join(name).is_file())
        .map(|name| name.to_string())
        .collect();

    let config = dir.join(CONFIG_DIR);
    if config.is_dir() {
        walk(&config, CONFIG_DIR, &mut found);
    }
    found
}

/// Parcours récursif de `config/`, en chemins relatifs à l'instance.
///
/// Écrit à la main plutôt qu'avec une dépendance de plus : c'est une
/// douzaine de lignes, et le seul besoin du launcher en la matière.
fn walk(dir: &Path, prefix: &str, out: &mut Vec<String>) {
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        let Some(name) = entry.file_name().to_str().map(str::to_string) else { continue };
        let rel = format!("{prefix}/{name}");
        match entry.file_type() {
            Ok(t) if t.is_dir() => walk(&entry.path(), &rel, out),
            Ok(t) if t.is_file() => out.push(rel),
            // Liens symboliques et autres : on ne les suit pas, pour ne pas
            // sortir du dossier de l'instance sans s'en apercevoir.
            _ => {}
        }
    }
}

/// Ce que l'écran annonce avant d'exporter.
#[derive(serde::Serialize)]
pub struct OptionsSummary {
    /// Nombre de fichiers de réglages trouvés.
    pub files: u32,
    /// Vrai si `options.txt` est là — son absence veut dire que le jeu n'a
    /// jamais été lancé, et l'écran le dit autrement qu'un simple « 0 ».
    pub has_options: bool,
    /// Nombre de fichiers sous `config/`, pour distinguer « juste le vanilla »
    /// d'une configuration de modpack entière.
    pub config_files: u32,
}

/// Ce qu'une exportation emporterait, sans rien écrire.
#[tauri::command]
pub async fn instance_options_summary(instance_id: String) -> Result<OptionsSummary, String> {
    let dir = instance_dir(&instance_id);
    let files = tokio::task::spawn_blocking(move || collect(&dir))
        .await
        .map_err(|e| e.to_string())?;
    Ok(OptionsSummary {
        files: files.len() as u32,
        has_options: files.iter().any(|f| f == "options.txt"),
        config_files: files.iter().filter(|f| f.starts_with(CONFIG_DIR)).count() as u32,
    })
}

/// Écrit toutes les options de l'instance dans une archive.
///
/// Le chemin vient du sélecteur d'enregistrement, donc d'un geste de
/// l'utilisateur — jamais d'un contenu lu ailleurs.
#[tauri::command]
pub async fn instance_export_options(instance_id: String, path: String) -> Result<u32, String> {
    let dir = instance_dir(&instance_id);
    tokio::task::spawn_blocking(move || {
        let files = collect(&dir);
        if files.is_empty() {
            return Err("Aucun fichier de réglages dans cette instance — lance le jeu au moins une fois.".to_string());
        }

        let out = std::fs::File::create(&path).map_err(|e| format!("Création du fichier : {e}"))?;
        let mut zip = zip::ZipWriter::new(out);
        let options: zip::write::FileOptions<'_, ()> =
            zip::write::FileOptions::default().compression_method(zip::CompressionMethod::Deflated);

        let mut written = 0u32;
        for rel in &files {
            let source = rel.split('/').fold(dir.clone(), |acc, c| acc.join(c));
            let mut content = Vec::new();
            match std::fs::File::open(&source).and_then(|mut f| f.read_to_end(&mut content)) {
                Ok(_) => {}
                Err(e) => {
                    // Un fichier verrouillé par le jeu en cours ne doit pas
                    // faire échouer l'export de tous les autres.
                    tracing::warn!("Réglage {} illisible, ignoré : {}", rel, e);
                    continue;
                }
            }
            zip.start_file(rel.clone(), options).map_err(|e| e.to_string())?;
            zip.write_all(&content).map_err(|e| e.to_string())?;
            written += 1;
        }
        zip.finish().map_err(|e| e.to_string())?;
        Ok(written)
    })
    .await
    .map_err(|e| e.to_string())?
}

/// Reprend des options depuis un fichier choisi — archive ou `options.txt` nu.
///
/// Les deux parce que les deux circulent : on exporte une archive, mais on
/// reçoit souvent un `options.txt` seul. Le format est reconnu à la signature
/// et non à l'extension, un fichier renommé étant plus fréquent qu'on ne
/// croit.
///
/// Écrase les fichiers de même nom : c'est le sens du geste. Ce qui n'est pas
/// dans l'archive est laissé en place — une importation n'est pas une remise
/// à zéro.
#[tauri::command]
pub async fn instance_import_options(instance_id: String, path: String) -> Result<u32, String> {
    let dir = instance_dir(&instance_id);
    tokio::task::spawn_blocking(move || {
        let bytes = std::fs::read(&path).map_err(|e| format!("Lecture du fichier : {e}"))?;

        // Un zip commence toujours par `PK\x03\x04`.
        if !bytes.starts_with(b"PK\x03\x04") {
            let name = Path::new(&path)
                .file_name()
                .and_then(|n| n.to_str())
                .unwrap_or("options.txt");
            if !ROOT_FILES.contains(&name) {
                return Err(
                    "Fichier non reconnu — attendu : une archive d'options exportée, ou un options.txt.".to_string(),
                );
            }
            std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
            std::fs::write(dir.join(name), &bytes).map_err(|e| e.to_string())?;
            return Ok(1);
        }

        let cursor = std::io::Cursor::new(bytes);
        let mut archive = zip::ZipArchive::new(cursor).map_err(|_| "Archive illisible".to_string())?;
        let mut restored = 0u32;
        for i in 0..archive.len() {
            let Ok(mut entry) = archive.by_index(i) else { continue };
            if entry.is_dir() {
                continue;
            }
            let name = entry.name().to_string();
            if !is_settings_entry(&name) {
                tracing::warn!("Entrée {} ignorée : ce n'est pas un fichier de réglages", name);
                continue;
            }
            let Some(dest) = safe_join(&dir, &name) else {
                tracing::warn!("Entrée {} ignorée : chemin refusé", name);
                continue;
            };
            let mut content = Vec::new();
            if entry.read_to_end(&mut content).is_err() {
                continue;
            }
            if let Some(parent) = dest.parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            match std::fs::write(&dest, &content) {
                Ok(()) => restored += 1,
                Err(e) => tracing::warn!("Écriture de {} échouée : {}", name, e),
            }
        }
        if restored == 0 {
            return Err("Aucun fichier de réglages dans cette archive.".to_string());
        }
        Ok(restored)
    })
    .await
    .map_err(|e| e.to_string())?
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Un nom d'entrée de zip est choisi par qui fabrique l'archive : la
    /// remontée de dossier doit être refusée, pas réparée.
    #[test]
    fn un_chemin_qui_remonte_est_refuse() {
        let base = Path::new("C:\\jeu\\instance");
        assert!(safe_join(base, "../../AppData/truc").is_none());
        assert!(safe_join(base, "config/../../evade").is_none());
        assert!(safe_join(base, "C:/Windows/system32").is_none());
        assert!(safe_join(base, "").is_none());
    }

    #[test]
    fn un_chemin_ordinaire_passe() {
        let base = Path::new("base");
        assert_eq!(safe_join(base, "options.txt"), Some(base.join("options.txt")));
        assert_eq!(
            safe_join(base, "config/sodium/options.json"),
            Some(base.join("config").join("sodium").join("options.json")),
        );
        // Les séparateurs Windows aussi : une archive faite ici peut en avoir.
        assert_eq!(safe_join(base, "config\\iris.properties"), Some(base.join("config").join("iris.properties")));
    }

    /// L'archive peut venir de n'importe où : seuls les fichiers de réglages
    /// sont écrits, jamais un mod glissé dedans.
    #[test]
    fn seuls_les_reglages_sont_acceptes() {
        assert!(is_settings_entry("options.txt"));
        assert!(is_settings_entry("optionsof.txt"));
        assert!(is_settings_entry("config/sodium/options.json"));
        assert!(is_settings_entry("config\\iris.properties"));

        assert!(!is_settings_entry("mods/trojan.jar"));
        assert!(!is_settings_entry("saves/monde/level.dat"));
        assert!(!is_settings_entry("configuration/truc"));
        assert!(!is_settings_entry("options.txt.bak"));
    }
}
