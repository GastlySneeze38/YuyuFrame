//! yuyu-horizon-gen : l'outil qui prépare les mods natifs « engine » d'Horizon.
//!
//! Ce fichier n'est PAS compilé avec le launcher. `native/engine.rs` le recopie,
//! avec un `Cargo.toml`, dans `<racine>/horizon/native/gen/<build>/` et le
//! compile avec le compilateur téléchargé, contre les crates d'Horizon
//! (`horizon-mod-api-gen`, `horizon-mod-package`) **prises dans le source du
//! build que joue l'instance** : leur générateur est donc toujours celui de ce
//! build, sans rien recopier de leur code dans notre dépôt.
//!
//! Logique reprise de leur launcher (`launcher/src/native_mods/patch.rs` et
//! `internals.rs`) : façades des internes déclarés, puis copies des fonctions
//! visées par les `[[native.mixin]]` (avec modules, ponts et boucles), et les
//! entrées de `horizon-mixins` réécrites vers ces copies.
//!
//! Entrée : un fichier JSON (`Request`) ; sortie : du JSON (`Response`) sur stdout.

use horizon_mod_api_gen as api_gen;
use horizon_mod_package as package;
use package::internals::{self as internals, ListedInternal};
use package::manifest::mixin::Kind;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::fs;
use std::path::{Path, PathBuf};

const CRATE: &str = "horizon_patched";

#[derive(Deserialize)]
struct Request {
    commit: String,
    source: PathBuf,
    functions: PathBuf,
    loops: PathBuf,
    internals: PathBuf,
    out: PathBuf,
    /// Les mods, dans l'ordre de compilation : identifiant et dossier décompressé.
    mods: Vec<(String, PathBuf)>,
}

#[derive(Serialize, Default)]
struct Response {
    facades: BTreeMap<String, Facade>,
    /// Les mods qui ne peuvent pas être préparés, et pourquoi.
    refused: BTreeMap<String, String>,
    patch: Option<Patch>,
}

#[derive(Serialize)]
struct Facade {
    source: String,
    crates: BTreeSet<String>,
}

#[derive(Serialize)]
struct Patch {
    workspace: PathBuf,
    mods: PathBuf,
    names: Vec<String>,
    installs: Vec<String>,
    /// Des `[[native.mixin]]` à appliquer par horizon-mixins.
    rows: bool,
}

struct Bundle {
    dir: PathBuf,
    manifest: package::Manifest,
}

impl Bundle {
    fn native(&self) -> &package::manifest::Native {
        self.manifest.native.as_ref().expect("mod natif")
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let result = match args.get(1).map(String::as_str) {
        Some("mixins-patch") => mixins_patch(Path::new(&args[2])),
        Some("prepare") => prepare(Path::new(&args[2])),
        _ => Err("usage: yuyu-horizon-gen mixins-patch <dir> | prepare <request.json>".into()),
    };
    match result {
        Ok(text) => println!("{text}"),
        Err(e) => {
            eprintln!("{e}");
            std::process::exit(1);
        }
    }
}

/// Applique au source de horizon-mixins le correctif que porte ce build.
fn mixins_patch(dir: &Path) -> Result<String, String> {
    package::mixins_tool::apply(dir, package::mixins_tool::PATCH)?;
    Ok(package::mixins_tool::REV.to_string())
}

fn prepare(request: &Path) -> Result<String, String> {
    let text = fs::read_to_string(request).map_err(|e| e.to_string())?;
    let request: Request = serde_json::from_str(&text).map_err(|e| e.to_string())?;
    let mut response = Response::default();
    let mut bundles = Vec::new();
    for (id, dir) in &request.mods {
        let parsed = fs::read_to_string(dir.join("horizon-mod.toml"))
            .map_err(|e| e.to_string())
            .and_then(|text| package::Manifest::parse(&text));
        match parsed {
            Ok(manifest) if manifest.native.is_some() => bundles.push(Bundle { dir: dir.clone(), manifest }),
            Ok(_) => {
                response.refused.insert(id.clone(), "n'est pas un mod natif".into());
            }
            Err(e) => {
                response.refused.insert(id.clone(), e);
            }
        }
    }

    // Les internes déclarés : une façade de `pub use` par mod, rien de plus large.
    let listed = internals::read(&request.internals)?;
    for bundle in &bundles {
        if bundle.manifest.internals.is_empty() {
            continue;
        }
        match facade(&listed, &bundle.manifest) {
            Ok(facade) => {
                response.facades.insert(bundle.manifest.id.clone(), facade);
            }
            Err(e) => {
                response.refused.insert(bundle.manifest.id.clone(), e);
            }
        }
    }
    bundles.retain(|b| !response.refused.contains_key(&b.manifest.id));

    // Les copies des fonctions : un mod refusé par le générateur est retiré et
    // on recommence sans lui, comme leur launcher.
    loop {
        match patch(&request, &bundles) {
            Ok(patch) => {
                response.patch = patch;
                break;
            }
            Err(refusals) => {
                let before = bundles.len();
                for (ids, message) in refusals {
                    for id in ids {
                        response.refused.entry(id).or_insert_with(|| message.clone());
                    }
                }
                bundles.retain(|b| !response.refused.contains_key(&b.manifest.id));
                if bundles.len() == before {
                    return Err("le générateur refuse des fonctions qu'aucun mod ne demande".into());
                }
            }
        }
    }
    serde_json::to_string(&response).map_err(|e| e.to_string())
}

// ── Internes ─────────────────────────────────────────────────────────────────

fn facade(listed: &[ListedInternal], manifest: &package::Manifest) -> Result<Facade, String> {
    let describe = |e: internals::Error| format!("{e:?}");
    let mut tree = Module::default();
    let mut crates = BTreeSet::from(["horizon_client".to_string()]);
    for declared in &manifest.internals {
        internals::check(listed, &manifest.id, declared).map_err(describe)?;
        let entry = internals::resolve(listed, &manifest.id, &declared.path, declared.kind.as_deref()).map_err(describe)?;
        let path = export_path(listed, entry)?;
        tree.export(&path, &mut crates);
    }
    let mut source = String::from("//! This mod's declared game internals, generated from its pinned build.\n");
    tree.write(&mut source, 0);
    Ok(Facade { source, crates })
}

fn export_path(listed: &[ListedInternal], entry: &ListedInternal) -> Result<String, String> {
    let invalid = || format!("E_INTERNAL_PATH: {} ne peut pas être réexporté", entry.path);
    if entry.kind == "mod" {
        return Err(invalid());
    }
    let member = matches!(entry.kind.as_str(), "field" | "method") || entry.signature.starts_with("impl ");
    let path = if member { entry.path.rsplit_once("::").ok_or_else(invalid)?.0 } else { &entry.path };
    let path = plain_path(path);
    syn::parse_str::<syn::Path>(&path).map_err(|_| invalid())?;
    if !member {
        return Ok(path);
    }
    let name = path.rsplit("::").next().ok_or_else(invalid)?;
    let mut scope = path.rsplit_once("::").ok_or_else(invalid)?.0;
    loop {
        if entry.kind == "field" && listed.iter().any(|e| e.path == scope && e.kind == "enum" && !e.shape_only) {
            return Ok(scope.to_owned());
        }
        let candidate = format!("{scope}::{name}");
        if listed.iter().any(|e| {
            e.path == candidate && matches!(e.kind.as_str(), "struct" | "enum" | "type" | "trait") && !e.shape_only
        }) {
            return Ok(candidate);
        }
        let Some((parent, _)) = scope.rsplit_once("::") else { break };
        scope = parent;
    }
    Err(invalid())
}

fn plain_path(path: &str) -> String {
    let mut depth = 0;
    path.chars()
        .filter(|&c| match c {
            '<' => {
                depth += 1;
                false
            }
            '>' => {
                depth -= 1;
                false
            }
            _ => depth == 0 && !c.is_whitespace(),
        })
        .collect()
}

#[derive(Default)]
struct Module {
    items: BTreeSet<String>,
    children: BTreeMap<String, Module>,
}

impl Module {
    fn export(&mut self, path: &str, crates: &mut BTreeSet<String>) {
        let parts: Vec<_> = path.split("::").collect();
        crates.insert(parts[0].to_owned());
        let parts = if parts[0] == "horizon_client" { &parts[1..] } else { &parts[..] };
        let mut module = self;
        for part in &parts[..parts.len() - 1] {
            module = module.children.entry((*part).to_owned()).or_default();
        }
        module.items.insert(format!("pub use {path};"));
    }

    fn write(&self, text: &mut String, depth: usize) {
        let indent = "    ".repeat(depth);
        for item in &self.items {
            text.push_str(&format!("{indent}{item}\n"));
        }
        for (name, child) in &self.children {
            text.push_str(&format!("{indent}pub mod {name} {{\n"));
            child.write(text, depth + 1);
            text.push_str(&format!("{indent}}}\n"));
        }
    }
}

// ── Copies des fonctions ─────────────────────────────────────────────────────

type Refusals = Vec<(Vec<String>, String)>;

fn patch(request: &Request, all: &[Bundle]) -> Result<Option<Patch>, Refusals> {
    let bundles: Vec<&Bundle> = all
        .iter()
        .filter(|b| {
            let n = b.native();
            !n.mixin.is_empty() || !n.module.is_empty() || !n.bridge.is_empty() || !n.state.is_empty() || !n.r#loop.is_empty()
        })
        .collect();
    if bundles.is_empty() {
        return Ok(None);
    }
    let fail = |ids: Vec<String>, message: String| vec![(ids, message)];
    let all_ids = || bundles.iter().map(|b| b.manifest.id.clone()).collect::<Vec<_>>();

    let loops = loop_slots(&request.loops, &bundles)?;
    let requests: Vec<(String, String, String)> = bundles
        .iter()
        .flat_map(|b| b.native().mixin.iter().map(|row| (b.manifest.id.clone(), row.id.clone(), row.target.clone())))
        .collect();
    let rows: Vec<String> = requests.iter().map(|(_, _, t)| t.clone()).collect();
    let index = function_index(&request.functions, &request.commit).map_err(|e| fail(all_ids(), e))?;
    let targets: Vec<String> = rows.iter().cloned().chain(loops.iter().map(|l| l.published.entry.clone())).collect();
    let mut extras = extras(&bundles).map_err(|e| fail(all_ids(), e))?;
    extras.loops = loops;
    let generated = match api_gen::patch::generate(&request.source, CRATE, &targets, &extras, &index) {
        Ok(generated) => generated,
        Err(refused) => {
            return Err(refused
                .iter()
                .map(|r| {
                    let mut owners: Vec<String> =
                        requests.iter().filter(|(_, _, t)| t == &r.target).map(|(id, _, _)| id.clone()).collect();
                    if owners.is_empty() {
                        // Un module ou un pont : son identifiant commence par celui du mod.
                        owners = bundles
                            .iter()
                            .filter(|b| r.target.starts_with(&format!("{}:", b.manifest.id)))
                            .map(|b| b.manifest.id.clone())
                            .collect();
                    }
                    if owners.is_empty() {
                        owners = all_ids();
                    }
                    (owners, r.to_string())
                })
                .collect());
        }
    };
    let ids: HashMap<&str, &str> = generated.patched.iter().map(|p| (p.target.as_str(), p.id.as_str())).collect();
    let io = |e: std::io::Error| fail(all_ids(), e.to_string());

    let workspace = request.out.join("patch-workspace");
    let _ = fs::remove_dir_all(&workspace);
    fs::create_dir_all(&workspace).map_err(io)?;
    let names: Vec<String> = generated.libs.iter().map(|(n, _)| n.clone()).collect();
    fs::write(
        workspace.join("Cargo.toml"),
        format!(
            "[workspace]\nmembers = [{}]\nresolver = \"2\"\n",
            names.iter().map(|n| format!("\"{n}\"")).collect::<Vec<_>>().join(", ")
        ),
    )
    .map_err(io)?;
    fs::write(
        workspace.join("Cargo.lock"),
        format!(
            "# Generated by the launcher.\nversion = 4\n{}",
            names.iter().map(|n| format!("\n[[package]]\nname = \"{n}\"\nversion = \"0.0.0\"\n")).collect::<String>()
        ),
    )
    .map_err(io)?;
    fs::write(workspace.join(".horizon-revision"), &request.commit).map_err(io)?;
    for (name, lib) in &generated.libs {
        let dir = workspace.join(name);
        fs::create_dir_all(dir.join("src")).map_err(io)?;
        fs::write(
            dir.join("Cargo.toml"),
            format!("[package]\nname = \"{name}\"\nversion = \"0.0.0\"\nedition = \"2024\"\n\n[lib]\npath = \"src/lib.rs\"\n"),
        )
        .map_err(io)?;
        fs::write(dir.join("src/lib.rs"), lib).map_err(io)?;
    }

    // Les règles de chaque mod, retournées vers les copies, pour horizon-mixins.
    let mods = request.out.join("patch-mods");
    let _ = fs::remove_dir_all(&mods);
    for bundle in bundles.iter().filter(|b| !b.native().mixin.is_empty()) {
        let dir = mods.join(&bundle.manifest.id);
        copy_dir(&bundle.dir.join("src"), &dir.join("src")).map_err(io)?;
        for row in &bundle.native().mixin {
            let from = bundle.dir.join(&row.helper);
            let to = dir.join(&row.helper);
            if let Some(parent) = to.parent() {
                fs::create_dir_all(parent).map_err(io)?;
            }
            let _ = fs::copy(from, to);
        }
        let mut rows = bundle.native().mixin.clone();
        for row in &mut rows {
            if let Some(id) = ids.get(row.target.as_str()) {
                row.target = (*id).to_owned();
            }
        }
        let mut manifest = toml::Table::new();
        manifest.insert(
            "mod".into(),
            toml::Value::Table(toml::Table::from_iter([
                ("id".into(), bundle.manifest.id.clone().into()),
                ("version".into(), bundle.manifest.version.to_string().into()),
            ])),
        );
        manifest.insert(
            "compatibility".into(),
            toml::Value::Table(toml::Table::from_iter([("horizon_revision".into(), request.commit.clone().into())])),
        );
        manifest.insert("injection".into(), toml::Value::try_from(&rows).map_err(|e| fail(all_ids(), e.to_string()))?);
        fs::write(dir.join("horizon-mixin.toml"), toml::to_string(&manifest).map_err(|e| fail(all_ids(), e.to_string()))?)
            .map_err(io)?;
    }

    let installs = generated
        .patched
        .iter()
        .filter_map(|p| {
            let mut owners: Vec<&str> =
                requests.iter().filter(|(_, _, t)| t == &p.target).map(|(id, _, _)| id.as_str()).collect();
            for l in &extras.loops {
                if l.published.entry == p.target {
                    owners.extend(l.slots.iter().map(|s| s.mod_id.as_str()));
                }
            }
            owners.sort_unstable();
            owners.dedup();
            Some(format!("({:?}, &{:?}, {})", p.target, owners, p.install.as_ref()?))
        })
        .collect();
    Ok(Some(Patch { workspace, mods, names, installs, rows: !rows.is_empty() }))
}

fn copy_dir(from: &Path, to: &Path) -> std::io::Result<()> {
    if !from.is_dir() {
        return Ok(());
    }
    fs::create_dir_all(to)?;
    for entry in fs::read_dir(from)? {
        let entry = entry?;
        let target = to.join(entry.file_name());
        if entry.path().is_dir() {
            copy_dir(&entry.path(), &target)?;
        } else {
            fs::copy(entry.path(), target)?;
        }
    }
    Ok(())
}

fn function_index(path: &Path, commit: &str) -> Result<api_gen::functions::FunctionIndex, String> {
    let text = fs::read_to_string(path).map_err(|e| format!("E_MIXIN_INDEX: {}: {e}", path.display()))?;
    let (build, index): (_, api_gen::functions::FunctionIndex) =
        package::build_data::read(api_gen::functions::FILE, &text).map_err(|e| format!("E_MIXIN_INDEX: {e}"))?;
    if build.commit != commit {
        return Err(format!("E_MIXIN_INDEX: functions.json décrit le build {}, pas {commit}", build.commit));
    }
    index.checked().map_err(|e| format!("E_MIXIN_INDEX: {e}"))
}

fn extras(bundles: &[&Bundle]) -> Result<api_gen::patch::Extras, String> {
    let mut extras = api_gen::patch::Extras::default();
    for bundle in bundles {
        for row in &bundle.native().mixin {
            let mut texts = vec![fs::read_to_string(bundle.dir.join(&row.helper)).unwrap_or_default()];
            fn strings(value: &toml::Value, out: &mut Vec<String>) {
                match value {
                    toml::Value::String(text) => out.push(text.replace('$', " ")),
                    toml::Value::Array(items) => items.iter().for_each(|v| strings(v, out)),
                    toml::Value::Table(table) => table.values().for_each(|v| strings(v, out)),
                    _ => {}
                }
            }
            for value in row.options.values() {
                strings(value, &mut texts);
            }
            for text in texts {
                if let Ok(tokens) = text.parse::<proc_macro2::TokenStream>() {
                    api_gen::patch::idents_in(&tokens, &mut extras.idents);
                }
            }
        }
    }
    let normalize = |s: &str| s.replace('-', "_").to_ascii_lowercase();
    for bundle in bundles {
        let id = &bundle.manifest.id;
        let native = bundle.native();
        for module in &native.module {
            let path = bundle.dir.join(&module.source);
            extras.modules.push(api_gen::patch::SupportModule {
                id: format!("{id}:{}", module.id),
                file: module.file.clone(),
                name: module.name.clone(),
                text: fs::read_to_string(&path).map_err(|e| format!("{}: {e}", path.display()))?,
                public: module.visibility.as_deref() == Some("pub"),
            });
        }
        for bridge in &native.bridge {
            let field = |key: &str| bridge.fields.get(key).and_then(toml::Value::as_str);
            extras.bridges.push(api_gen::patch::Bridge {
                id: format!("{id}:{}", bridge.id),
                method: format!("__horizon_mixins_{}_{}", normalize(id), normalize(&bridge.id)),
                kind: field("kind").unwrap_or_default().to_owned(),
                target: field("target").unwrap_or_default().to_owned(),
                member: field("member").map(str::to_owned),
            });
        }
    }
    Ok(extras)
}

fn loop_slots(path: &Path, bundles: &[&Bundle]) -> Result<Vec<api_gen::patch::LoopSlots>, Refusals> {
    #[derive(Deserialize)]
    struct Loops {
        loops: Vec<api_gen::loops::PublishedLoop>,
    }
    let published = match fs::read_to_string(path) {
        Ok(text) => package::build_data::read::<Loops>(api_gen::loops::FILE, &text)
            .map_err(|e| vec![(bundles.iter().map(|b| b.manifest.id.clone()).collect(), e)])?
            .1
            .loops,
        Err(_) => Vec::new(),
    };
    let mut refusals: Refusals = Vec::new();
    let mut filled: Vec<api_gen::patch::LoopSlots> = published
        .iter()
        .filter(|l| {
            let generic = l.generic_target();
            bundles.iter().any(|b| b.native().mixin.iter().any(|m| m.target == generic))
        })
        .map(|l| api_gen::patch::LoopSlots { published: l.clone(), slots: Vec::new() })
        .collect();
    let mut ordered: Vec<(&Bundle, &package::manifest::loops::Loop)> =
        bundles.iter().flat_map(|b| b.native().r#loop.iter().map(move |row| (*b, row))).collect();
    ordered.sort_by(|(a, ra), (b, rb)| (ra.priority, &a.manifest.id, &ra.id).cmp(&(rb.priority, &b.manifest.id, &rb.id)));
    for (bundle, row) in ordered {
        let id = &bundle.manifest.id;
        let Some(found) = published.iter().find(|l| l.name == row.name) else {
            refusals.push((vec![id.clone()], format!("E_LOOP_UNKNOWN: {id}:{}: le jeu ne publie pas de boucle `{}`", row.id, row.name)));
            continue;
        };
        let helper = match fs::read_to_string(bundle.dir.join(&row.helper)) {
            Ok(text) => text,
            Err(e) => {
                refusals.push((vec![id.clone()], format!("{}: {e}", row.helper)));
                continue;
            }
        };
        let slot = api_gen::patch::LoopSlot { mod_id: id.clone(), id: row.id.clone(), helper, with: row.with.clone() };
        match filled.iter_mut().find(|l| l.published.name == found.name) {
            Some(slots) => slots.slots.push(slot),
            None => filled.push(api_gen::patch::LoopSlots { published: found.clone(), slots: vec![slot] }),
        }
    }
    for bundle in bundles {
        for mixin in &bundle.native().mixin {
            let Some(slots) = filled.iter().find(|l| {
                !l.slots.is_empty()
                    && (l.published.entry == mixin.target
                        || (l.published.generic_target() == mixin.target && mixin.kind == Kind::Overwrite))
            }) else {
                continue;
            };
            if mixin.kind == Kind::Overwrite
                || !matches!(mixin.kind, Kind::Head | Kind::Return) && slots.published.entry == mixin.target
            {
                let mut owners = vec![bundle.manifest.id.clone()];
                owners.extend(slots.slots.iter().map(|s| s.mod_id.clone()));
                refusals.push((
                    owners,
                    format!(
                        "E_LOOP_ENTRY_REPLACED: {}:{} remplacerait la boucle `{}`",
                        bundle.manifest.id, mixin.id, slots.published.name
                    ),
                ));
            }
        }
    }
    if refusals.is_empty() { Ok(filled) } else { Err(refusals) }
}
