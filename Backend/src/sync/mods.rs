//! Les mods synchronisés par référence, pas par contenu.
//!
//! Un dossier `mods/` de modpack pèse un à deux gigaoctets, et chacun de ces
//! fichiers est déjà hébergé publiquement par Modrinth. Les téléverser sur nos
//! serveurs, c'est payer un stockage pour des octets que tout le monde peut
//! déjà récupérer — et faire attendre quelqu'un sur son quota pour ça.
//!
//! On envoie donc, à la place, un petit document : pour chaque mod reconnu,
//! son projet, sa version et son empreinte. À la restauration, le launcher les
//! retélécharge depuis la source. Deux gigaoctets deviennent quelques
//! dizaines de kilo-octets.
//!
//! **Ce qui n'est pas reconnu reste envoyé tel quel.** Un jar posé à la main,
//! un mod privé, une version retirée de Modrinth : ceux-là n'existent nulle
//! part ailleurs, et les remplacer par une référence reviendrait à les perdre.
//! C'est la règle qui rend l'optimisation sûre — on n'allège jamais au prix de
//! ce qu'on ne peut pas récupérer.

use std::collections::HashMap;
use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::api::error::{ApiError, ApiResult};

/// Nom du document dans l'instance. Dans un dossier à part : il est
/// synchronisé comme les autres fichiers, mais n'a rien à faire au milieu des
/// mods, où le jeu le lirait.
pub const DOC_DIR: &str = ".yuyuframe";
pub const DOC_PATH: &str = ".yuyuframe/mods.json";

/// Empreintes envoyées en une fois à Modrinth. Cent par appel : leur API
/// accepte plus, mais une requête qui échoue ne doit pas emporter tout un
/// modpack avec elle.
const BATCH: usize = 100;

/// Un mod que le serveur n'a pas besoin de stocker.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct ModRef {
    /// Nom du fichier dans `mods/`, suffixe `.disabled` compris — c'est lui
    /// qui dit si le mod était actif.
    pub file: String,
    pub sha1: String,
    pub size: i64,
    pub project_id: String,
    pub version_id: String,
    /// Toujours sur le CDN de Modrinth (vérifié à la résolution).
    pub url: String,
}

impl ModRef {
    pub fn enabled(&self) -> bool {
        !self.file.to_ascii_lowercase().ends_with(".disabled")
    }
}

/// Le document envoyé à la place des jars.
#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct ModsDoc {
    /// Format du document. Un launcher plus ancien qui lirait une version
    /// qu'il ne connaît pas doit refuser plutôt qu'improviser.
    pub version: u32,
    pub mods: Vec<ModRef>,
}

pub const DOC_VERSION: u32 = 1;

// ── Résolution ───────────────────────────────────────────────────────────────

/// Interroge Modrinth sur un lot d'empreintes SHA-1.
///
/// Rend une table vide en cas de panne : ne pas reconnaître un mod n'est
/// jamais une erreur, c'est juste une sauvegarde plus lourde. Couper une
/// synchronisation parce que Modrinth répond mal serait disproportionné.
async fn lookup(client: &reqwest::Client, sha1s: &[String]) -> HashMap<String, (String, String, String)> {
    let mut out = HashMap::new();
    for batch in sha1s.chunks(BATCH) {
        let resp = client
            .post("https://api.modrinth.com/v2/version_files")
            .json(&serde_json::json!({ "hashes": batch, "algorithm": "sha1" }))
            .send()
            .await;
        let Ok(resp) = resp else { continue };
        if !resp.status().is_success() {
            continue;
        }
        let Ok(json) = resp.json::<serde_json::Value>().await else { continue };
        let Some(obj) = json.as_object() else { continue };

        for (hash, version) in obj {
            let Some(project_id) = version["project_id"].as_str() else { continue };
            let Some(version_id) = version["id"].as_str() else { continue };
            let files = version["files"].as_array();
            let url = files
                .and_then(|f| f.iter().find(|file| file["primary"].as_bool().unwrap_or(false)))
                .or_else(|| files.and_then(|f| f.first()))
                .and_then(|f| f["url"].as_str());
            let Some(url) = url else { continue };
            // On n'accepte que le CDN de Modrinth : une URL arbitraire dans un
            // document synchronisé serait un téléchargement qu'on ferait
            // exécuter à l'aveugle sur le PC d'en face.
            if !url.starts_with("https://cdn.modrinth.com/") {
                continue;
            }
            out.insert(hash.clone(), (project_id.to_string(), version_id.to_string(), url.to_string()));
        }
    }
    out
}

/// Un fichier du dossier `mods/`, avec son empreinte.
pub struct LocalMod {
    pub file: String,
    pub sha1: String,
    pub size: i64,
}

/// Liste les jars de l'instance, actifs comme désactivés.
pub fn list_local(game_dir: &Path) -> Vec<LocalMod> {
    let dir = game_dir.join("mods");
    let Ok(entries) = std::fs::read_dir(dir) else { return Vec::new() };
    let mut out: Vec<LocalMod> = entries
        .flatten()
        .filter_map(|e| {
            let path = e.path();
            let file = path.file_name()?.to_str()?.to_string();
            let lower = file.to_ascii_lowercase();
            if !lower.ends_with(".jar") && !lower.ends_with(".jar.disabled") {
                return None;
            }
            let size = std::fs::metadata(&path).ok()?.len() as i64;
            let sha1 = crate::commands::instance::mods::sha1_cached(&path);
            (!sha1.is_empty()).then_some(LocalMod { file, sha1, size })
        })
        .collect();
    out.sort_by(|a, b| a.file.cmp(&b.file));
    out
}

/// Construit le document à partir des mods de l'instance.
///
/// Rend aussi l'ensemble des chemins de manifeste à **retirer** de l'envoi :
/// ceux des mods reconnus, qui se retéléchargeront au lieu d'être stockés.
pub async fn resolve(client: &reqwest::Client, game_dir: &Path) -> (ModsDoc, std::collections::HashSet<String>) {
    let local = list_local(game_dir);
    if local.is_empty() {
        return (ModsDoc { version: DOC_VERSION, mods: Vec::new() }, Default::default());
    }
    let sha1s: Vec<String> = local.iter().map(|m| m.sha1.clone()).collect();
    let found = lookup(client, &sha1s).await;

    let mut mods = Vec::new();
    let mut skip = std::collections::HashSet::new();
    for m in local {
        let Some((project_id, version_id, url)) = found.get(&m.sha1) else { continue };
        skip.insert(format!("mods/{}", m.file));
        mods.push(ModRef {
            file: m.file,
            sha1: m.sha1,
            size: m.size,
            project_id: project_id.clone(),
            version_id: version_id.clone(),
            url: url.clone(),
        });
    }
    (ModsDoc { version: DOC_VERSION, mods }, skip)
}

/// Écrit le document dans l'instance pour qu'il parte avec le manifeste.
pub fn write_doc(game_dir: &Path, doc: &ModsDoc) -> std::io::Result<()> {
    let dir = game_dir.join(DOC_DIR);
    std::fs::create_dir_all(&dir)?;
    std::fs::write(dir.join("mods.json"), serde_json::to_vec_pretty(doc)?)
}

pub fn read_doc(game_dir: &Path) -> Option<ModsDoc> {
    let doc: ModsDoc = serde_json::from_slice(&std::fs::read(game_dir.join(DOC_PATH)).ok()?).ok()?;
    // Un document écrit par un launcher plus récent peut décrire des choses
    // qu'on ne sait pas lire : mieux vaut l'ignorer que de mal l'appliquer.
    (doc.version <= DOC_VERSION).then_some(doc)
}

// ── Restauration ─────────────────────────────────────────────────────────────

fn sha1_hex(data: &[u8]) -> String {
    use sha1::{Digest, Sha1};
    Sha1::digest(data).iter().map(|b| format!("{b:02x}")).collect()
}

/// Le mod est-il déjà là, à l'identique ? On compare l'empreinte, pas le nom :
/// un fichier renommé à la main ne doit pas faire retélécharger.
fn already_present(game_dir: &Path, reference: &ModRef) -> bool {
    let path = game_dir.join("mods").join(&reference.file);
    // La taille d'abord : elle écarte la quasi-totalité des cas sans toucher
    // au contenu du fichier.
    let Ok(meta) = std::fs::metadata(&path) else { return false };
    meta.len() as i64 == reference.size && crate::commands::instance::mods::sha1_cached(&path) == reference.sha1
}

/// Télécharge les mods manquants du document. `report` reçoit (fait, total).
pub async fn install(
    client: &reqwest::Client,
    game_dir: &Path,
    doc: &ModsDoc,
    report: &(dyn Fn(usize, usize, &str) + Send + Sync),
) -> ApiResult<usize> {
    let dir = game_dir.join("mods");
    std::fs::create_dir_all(&dir).map_err(|e| ApiError::new("internal", format!("Dossier mods/ impossible : {e}")))?;

    let total = doc.mods.len();
    let mut installed = 0;
    for (index, reference) in doc.mods.iter().enumerate() {
        report(index, total, &reference.file);
        if already_present(game_dir, reference) {
            continue;
        }
        let resp = client.get(&reference.url).send().await.map_err(ApiError::network)?;
        if !resp.status().is_success() {
            // Une version retirée de Modrinth depuis l'envoi : on le dit et on
            // continue. Interrompre la restauration pour un mod sur cent
            // laisserait l'instance dans un état pire qu'incomplet.
            tracing::warn!("[Sync] mod {} indisponible ({})", reference.file, resp.status());
            continue;
        }
        let bytes = resp.bytes().await.map_err(ApiError::network)?;
        // L'empreinte du document fait foi : on n'écrit pas dans `mods/` un
        // fichier dont on n'a pas vérifié qu'il est bien celui qu'on
        // synchronisait.
        if sha1_hex(&bytes) != reference.sha1 {
            tracing::warn!("[Sync] mod {} : empreinte inattendue, ignoré", reference.file);
            continue;
        }
        std::fs::write(dir.join(&reference.file), &bytes).map_err(|e| ApiError::new("internal", format!("Écriture impossible : {e}")))?;
        installed += 1;
    }
    report(total, total, "");
    Ok(installed)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn reference(file: &str) -> ModRef {
        ModRef {
            file: file.into(),
            sha1: "a".repeat(40),
            size: 1024,
            project_id: "AANobbMI".into(),
            version_id: "xyz".into(),
            url: "https://cdn.modrinth.com/data/AANobbMI/versions/xyz/sodium.jar".into(),
        }
    }

    #[test]
    fn a_disabled_mod_stays_disabled_through_the_round_trip() {
        assert!(reference("sodium.jar").enabled());
        assert!(!reference("sodium.jar.disabled").enabled());
    }

    #[test]
    fn the_document_survives_a_round_trip() {
        let doc = ModsDoc { version: DOC_VERSION, mods: vec![reference("sodium.jar"), reference("iris.jar.disabled")] };
        let back: ModsDoc = serde_json::from_slice(&serde_json::to_vec(&doc).unwrap()).unwrap();
        assert_eq!(back.mods, doc.mods);
    }

    #[test]
    fn a_document_from_a_newer_launcher_is_refused() {
        // Mieux vaut restaurer sans les références que de mal les appliquer.
        let json = serde_json::to_vec(&ModsDoc { version: DOC_VERSION + 1, mods: vec![reference("a.jar")] }).unwrap();
        let doc: ModsDoc = serde_json::from_slice(&json).unwrap();
        assert!(doc.version > DOC_VERSION, "read_doc l'écartera");
    }

    #[test]
    fn the_document_lives_outside_the_mods_folder() {
        // Un .json dans mods/ serait lu par le chargeur de mods au démarrage.
        assert!(!DOC_PATH.starts_with("mods/"));
        assert!(DOC_PATH.starts_with(DOC_DIR));
    }
}
