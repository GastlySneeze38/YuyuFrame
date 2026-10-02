//! Partager une instance sans rien héberger (2026-10-02).
//!
//! Une instance est presque entièrement **reconstructible** : ses mods, packs
//! de ressources et shaders viennent de Modrinth ou de CurseForge. On ne
//! partage donc pas les fichiers, mais **ce qu'il faut pour les retrouver**,
//! et rien ne passe par nos serveurs. Deux formes :
//!
//! - **Un fichier `.mrpack`** (format Modrinth, lu aussi par Prism, ATLauncher
//!   et la Modrinth App) : chaque fichier connu de Modrinth ou de CurseForge
//!   devient une ligne avec son adresse et ses empreintes, tout le reste (mod
//!   fait maison, `config/`, options, mondes choisis) est **embarqué** dans
//!   `overrides/`. Le pack pèse le poids de ce qui n'est nulle part ailleurs.
//! - **Un lien `yuyuframe://instance/…`** (`crate::share_link`), sans fichier
//!   du tout, quand tout ce qui est choisi est sur Modrinth : version du jeu,
//!   loader, l'identifiant de version Modrinth de chaque fichier, et la
//!   configuration Java. Une instance de 22 mods avec ses drapeaux JVM tient
//!   en moins de 300 caractères (test `lien_instance_compact`). Ni configs ni
//!   fichiers locaux : c'est le rôle du fichier.
//!
//! ── Ce qui se partage ───────────────────────────────────────────────────────
//! L'inventaire part du **contenu réel** du dossier, pas d'une liste figée :
//! un mod de minicarte qui range ses points de passage dans `xaero/` doit
//! apparaître, même si on ne le connaît pas. Trois règles seulement :
//! - **Jamais proposé** (`NEVER_SHARED`, plus tout nom commençant par un
//!   point) : caches (`.fabric`, `.voxy`, `downloads/`…), journaux, rapports
//!   de plantage, et ce qui touche au compte ou à la vie privée —
//!   `usercache.json`, l'historique des commandes, `essential/` (le mod
//!   Essential y garde des jetons de connexion), et les fichiers internes du
//!   launcher (`meta.json`, `modpack.json`).
//! - **Coché d'office** : mods, packs de ressources, shaders et les fichiers
//!   d'options — c'est ce qu'on attend en partageant une instance. `config/`
//!   est proposé décoché : il pèse vite plus que tout le reste et contient la
//!   configuration personnelle de chaque mod.
//! - **Proposé, décoché** : la liste des serveurs (elle peut contenir
//!   l'adresse d'un serveur privé), chaque monde (lourd, et rarement voulu),
//!   et tout le reste.
//!
//! ── Ce qui se reçoit ────────────────────────────────────────────────────────
//! Un pack reçu vient de quelqu'un d'autre, donc tout y est vérifié : chemins
//! relatifs sans `..` ni lecteur, aucune écriture sur les fichiers internes du
//! launcher (un `meta.json` posé par un pack pourrait désigner un exécutable
//! Java arbitraire), téléchargements limités aux domaines des plateformes, et
//! chaque fichier téléchargé comparé à l'empreinte annoncée avant d'être écrit.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::{LazyLock, Mutex};

use serde::{Deserialize, Serialize};
use sha1::{Digest, Sha1};
use sha2::Sha512;

use super::agent_options::{agent_options_read, agent_options_write};
use super::crud::{instance_dir, row_to_instance, user_id, Instance};
use super::mods::sha1_cached;
use super::options::{mc_options_write, McOption};
use super::options_share::{from_text, keep_client, keep_game, to_text};
use crate::db;
use crate::minecraft::mod_files::{is_disabled_jar, is_jar_file};
use crate::state::SharedState;

const USER_AGENT: &str = "YuyuFrame/1.0 (yuyuframe.eu)";

/// Noms de premier niveau jamais proposés (comparés en minuscules). Les noms
/// commençant par un point sont écartés à part : ce sont des caches.
const NEVER_SHARED: &[&str] = &[
    // Fichiers internes du launcher.
    "meta.json",
    "modpack.json",
    "modpack_backup.json",
    // Caches, journaux, diagnostics.
    "logs",
    "crash-reports",
    "debug",
    "downloads",
    "natives",
    "debug-profile.json",
    // Compte et vie privée.
    "usercache.json",
    "usernamecache.json",
    "realms_persistence.json",
    "command_history.txt",
    "essential",
    "servers.dat_old",
    "servers.essential.dat",
    "whitelist.json",
    "banned-players.json",
    "banned-ips.json",
    "ops.json",
];

/// Fichiers d'options à la racine de l'instance.
const SETTINGS_FILES: &[&str] = &["options.txt", "optionsof.txt", "optionsshaders.txt"];

/// Fichiers qu'un pack reçu n'a jamais le droit d'écrire.
const PROTECTED_FILES: &[&str] = &["meta.json", "modpack.json", "modpack_backup.json"];

/// Domaines d'où un pack reçu peut faire télécharger : ceux qu'autorise le
/// format Modrinth, plus le CDN de CurseForge (nos packs l'utilisent pour les
/// mods qui ne sont que là-bas).
const ALLOWED_DOWNLOADS: &[&str] = &[
    "https://cdn.modrinth.com/",
    "https://github.com/",
    "https://raw.githubusercontent.com/",
    "https://gitlab.com/",
    "https://edge.forgecdn.net/",
    "https://media.forgecdn.net/",
    "https://mediafilez.forgecdn.net/",
];

const CURSEFORGE_CDN: &[&str] = &[
    "https://edge.forgecdn.net/",
    "https://media.forgecdn.net/",
    "https://mediafilez.forgecdn.net/",
];

/// Téléchargements simultanés à l'import.
const PARALLEL_DOWNLOADS: usize = 6;

// ── Inventaire ──────────────────────────────────────────────────────────────

#[derive(Serialize, Clone, Copy, PartialEq, Eq, Debug)]
#[serde(rename_all = "lowercase")]
pub enum Group {
    Mods,
    Resourcepacks,
    Shaderpacks,
    Settings,
    Servers,
    Saves,
    Other,
}

impl Group {
    fn selected_by_default(self) -> bool {
        matches!(self, Group::Mods | Group::Resourcepacks | Group::Shaderpacks | Group::Settings)
    }
}

/// D'où le destinataire récupérera un élément.
#[derive(Serialize, Clone, Copy, PartialEq, Eq, Debug)]
#[serde(rename_all = "lowercase")]
pub enum Source {
    Modrinth,
    Curseforge,
    /// Copié dans le pack.
    Embedded,
}

/// Une unité de partage : un fichier de contenu, ou un dossier entier
/// (`config/`, un monde…) qui se coche d'un seul geste.
#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ShareItem {
    /// Chemin relatif à l'instance, séparé par `/` — c'est aussi l'identifiant
    /// que l'interface renvoie pour désigner sa sélection.
    pub path: String,
    pub group: Group,
    pub size: u64,
    pub files: u32,
    pub source: Source,
    pub selected: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ShareScan {
    pub name: String,
    pub mc_version: String,
    pub loader: String,
    pub items: Vec<ShareItem>,
    /// Modrinth n'a pas répondu : les fichiers qu'il aurait reconnus sont
    /// comptés comme embarqués, et l'interface le dit — sinon le pack
    /// gonflerait sans explication.
    pub lookup_failed: bool,
    /// Configuration Java qui partira, déjà filtrée.
    pub jvm: JvmShare,
    /// Arguments JVM qui ne partiront pas (filtre de sécurité).
    pub jvm_rejected: Vec<String>,
    pub jvm_profile: Option<String>,
    /// Nombre d'options du client intégré qui partiraient (0 : jamais lancé).
    pub client_options: u32,
}

/// Élément trouvé sur le disque, avant d'avoir cherché où il se télécharge.
struct Entry {
    path: String,
    abs: PathBuf,
    group: Group,
    size: u64,
    files: u32,
    /// Fichier de contenu qu'une plateforme peut connaître (jar, zip).
    lookup: bool,
}

/// Classe un nom de premier niveau. `None` = jamais proposé.
fn top_level_group(name: &str, is_dir: bool) -> Option<Group> {
    let lower = name.to_lowercase();
    if lower.starts_with('.') || NEVER_SHARED.contains(&lower.as_str()) {
        return None;
    }
    Some(match (lower.as_str(), is_dir) {
        ("mods", true) => Group::Mods,
        ("resourcepacks", true) => Group::Resourcepacks,
        ("shaderpacks", true) => Group::Shaderpacks,
        ("saves", true) => Group::Saves,
        ("config", true) => Group::Settings,
        ("servers.dat", false) => Group::Servers,
        (n, false) if SETTINGS_FILES.contains(&n) => Group::Settings,
        _ => Group::Other,
    })
}

/// Taille et nombre de fichiers d'un dossier, liens symboliques ignorés.
fn dir_stats(dir: &Path) -> (u64, u32) {
    let mut size = 0u64;
    let mut files = 0u32;
    let Ok(entries) = std::fs::read_dir(dir) else { return (0, 0) };
    for entry in entries.flatten() {
        let Ok(ty) = entry.file_type() else { continue };
        if ty.is_symlink() {
            continue;
        }
        if ty.is_dir() {
            let (s, f) = dir_stats(&entry.path());
            size += s;
            files += f;
        } else if let Ok(meta) = entry.metadata() {
            size += meta.len();
            files += 1;
        }
    }
    (size, files)
}

fn entry_for(abs: PathBuf, path: String, group: Group, lookup: bool) -> Option<Entry> {
    let meta = std::fs::symlink_metadata(&abs).ok()?;
    if meta.file_type().is_symlink() {
        return None;
    }
    let (size, files) = if meta.is_dir() { dir_stats(&abs) } else { (meta.len(), 1) };
    if files == 0 {
        return None;
    }
    Some(Entry { path, abs, group, size, files, lookup })
}

/// Tout ce qui peut se partager dans une instance.
fn inventory(dir: &Path) -> Vec<Entry> {
    let mut out = Vec::new();
    let Ok(entries) = std::fs::read_dir(dir) else { return out };
    let mut entries: Vec<_> = entries.flatten().collect();
    entries.sort_by_key(|e| e.file_name().to_string_lossy().to_lowercase());

    for entry in entries {
        let name = entry.file_name().to_string_lossy().to_string();
        let Ok(ty) = entry.file_type() else { continue };
        if ty.is_symlink() {
            continue;
        }
        let Some(group) = top_level_group(&name, ty.is_dir()) else { continue };

        match group {
            // Dossiers de contenu : un élément par fichier, pour qu'on puisse
            // retirer un mod sans retirer les autres.
            Group::Mods | Group::Resourcepacks | Group::Shaderpacks | Group::Saves => {
                let Ok(children) = std::fs::read_dir(entry.path()) else { continue };
                let mut children: Vec<_> = children.flatten().collect();
                children.sort_by_key(|e| e.file_name().to_string_lossy().to_lowercase());
                for child in children {
                    let child_name = child.file_name().to_string_lossy().to_string();
                    if child_name.starts_with('.') {
                        continue;
                    }
                    let Ok(child_ty) = child.file_type() else { continue };
                    let lower = child_name.to_lowercase();
                    let lookup = match group {
                        // Hors des jars, `mods/` ne contient que des restes
                        // d'outils (sauvegardes, index) : rien à partager.
                        Group::Mods => {
                            if !child_ty.is_file() || !is_jar_file(&child_name) {
                                continue;
                            }
                            true
                        }
                        // Les mondes sont des dossiers.
                        Group::Saves => {
                            if !child_ty.is_dir() {
                                continue;
                            }
                            false
                        }
                        // Un pack zippé peut être connu d'une plateforme ; un
                        // pack décompressé ou le réglage d'un shader (`.txt`)
                        // ne l'est jamais.
                        _ => child_ty.is_file() && lower.ends_with(".zip"),
                    };
                    let path = format!("{name}/{child_name}");
                    if let Some(e) = entry_for(child.path(), path, group, lookup) {
                        out.push(e);
                    }
                }
            }
            _ => {
                if let Some(e) = entry_for(entry.path(), name, group, false) {
                    out.push(e);
                }
            }
        }
    }
    out
}

// ── Où se télécharge un fichier ─────────────────────────────────────────────

#[derive(Clone)]
struct Remote {
    source: Source,
    url: String,
    /// Identifiant de version Modrinth, pour le lien de partage.
    modrinth_version: Option<String>,
    /// La version Modrinth publie plusieurs fichiers : le lien doit dire
    /// lequel (début du SHA-1). Sinon, l'identifiant suffit.
    ambiguous: bool,
    sha512: Option<String>,
}

/// Réponses déjà obtenues, par SHA-1 (`None` = connu de personne). Un même
/// fichier ne change pas de réponse pendant une session ; sans ce cache,
/// exporter refaisait toutes les requêtes que l'aperçu venait de faire.
static RESOLVED: LazyLock<Mutex<HashMap<String, Option<Remote>>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

#[derive(Deserialize)]
struct MrVersion {
    id: String,
    files: Vec<MrVersionFile>,
}

#[derive(Deserialize)]
struct MrVersionFile {
    hashes: MrHashes,
    url: String,
    filename: String,
    #[serde(default)]
    primary: bool,
    #[serde(default)]
    size: u64,
}

#[derive(Deserialize)]
struct MrHashes {
    sha1: String,
    #[serde(default)]
    sha512: Option<String>,
}

fn http() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .user_agent(USER_AGENT)
        .timeout(std::time::Duration::from_secs(30))
        .build()
        .map_err(|e| e.to_string())
}

/// Cherche chez Modrinth, puis chez CurseForge pour ce qui reste. Rend vrai
/// si Modrinth n'a pas pu répondre.
async fn resolve(state: &tauri::State<'_, SharedState>, files: &[(String, PathBuf)]) -> bool {
    let unknown: Vec<(String, PathBuf)> = {
        let cache = RESOLVED.lock().unwrap();
        files.iter().filter(|(sha1, _)| !cache.contains_key(sha1)).cloned().collect()
    };
    if unknown.is_empty() {
        return false;
    }

    // Modrinth : une seule requête pour toute la liste.
    let hashes: Vec<&str> = unknown.iter().map(|(h, _)| h.as_str()).collect();
    let modrinth: Option<HashMap<String, MrVersion>> = async {
        let resp = http()
            .ok()?
            .post("https://api.modrinth.com/v2/version_files")
            .json(&serde_json::json!({ "hashes": hashes, "algorithm": "sha1" }))
            .send()
            .await
            .ok()?;
        if !resp.status().is_success() {
            return None;
        }
        resp.json().await.ok()
    }
    .await;
    let lookup_failed = modrinth.is_none();

    let mut found: HashMap<String, Remote> = HashMap::new();
    for (sha1, version) in modrinth.unwrap_or_default() {
        if let Some(file) = version.files.iter().find(|f| f.hashes.sha1 == sha1) {
            found.insert(sha1, Remote {
                source: Source::Modrinth,
                url: file.url.clone(),
                modrinth_version: Some(version.id.clone()),
                ambiguous: version.files.len() > 1,
                sha512: file.hashes.sha512.clone(),
            });
        }
    }

    // CurseForge, par empreinte, pour ce que Modrinth ne connaît pas. Passe
    // par LauncherAPI (la clé de leur API n'est pas dans le launcher) : une
    // panne n'empêche rien, le fichier sera simplement embarqué.
    let rest: Vec<&(String, PathBuf)> = unknown.iter().filter(|(h, _)| !found.contains_key(h)).collect();
    let mut curseforge_ok = rest.is_empty();
    if !rest.is_empty() {
        let paths: Vec<(String, PathBuf)> = rest.iter().map(|(h, p)| (h.clone(), p.clone())).collect();
        let prints: Vec<(String, u32)> = tokio::task::spawn_blocking(move || {
            paths
                .into_iter()
                .filter_map(|(h, p)| std::fs::read(&p).ok().map(|d| (h, crate::commands::curseforge::curseforge_fingerprint(&d))))
                .collect()
        })
        .await
        .unwrap_or_default();
        let body = serde_json::json!({ "fingerprints": prints.iter().map(|(_, f)| *f).collect::<Vec<_>>() });
        if let Ok(value) = crate::commands::curseforge::post_json(state, "/curseforge/fingerprints", body).await {
            curseforge_ok = true;
            let by_print: HashMap<u32, &str> = prints.iter().map(|(h, f)| (*f, h.as_str())).collect();
            let matches = value["data"]["exactMatches"].as_array().cloned().unwrap_or_default();
            for m in matches {
                let file = &m["file"];
                let Some(print) = file["fileFingerprint"].as_u64() else { continue };
                let Some(sha1) = by_print.get(&(print as u32)) else { continue };
                // Sans adresse, l'auteur a refusé la distribution par des tiers :
                // le fichier reste embarqué.
                let Some(url) = file["downloadUrl"].as_str() else { continue };
                if !CURSEFORGE_CDN.iter().any(|p| url.starts_with(p)) {
                    continue;
                }
                found.insert(sha1.to_string(), Remote {
                    source: Source::Curseforge,
                    url: url.to_string(),
                    modrinth_version: None,
                    ambiguous: false,
                    sha512: None,
                });
            }
        }
    }

    // On ne retient « inconnu » que si les deux plateformes ont répondu : une
    // panne ne doit pas laisser une mauvaise réponse en cache.
    let mut cache = RESOLVED.lock().unwrap();
    for (sha1, _) in &unknown {
        match found.remove(sha1) {
            Some(remote) => {
                cache.insert(sha1.clone(), Some(remote));
            }
            None if !lookup_failed && curseforge_ok => {
                cache.insert(sha1.clone(), None);
            }
            None => {}
        }
    }
    lookup_failed
}

fn cached(sha1: &str) -> Option<Remote> {
    RESOLVED.lock().unwrap().get(sha1).cloned().flatten()
}

/// Inventaire + empreintes + recherche. Partagé par l'aperçu et l'export, qui
/// doivent voir exactement la même chose.
struct Scanned {
    instance: Instance,
    entries: Vec<(Entry, Option<String>)>,
    lookup_failed: bool,
    /// Configuration Java déjà filtrée, et ce que le filtre a retiré.
    jvm: JvmShare,
    jvm_rejected: Vec<String>,
    /// Nom de la config JVM reliée, s'il y en a une.
    jvm_profile: Option<String>,
    /// Options du client intégré, déjà filtrées (ni mots de passe ni macros).
    client_options: Vec<McOption>,
}

async fn scan(state: &tauri::State<'_, SharedState>, instance_id: &str) -> Result<Scanned, String> {
    let (instance, profile) = {
        let s = state.read().await;
        let uid = user_id(&s);
        let db = s.db.lock().await;
        let instance = db::instance_get(&db, instance_id, uid)
            .map_err(|e| e.to_string())?
            .map(row_to_instance)
            .ok_or("Instance introuvable")?;
        // Une config supprimée laisse un id orphelin : comme au lancement, on
        // retombe sur les réglages de l'instance.
        let profile = instance
            .jvm_profile_id
            .as_deref()
            .and_then(|id| db::jvm_profile_get(&db, id, uid).ok().flatten());
        (instance, profile)
    };
    let (jvm, jvm_rejected) = sanitize_jvm(effective_jvm(&instance, profile.as_ref()));
    let jvm_profile = profile.map(|p| p.name);
    let dir = instance_dir(instance_id);
    let entries = tokio::task::spawn_blocking(move || {
        inventory(&dir)
            .into_iter()
            .map(|e| {
                let sha1 = e.lookup.then(|| sha1_cached(&e.abs)).filter(|h| !h.is_empty());
                (e, sha1)
            })
            .collect::<Vec<_>>()
    })
    .await
    .map_err(|e| e.to_string())?;

    let to_resolve: Vec<(String, PathBuf)> = entries
        .iter()
        .filter_map(|(e, h)| h.as_ref().map(|h| (h.clone(), e.abs.clone())))
        .collect();
    let lookup_failed = resolve(state, &to_resolve).await;
    // Un fichier illisible vaut « pas d'options » : ne bloque pas le reste.
    let client_options: Vec<McOption> = agent_options_read(instance_id.to_string())
        .await
        .unwrap_or_default()
        .into_iter()
        .filter(|o| keep_client(o, true))
        .collect();
    Ok(Scanned { instance, entries, lookup_failed, jvm, jvm_rejected, jvm_profile, client_options })
}

fn item_of(entry: &Entry, sha1: Option<&String>) -> ShareItem {
    let source = sha1.and_then(|h| cached(h)).map(|r| r.source).unwrap_or(Source::Embedded);
    ShareItem {
        path: entry.path.clone(),
        group: entry.group,
        size: entry.size,
        files: entry.files,
        source,
        selected: selected_by_default(entry),
    }
}

/// Dossier des réglages de mods : décoché d'office. Il pèse vite plus que
/// tout le reste (138 Ko, 8 parties de lien sur une vraie instance) et
/// contient la configuration personnelle de chaque mod — on le coche quand
/// on veut vraiment la transmettre.
const CONFIG_DIR: &str = "config";

fn selected_by_default(entry: &Entry) -> bool {
    entry.group.selected_by_default() && entry.path != CONFIG_DIR
}

#[tauri::command]
pub async fn instance_share_scan(
    state: tauri::State<'_, SharedState>,
    instance_id: String,
) -> Result<ShareScan, String> {
    let scanned = scan(&state, &instance_id).await?;
    let items = scanned.entries.iter().map(|(e, h)| item_of(e, h.as_ref())).collect();
    Ok(ShareScan {
        name: scanned.instance.name,
        mc_version: scanned.instance.mc_version,
        loader: scanned.instance.loader,
        items,
        lookup_failed: scanned.lookup_failed,
        jvm: scanned.jvm,
        jvm_rejected: scanned.jvm_rejected,
        jvm_profile: scanned.jvm_profile,
        client_options: scanned.client_options.len() as u32,
    })
}

// ── Configuration Java ──────────────────────────────────────────────────────
//
// Partagée avec l'instance : RAM, JVM, ramasse-miettes, mode et arguments —
// ce que le lancement emploie réellement, donc la config JVM reliée si
// l'instance en a une (`launch.rs` : elle remplace intégralement le bloc de
// l'instance). Jamais le chemin d'un Java personnalisé : il n'existe que sur
// la machine de l'expéditeur.
//
// **Les arguments passent par une liste blanche**, à l'envoi comme à la
// réception. Un argument JVM n'est pas un réglage anodin : `-javaagent:`,
// `-XX:OnOutOfMemoryError=…` ou `-Djava.library.path=` (pointé vers une DLL
// glissée dans le pack) suffisent à faire exécuter n'importe quoi par la
// machine qui importe. Ne passe que du réglage — tailles mémoire, drapeaux
// `-XX:` sans fichier ni commande, propriétés `-D` sans chemin — et ce qui
// est écarté est montré, des deux côtés. À l'envoi, le filtre protège aussi
// l'expéditeur : un `-XX:HeapDumpPath=C:\Users\<nom>\…` ne part pas.

/// Configuration Java telle qu'elle voyage.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct JvmShare {
    pub ram_mb: u32,
    /// auto | temurin | openj9 | graal
    pub vendor: String,
    pub gc_policy: String,
    /// append | replace — voir `merge_jvm_args`.
    pub args_mode: String,
    pub args: Vec<String>,
}

const JVM_VENDORS: &[&str] = &["auto", "temurin", "openj9", "graal"];
const RAM_RANGE: std::ops::RangeInclusive<u32> = 512..=65_536;

/// Morceaux de nom de drapeau `-XX:` refusés : tout ce qui lance une commande,
/// lit ou écrit un fichier, ou charge du code. Pas « options » : il bloquait
/// `-XX:+UnlockExperimentalVMOptions`, présent dans presque tous les jeux de
/// drapeaux, et `VMOptionsFile` tombe déjà sur « file ».
const XX_BLOCKED: &[&str] = &[
    "onerror", "onoutofmemory", "file", "path", "log", "dump", "flags", "command",
    "library", "agent", "recording", "archive", "exec", "script",
];

/// Préfixes de propriétés `-D` refusés : celles de la JVM et de LWJGL règlent
/// le chargement des classes et des bibliothèques natives.
const D_BLOCKED_PREFIXES: &[&str] = &["java.", "jdk.", "sun.", "javax.", "com.sun.", "org.lwjgl."];
/// Morceaux de nom de propriété `-D` refusés (même raison que `XX_BLOCKED`).
const D_BLOCKED: &[&str] = &[
    "path", "file", "dir", "home", "class", "agent", "library", "loader", "security",
    "ssl", "config", "proxy", "url", "host",
];

fn plain_value(v: &str) -> bool {
    !v.is_empty() && v.len() <= 64 && v.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | ',' | '_' | '+' | '-'))
}

fn plain_name(v: &str) -> bool {
    !v.is_empty() && v.len() <= 80 && v.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'))
}

/// `4G`, `512m`, `1024` — une taille mémoire de la JVM.
fn memory_size(v: &str) -> bool {
    let digits = v.trim_end_matches(['k', 'K', 'm', 'M', 'g', 'G', 't', 'T']);
    !digits.is_empty() && digits.len() + 1 >= v.len() && digits.chars().all(|c| c.is_ascii_digit())
}

/// Un argument JVM peut-il venir de quelqu'un d'autre ?
fn jvm_arg_allowed(arg: &str) -> bool {
    if let Some(rest) = arg.strip_prefix("-XX:") {
        let (name, value) = match rest.strip_prefix(['+', '-']) {
            Some(flag) => (flag, None),
            None => match rest.split_once('=') {
                Some((n, v)) => (n, Some(v)),
                None => return false,
            },
        };
        let lower = name.to_ascii_lowercase();
        return name.chars().all(|c| c.is_ascii_alphanumeric())
            && !name.is_empty()
            && value.map_or(true, plain_value)
            && !XX_BLOCKED.iter().any(|b| lower.contains(b));
    }
    if let Some(rest) = arg.strip_prefix("-D") {
        let (key, value) = match rest.split_once('=') {
            Some((k, v)) => (k, Some(v)),
            None => (rest, None),
        };
        let lower = key.to_ascii_lowercase();
        return plain_name(key)
            && value.map_or(true, plain_value)
            && !D_BLOCKED_PREFIXES.iter().any(|p| lower.starts_with(p))
            && !D_BLOCKED.iter().any(|b| lower.contains(b));
    }
    for prefix in ["-Xmx", "-Xms", "-Xmn", "-Xss"] {
        if let Some(size) = arg.strip_prefix(prefix) {
            return memory_size(size);
        }
    }
    for prefix in ["-Xgcpolicy:", "-Xtune:"] {
        if let Some(word) = arg.strip_prefix(prefix) {
            return word.chars().all(|c| c.is_ascii_alphanumeric()) && !word.is_empty();
        }
    }
    matches!(arg, "-Xdisableexplicitgc" | "-Xnoclassgc")
}

/// Ramène une configuration à ce qui peut voyager. Rend aussi les arguments
/// écartés, pour les montrer.
fn sanitize_jvm(raw: JvmShare) -> (JvmShare, Vec<String>) {
    let (args, rejected): (Vec<String>, Vec<String>) = raw.args.into_iter().partition(|a| jvm_arg_allowed(a));
    let clean = JvmShare {
        ram_mb: raw.ram_mb.clamp(*RAM_RANGE.start(), *RAM_RANGE.end()),
        vendor: if JVM_VENDORS.contains(&raw.vendor.as_str()) { raw.vendor } else { "auto".into() },
        gc_policy: if raw.gc_policy.len() <= 32 && raw.gc_policy.chars().all(|c| c.is_ascii_alphanumeric()) && !raw.gc_policy.is_empty() {
            raw.gc_policy
        } else {
            "auto".into()
        },
        args_mode: if raw.args_mode == "replace" { "replace".into() } else { "append".into() },
        args,
    };
    (clean, rejected)
}

/// La configuration que le lancement emploierait (voir `launch.rs`).
fn effective_jvm(instance: &Instance, profile: Option<&db::JvmProfileRow>) -> JvmShare {
    use crate::minecraft::launcher::parse_user_jvm_args;
    match profile {
        Some(p) => JvmShare {
            ram_mb: p.ram_mb.unwrap_or(instance.ram_mb),
            vendor: p.jvm_vendor.clone(),
            gc_policy: p.gc_policy.clone(),
            args_mode: p.args_mode.clone(),
            args: parse_user_jvm_args(&p.all_args()),
        },
        None => JvmShare {
            ram_mb: instance.ram_mb,
            vendor: instance.jvm_vendor.clone(),
            gc_policy: instance.gc_policy.clone(),
            args_mode: instance.jvm_args_mode.clone(),
            args: parse_user_jvm_args(&instance.jvm_extra_args),
        },
    }
}

/// Fichier propre à YuyuFrame à la racine du `.mrpack` : le format Modrinth
/// n'a rien pour la JVM, et les autres launchers ignorent ce qu'ils ne
/// connaissent pas à cet endroit.
const YUYU_FILE: &str = "yuyuframe.json";

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct YuyuExtras {
    format_version: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    jvm: Option<JvmShare>,
    /// Options du client intégré (`agent/module-config/<instance>.properties`) :
    /// hors du dossier de l'instance, donc hors d'`overrides/`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    client: Option<Vec<(String, String)>>,
}

/// `options.txt` passe par les mêmes règles que le partage d'options
/// (`options_share.rs`) : sans `lastServer`, sans ligne piégée.
const OPTIONS_FILE: &str = "options.txt";

fn shared_options_txt(abs: &Path) -> Option<String> {
    let content = std::fs::read_to_string(abs).ok()?;
    let options: Vec<McOption> = from_text(&content, ':').into_iter().filter(keep_game).collect();
    Some(to_text(&options, ':') + "\n")
}

// ── Version du loader ───────────────────────────────────────────────────────

/// Clé du loader dans les dépendances d'un `.mrpack`.
fn loader_dependency(loader: &str) -> Option<&'static str> {
    match loader {
        "fabric" => Some("fabric-loader"),
        "quilt" => Some("quilt-loader"),
        "forge" => Some("forge"),
        "neoforge" => Some("neoforge"),
        _ => None,
    }
}

/// Le loader d'un pack, d'après ses dépendances.
fn loader_from_dependencies(deps: &BTreeMap<String, String>) -> (String, String) {
    for (loader, key) in [("quilt", "quilt-loader"), ("fabric", "fabric-loader"), ("neoforge", "neoforge"), ("forge", "forge")] {
        if let Some(version) = deps.get(key) {
            return (loader.to_string(), version.clone());
        }
    }
    ("vanilla".to_string(), String::new())
}

/// La version épinglée, sinon celle que le launcher installerait — la
/// recommandée, exactement comme au lancement. Le format l'exige, et c'est ce
/// qui garantit au destinataire le même loader que l'expéditeur.
async fn loader_version_of(instance: &Instance) -> Result<String, String> {
    if loader_dependency(&instance.loader).is_none() {
        return Ok(String::new());
    }
    if !instance.loader_version.is_empty() {
        return Ok(instance.loader_version.clone());
    }
    let versions = crate::commands::system::versions::loader_versions(instance.loader.clone(), instance.mc_version.clone()).await?;
    versions
        .into_iter()
        .find(|v| v.recommended)
        .map(|v| v.version)
        .ok_or_else(|| "Impossible de trouver la version du loader de cette instance (connexion à Internet ?)".to_string())
}

// ── Export en fichier ───────────────────────────────────────────────────────

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct MrIndex {
    format_version: u32,
    game: String,
    version_id: String,
    name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    summary: Option<String>,
    files: Vec<MrIndexFile>,
    dependencies: BTreeMap<String, String>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct MrIndexFile {
    path: String,
    hashes: BTreeMap<String, String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    env: Option<MrEnv>,
    downloads: Vec<String>,
    #[serde(default)]
    file_size: u64,
}

#[derive(Serialize, Deserialize)]
struct MrEnv {
    #[serde(default)]
    client: String,
    #[serde(default)]
    server: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ShareExport {
    /// Fichiers que le destinataire téléchargera lui-même.
    pub linked: u32,
    /// Fichiers copiés dans le pack.
    pub embedded: u32,
    /// Poids du pack écrit.
    pub size: u64,
}

fn hash_file(path: &Path) -> std::io::Result<(String, String)> {
    let mut file = std::fs::File::open(path)?;
    let mut sha1 = Sha1::new();
    let mut sha512 = Sha512::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        sha1.update(&buf[..n]);
        sha512.update(&buf[..n]);
    }
    Ok((format!("{:x}", sha1.finalize()), format!("{:x}", sha512.finalize())))
}

/// Tous les fichiers d'un élément, en chemins relatifs à l'instance.
fn files_of(abs: &Path, rel: &str, out: &mut Vec<(PathBuf, String)>) {
    let Ok(meta) = std::fs::symlink_metadata(abs) else { return };
    if meta.file_type().is_symlink() {
        return;
    }
    if meta.is_file() {
        out.push((abs.to_path_buf(), rel.to_string()));
        return;
    }
    let Ok(entries) = std::fs::read_dir(abs) else { return };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        files_of(&entry.path(), &format!("{rel}/{name}"), out);
    }
}

/// Les éléments choisis. Un chemin envoyé par l'interface ne compte que s'il
/// figure dans l'inventaire : on n'exporte jamais un chemin arbitraire.
fn selection<'a>(scanned: &'a Scanned, paths: &[String]) -> Vec<&'a (Entry, Option<String>)> {
    let wanted: HashSet<&str> = paths.iter().map(String::as_str).collect();
    scanned.entries.iter().filter(|(e, _)| wanted.contains(e.path.as_str())).collect()
}

#[tauri::command]
pub async fn instance_share_export(
    state: tauri::State<'_, SharedState>,
    instance_id: String,
    paths: Vec<String>,
    include_jvm: bool,
    include_client: bool,
    file_path: String,
) -> Result<ShareExport, String> {
    let scanned = scan(&state, &instance_id).await?;
    let client = Some(scanned.client_options.iter().map(|o| (o.key.clone(), o.value.clone())).collect::<Vec<_>>())
        .filter(|c| include_client && !c.is_empty());
    let jvm = include_jvm.then(|| scanned.jvm.clone());
    let extras = (jvm.is_some() || client.is_some()).then_some(YuyuExtras { format_version: 1, jvm, client });
    let loader_version = loader_version_of(&scanned.instance).await?;
    let chosen = selection(&scanned, &paths);

    let mut linked: Vec<(PathBuf, String, Remote)> = Vec::new();
    let mut embedded: Vec<(PathBuf, String)> = Vec::new();
    // Fichiers réécrits avant de partir (`options.txt` filtré).
    let mut generated: Vec<(String, Vec<u8>)> = Vec::new();
    for (entry, sha1) in chosen {
        match sha1.as_deref().and_then(cached) {
            Some(remote) => linked.push((entry.abs.clone(), entry.path.clone(), remote)),
            None if entry.path == OPTIONS_FILE => {
                if let Some(text) = shared_options_txt(&entry.abs) {
                    generated.push((entry.path.clone(), text.into_bytes()));
                }
            }
            None => files_of(&entry.abs, &entry.path, &mut embedded),
        }
    }

    let mut dependencies = BTreeMap::new();
    dependencies.insert("minecraft".to_string(), scanned.instance.mc_version.clone());
    if let Some(key) = loader_dependency(&scanned.instance.loader) {
        dependencies.insert(key.to_string(), loader_version);
    }
    let name = scanned.instance.name.clone();
    let summary = Some(scanned.instance.description.clone()).filter(|s| !s.trim().is_empty());
    let target = PathBuf::from(&file_path);

    tokio::task::spawn_blocking(move || -> Result<ShareExport, String> {
        let mut files = Vec::with_capacity(linked.len());
        for (abs, path, remote) in &linked {
            let (sha1, sha512) = hash_file(abs).map_err(|e| format!("{path} : {e}"))?;
            let size = std::fs::metadata(abs).map(|m| m.len()).unwrap_or(0);
            let mut hashes = BTreeMap::new();
            hashes.insert("sha1".to_string(), sha1);
            hashes.insert("sha512".to_string(), remote.sha512.clone().unwrap_or(sha512));
            files.push(MrIndexFile {
                path: path.clone(),
                hashes,
                env: Some(MrEnv { client: "required".into(), server: "optional".into() }),
                downloads: vec![remote.url.clone()],
                file_size: size,
            });
        }
        let index = MrIndex {
            format_version: 1,
            game: "minecraft".into(),
            version_id: chrono_stamp(),
            name,
            summary,
            files,
            dependencies,
        };

        // Écrit à côté puis renommé : un export interrompu ne laisse pas un
        // pack tronqué sous le nom choisi.
        let partial = target.with_extension("mrpack.part");
        let write = || -> Result<(), String> {
            let out = std::fs::File::create(&partial).map_err(|e| e.to_string())?;
            let mut zip = zip::ZipWriter::new(out);
            let options = zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Deflated)
                .large_file(true);
            zip.start_file("modrinth.index.json", options).map_err(|e| e.to_string())?;
            zip.write_all(&serde_json::to_vec_pretty(&index).map_err(|e| e.to_string())?)
                .map_err(|e| e.to_string())?;
            if let Some(extras) = &extras {
                zip.start_file(YUYU_FILE, options).map_err(|e| e.to_string())?;
                zip.write_all(&serde_json::to_vec_pretty(extras).map_err(|e| e.to_string())?)
                    .map_err(|e| e.to_string())?;
            }
            for (abs, rel) in &embedded {
                zip.start_file(format!("overrides/{rel}"), options).map_err(|e| e.to_string())?;
                let mut file = std::fs::File::open(abs).map_err(|e| format!("{rel} : {e}"))?;
                std::io::copy(&mut file, &mut zip).map_err(|e| format!("{rel} : {e}"))?;
            }
            for (rel, content) in &generated {
                zip.start_file(format!("overrides/{rel}"), options).map_err(|e| e.to_string())?;
                zip.write_all(content).map_err(|e| format!("{rel} : {e}"))?;
            }
            zip.finish().map_err(|e| e.to_string())?;
            Ok(())
        };
        if let Err(e) = write() {
            let _ = std::fs::remove_file(&partial);
            return Err(e);
        }
        std::fs::rename(&partial, &target).map_err(|e| e.to_string())?;
        Ok(ShareExport {
            linked: linked.len() as u32,
            embedded: (embedded.len() + generated.len()) as u32,
            size: std::fs::metadata(&target).map(|m| m.len()).unwrap_or(0),
        })
    })
    .await
    .map_err(|e| e.to_string())?
}

/// Identifiant de version du pack : sa date d'export, lisible.
fn chrono_stamp() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    format!("share-{secs}")
}

// ── Lien de partage ─────────────────────────────────────────────────────────

// Le lien porte tout ce que porte le fichier, compressé par `crate::share_link`
// (et découpé en parties s'il le faut). Les données sont une suite
// d'enregistrements « étiquette (1 octet), longueur (LEB128), contenu » :
//
//   H  en-tête, une information par ligne : nom, version de Minecraft,
//      loader, version du loader, jetons des fichiers Modrinth (collés)
//   J  configuration Java : « RAM JVM GC mode », puis les arguments
//   O  `options.txt`, en lignes `clé:valeur` filtrées (`options_share.rs`)
//   C  options du client intégré, en lignes `clé=valeur` filtrées
//   S  serveurs : « nom<TAB>adresse » par ligne (ni icône ni rien d'autre)
//   R  fichier à télécharger hors Modrinth (CurseForge) : chemin, adresse,
//      SHA-1, séparés par NUL
//   F  fichier copié dans le lien : chemin, NUL, contenu
//
// Les options et les serveurs voyagent en texte, sous la forme du
// dictionnaire de compression, et sont fusionnés à l'arrivée — exactement
// comme le partage d'options. Seuls les fichiers `F` pèsent vraiment : c'est
// eux que l'interface nomme quand un lien dépasse `MAX_PARTS`.
//
// Un jeton = la famille (`m`, `M` pour un mod désactivé, `r`, `s`), les 8
// caractères de l'identifiant de version Modrinth, puis 4 chiffres hexa du
// SHA-1 **seulement** si cette version publie plusieurs fichiers. Pas
// d'ambiguïté à la lecture : aucune famille n'est un chiffre hexa.

const REC_HEADER: u8 = b'H';
const REC_JVM: u8 = b'J';
const REC_OPTIONS: u8 = b'O';
const REC_CLIENT: u8 = b'C';
const REC_SERVERS: u8 = b'S';
const REC_REMOTE: u8 = b'R';
const REC_FILE: u8 = b'F';

fn push_record(out: &mut Vec<u8>, tag: u8, body: &[u8]) {
    out.push(tag);
    let mut n = body.len();
    loop {
        let byte = (n & 0x7F) as u8;
        n >>= 7;
        if n == 0 {
            out.push(byte);
            break;
        }
        out.push(byte | 0x80);
    }
    out.extend_from_slice(body);
}

fn records(mut data: &[u8]) -> Option<Vec<(u8, &[u8])>> {
    let mut out = Vec::new();
    while let Some((&tag, rest)) = data.split_first() {
        let (mut len, mut shift, mut pos) = (0usize, 0u32, 0usize);
        loop {
            let byte = *rest.get(pos)?;
            pos += 1;
            len |= ((byte & 0x7F) as usize).checked_shl(shift)?;
            if byte & 0x80 == 0 {
                break;
            }
            shift += 7;
            if shift > 28 {
                return None;
            }
        }
        let body = rest.get(pos..pos + len)?;
        out.push((tag, body));
        data = &rest[pos + len..];
    }
    Some(out)
}

/// Une valeur sur une ligne : ni retour à la ligne ni tabulation (séparateurs).
fn one_line(s: &str) -> String {
    s.replace(['\n', '\r', '\t', '\0'], " ")
}

const LINK_KIND: &str = "instance";
/// Longueur d'un identifiant de version Modrinth.
const VERSION_ID_LEN: usize = 8;
/// Début du SHA-1, quand une version publie plusieurs fichiers (un jar
/// Fabric et un jar Forge publiés ensemble, par exemple).
const HASH_PREFIX_LEN: usize = 4;

/// Famille d'un fichier dans le lien. Majuscule = mod désactivé.
fn kind_of(group: Group, disabled: bool) -> Option<char> {
    match (group, disabled) {
        (Group::Mods, false) => Some('m'),
        (Group::Mods, true) => Some('M'),
        (Group::Resourcepacks, _) => Some('r'),
        (Group::Shaderpacks, _) => Some('s'),
        _ => None,
    }
}

fn dir_of(kind: char) -> Option<(&'static str, bool)> {
    match kind {
        'm' => Some(("mods", false)),
        'M' => Some(("mods", true)),
        'r' => Some(("resourcepacks", false)),
        's' => Some(("shaderpacks", false)),
        _ => None,
    }
}

fn valid_version_id(id: &str) -> bool {
    id.len() == VERSION_ID_LEN && id.chars().all(|c| c.is_ascii_alphanumeric())
}

struct LinkToken {
    kind: char,
    version_id: String,
    /// Vide quand la version n'a qu'un fichier.
    hash_prefix: String,
}

fn encode_token(kind: char, version_id: &str, sha1: &str, ambiguous: bool) -> Option<String> {
    if !valid_version_id(version_id) || sha1.len() < HASH_PREFIX_LEN {
        return None;
    }
    let prefix = if ambiguous { &sha1[..HASH_PREFIX_LEN] } else { "" };
    Some(format!("{kind}{version_id}{}", prefix.to_ascii_lowercase()))
}

fn decode_tokens(raw: &str) -> Result<Vec<LinkToken>, String> {
    let broken = || "Lien de partage abîmé".to_string();
    if !raw.is_ascii() {
        return Err(broken());
    }
    let bytes = raw.as_bytes();
    let is_hex = |b: &u8| b.is_ascii_digit() || (b'a'..=b'f').contains(b);
    let mut tokens = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
        let kind = bytes[i] as char;
        let id = raw.get(i + 1..i + 1 + VERSION_ID_LEN).ok_or_else(broken)?;
        if dir_of(kind).is_none() || !valid_version_id(id) {
            return Err(broken());
        }
        i += 1 + VERSION_ID_LEN;
        let prefix = match bytes.get(i..i + HASH_PREFIX_LEN) {
            Some(p) if p.iter().all(is_hex) => {
                i += HASH_PREFIX_LEN;
                std::str::from_utf8(p).map_err(|_| broken())?.to_string()
            }
            _ => String::new(),
        };
        tokens.push(LinkToken { kind, version_id: id.to_string(), hash_prefix: prefix });
    }
    Ok(tokens)
}

#[tauri::command]
pub async fn instance_share_link(
    state: tauri::State<'_, SharedState>,
    instance_id: String,
    paths: Vec<String>,
    include_jvm: bool,
    include_client: bool,
) -> Result<Vec<String>, String> {
    let scanned = scan(&state, &instance_id).await?;
    let loader_version = loader_version_of(&scanned.instance).await?;
    let dir = instance_dir(&instance_id);

    let mut tokens = String::new();
    let mut body: Vec<u8> = Vec::new();
    // Taille de ce qui est copié dans le lien, par élément : si le lien
    // déborde, on dit lesquels décocher.
    let mut heavy: Vec<(String, u64)> = Vec::new();

    for (entry, sha1) in selection(&scanned, &paths) {
        let name = entry.path.rsplit('/').next().unwrap_or_default();
        let remote = sha1.as_deref().and_then(cached);
        // Modrinth : un jeton de quelques caractères.
        if let (Some(r), Some(h)) = (&remote, sha1.as_deref()) {
            let token = kind_of(entry.group, is_disabled_jar(name))
                .zip(r.modrinth_version.as_deref())
                .and_then(|(kind, version)| encode_token(kind, version, h, r.ambiguous));
            if let Some(t) = token {
                tokens.push_str(&t);
                continue;
            }
            // Ailleurs (CurseForge) : l'adresse et l'empreinte.
            let record = format!("{}\0{}\0{}", entry.path, r.url, h);
            push_record(&mut body, REC_REMOTE, record.as_bytes());
            continue;
        }
        if entry.path == OPTIONS_FILE {
            if let Some(text) = shared_options_txt(&entry.abs) {
                push_record(&mut body, REC_OPTIONS, text.trim_end().as_bytes());
            }
            continue;
        }
        if entry.group == Group::Servers {
            let servers = crate::minecraft::launcher::read_saved_servers(&dir).unwrap_or_default();
            let text = servers
                .iter()
                .map(|s| format!("{}\t{}", one_line(&s.name), one_line(&s.ip)))
                .collect::<Vec<_>>()
                .join("\n");
            if !text.is_empty() {
                push_record(&mut body, REC_SERVERS, text.as_bytes());
            }
            continue;
        }
        // Le reste est copié tel quel.
        let mut files = Vec::new();
        files_of(&entry.abs, &entry.path, &mut files);
        let mut size = 0u64;
        for (abs, rel) in files {
            let content = std::fs::read(&abs).map_err(|e| format!("{rel} : {e}"))?;
            size += content.len() as u64;
            let mut record = rel.into_bytes();
            record.push(0);
            record.extend_from_slice(&content);
            push_record(&mut body, REC_FILE, &record);
        }
        heavy.push((entry.path.clone(), size));
    }

    if include_jvm {
        let jvm = &scanned.jvm;
        let text = format!("{} {} {} {}\n{}", jvm.ram_mb, jvm.vendor, jvm.gc_policy, jvm.args_mode, jvm.args.join(" "));
        push_record(&mut body, REC_JVM, text.as_bytes());
    }
    if include_client && !scanned.client_options.is_empty() {
        push_record(&mut body, REC_CLIENT, to_text(&scanned.client_options, '=').as_bytes());
    }

    let header = [one_line(&scanned.instance.name), scanned.instance.mc_version.clone(), scanned.instance.loader.clone(), loader_version, tokens]
        .join("\n");
    let mut data = Vec::new();
    push_record(&mut data, REC_HEADER, header.as_bytes());
    data.extend_from_slice(&body);

    crate::share_link::build(LINK_KIND, &data).map_err(|e| match e {
        crate::share_link::LinkError::TooLarge { parts } => {
            heavy.sort_by(|a, b| b.1.cmp(&a.1));
            let names: Vec<&str> = heavy.iter().take(3).map(|(p, _)| p.as_str()).collect();
            if names.is_empty() {
                String::from(crate::share_link::LinkError::TooLarge { parts })
            } else {
                format!(
                    "Trop volumineux pour un lien ({parts} parties, {} au plus). Les éléments copiés les plus lourds : {}. Décoche-les, ou partage le fichier.",
                    crate::share_link::MAX_PARTS,
                    names.join(", ")
                )
            }
        }
        other => String::from(other),
    })
}

// ── Lecture d'un pack reçu ──────────────────────────────────────────────────

/// Chemin relatif sûr : ni `..`, ni lecteur, ni chemin absolu, ni fichier
/// interne du launcher. Rend le chemin normalisé avec des `/`. Sert aussi à
/// l'import de modpacks (`modpack.rs`), qui lit des archives tout aussi
/// étrangères.
pub(super) fn safe_relative(path: &str) -> Option<String> {
    if path.starts_with('/') || path.starts_with('\\') {
        return None;
    }
    let parts: Vec<&str> = path.split(['/', '\\']).filter(|p| !p.is_empty() && *p != ".").collect();
    if parts.is_empty() || parts.iter().any(|p| *p == ".." || p.contains(':')) {
        return None;
    }
    if parts.len() == 1 && PROTECTED_FILES.contains(&parts[0].to_lowercase().as_str()) {
        return None;
    }
    Some(parts.join("/"))
}

pub(super) fn join_relative(base: &Path, rel: &str) -> PathBuf {
    rel.split('/').fold(base.to_path_buf(), |acc, part| acc.join(part))
}

fn allowed_download(url: &str) -> bool {
    ALLOWED_DOWNLOADS.iter().any(|p| url.starts_with(p))
}

#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum ShareSource {
    File { path: String },
    Link { link: String },
}

struct PackFile {
    path: String,
    url: String,
    sha1: Option<String>,
    sha512: Option<String>,
    size: u64,
}

struct Pack {
    name: String,
    summary: String,
    mc_version: String,
    loader: String,
    loader_version: String,
    files: Vec<PackFile>,
    /// (nom dans l'archive, chemin dans l'instance, taille).
    overrides: Vec<(String, String, u64)>,
    archive: Option<PathBuf>,
    /// Fichiers ignorés : chemin refusé, adresse hors des plateformes, version
    /// introuvable.
    rejected: Vec<String>,
    /// Configuration Java jointe, déjà filtrée, et les arguments écartés.
    jvm: Option<JvmShare>,
    jvm_rejected: Vec<String>,
    /// `options.txt` reçu en réglages (lien), fusionné à l'arrivée.
    options: Vec<McOption>,
    /// Options du client intégré, filtrées.
    client: Vec<McOption>,
    /// Serveurs (nom, adresse), ajoutés à la liste s'ils n'y sont pas.
    servers: Vec<(String, String)>,
    /// Fichiers copiés dans un lien : (chemin vérifié, contenu).
    inline: Vec<(String, Vec<u8>)>,
}

/// Options du client reçues : mêmes règles que le partage d'options.
fn received_client(pairs: Vec<(String, String)>) -> Vec<McOption> {
    pairs
        .into_iter()
        .map(|(key, value)| McOption { key, value })
        .filter(|o| keep_client(o, true))
        .collect()
}

/// Filtre une configuration reçue ; rend aussi ce qui a été écarté.
fn received_jvm(raw: Option<JvmShare>) -> (Option<JvmShare>, Vec<String>) {
    match raw {
        Some(raw) => {
            let (clean, rejected) = sanitize_jvm(raw);
            (Some(clean), rejected)
        }
        None => (None, Vec::new()),
    }
}

fn read_mrpack(path: &Path) -> Result<Pack, String> {
    let file = std::fs::File::open(path).map_err(|e| format!("Lecture du fichier : {e}"))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|_| "Ce fichier n'est pas un pack valide".to_string())?;
    let index: MrIndex = {
        let mut entry = archive
            .by_name("modrinth.index.json")
            .map_err(|_| "Ce fichier n'est pas un pack Modrinth (.mrpack)".to_string())?;
        let mut content = String::new();
        entry.read_to_string(&mut content).map_err(|e| e.to_string())?;
        serde_json::from_str(&content).map_err(|e| format!("Pack illisible : {e}"))?
    };
    if index.game != "minecraft" {
        return Err("Ce pack n'est pas pour Minecraft".into());
    }
    let mc_version = index
        .dependencies
        .get("minecraft")
        .cloned()
        .ok_or("Ce pack n'indique pas sa version de Minecraft")?;
    let (loader, loader_version) = loader_from_dependencies(&index.dependencies);

    // Fichier propre à YuyuFrame, facultatif : un pack venu d'ailleurs n'en a
    // pas, et un fichier illisible vaut « pas de configuration Java ».
    let extras: Option<YuyuExtras> = archive.by_name(YUYU_FILE).ok().and_then(|mut entry| {
        let mut content = String::new();
        entry.read_to_string(&mut content).ok()?;
        serde_json::from_str(&content).ok()
    });
    let (extras_jvm, extras_client) = extras.map(|e| (e.jvm, e.client)).unwrap_or_default();
    let (jvm, jvm_rejected) = received_jvm(extras_jvm);
    let client = received_client(extras_client.unwrap_or_default());

    let mut rejected = Vec::new();
    let mut files = Vec::new();
    for f in index.files {
        if f.env.as_ref().is_some_and(|e| e.client == "unsupported") {
            continue;
        }
        let Some(path) = safe_relative(&f.path) else {
            rejected.push(f.path);
            continue;
        };
        let Some(url) = f.downloads.iter().find(|u| allowed_download(u)) else {
            rejected.push(path);
            continue;
        };
        files.push(PackFile {
            path,
            url: url.clone(),
            sha1: f.hashes.get("sha1").map(|h| h.to_lowercase()),
            sha512: f.hashes.get("sha512").map(|h| h.to_lowercase()),
            size: f.file_size,
        });
    }

    // `client-overrides/` après `overrides/` : il a la priorité (format Modrinth).
    let mut overrides: Vec<(String, String, u64)> = Vec::new();
    for prefix in ["overrides/", "client-overrides/"] {
        for i in 0..archive.len() {
            let Ok(entry) = archive.by_index(i) else { continue };
            let name = entry.name().to_string();
            if entry.is_dir() {
                continue;
            }
            let Some(rel) = name.strip_prefix(prefix) else { continue };
            match safe_relative(rel) {
                Some(rel) => {
                    overrides.retain(|(_, r, _)| r != &rel);
                    overrides.push((name.clone(), rel, entry.size()));
                }
                None => rejected.push(name.clone()),
            }
        }
    }

    Ok(Pack {
        name: index.name,
        summary: index.summary.unwrap_or_default(),
        mc_version,
        loader,
        loader_version,
        files,
        overrides,
        archive: Some(path.to_path_buf()),
        rejected,
        jvm,
        jvm_rejected,
        // `options.txt` et la liste des serveurs voyagent dans `overrides/`,
        // comme le format Modrinth le veut (les autres launchers les lisent).
        options: Vec::new(),
        client,
        servers: Vec::new(),
        inline: Vec::new(),
    })
}

/// Contenu d'un enregistrement texte du lien.
fn record_text(body: &[u8]) -> Result<&str, String> {
    std::str::from_utf8(body).map_err(|_| "Lien de partage abîmé".to_string())
}

async fn read_link(link: &str) -> Result<Pack, String> {
    let broken = || "Lien de partage abîmé".to_string();
    let data = crate::share_link::read(link, LINK_KIND)?;
    let records = records(&data).ok_or_else(broken)?;

    let header = records.iter().find(|(tag, _)| *tag == REC_HEADER).ok_or_else(broken)?;
    let lines: Vec<&str> = record_text(header.1)?.split('\n').collect();
    let [name, mc_version, loader, loader_version, tokens] = lines.as_slice() else {
        return Err(broken());
    };
    if mc_version.is_empty() || !matches!(*loader, "vanilla" | "fabric" | "quilt" | "forge" | "neoforge") {
        return Err(broken());
    }
    let (name, mc_version, loader, loader_version) =
        (name.to_string(), mc_version.to_string(), loader.to_string(), loader_version.to_string());
    let tokens = decode_tokens(tokens)?;

    let mut raw_jvm = None;
    let mut options = Vec::new();
    let mut client = Vec::new();
    let mut servers = Vec::new();
    let mut inline = Vec::new();
    let mut remote_files = Vec::new();
    let mut rejected = Vec::new();
    for (tag, body) in &records {
        match *tag {
            // « RAM JVM GC mode », puis les arguments.
            REC_JVM => {
                let text = record_text(body)?;
                let (head, args) = text.split_once('\n').unwrap_or((text, ""));
                let mut words = head.split(' ');
                raw_jvm = (|| {
                    Some(JvmShare {
                        ram_mb: words.next()?.parse().ok()?,
                        vendor: words.next()?.to_string(),
                        gc_policy: words.next()?.to_string(),
                        args_mode: words.next()?.to_string(),
                        args: args.split_whitespace().map(str::to_string).collect(),
                    })
                })();
            }
            REC_OPTIONS => options = from_text(record_text(body)?, ':').into_iter().filter(keep_game).collect(),
            REC_CLIENT => {
                client = from_text(record_text(body)?, '=').into_iter().filter(|o| keep_client(o, true)).collect()
            }
            REC_SERVERS => {
                servers = record_text(body)?
                    .lines()
                    .filter_map(|l| l.split_once('\t'))
                    .filter(|(name, ip)| !ip.is_empty() && name.len() <= 200 && ip.len() <= 255)
                    .map(|(name, ip)| (name.to_string(), ip.to_string()))
                    .collect()
            }
            REC_REMOTE => {
                let mut fields = record_text(body)?.split('\0');
                let (Some(path), Some(url), Some(sha1)) = (fields.next(), fields.next(), fields.next()) else {
                    return Err(broken());
                };
                match safe_relative(path) {
                    Some(path) if allowed_download(url) => remote_files.push(PackFile {
                        path,
                        url: url.to_string(),
                        sha1: Some(sha1.to_lowercase()),
                        sha512: None,
                        size: 0,
                    }),
                    _ => rejected.push(path.to_string()),
                }
            }
            REC_FILE => {
                let split = body.iter().position(|b| *b == 0).ok_or_else(broken)?;
                let path = std::str::from_utf8(&body[..split]).map_err(|_| broken())?;
                match safe_relative(path) {
                    Some(path) => inline.push((path, body[split + 1..].to_vec())),
                    None => rejected.push(path.to_string()),
                }
            }
            // Une étiquette inconnue vient d'une version plus récente : on
            // prend ce qu'on comprend.
            _ => {}
        }
    }
    let (jvm, jvm_rejected) = received_jvm(raw_jvm);

    let mut versions: HashMap<String, MrVersion> = HashMap::new();
    if !tokens.is_empty() {
        let ids: Vec<&str> = tokens.iter().map(|t| t.version_id.as_str()).collect::<HashSet<_>>().into_iter().collect();
        let resp = http()?
            .get("https://api.modrinth.com/v2/versions")
            .query(&[("ids", serde_json::to_string(&ids).map_err(|e| e.to_string())?)])
            .send()
            .await
            .map_err(|_| "Modrinth est injoignable : réessaie dans un instant".to_string())?;
        if !resp.status().is_success() {
            return Err(format!("Modrinth a refusé la demande (HTTP {})", resp.status()));
        }
        let list: Vec<MrVersion> = resp.json().await.map_err(|e| e.to_string())?;
        versions = list.into_iter().map(|v| (v.id.clone(), v)).collect();
    }

    let mut files = remote_files;
    for token in tokens {
        let (dir, disabled) = dir_of(token.kind).expect("vérifié au décodage");
        // Sans début d'empreinte, la version n'avait qu'un fichier quand le
        // lien a été fait : le principal (ou le seul).
        let file = versions.get(&token.version_id).and_then(|v| {
            if token.hash_prefix.is_empty() {
                v.files.iter().find(|f| f.primary).or_else(|| v.files.first())
            } else {
                v.files.iter().find(|f| f.hashes.sha1.to_lowercase().starts_with(&token.hash_prefix))
            }
        });
        let Some(file) = file else {
            rejected.push(token.version_id);
            continue;
        };
        // Le nom vient de Modrinth : on n'en garde que le dernier segment.
        let name = file.filename.rsplit(['/', '\\']).next().unwrap_or_default();
        let Some(path) = safe_relative(&format!("{dir}/{name}{}", if disabled { ".disabled" } else { "" })) else {
            rejected.push(file.filename.clone());
            continue;
        };
        if !allowed_download(&file.url) {
            rejected.push(path);
            continue;
        }
        files.push(PackFile {
            path,
            url: file.url.clone(),
            sha1: Some(file.hashes.sha1.to_lowercase()),
            sha512: file.hashes.sha512.clone(),
            size: file.size,
        });
    }

    Ok(Pack {
        name,
        summary: String::new(),
        mc_version,
        loader,
        loader_version,
        files,
        overrides: Vec::new(),
        archive: None,
        rejected,
        jvm,
        jvm_rejected,
        options,
        client,
        servers,
        inline,
    })
}

async fn load_pack(source: &ShareSource) -> Result<Pack, String> {
    match source {
        ShareSource::File { path } => {
            let path = PathBuf::from(path);
            tokio::task::spawn_blocking(move || read_mrpack(&path)).await.map_err(|e| e.to_string())?
        }
        ShareSource::Link { link } => read_link(link).await,
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PreviewFile {
    pub path: String,
    pub size: u64,
    /// Domaine d'origine (« modrinth », « curseforge », « github »…) ; vide
    /// pour un fichier embarqué.
    pub source: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SharePreview {
    pub name: String,
    pub summary: String,
    pub mc_version: String,
    pub loader: String,
    pub loader_version: String,
    pub downloads: Vec<PreviewFile>,
    pub embedded: Vec<PreviewFile>,
    pub rejected: Vec<String>,
    /// Configuration Java jointe (déjà filtrée), à appliquer ou non.
    pub jvm: Option<JvmShare>,
    /// Arguments JVM du pack écartés par le filtre de sécurité.
    pub jvm_rejected: Vec<String>,
    /// Réglages d'`options.txt` reçus par lien (0 : aucun).
    pub options: u32,
    /// Options du client intégré reçues (0 : aucune).
    pub client: u32,
    /// Noms des serveurs reçus par lien.
    pub servers: Vec<String>,
}

fn source_of(url: &str) -> &'static str {
    if url.starts_with("https://cdn.modrinth.com/") {
        "modrinth"
    } else if CURSEFORGE_CDN.iter().any(|p| url.starts_with(p)) {
        "curseforge"
    } else if url.starts_with("https://gitlab.com/") {
        "gitlab"
    } else {
        "github"
    }
}

#[tauri::command]
pub async fn instance_share_preview(source: ShareSource) -> Result<SharePreview, String> {
    let pack = load_pack(&source).await?;
    Ok(SharePreview {
        downloads: pack
            .files
            .iter()
            .map(|f| PreviewFile { path: f.path.clone(), size: f.size, source: source_of(&f.url).into() })
            .collect(),
        embedded: pack
            .overrides
            .iter()
            .map(|(_, rel, size)| PreviewFile { path: rel.clone(), size: *size, source: String::new() })
            .chain(pack.inline.iter().map(|(rel, content)| PreviewFile {
                path: rel.clone(),
                size: content.len() as u64,
                source: String::new(),
            }))
            .collect(),
        options: pack.options.len() as u32,
        client: pack.client.len() as u32,
        servers: pack.servers.iter().map(|(name, _)| name.clone()).collect(),
        name: pack.name,
        summary: pack.summary,
        mc_version: pack.mc_version,
        loader: pack.loader,
        loader_version: pack.loader_version,
        rejected: pack.rejected,
        jvm: pack.jvm,
        jvm_rejected: pack.jvm_rejected,
    })
}

// ── Import ──────────────────────────────────────────────────────────────────

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ShareImport {
    pub instance: Instance,
    /// Fichiers qui n'ont pas pu être installés (réseau, empreinte fausse).
    pub failed: Vec<String>,
}

/// Le contenu correspond-il à ce que le pack annonce ? La plus forte des
/// empreintes données fait foi ; sans aucune, on accepte (le format les rend
/// obligatoires, mais un pack fait à la main peut les omettre).
fn matches_hashes(data: &[u8], sha1: Option<&str>, sha512: Option<&str>) -> bool {
    if let Some(expected) = sha512 {
        return format!("{:x}", Sha512::digest(data)) == expected.to_lowercase();
    }
    if let Some(expected) = sha1 {
        return format!("{:x}", Sha1::digest(data)) == expected.to_lowercase();
    }
    true
}

async fn download(client: &reqwest::Client, file: &PackFile, dest: &Path) -> Result<(), String> {
    let resp = client.get(&file.url).send().await.map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("HTTP {}", resp.status()));
    }
    let data = resp.bytes().await.map_err(|e| e.to_string())?;
    if !matches_hashes(&data, file.sha1.as_deref(), file.sha512.as_deref()) {
        return Err("empreinte différente de celle annoncée".into());
    }
    if let Some(parent) = dest.parent() {
        tokio::fs::create_dir_all(parent).await.map_err(|e| e.to_string())?;
    }
    tokio::fs::write(dest, &data).await.map_err(|e| e.to_string())
}

fn extract_overrides(archive_path: &Path, overrides: &[(String, String, u64)], dir: &Path) -> Vec<String> {
    let mut failed = Vec::new();
    let Ok(file) = std::fs::File::open(archive_path) else {
        return overrides.iter().map(|(_, rel, _)| rel.clone()).collect();
    };
    let Ok(mut archive) = zip::ZipArchive::new(file) else {
        return overrides.iter().map(|(_, rel, _)| rel.clone()).collect();
    };
    for (name, rel, _) in overrides {
        let dest = join_relative(dir, rel);
        let result = (|| -> std::io::Result<()> {
            let mut entry = archive.by_name(name).map_err(std::io::Error::other)?;
            if let Some(parent) = dest.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let mut out = std::fs::File::create(&dest)?;
            std::io::copy(&mut entry, &mut out)?;
            Ok(())
        })();
        if let Err(e) = result {
            tracing::warn!("[Partage] extraction de {} échouée : {}", rel, e);
            failed.push(rel.clone());
        }
    }
    failed
}

#[tauri::command]
pub async fn instance_share_import(
    app: tauri::AppHandle,
    state: tauri::State<'_, SharedState>,
    source: ShareSource,
    name: String,
    ram_mb: u32,
    apply_jvm: bool,
) -> Result<ShareImport, String> {
    use futures::StreamExt;
    use tauri::Emitter;

    let pack = load_pack(&source).await?;
    let name = Some(name.trim().to_string())
        .filter(|n| !n.is_empty())
        .or_else(|| Some(pack.name.trim().to_string()).filter(|n| !n.is_empty()))
        .unwrap_or_else(|| "Instance partagée".to_string());

    // La configuration Java reçue a déjà passé le filtre (`received_jvm`) ;
    // elle ne s'applique que si l'utilisateur l'a gardée cochée. Sinon les
    // réglages par défaut du launcher, et la RAM choisie de ce côté-ci.
    let jvm = pack.jvm.clone().filter(|_| apply_jvm);
    let mut instance = super::crud::instance_create(
        state.clone(),
        name,
        pack.mc_version.clone(),
        pack.loader.clone(),
        jvm.as_ref().map_or(ram_mb, |j| j.ram_mb),
        Some(pack.summary.clone()),
        jvm.as_ref().map(|j| j.vendor.clone()),
        None,
        jvm.as_ref().map(|j| j.gc_policy.clone()),
        jvm.as_ref().map(|j| j.args.join("\n")),
        jvm.as_ref().map(|j| j.args_mode.clone()),
    )
    .await?;

    // Le même loader que l'expéditeur, épinglé : sans ça, le destinataire
    // prendrait « le plus récent », qui n'est peut-être plus celui avec lequel
    // les mods ont été testés.
    if !pack.loader_version.is_empty() && loader_dependency(&pack.loader).is_some() {
        let s = state.read().await;
        let uid = user_id(&s);
        let db = s.db.lock().await;
        db::instance_set_loader_version(&db, &instance.id, uid, &pack.loader_version).map_err(|e| e.to_string())?;
        instance.loader_version = pack.loader_version.clone();
    }

    let dir = instance_dir(&instance.id);
    let client = http()?;
    let total = pack.files.len();
    let mut failed: Vec<String> = Vec::new();

    // Données possédées par chaque téléchargement : un flux de futures qui
    // emprunteraient la liste ne passe pas la vérification `Send` des
    // commandes Tauri. Le client se clone sans rien recopier (il est partagé).
    let jobs: Vec<(PackFile, PathBuf, reqwest::Client)> = pack
        .files
        .into_iter()
        .map(|file| {
            let dest = join_relative(&dir, &file.path);
            (file, dest, client.clone())
        })
        .collect();
    let mut downloads = futures::stream::iter(jobs.into_iter().map(|(file, dest, client)| async move {
        let result = download(&client, &file, &dest).await;
        (file.path, result)
    }))
    .buffer_unordered(PARALLEL_DOWNLOADS);

    let mut done = 0usize;
    while let Some((path, result)) = downloads.next().await {
        done += 1;
        let label = path.rsplit('/').next().unwrap_or(&path).to_string();
        let _ = app.emit("share_import_progress", serde_json::json!({ "current": done, "total": total, "label": label }));
        if let Err(e) = result {
            tracing::warn!("[Partage] {} : {}", path, e);
            failed.push(path);
        }
    }
    drop(downloads);

    // Après les téléchargements : un fichier embarqué prime sur la version
    // publique du même nom, c'est celui que l'expéditeur utilisait.
    if let Some(archive) = pack.archive.clone() {
        let overrides = pack.overrides.clone();
        let dir = dir.clone();
        let extract_failed = tokio::task::spawn_blocking(move || extract_overrides(&archive, &overrides, &dir))
            .await
            .map_err(|e| e.to_string())?;
        failed.extend(extract_failed);
    }

    // Ce qu'un lien porte en plus des téléchargements. Les chemins ont été
    // vérifiés à la lecture (`safe_relative`) ; les options et les serveurs
    // sont **fusionnés**, comme le partage d'options.
    for (rel, content) in &pack.inline {
        let dest = join_relative(&dir, rel);
        let written = match dest.parent() {
            Some(parent) => tokio::fs::create_dir_all(parent).await.and(tokio::fs::write(&dest, content).await),
            None => tokio::fs::write(&dest, content).await,
        };
        if let Err(e) = written {
            tracing::warn!("[Partage] écriture de {} échouée : {}", rel, e);
            failed.push(rel.clone());
        }
    }
    if !pack.options.is_empty() {
        if let Err(e) = mc_options_write(instance.id.clone(), pack.options.clone()).await {
            tracing::warn!("[Partage] options du jeu : {}", e);
            failed.push(OPTIONS_FILE.into());
        }
    }
    if !pack.client.is_empty() {
        if let Err(e) = agent_options_write(instance.id.clone(), pack.client.clone()).await {
            tracing::warn!("[Partage] options du client : {}", e);
            failed.push("options du client YuyuFrame".into());
        }
    }
    if !pack.servers.is_empty() {
        let servers: Vec<crate::minecraft::launcher::SavedServer> = pack
            .servers
            .iter()
            .map(|(name, ip)| crate::minecraft::launcher::SavedServer { name: name.clone(), ip: ip.clone() })
            .collect();
        let dir = dir.clone();
        let merged = tokio::task::spawn_blocking(move || crate::minecraft::launcher::merge_saved_servers(&dir, &servers))
            .await
            .map_err(|e| e.to_string())?;
        if let Err(e) = merged {
            tracing::warn!("[Partage] serveurs : {}", e);
            failed.push("servers.dat".into());
        }
    }

    crate::integrations::analytics::capture("instance_share_imported", serde_json::json!({
        "from": match source { ShareSource::File { .. } => "file", ShareSource::Link { .. } => "link" },
        "files": total,
        "failed": failed.len(),
    }));

    Ok(ShareImport { instance, failed })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn caches_comptes_et_fichiers_internes_jamais_proposes() {
        for name in ["logs", ".fabric", ".voxy", "essential", "usercache.json", "meta.json", "Command_History.txt"] {
            assert_eq!(top_level_group(name, true), None, "{name}");
            assert_eq!(top_level_group(name, false), None, "{name}");
        }
    }

    #[test]
    fn classement_des_dossiers_et_fichiers() {
        assert_eq!(top_level_group("mods", true), Some(Group::Mods));
        assert_eq!(top_level_group("config", true), Some(Group::Settings));
        assert_eq!(top_level_group("options.txt", false), Some(Group::Settings));
        assert_eq!(top_level_group("servers.dat", false), Some(Group::Servers));
        assert_eq!(top_level_group("saves", true), Some(Group::Saves));
        // Un dossier de mod inconnu reste proposable, décoché.
        assert_eq!(top_level_group("xaero", true), Some(Group::Other));
        assert!(!Group::Other.selected_by_default());
        assert!(!Group::Servers.selected_by_default());
        assert!(Group::Mods.selected_by_default());
    }

    #[test]
    fn chemins_dangereux_refuses() {
        assert_eq!(safe_relative("mods/a.jar").as_deref(), Some("mods/a.jar"));
        assert_eq!(safe_relative("config\\x\\y.json").as_deref(), Some("config/x/y.json"));
        for bad in ["../a", "mods/../../a", "/etc/x", "\\x", "C:/x", "mods/C:x", "meta.json", "META.JSON", "", "./"] {
            assert_eq!(safe_relative(bad), None, "{bad}");
        }
        // Un meta.json plus bas dans l'arborescence n'est pas celui du launcher.
        assert!(safe_relative("config/meta.json").is_some());
    }

    #[test]
    fn jeton_de_lien_aller_retour() {
        // Avec empreinte (version à plusieurs fichiers), sans, puis encore avec.
        let a = encode_token('M', "AbCd1234", "DEADbeef00", true).unwrap();
        let b = encode_token('r', "zzzzzzzz", "0123abcd", false).unwrap();
        let c = encode_token('s', "9f9f9f9f", "abcdef01", true).unwrap();
        assert_eq!(a.len(), 13);
        assert_eq!(b.len(), 9);
        let decoded = decode_tokens(&format!("{a}{b}{c}")).unwrap();
        assert_eq!(decoded.len(), 3);
        assert_eq!((decoded[0].kind, decoded[0].version_id.as_str(), decoded[0].hash_prefix.as_str()), ('M', "AbCd1234", "dead"));
        assert_eq!(dir_of(decoded[0].kind), Some(("mods", true)));
        assert_eq!((decoded[1].kind, decoded[1].hash_prefix.as_str()), ('r', ""));
        assert_eq!((decoded[2].version_id.as_str(), decoded[2].hash_prefix.as_str()), ("9f9f9f9f", "abcd"));
    }

    #[test]
    fn jeton_de_lien_invalide() {
        assert!(encode_token('m', "trop-long-id", "dead", false).is_none());
        assert!(decode_tokens("mAbCd123").is_err()); // identifiant tronqué
        assert!(decode_tokens("xAbCd1234").is_err()); // famille inconnue
        assert!(decode_tokens("mAbCd12/4").is_err()); // identifiant
        assert!(decode_tokens("mAbCd1234zz").is_err()); // ni empreinte ni jeton suivant
        assert!(decode_tokens("").unwrap().is_empty());
    }

    /// Le lien de ton instance CocoWorld (22 mods, packs et config Java) :
    /// dans le format d'avant, 1 523 caractères.
    #[test]
    fn lien_instance_compact() {
        let tokens = "mL6Sv1iN2mAfA2Emwm89mGFM8zmJ832fm3dmX6ou16c3mUdiBeac7mRystERKEmMwcLS51SmYo9xOcemw8P6TokGmOqq8TOAVmFItuNokSmZJ6YTrMYm4H8A03wameRJU33HpmiFNRLrRBm7RYVKQJmmgjsLvJfWmpX4mxVAvmkWf58HtHmIYPINJuwrBX6pU42frWWLpy1hrR5ZGSF8ArRGIzA5emrxeIjARlrryEg1LARqryQdcUfnrrkqcBpfhrrI4ivyUVarrJtYoNiksyCCduG4sy6zWED9ssgUv7fBPsWcoEHPPx";
        let args = "-XX:+UnlockExperimentalVMOptions -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:+PerfDisableSharedMem -XX:+AlwaysActAsServerClassMachine -XX:+UseCriticalJavaThreadPriority -XX:MetaspaceSize=256m -XX:-UseG1GC -XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational -XX:+ParallelRefProcEnabled -XX:ShenandoahGuaranteedGCInterval=1000000 -XX:-ShenandoahUncommit -XX:-DontCompileHugeMethods -XX:MaxNodeLimit=240000 -XX:NodeLimitFudgeFactor=8000 -XX:NmethodSweepActivity=1 -XX:ReservedCodeCacheSize=400M -XX:NonNMethodCodeHeapSize=12M";
        let mut data = Vec::new();
        push_record(&mut data, REC_HEADER, format!("CocoWorld 1.5\n26.1.2\nfabric\n0.19.5\n{tokens}").as_bytes());
        push_record(&mut data, REC_JVM, format!("6144 temurin g1 replace\n{args}").as_bytes());
        let links = crate::share_link::build(LINK_KIND, &data).unwrap();
        assert_eq!(links.len(), 1);
        let length = links[0].chars().count();
        assert!(length < 300, "{length} caractères");
        assert_eq!(crate::share_link::read(&links[0], LINK_KIND).unwrap(), data);
    }

    /// Un lien fabriqué à la main : options filtrées à la lecture, serveurs,
    /// fichier copié, et un chemin qui sort de l'instance refusé.
    #[tokio::test]
    async fn lecture_d_un_lien_complet() {
        let mut data = Vec::new();
        push_record(&mut data, REC_HEADER, b"Test\n1.21.11\nvanilla\n\n");
        push_record(&mut data, REC_OPTIONS, b"fov:0.5\nlastServer:prive.example");
        push_record(&mut data, REC_CLIENT, b"zoom.enabled=true\nmacros.setting.logins=secret");
        push_record(&mut data, REC_SERVERS, b"Mon serveur\tmc.example.org\nSans adresse\t");
        push_record(&mut data, REC_FILE, b"config/a.json\0{\"x\":1}");
        push_record(&mut data, REC_FILE, b"../../evil.txt\0boom");
        push_record(&mut data, b'Z', b"etiquette future, ignoree");
        let link = crate::share_link::build(LINK_KIND, &data).unwrap().join("\n");

        let pack = read_link(&link).await.unwrap();
        assert_eq!(pack.name, "Test");
        assert_eq!(pack.options, vec![McOption { key: "fov".into(), value: "0.5".into() }]);
        assert_eq!(pack.client, vec![McOption { key: "zoom.enabled".into(), value: "true".into() }]);
        assert_eq!(pack.servers, vec![("Mon serveur".to_string(), "mc.example.org".to_string())]);
        assert_eq!(pack.inline, vec![("config/a.json".to_string(), b"{\"x\":1}".to_vec())]);
        assert_eq!(pack.rejected, vec!["../../evil.txt".to_string()]);
    }

    #[test]
    fn enregistrements_aller_retour() {
        let mut data = Vec::new();
        push_record(&mut data, REC_HEADER, b"abc");
        push_record(&mut data, REC_FILE, &vec![7u8; 300]);
        let parsed = records(&data).unwrap();
        assert_eq!(parsed.len(), 2);
        assert_eq!(parsed[1].1.len(), 300);
        assert!(records(&data[..data.len() - 1]).is_none());
    }

    #[test]
    fn loader_depuis_les_dependances() {
        let mut deps = BTreeMap::new();
        deps.insert("minecraft".to_string(), "1.21.4".to_string());
        assert_eq!(loader_from_dependencies(&deps), ("vanilla".into(), String::new()));
        deps.insert("fabric-loader".to_string(), "0.16.9".to_string());
        assert_eq!(loader_from_dependencies(&deps), ("fabric".into(), "0.16.9".into()));
    }

    #[test]
    fn verification_des_empreintes() {
        let data = b"hello";
        let sha1 = format!("{:x}", Sha1::digest(data));
        let sha512 = format!("{:x}", Sha512::digest(data));
        assert!(matches_hashes(data, Some(&sha1), None));
        assert!(matches_hashes(data, Some("0000"), Some(&sha512)));
        assert!(!matches_hashes(data, Some(&sha1), Some("00")));
        assert!(!matches_hashes(b"other", Some(&sha1), None));
    }

    /// Des réglages courants, tirés des jeux de drapeaux qui circulent
    /// (Aikar, Graal, ZGC) : ils doivent passer.
    #[test]
    fn arguments_jvm_de_reglage_acceptes() {
        for arg in [
            "-Xmx6G", "-Xms4096m", "-Xss2m", "-XX:+UseG1GC", "-XX:+UnlockExperimentalVMOptions",
            "-XX:MaxGCPauseMillis=50", "-XX:G1NewSizePercent=30", "-XX:-DontCompileHugeMethods",
            "-XX:+UseZGC", "-XX:+ZGenerational", "-XX:+AlwaysPreTouch", "-Xgcpolicy:gencon",
            "-Xdisableexplicitgc", "-Dfml.ignoreInvalidMinecraftCertificates=true",
            "-Dlog4j2.formatMsgNoLookups=true",
        ] {
            assert!(jvm_arg_allowed(arg), "{arg}");
        }
    }

    /// De quoi faire exécuter du code ou écrire un fichier : jamais.
    #[test]
    fn arguments_jvm_dangereux_refuses() {
        for arg in [
            "-javaagent:C:/evil.jar", "-agentlib:jdwp=transport=dt_socket", "-agentpath:x.dll",
            "-XX:OnOutOfMemoryError=calc.exe", "-XX:OnError=cmd", "-XX:ErrorFile=C:/x.log",
            "-XX:HeapDumpPath=C:/Users/moi", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:Flags=x",
            "-XX:VMOptionsFile=x", "-XX:CompileCommand=x", "-XX:StartFlightRecording=filename=x",
            "-Djava.library.path=mods", "-Dorg.lwjgl.librarypath=x", "-Djava.system.class.loader=Evil",
            "-Dlog4j.configurationFile=http://x", "-Dfoo=C:/bar", "-Dfoo=a/b", "-cp", "evil.jar",
            "@argfile", "--add-opens=java.base/java.lang=ALL-UNNAMED", "-jar", "-Xmx4GG", "-Xbootclasspath/a:x",
        ] {
            assert!(!jvm_arg_allowed(arg), "{arg}");
        }
    }

    #[test]
    fn configuration_jvm_assainie() {
        let (clean, rejected) = sanitize_jvm(JvmShare {
            ram_mb: 999_999,
            vendor: "custom".into(),
            gc_policy: "g1 ; rm".into(),
            args_mode: "n'importe quoi".into(),
            args: vec!["-XX:+UseG1GC".into(), "-javaagent:x.jar".into()],
        });
        assert_eq!(clean.ram_mb, 65_536);
        assert_eq!(clean.vendor, "auto");
        assert_eq!(clean.gc_policy, "auto");
        assert_eq!(clean.args_mode, "append");
        assert_eq!(clean.args, vec!["-XX:+UseG1GC".to_string()]);
        assert_eq!(rejected, vec!["-javaagent:x.jar".to_string()]);
    }

    #[test]
    fn domaines_autorises() {
        assert!(allowed_download("https://cdn.modrinth.com/data/x/y.jar"));
        assert!(allowed_download("https://edge.forgecdn.net/files/1/2/x.jar"));
        assert!(!allowed_download("http://cdn.modrinth.com/x"));
        assert!(!allowed_download("https://cdn.modrinth.com.evil.example/x"));
        assert!(!allowed_download("https://example.com/x.jar"));
    }
}
