//! Compiler les mods natifs d'une instance et assembler le jeu qui les charge.
//!
//! Recette reprise du launcher d'Horizon (`launcher/src/native_mods/api_route.rs`
//! et `prebuilt.rs`, route « api ») et vérifiée à la main le 2026-11-21 :
//! AppleSkin, fait pour le build `5ef7734`, compilé contre le SDK de `f037744`
//! en 3 secondes, puis chargé par le jeu.
//!
//! 1. Chaque mod est compilé seul, par `rustc` (pas de cargo), en bibliothèque
//!    dynamique liée aux bibliothèques du jeu précompilé (`-C prefer-dynamic`).
//!    Son point d'entrée vient du modèle que livre le SDK
//!    (`native_mod_entry.rs.template`), rempli avec son identifiant.
//! 2. Le jeu assemblé est l'exécutable **inchangé** du SDK, ses bibliothèques,
//!    la bibliothèque standard de Rust, deux DLL de MinGW, et les mods ; le
//!    fichier `native-mods.json` dit au jeu quelles bibliothèques sont des mods.
//!
//! Le jeu n'est jamais recompilé. Un mod qui ne peut pas être compilé est
//! laissé de côté avec la raison, jamais supprimé, et les autres sont compilés
//! quand même : la partie se lance toujours.
//!
//! Pas encore pris en charge (laissés de côté, raison donnée au joueur) : les
//! mods qui modifient le code du jeu (`[[native.mixin]]`, modules, internes —
//! route « engine », environ 15 % du registre), et ceux qui déclarent des
//! `[[inject]]` (aucun dans le registre au 2026-11-21).

use anyhow::{anyhow, Result};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::io::Read;
use std::path::{Path, PathBuf};

use super::sdk::Sdk;
use super::toolchain::Toolchain;

use super::super::content::NATIVE_DIR;
const ENTRY_TEMPLATE: &str = "native_mod_entry.rs.template";
const CLIENT: &str = "horizon-client.exe";
/// Taille maximale d'un `.hmod` lu (règle du registre).
const HMOD_LIMIT: u64 = 64 * 1024 * 1024;

/// Un mod natif lu depuis son `.hmod`.
pub struct Bundle {
    pub id: String,
    pub version: String,
    pub side: String,
    /// Le build d'Horizon pour lequel il est fait (`[native] horizon`).
    pub made_for: String,
    /// Pourquoi il ne peut tourner que sur ce build-là, s'il modifie le jeu.
    pub engine: Option<&'static str>,
    pub inject: bool,
    pub files: Vec<(String, Vec<u8>)>,
    pub sha256: String,
    cargo: toml::Table,
    /// Les mods dont il dépend : (identifiant, facultatif).
    pub depends: Vec<(String, bool)>,
}

impl Bundle {
    fn file(&self, name: &str) -> Option<&[u8]> {
        self.files.iter().find(|(n, _)| n == name).map(|(_, b)| b.as_slice())
    }

    fn edition(&self) -> String {
        self.cargo
            .get("package")
            .and_then(|p| p.get("edition"))
            .and_then(|e| e.as_str())
            .unwrap_or("2015")
            .to_string()
    }

    fn crate_name(&self) -> String {
        let lib = self.cargo.get("lib").and_then(|l| l.get("name")).and_then(|n| n.as_str());
        let package = self.cargo.get("package").and_then(|p| p.get("name")).and_then(|n| n.as_str());
        lib.or(package).unwrap_or(&self.id).replace('-', "_")
    }

    fn lib_path(&self) -> String {
        self.cargo
            .get("lib")
            .and_then(|l| l.get("path"))
            .and_then(|p| p.as_str())
            .unwrap_or("src/lib.rs")
            .to_string()
    }

    /// Les paquets Rust dont il dépend, hors SDK et hors autres mods :
    /// (nom de la dépendance, nom du crate).
    fn crates(&self) -> Vec<(String, String)> {
        let Some(deps) = self.cargo.get("dependencies").and_then(|d| d.as_table()) else {
            return Vec::new();
        };
        deps.iter()
            .filter(|(name, _)| !SDK_PACKAGES.iter().any(|(p, _)| p == name))
            .filter(|(name, _)| !self.depends.iter().any(|(id, _)| id.replace('_', "-") == name.replace('_', "-")))
            .map(|(name, value)| {
                let package = value.get("package").and_then(|p| p.as_str()).unwrap_or(name);
                (name.replace('-', "_"), package.replace('-', "_"))
            })
            .collect()
    }

    /// Les paquets du SDK qu'il nomme, sous le nom qu'il leur donne.
    fn sdk_aliases(&self) -> Vec<(String, &'static str)> {
        let Some(deps) = self.cargo.get("dependencies").and_then(|d| d.as_table()) else {
            return Vec::new();
        };
        deps.iter()
            .filter_map(|(name, _)| {
                SDK_PACKAGES.iter().find(|(p, _)| p == name).map(|(_, lib)| (name.replace('-', "_"), *lib))
            })
            .collect()
    }
}

/// Les paquets du SDK qu'un mod peut nommer, et leur bibliothèque.
const SDK_PACKAGES: [(&str, &str); 2] = [("horizon-mod-api", "mod_api"), ("horizon-mod-points", "mod_points")];

/// Lit un `.hmod` : une archive zip, chemins relatifs simples, fichiers
/// ordinaires. Rien n'est exécuté.
pub fn read_bundle(path: &Path) -> Result<Bundle> {
    let bytes = {
        let file = std::fs::File::open(path)?;
        let mut bytes = Vec::new();
        file.take(HMOD_LIMIT + 1).read_to_end(&mut bytes)?;
        if bytes.len() as u64 > HMOD_LIMIT {
            return Err(anyhow!("{} dépasse 64 Mo", path.display()));
        }
        bytes
    };
    let sha256 = format!("{:x}", Sha256::digest(&bytes));
    let mut zip = zip::ZipArchive::new(std::io::Cursor::new(bytes))?;
    let mut files = Vec::new();
    let mut total = 0u64;
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i)?;
        if entry.is_dir() {
            continue;
        }
        let name = entry.name().to_string();
        let safe = !name.is_empty()
            && !name.starts_with('/')
            && !name.contains('\\')
            && !name.contains(':')
            && name.split('/').all(|p| !p.is_empty() && p != "." && p != "..");
        if !safe {
            return Err(anyhow!("{} : chemin refusé dans l'archive ({name})", path.display()));
        }
        total += entry.size();
        if total > 4 * HMOD_LIMIT {
            return Err(anyhow!("{} : archive trop grosse une fois décompressée", path.display()));
        }
        let mut data = Vec::with_capacity(entry.size() as usize);
        entry.read_to_end(&mut data)?;
        files.push((name, data));
    }
    let manifest_text = files
        .iter()
        .find(|(n, _)| n == "horizon-mod.toml")
        .map(|(_, b)| String::from_utf8_lossy(b).into_owned())
        .ok_or_else(|| anyhow!("{} n'a pas de horizon-mod.toml", path.display()))?;
    let manifest: toml::Table = manifest_text.parse().map_err(|e| anyhow!("{} : {e}", path.display()))?;
    let get = |key: &str| manifest.get(key).and_then(|v| v.as_str()).unwrap_or_default().to_string();
    let native = manifest
        .get("native")
        .and_then(|n| n.as_table())
        .ok_or_else(|| anyhow!("{} n'est pas un mod natif", path.display()))?;
    let made_for = native.get("horizon").and_then(|v| v.as_str()).unwrap_or_default().to_string();
    let engine = if ["mixin", "module", "bridge", "loop", "state"].iter().any(|k| native.contains_key(*k)) {
        Some("modifie le code du jeu")
    } else if manifest.contains_key("internals") {
        Some("utilise des parties internes du jeu")
    } else {
        None
    };
    let depends = manifest
        .get("dependencies")
        .and_then(|d| d.as_table())
        .map(|deps| {
            deps.iter()
                .filter(|(_, v)| v.get("side").and_then(|s| s.as_str()).map_or(true, |s| s != "server"))
                .map(|(id, v)| (id.clone(), v.get("optional").and_then(|o| o.as_bool()).unwrap_or(false)))
                .collect()
        })
        .unwrap_or_default();
    let cargo_text = files
        .iter()
        .find(|(n, _)| n == "Cargo.toml")
        .map(|(_, b)| String::from_utf8_lossy(b).into_owned())
        .ok_or_else(|| anyhow!("{} n'a pas de Cargo.toml", path.display()))?;
    let cargo: toml::Table = cargo_text.parse().map_err(|e| anyhow!("{} : {e}", path.display()))?;
    let id = get("id");
    if id.is_empty() || !id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_') || id.len() > 64 {
        return Err(anyhow!("{} : identifiant de mod invalide", path.display()));
    }
    Ok(Bundle {
        id,
        version: get("version"),
        side: get("side"),
        made_for,
        engine,
        inject: manifest.contains_key("inject"),
        files,
        sha256,
        cargo,
        depends,
    })
}

/// Pourquoi un mod n'est pas dans la partie.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Reason {
    /// Fait pour un autre build, et il modifie le jeu ou ne dit pas ce qu'il
    /// utilise : il ne tourne que sur le sien.
    OtherBuild { made_for: String, why: String },
    /// Fait pour un autre build, et des éléments qu'il utilise ont changé ici.
    DoesNotFit { made_for: String, items: Vec<String> },
    /// Il modifie le code du jeu : pas encore pris en charge par YuyuFrame.
    ChangesGame,
    /// Il déclare des points d'injection : pas encore pris en charge.
    Injections,
    /// Il dépend de paquets Rust que le SDK ne fournit pas.
    Crates { names: Vec<String> },
    /// Un mod dont il a besoin manque ou est lui-même laissé de côté.
    MissingDependency { id: String },
    /// Il refuse le code non sûr, ce qu'exige son point d'entrée.
    ForbidsUnsafe,
    /// La compilation a échoué : les dernières lignes du compilateur.
    Compile { log: String },
    /// Le fichier n'a pas pu être lu.
    Unreadable { error: String },
}

#[derive(Serialize, Clone, Debug)]
pub struct LeftOut {
    pub id: String,
    pub file: String,
    #[serde(flatten)]
    pub reason: Reason,
}

/// Le résultat d'une préparation : le jeu à lancer et ce qui manque.
pub struct Built {
    pub dir: PathBuf,
    pub mods: Vec<String>,
    pub left_out: Vec<LeftOut>,
}

/// Les mods natifs allumés d'une instance (dossier de jeu `game_dir`).
pub fn enabled_mods(game_dir: &Path) -> (Vec<(PathBuf, Bundle)>, Vec<LeftOut>) {
    let enabled = super::super::content::enabled_ids(game_dir);
    let mut paths: Vec<PathBuf> = std::fs::read_dir(game_dir.join(NATIVE_DIR))
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|e| e == "hmod") && p.is_file())
        .collect();
    paths.sort();
    let mut mods = Vec::new();
    let mut bad = Vec::new();
    for path in paths {
        let file = path.file_name().unwrap_or_default().to_string_lossy().into_owned();
        match read_bundle(&path) {
            Ok(bundle) if enabled.contains(&bundle.id) => mods.push((path, bundle)),
            Ok(_) => {}
            Err(e) => bad.push(LeftOut {
                id: file.trim_end_matches(".hmod").to_string(),
                file,
                reason: Reason::Unreadable { error: e.to_string() },
            }),
        }
    }
    (mods, bad)
}

/// Ce que chaque mod donne contre le build `commit` : compilable, ou pourquoi pas.
pub fn judge(bundle: &Bundle, commit: &str, surface: Option<&HashMap<String, String>>, prebuilt: &super::sdk::Prebuilt, api_deps: &Path) -> Result<(), Reason> {
    if bundle.engine.is_some() {
        if bundle.made_for != commit {
            return Err(Reason::OtherBuild { made_for: bundle.made_for.clone(), why: bundle.engine.unwrap_or_default().to_string() });
        }
        return Err(Reason::ChangesGame);
    }
    if bundle.inject {
        return Err(Reason::Injections);
    }
    let missing: Vec<String> = bundle
        .crates()
        .into_iter()
        .filter(|(_, krate)| facade(prebuilt, api_deps, krate).is_none())
        .map(|(name, _)| name)
        .collect();
    if !missing.is_empty() {
        return Err(Reason::Crates { names: missing });
    }
    if bundle.made_for == commit {
        return Ok(());
    }
    // Fait pour un autre build : il va ici si tout ce qu'il utilise y est
    // inchangé (`uses.json`, écrit par leur outil, avec l'empreinte de chaque
    // élément au build visé). La compilation reste la vérification finale.
    let uses = bundle
        .file("uses.json")
        .and_then(|b| serde_json::from_slice::<serde_json::Value>(b).ok())
        .filter(|u| u.get("made_for").and_then(|m| m.as_str()) == Some(bundle.made_for.as_str()));
    let (Some(uses), Some(surface)) = (uses, surface) else {
        return Err(Reason::OtherBuild { made_for: bundle.made_for.clone(), why: "ne dit pas ce qu'il utilise".into() });
    };
    let items: Vec<String> = uses
        .get("entries")
        .and_then(|e| e.as_array())
        .into_iter()
        .flatten()
        .filter_map(|entry| {
            let id = entry.get("id")?.as_str()?;
            let hash = entry.get("hash")?.as_str()?;
            (surface.get(id).map(String::as_str) != Some(hash)).then(|| id.to_string())
        })
        .collect();
    if items.is_empty() {
        Ok(())
    } else {
        Err(Reason::DoesNotFit { made_for: bundle.made_for.clone(), items })
    }
}

/// La bibliothèque d'un paquet tiers que le jeu fournit déjà (serde_json,
/// log…), s'il fait partie de ceux que le SDK expose (`facade_crates`).
fn facade(prebuilt: &super::sdk::Prebuilt, api_deps: &Path, krate: &str) -> Option<PathBuf> {
    if !prebuilt.facade_crates.iter().any(|c| c == krate) {
        return None;
    }
    let prefix = format!("lib{krate}-");
    let found: Vec<PathBuf> = std::fs::read_dir(api_deps)
        .ok()?
        .flatten()
        .map(|e| e.path())
        .filter(|p| {
            let name = p.file_name().unwrap_or_default().to_string_lossy();
            (name.starts_with(&prefix) && name.ends_with(".rmeta")) || name == format!("lib{krate}.rmeta")
        })
        .collect();
    match found.as_slice() {
        [one] => Some(one.clone()),
        _ => None,
    }
}

/// Les mods dans l'ordre de leurs dépendances ; ceux dont une dépendance
/// obligatoire manque sont rendus à part.
fn order(mods: Vec<Bundle>) -> (Vec<Bundle>, Vec<(Bundle, Reason)>) {
    let mut left: Vec<(Bundle, Reason)> = Vec::new();
    let mut pending = mods;
    // Retire en cascade ceux dont une dépendance obligatoire n'est pas là.
    loop {
        let present: HashSet<String> = pending.iter().map(|b| b.id.clone()).collect();
        let (ok, missing): (Vec<Bundle>, Vec<Bundle>) = pending
            .into_iter()
            .partition(|b| b.depends.iter().all(|(id, optional)| *optional || present.contains(id)));
        let done = missing.is_empty();
        for b in missing {
            let id = b.depends.iter().find(|(id, o)| !o && !present.contains(id)).map(|(id, _)| id.clone()).unwrap_or_default();
            left.push((b, Reason::MissingDependency { id }));
        }
        pending = ok;
        if done {
            break;
        }
    }
    let mut ordered: Vec<Bundle> = Vec::new();
    let mut placed: HashSet<String> = HashSet::new();
    while !pending.is_empty() {
        let present: HashSet<String> = pending.iter().map(|b| b.id.clone()).collect();
        let (ready, rest): (Vec<Bundle>, Vec<Bundle>) = pending
            .into_iter()
            .partition(|b| b.depends.iter().all(|(id, _)| placed.contains(id) || !present.contains(id)));
        if ready.is_empty() {
            // Cycle : personne ne peut passer en premier.
            for b in rest {
                let id = b.depends.first().map(|(id, _)| id.clone()).unwrap_or_default();
                left.push((b, Reason::MissingDependency { id }));
            }
            break;
        }
        for b in ready {
            placed.insert(b.id.clone());
            ordered.push(b);
        }
        pending = rest;
    }
    (ordered, left)
}

/// L'identité d'un ensemble de mods sur un build : même clé, même jeu assemblé.
fn key(commit: &str, mods: &[(&str, &str)]) -> String {
    let mut hash = Sha256::new();
    hash.update(commit);
    hash.update(super::toolchain::VERSION);
    hash.update(BUILD_RECIPE.to_le_bytes());
    for (id, sha256) in mods {
        hash.update(id);
        hash.update(sha256);
    }
    format!("{:x}", hash.finalize())
}

/// À monter quand la façon de compiler ou d'assembler change : les jeux déjà
/// assemblés ne sont alors plus repris.
const BUILD_RECIPE: u32 = 1;

pub fn builds_dir() -> PathBuf {
    super::root().join("builds")
}

/// Compile les mods acceptés et assemble le jeu ; reprend un jeu déjà
/// assemblé pour le même ensemble. `on_mod(id, n, total)` avant chaque compilation.
pub fn build(
    rust: &Toolchain,
    sdk: &Sdk,
    base_dir: &Path,
    accepted: Vec<Bundle>,
    mut left_out: Vec<LeftOut>,
    on_mod: &dyn Fn(&str, usize, usize),
    cancel: &dyn Fn() -> bool,
) -> Result<Built> {
    let (ordered, missing) = order(accepted);
    for (b, reason) in missing {
        left_out.push(LeftOut { file: format!("{}.hmod", b.id), id: b.id, reason });
    }
    let commit = sdk.index.build.commit.clone();
    let runtime = sdk.runtime();
    let api = sdk.api();
    let prebuilt = super::sdk::prebuilt(&runtime)?;
    if prebuilt.commit != commit {
        return Err(anyhow!("Le jeu précompilé du SDK est celui d'un autre build : refusé"));
    }
    let template = std::fs::read_to_string(api.join(ENTRY_TEMPLATE))
        .map_err(|_| anyhow!("Ce SDK n'a pas de modèle de point d'entrée : mets à jour YuyuFrame"))?;
    for marker in ["/* MOD_ENTRIES */", "/* MOD_SLOTS */", "/* BUILT_MODS */"] {
        if template.matches(marker).count() != 1 {
            return Err(anyhow!("Le modèle de point d'entrée de ce SDK a changé : mets à jour YuyuFrame"));
        }
    }

    // Compiler dans l'ordre ; un échec retire le mod et ceux qui en dépendent.
    let work = super::root().join("work").join(&commit[..12]);
    let out = work.join("out");
    std::fs::create_dir_all(&out)?;
    let mut compiled: Vec<(Bundle, PathBuf)> = Vec::new();
    let mut failed: HashSet<String> = HashSet::new();
    let total = ordered.len();
    for (n, bundle) in ordered.into_iter().enumerate() {
        if cancel() {
            return Err(anyhow!(super::super::super::orchestrator::LAUNCH_CANCELLED_MSG));
        }
        if let Some((dep, _)) = bundle.depends.iter().find(|(id, optional)| !optional && failed.contains(id)) {
            failed.insert(bundle.id.clone());
            left_out.push(LeftOut { file: format!("{}.hmod", bundle.id), id: bundle.id.clone(), reason: Reason::MissingDependency { id: dep.clone() } });
            continue;
        }
        on_mod(&bundle.id, n + 1, total);
        let deps: Vec<(String, bool, PathBuf)> = bundle
            .depends
            .iter()
            .filter_map(|(id, optional)| compiled.iter().find(|(b, _)| &b.id == id).map(|(_, lib)| (id.clone(), *optional, lib.clone())))
            .collect();
        match compile(rust, &runtime, &api, &out, &bundle, &deps, &template, &commit, &prebuilt) {
            Ok(lib) => compiled.push((bundle, lib)),
            Err(reason) => {
                failed.insert(bundle.id.clone());
                left_out.push(LeftOut { file: format!("{}.hmod", bundle.id), id: bundle.id.clone(), reason });
            }
        }
    }

    let identity: Vec<(&str, &str)> = compiled.iter().map(|(b, _)| (b.id.as_str(), b.sha256.as_str())).collect();
    let dir = builds_dir().join(&key(&commit, &identity)[..16]);
    if !dir.join(".installed").is_file() {
        assemble(rust, sdk, base_dir, &compiled, &dir, &left_out)?;
    } else {
        let _ = std::fs::write(dir.join(".installed"), &commit);
    }
    prune(&dir);
    Ok(Built { dir, mods: compiled.iter().map(|(b, _)| b.id.clone()).collect(), left_out })
}

#[allow(clippy::too_many_arguments)]
fn compile(
    rust: &Toolchain,
    runtime: &Path,
    api: &Path,
    out: &Path,
    bundle: &Bundle,
    deps: &[(String, bool, PathBuf)],
    template: &str,
    commit: &str,
    prebuilt: &super::sdk::Prebuilt,
) -> std::result::Result<PathBuf, Reason> {
    let krate = bundle.crate_name();
    let lib = out.join(format!("{krate}.dll"));
    let entry = entry_source(template, bundle, commit);

    // Une compilation déjà faite pour exactement ces entrées est reprise.
    let mut hash = Sha256::new();
    hash.update(&bundle.sha256);
    hash.update(&entry);
    hash.update(BUILD_RECIPE.to_le_bytes());
    for (_, _, dep) in deps {
        hash.update(std::fs::read(dep).unwrap_or_default());
    }
    let fingerprint = format!("{:x}", hash.finalize());
    let saved = lib.with_extension("fingerprint");
    if lib.is_file() && std::fs::read_to_string(&saved).ok().as_deref() == Some(&fingerprint) {
        return Ok(lib);
    }

    let staged = out.join("staged").join(&bundle.id);
    let _ = std::fs::remove_dir_all(&staged);
    let write_err = |e: std::io::Error| Reason::Compile { log: e.to_string() };
    for (name, bytes) in &bundle.files {
        let to = staged.join(name);
        std::fs::create_dir_all(to.parent().unwrap_or(&staged)).map_err(write_err)?;
        std::fs::write(&to, bytes).map_err(write_err)?;
    }
    let source = staged.join(bundle.lib_path());
    let mut root = std::fs::read_to_string(&source).map_err(write_err)?;
    if forbids_unsafe(&root) {
        return Err(Reason::ForbidsUnsafe);
    }
    std::fs::write(source.with_file_name("__horizon_entry.rs"), &entry).map_err(write_err)?;
    root.push_str("\n#[allow(unsafe_code)]\n#[path = \"__horizon_entry.rs\"] mod __horizon_entry;\n");
    std::fs::write(&source, root).map_err(write_err)?;

    let runtime_deps = runtime.join("deps");
    let mut cmd = rust.command();
    cmd.args(["--edition", &bundle.edition(), "-C", "prefer-dynamic", "-C", "opt-level=3"]);
    for folder in [api.join("deps"), api.join("host"), runtime_deps.clone(), out.to_path_buf()] {
        cmd.arg("-L").arg(format!("dependency={}", folder.display()));
    }
    cmd.args(["--crate-type", "dylib", "--crate-name", &krate])
        .arg("-C")
        .arg(format!("metadata={}", bundle.id));
    for name in ["mod_api", "mod_vocab", "mod_points"] {
        cmd.arg("--extern").arg(format!("{name}={}", runtime_deps.join(format!("{name}.dll")).display()));
    }
    for (alias, name) in bundle.sdk_aliases() {
        if alias != name {
            cmd.arg("--extern").arg(format!("{alias}={}", runtime_deps.join(format!("{name}.dll")).display()));
        }
    }
    for (alias, krate) in bundle.crates() {
        if let Some(path) = facade(prebuilt, &api.join("deps"), &krate) {
            cmd.arg("--extern").arg(format!("{alias}={}", path.display()));
        }
    }
    for (id, optional, path) in deps {
        cmd.arg("--extern").arg(format!("{}={}", id.replace('-', "_"), path.display()));
        if *optional {
            cmd.arg("--cfg").arg(format!("feature=\"{id}\""));
        }
    }
    cmd.arg("--out-dir").arg(out).arg(&source).current_dir(&staged);
    let output = cmd.output().map_err(|e| Reason::Compile { log: format!("rustc ne démarre pas : {e}") })?;
    let _ = std::fs::remove_dir_all(&staged);
    if !output.status.success() || !lib.is_file() {
        let text = String::from_utf8_lossy(&output.stderr);
        let lines: Vec<&str> = text.lines().filter(|l| !l.trim().is_empty()).collect();
        let tail = lines[lines.len().saturating_sub(12)..].join("\n");
        tracing::warn!("[Horizon] compilation de {} échouée :\n{text}", bundle.id);
        return Err(Reason::Compile { log: tail });
    }
    let _ = std::fs::write(&saved, fingerprint);
    Ok(lib)
}

/// Le point d'entrée d'un mod, rempli depuis le modèle du SDK comme le fait
/// leur launcher (`prebuilt::library_entry`).
fn entry_source(template: &str, bundle: &Bundle, commit: &str) -> String {
    let side = match bundle.side.as_str() {
        "server" => "Server",
        "both" => "Both",
        _ => "Client",
    };
    let fill = |text: &str, marker: &str, value: &str| -> String {
        match text.find(marker) {
            Some(i) => format!("{}{}{}", text[..i].trim_end_matches([' ', '\t']), value, &text[i + marker.len()..]),
            None => text.to_string(),
        }
    };
    let mut text = template.to_string();
    text = fill(&text, "/* MOD_ENTRIES */", &format!("        {:?} => crate::hooks,\n", bundle.id));
    text = fill(&text, "/* MOD_SLOTS */", "");
    text = fill(&text, "/* MOD_REDIRECTS */", "");
    text = fill(
        &text,
        "/* BUILT_MODS */",
        &format!(
            "    mod_api::__private::native::BuiltMod {{ id: {:?}, version: {:?}, side: mod_api::Side::{side} }},\n",
            bundle.id, bundle.version
        ),
    );
    text.replace(
        "/* NATIVE_BUILD */",
        &format!("{commit} {} {} {}", bundle.id, bundle.version, super::toolchain::VERSION),
    )
}

/// Le point d'entrée a besoin de code non sûr : un mod qui l'interdit dans
/// tout son crate (`#![forbid(unsafe_code)]`) ne peut pas le recevoir.
fn forbids_unsafe(root: &str) -> bool {
    root.lines()
        .map(str::trim_start)
        .filter(|l| !l.starts_with("//"))
        .any(|l| l.starts_with("#![") && l.contains("forbid") && l.contains("unsafe_code"))
}

/// Pose le jeu assemblé dans `dir`, en entier ou pas du tout.
fn assemble(rust: &Toolchain, sdk: &Sdk, base_dir: &Path, compiled: &[(Bundle, PathBuf)], dir: &Path, left_out: &[LeftOut]) -> Result<()> {
    let runtime = sdk.runtime();
    let partial = dir.with_extension("partial");
    let _ = std::fs::remove_dir_all(&partial);
    std::fs::create_dir_all(&partial)?;

    // Les fichiers de la version d'origine qu'utilise le jeu (compilateur de
    // shaders, bibliothèque des mods WebAssembly, épinglage), sauf son
    // exécutable : c'est celui du SDK qui charge les mods.
    for entry in std::fs::read_dir(base_dir)?.flatten() {
        let name = entry.file_name();
        let text = name.to_string_lossy();
        if entry.path().is_file() && text != CLIENT && !text.starts_with('.') && text != "native-mods.json" {
            std::fs::copy(entry.path(), partial.join(&name))?;
        }
    }
    std::fs::copy(runtime.join("bin").join(CLIENT), partial.join(CLIENT))?;
    let mut libraries: Vec<PathBuf> = std::fs::read_dir(runtime.join("deps"))?
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|e| e == "dll"))
        .collect();
    let std_dll = std::fs::read_dir(rust.target_libdir())?
        .flatten()
        .map(|e| e.path())
        .find(|p| {
            let name = p.file_name().unwrap_or_default().to_string_lossy();
            name.starts_with("std-") && name.ends_with(".dll")
        })
        .ok_or_else(|| anyhow!("La bibliothèque standard de Rust manque au compilateur"))?;
    libraries.push(std_dll);
    // Les DLL de MinGW qu'importent celles du jeu.
    for name in ["libgcc_s_seh-1.dll", "libwinpthread-1.dll"] {
        libraries.push(rust.mingw_bin().join(name));
    }
    for library in &libraries {
        let name = library.file_name().ok_or_else(|| anyhow!("bibliothèque sans nom"))?;
        std::fs::copy(library, partial.join(name))?;
    }

    let mut records = Vec::new();
    for (bundle, lib) in compiled {
        let name = lib.file_name().ok_or_else(|| anyhow!("bibliothèque sans nom"))?;
        std::fs::copy(lib, partial.join(name))?;
        records.push(serde_json::json!({
            "file": name.to_string_lossy(),
            "sha256": format!("{:x}", Sha256::digest(std::fs::read(lib)?)),
            "kind": "mod",
            "mod": bundle.id,
            "identity": format!("{} {} {} {}", sdk.index.build.commit, bundle.id, bundle.version, super::toolchain::VERSION),
        }));
        // Les fichiers du mod (assets, données) : le jeu les lit depuis le
        // dossier des mods natifs de son build.
        let mod_dir = partial.join(NATIVE_DIR).join(&bundle.id);
        for (name, bytes) in &bundle.files {
            let to = mod_dir.join(name);
            std::fs::create_dir_all(to.parent().unwrap_or(&mod_dir))?;
            std::fs::write(to, bytes)?;
        }
    }
    let about = serde_json::json!({
        "base": base_dir.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default(),
        "horizon": sdk.index.build.commit,
        "rust": super::toolchain::VERSION,
        "profile": "optimized",
        "route": "api",
        "left_out": left_out.iter().map(|l| serde_json::json!({ "id": l.id })).collect::<Vec<_>>(),
        "target": "client",
        "rust_target": super::toolchain::TARGET,
        "mods": compiled.iter().map(|(b, _)| serde_json::json!({ "id": b.id, "version": b.version, "sha256": b.sha256, "internals": [] })).collect::<Vec<_>>(),
        "libraries": records,
    });
    std::fs::write(partial.join("native-mods.json"), about.to_string())?;
    let _ = std::fs::remove_dir_all(dir);
    std::fs::rename(&partial, dir)?;
    std::fs::write(dir.join(".installed"), &sdk.index.build.commit)?;
    Ok(())
}

/// Garde les trois jeux assemblés les plus récents, plus celui qu'on lance.
fn prune(current: &Path) {
    let mut builds: Vec<(std::time::SystemTime, PathBuf)> = std::fs::read_dir(builds_dir())
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.join(".installed").is_file())
        .filter_map(|p| Some((std::fs::metadata(p.join(".installed")).ok()?.modified().ok()?, p)))
        .collect();
    builds.sort_by(|a, b| b.0.cmp(&a.0));
    for (_, path) in builds.into_iter().skip(3) {
        if path != current {
            let _ = std::fs::remove_dir_all(path);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn point_d_entree() {
        let template = "native_mods! {\n    /* MOD_ENTRIES */\n    }\nvec![\n/* MOD_SLOTS */\n    ]\nstatic BUILT: &[X] = &[\n/* BUILT_MODS */\n];\nb\"/* NATIVE_BUILD */\\0\"";
        let bundle = Bundle {
            id: "appleskin_hzn".into(),
            version: "0.2.0".into(),
            side: "client".into(),
            made_for: String::new(),
            engine: None,
            inject: false,
            files: Vec::new(),
            sha256: String::new(),
            cargo: toml::Table::new(),
            depends: Vec::new(),
        };
        let text = entry_source(template, &bundle, "abc");
        assert!(text.contains("\"appleskin_hzn\" => crate::hooks,"));
        assert!(text.contains("side: mod_api::Side::Client"));
        assert!(text.contains("abc appleskin_hzn 0.2.0 1.99.1"));
        assert!(!text.contains("/*"));
    }

    /// La chaîne entière sur un vrai mod, avec le compilateur et le SDK déjà
    /// installés : `YF_NATIVE_HMOD=<fichier .hmod> YF_NATIVE_BASE=<dossier de
    /// version> cargo test --lib compile_un_vrai_mod -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn compile_un_vrai_mod() {
        let hmod = PathBuf::from(std::env::var("YF_NATIVE_HMOD").expect("YF_NATIVE_HMOD"));
        let base = PathBuf::from(std::env::var("YF_NATIVE_BASE").expect("YF_NATIVE_BASE"));
        let commit = std::fs::read(base.join("sdk.json"))
            .ok()
            .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
            .and_then(|v| v["build"]["commit"].as_str().map(str::to_string))
            .or_else(|| std::fs::read_to_string(base.join(".build-commit")).ok())
            .expect("commit du build");
        let rust = super::super::toolchain::installed().expect("compilateur installé");
        let dir = super::super::sdk::dir_for(&commit);
        let index: super::super::sdk::Index =
            serde_json::from_slice(&std::fs::read(dir.join("sdk.json")).expect("sdk.json")).unwrap();
        let sdk = Sdk { index, dir };
        let prebuilt = super::super::sdk::prebuilt(&sdk.runtime()).unwrap();
        let bundle = read_bundle(&hmod).unwrap();
        let surface = {
            let bytes = std::fs::read(sdk.dir.join("surface.json")).ok();
            bytes.map(|b| {
                let mut out = HashMap::new();
                super::super::sdk::tests_collect(&serde_json::from_slice(&b).unwrap(), &mut out);
                out
            })
        };
        judge(&bundle, &commit, surface.as_ref(), &prebuilt, &sdk.api().join("deps")).expect("compatible");
        let started = std::time::Instant::now();
        let built = build(&rust, &sdk, &base, vec![bundle], Vec::new(), &|id, n, t| println!("{id} {n}/{t}"), &|| false).unwrap();
        println!("{:?} en {:?}, laissés : {:?}", built.dir, started.elapsed(), built.left_out);
        assert_eq!(built.mods.len(), 1);
        assert!(built.dir.join("native-mods.json").is_file());
        assert!(built.dir.join(CLIENT).is_file());
    }

    #[test]
    fn code_non_sur_interdit() {
        assert!(forbids_unsafe("#![forbid(unsafe_code)]\nmod a;"));
        assert!(!forbids_unsafe("#![deny(unsafe_code)]"));
        assert!(!forbids_unsafe("// #![forbid(unsafe_code)]"));
    }
}
