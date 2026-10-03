//! Diagnostic et réparation d'une installation.
//!
//! Ce que le lancement vérifie déjà, et ce qu'il laisse passer — c'est toute
//! la raison d'être de cet écran. Au lancement, un fichier est retéléchargé
//! s'il **manque ou n'a pas la bonne taille** (`file_matches`), ce qui attrape
//! une coupure réseau en cours de route. Mais un fichier de la **bonne taille
//! et du mauvais contenu** passe inaperçu : disque qui a lâché, antivirus qui
//! a réécrit un jar, copie d'installation faite à la main. Le jeu démarre
//! alors sur une erreur Java incompréhensible.
//!
//! Le diagnostic vérifie donc ce que le lancement ne vérifie pas : le **SHA1**
//! des fichiers dont Mojang le publie.
//!
//! ── Ce qui n'est jamais touché ────────────────────────────────────────────
//! La réparation ne travaille que dans les dossiers **partagés** du launcher —
//! `versions/`, `libraries/`, `assets/`. Jamais dans le dossier de l'instance,
//! donc jamais les mondes, les mods ni les configurations. Tout ce qu'elle
//! supprime est un fichier téléchargeable, et elle le retélécharge dans la
//! foulée. C'est la règle posée après la perte d'instances du 2026-09-27 (voir
//! la note dans CLAUDE.md), et elle vaut ici plus qu'ailleurs : un bouton qui
//! s'appelle « Réparer » ne doit rien pouvoir casser.

use serde::Serialize;
use std::path::{Path, PathBuf};

use crate::db;
use crate::minecraft::launcher::{
    artifact_path, download_verified, minecraft_dir, should_download_library,
};
use crate::minecraft::versions::{fetch_version_list, AssetIndexFile, VersionDetails};
use crate::state::SharedState;

use crate::instances::crud::user_id;

/// Un point de contrôle de l'installation, tel que l'écran l'affiche.
#[derive(Serialize)]
pub struct HealthCheck {
    /// `game`, `libraries`, `assets` ou `loader` — l'interface traduit.
    pub id: String,
    /// `ok` · `broken` (présent mais altéré) · `missing` · `unknown` (la
    /// vérification elle-même n'a pas pu se faire, typiquement hors ligne).
    pub status: String,
    /// Nombre d'éléments en défaut, et total examiné. Zéro et zéro pour un
    /// contrôle qui ne compte rien (le dossier du loader, par exemple).
    pub broken: u32,
    pub total: u32,
    /// Précision libre, déjà rédigée : version attendue, nom du fichier fautif…
    pub detail: Option<String>,
}

impl HealthCheck {
    fn ok(id: &str, total: u32) -> Self {
        Self { id: id.into(), status: "ok".into(), broken: 0, total, detail: None }
    }
    fn broken(id: &str, broken: u32, total: u32) -> Self {
        Self { id: id.into(), status: "broken".into(), broken, total, detail: None }
    }
    fn missing(id: &str, detail: impl Into<String>) -> Self {
        Self { id: id.into(), status: "missing".into(), broken: 1, total: 1, detail: Some(detail.into()) }
    }
    fn unknown(id: &str, detail: impl Into<String>) -> Self {
        Self { id: id.into(), status: "unknown".into(), broken: 0, total: 0, detail: Some(detail.into()) }
    }
}

/// Le SHA1 d'un fichier, en hexadécimal minuscule.
///
/// Sur un thread bloquant et en lecture tamponnée : une installation complète
/// représente quelques centaines de mégaoctets, et les lire par le runtime
/// asynchrone bloquerait ses threads de travail pour un calcul qui n'attend
/// rien.
async fn sha1_of(path: PathBuf) -> Option<String> {
    tokio::task::spawn_blocking(move || {
        use sha1::{Digest, Sha1};
        use std::io::Read;

        let file = std::fs::File::open(&path).ok()?;
        let mut reader = std::io::BufReader::with_capacity(1 << 20, file);
        let mut hasher = Sha1::new();
        let mut buf = vec![0u8; 1 << 20];
        loop {
            match reader.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => hasher.update(&buf[..n]),
                Err(_) => return None,
            }
        }
        Some(format!("{:x}", hasher.finalize()))
    })
    .await
    .ok()
    .flatten()
}

/// Le fichier est-il là, et son contenu est-il bien celui annoncé ?
///
/// `None` = absent, `Some(false)` = présent mais altéré.
async fn intact(path: &Path, expected_sha1: &str) -> Option<bool> {
    if !tokio::fs::try_exists(path).await.unwrap_or(false) {
        return None;
    }
    Some(sha1_of(path.to_path_buf()).await.map(|h| h == expected_sha1).unwrap_or(false))
}

/// Le JSON de version, depuis le cache local ou, à défaut, depuis Mojang.
///
/// Le cache d'abord : c'est lui que le lancement utilise, donc c'est lui qu'il
/// faut diagnostiquer. Le relire par le réseau examinerait une installation
/// qui n'est pas celle du disque.
async fn version_details(version_id: &str) -> anyhow::Result<VersionDetails> {
    let cache = minecraft_dir()
        .join("versions")
        .join(version_id)
        .join(format!("{version_id}.json"));
    if let Ok(text) = tokio::fs::read_to_string(&cache).await {
        if let Ok(details) = serde_json::from_str::<VersionDetails>(&text) {
            return Ok(details);
        }
    }
    let versions = fetch_version_list().await?;
    let info = versions
        .iter()
        .find(|v| v.id == version_id)
        .ok_or_else(|| anyhow::anyhow!("Version {version_id} introuvable"))?;
    let raw = crate::minecraft::http::short_lived_client()
        .get(&info.url)
        .send()
        .await?
        .text()
        .await?;
    Ok(serde_json::from_str(&raw)?)
}

/// Les artefacts vanilla attendus sur le disque pour cette version.
///
/// Seul l'artefact principal de chaque bibliothèque, pas les classifiers
/// natifs : ceux-ci sont réextraits à chaque lancement, donc un natif abîmé se
/// répare tout seul, alors qu'un jar de bibliothèque reste tel quel.
fn expected_files(details: &VersionDetails, libraries_dir: &Path) -> Vec<(PathBuf, String, String)> {
    let mut out = Vec::new();
    for lib in &details.libraries {
        if !should_download_library(lib) {
            continue;
        }
        let Some(dl) = &lib.downloads else { continue };
        let Some(art) = &dl.artifact else { continue };
        out.push((
            artifact_path(libraries_dir, art, &lib.name),
            art.sha1.clone(),
            art.url.clone(),
        ));
    }
    out
}

/// Le dossier de version propre au loader, quand il en a un.
///
/// Forge et NeoForge installent une version à part (`versions/1.21.4-forge-…`)
/// par leur installeur ; Fabric et Quilt n'installent rien de tel, leur profil
/// n'étant qu'un JSON mis en cache et des bibliothèques comme les autres.
fn loader_marker(loader: &str, mc_version: &str, loader_version: &str) -> Option<PathBuf> {
    let versions = minecraft_dir().join("versions");
    match loader {
        "forge" if !loader_version.is_empty() => {
            Some(versions.join(format!("{mc_version}-forge-{loader_version}")))
        }
        "neoforge" if !loader_version.is_empty() => {
            Some(versions.join(format!("neoforge-{loader_version}")))
        }
        _ => None,
    }
}

/// Examine l'installation d'une instance : le jeu, ses bibliothèques, ses
/// ressources, son loader.
#[tauri::command]
pub async fn instance_diagnose(
    state: tauri::State<'_, SharedState>,
    instance_id: String,
) -> Result<Vec<HealthCheck>, String> {
    let row = {
        let s = state.read().await;
        let uid = user_id(&s);
        let db = s.db.lock().await;
        db::instance_get(&db, &instance_id, uid)
            .map_err(|e| e.to_string())?
            .ok_or("Instance introuvable")?
    };

    let mc_dir = minecraft_dir();
    let libraries_dir = mc_dir.join("libraries");
    let assets_dir = mc_dir.join("assets");
    let version_dir = mc_dir.join("versions").join(&row.mc_version);

    let details = match version_details(&row.mc_version).await {
        Ok(d) => d,
        // Rien d'autre n'est vérifiable sans le manifeste : il porte toutes
        // les empreintes attendues. On le dit plutôt que de rendre une liste
        // de contrôles tous verts.
        Err(e) => {
            return Ok(vec![HealthCheck::unknown(
                "game",
                format!("Manifeste de la version {} indisponible : {e}", row.mc_version),
            )])
        }
    };

    let mut checks = Vec::new();

    // ── Le jeu lui-même ──────────────────────────────────────────────────
    let client_jar = version_dir.join(format!("{}.jar", row.mc_version));
    checks.push(match intact(&client_jar, &details.downloads.client.sha1).await {
        None => HealthCheck::missing("game", format!("Minecraft {}", row.mc_version)),
        Some(false) => HealthCheck::broken("game", 1, 1),
        Some(true) => HealthCheck::ok("game", 1),
    });

    // ── Les bibliothèques ────────────────────────────────────────────────
    let expected = expected_files(&details, &libraries_dir);
    let total = expected.len() as u32;
    let mut damaged = 0u32;
    for (path, sha1, _) in &expected {
        if intact(path, sha1).await != Some(true) {
            damaged += 1;
        }
    }
    checks.push(if damaged == 0 {
        HealthCheck::ok("libraries", total)
    } else {
        HealthCheck::broken("libraries", damaged, total)
    });

    // ── Les ressources ───────────────────────────────────────────────────
    //
    // Par la taille et non par le SHA1 : il y en a plusieurs milliers, et le
    // nom de chaque fichier EST son empreinte, donc une altération de contenu
    // demanderait de tout relire — plusieurs centaines de mégaoctets de très
    // petits fichiers, là où la présence et la taille attrapent déjà le
    // téléchargement interrompu, qui est le cas réel.
    let index_path = assets_dir.join("indexes").join(format!("{}.json", details.asset_index.id));
    match tokio::fs::read_to_string(&index_path).await {
        Err(_) => checks.push(HealthCheck::missing("assets", details.asset_index.id.clone())),
        Ok(text) => match serde_json::from_str::<AssetIndexFile>(&text) {
            Err(_) => checks.push(HealthCheck::missing("assets", details.asset_index.id.clone())),
            Ok(index) => {
                let objects_dir = assets_dir.join("objects");
                let total = index.objects.len() as u32;
                let mut damaged = 0u32;
                for obj in index.objects.values() {
                    let Some(prefix) = obj.hash.get(..2) else { continue };
                    let path = objects_dir.join(prefix).join(&obj.hash);
                    let size_ok = tokio::fs::metadata(&path)
                        .await
                        .map(|m| m.len() == obj.size)
                        .unwrap_or(false);
                    if !size_ok {
                        damaged += 1;
                    }
                }
                checks.push(if damaged == 0 {
                    HealthCheck::ok("assets", total)
                } else {
                    HealthCheck::broken("assets", damaged, total)
                });
            }
        },
    }

    // ── Le loader ────────────────────────────────────────────────────────
    if row.loader != "vanilla" {
        match loader_marker(&row.loader, &row.mc_version, &row.loader_version) {
            // Sans version épinglée, le build est résolu au lancement : on ne
            // sait pas quel dossier chercher, et en inventer un donnerait une
            // alerte fausse.
            None => checks.push(HealthCheck::unknown(
                "loader",
                format!("{} — version résolue au lancement", row.loader),
            )),
            Some(dir) => checks.push(if tokio::fs::try_exists(&dir).await.unwrap_or(false) {
                HealthCheck::ok("loader", 1)
            } else {
                HealthCheck::missing("loader", format!("{} {}", row.loader, row.loader_version))
            }),
        }
    }

    Ok(checks)
}

/// Remet en état ce que le diagnostic a trouvé abîmé.
///
/// Retélécharge plutôt que de simplement supprimer : le lancement suivant
/// retéléchargerait bien ce qui manque, mais l'utilisateur a cliqué sur
/// « Réparer », pas sur « Préparer la prochaine fois ». Rend le nombre de
/// fichiers remis en état.
#[tauri::command]
pub async fn instance_repair(
    state: tauri::State<'_, SharedState>,
    instance_id: String,
) -> Result<u32, String> {
    let row = {
        let s = state.read().await;
        let uid = user_id(&s);
        let db = s.db.lock().await;
        db::instance_get(&db, &instance_id, uid)
            .map_err(|e| e.to_string())?
            .ok_or("Instance introuvable")?
    };

    let mc_dir = minecraft_dir();
    let libraries_dir = mc_dir.join("libraries");
    let assets_dir = mc_dir.join("assets");
    let version_dir = mc_dir.join("versions").join(&row.mc_version);

    let details = version_details(&row.mc_version).await.map_err(|e| e.to_string())?;
    let client = crate::minecraft::http::short_lived_client();
    let mut repaired = 0u32;

    // Le client jar.
    let client_jar = version_dir.join(format!("{}.jar", row.mc_version));
    if intact(&client_jar, &details.downloads.client.sha1).await != Some(true) {
        if let Some(parent) = client_jar.parent() {
            let _ = tokio::fs::create_dir_all(parent).await;
        }
        let _ = tokio::fs::remove_file(&client_jar).await;
        download_verified(
            &client,
            &details.downloads.client.url,
            &client_jar,
            Some(&details.downloads.client.sha1),
        )
        .await
        .map_err(|e| e.to_string())?;
        repaired += 1;
    }

    // Les bibliothèques. Une par une et non en parallèle : la réparation est
    // un geste rare, et un échec doit pouvoir nommer le fichier fautif plutôt
    // que de se perdre dans seize tâches concurrentes.
    for (path, sha1, url) in expected_files(&details, &libraries_dir) {
        if intact(&path, &sha1).await == Some(true) {
            continue;
        }
        let _ = tokio::fs::remove_file(&path).await;
        download_verified(&client, &url, &path, Some(&sha1))
            .await
            .map_err(|e| format!("{} : {e}", path.display()))?;
        repaired += 1;
    }

    // Les ressources manquantes. Leur URL se déduit du hash, qui est aussi le
    // nom du fichier — rien à chercher ailleurs.
    let index_path = assets_dir.join("indexes").join(format!("{}.json", details.asset_index.id));
    if !tokio::fs::try_exists(&index_path).await.unwrap_or(false) {
        if let Some(parent) = index_path.parent() {
            let _ = tokio::fs::create_dir_all(parent).await;
        }
        download_verified(&client, &details.asset_index.url, &index_path, None)
            .await
            .map_err(|e| e.to_string())?;
        repaired += 1;
    }
    if let Ok(text) = tokio::fs::read_to_string(&index_path).await {
        if let Ok(index) = serde_json::from_str::<AssetIndexFile>(&text) {
            let objects_dir = assets_dir.join("objects");
            for obj in index.objects.values() {
                let Some(prefix) = obj.hash.get(..2) else { continue };
                let path = objects_dir.join(prefix).join(&obj.hash);
                let size_ok = tokio::fs::metadata(&path).await.map(|m| m.len() == obj.size).unwrap_or(false);
                if size_ok {
                    continue;
                }
                let url = format!("https://resources.download.minecraft.net/{}/{}", prefix, obj.hash);
                // Best-effort, comme au lancement : une ressource manquante
                // dégrade le jeu sans l'empêcher de démarrer, et faire échouer
                // toute la réparation pour une icône serait disproportionné.
                if download_verified(&client, &url, &path, Some(&obj.hash)).await.is_ok() {
                    repaired += 1;
                }
            }
        }
    }

    // Le loader : son installeur n'est pas rejouable ici (il a besoin d'un
    // Java, du progrès, de tout le contexte de lancement). On retire son
    // dossier de version, et le prochain lancement le réinstalle — c'est
    // exactement le chemin déjà prévu pour une première installation.
    if let Some(dir) = loader_marker(&row.loader, &row.mc_version, &row.loader_version) {
        if tokio::fs::try_exists(&dir).await.unwrap_or(false) {
            let json = dir.join(format!("{}.json", dir.file_name().unwrap_or_default().to_string_lossy()));
            if !tokio::fs::try_exists(&json).await.unwrap_or(false) {
                let _ = tokio::fs::remove_dir_all(&dir).await;
                repaired += 1;
            }
        }
    }

    Ok(repaired)
}
