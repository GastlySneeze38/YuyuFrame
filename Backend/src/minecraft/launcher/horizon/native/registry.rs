//! Route « registry » : les mods natifs qui ont leurs propres paquets —
//! dépendances de crates.io, ou crates locales dans leur archive (Earshot,
//! Minelights, Storage Drawers, Voice Chat…).
//!
//! Même idée que leur launcher (`launcher/src/native_mods/registry_*.rs`,
//! build `f037744`), en plus court : **cargo** construit le mod et ses paquets
//! depuis crates.io, au verrou de versions que le mod livre (`Cargo.lock`), et
//! un intermédiaire de rustc à nous (`wrap_main.rs`) branche sur les crates du
//! mod — et seulement sur elles — les bibliothèques du jeu précompilé
//! (`mod_api`, `mod_points`, `mod_vocab`, les mods dont il dépend). Le jeu
//! n'est jamais recompilé ; le mod devient une DLL comme sur la route directe.
//!
//! Ce que fait cargo : télécharger des paquets de crates.io (dans notre
//! `cargo-home`) et exécuter leurs scripts de build. C'est le code que le
//! joueur accepte en autorisant les mods natifs (Paramètres → Stockage).
//!
//! Différences avec leur chaîne, assumées : le verrou du mod complète
//! en ligne ce qui lui manque au lieu de le refuser, et les correctifs de
//! paquets du jeu (`registry_patches` : wgpu-hal, naga, libmimalloc-sys) ne
//! sont pas appliqués — aucun mod du registre ne les tire.

use anyhow::{anyhow, Result};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};

use super::build::{entry_source, forbids_unsafe, library_in, Bundle, Ctx, Reason, INTERNALS_PACKAGE, SDK_PACKAGES};
use super::toolchain::{Toolchain, TARGET, VERSION};

const WRAP_MAIN: &str = include_str!("wrap_main.rs");
/// À monter quand la façon de préparer un mod pour cargo change.
const RECIPE: u32 = 1;

/// L'intermédiaire de rustc et le dossier de travail de cargo.
pub struct Tools {
    wrapper: PathBuf,
    target_dir: PathBuf,
}

impl Tools {
    /// Compile l'intermédiaire une fois par compilateur (quelques secondes,
    /// sans dépendance) ; le dossier de cargo est gardé d'une partie à
    /// l'autre pour le même build, ce qui évite de tout recompiler.
    pub fn ensure(rust: &Toolchain, work: &Path) -> Result<Self> {
        let hash = format!("{:x}", Sha256::digest(format!("{WRAP_MAIN}{VERSION}").as_bytes()));
        let dir = super::root().join("tools");
        let exe = dir.join(format!("yuyu-rustc-wrap-{}.exe", &hash[..12]));
        if !exe.is_file() {
            std::fs::create_dir_all(&dir)?;
            let source = dir.join("yuyu-rustc-wrap.rs");
            std::fs::write(&source, WRAP_MAIN)?;
            let partial = exe.with_extension("partial.exe");
            let output = rust
                .command()
                .args(["--edition", "2021", "-O", "--crate-type", "bin", "--crate-name", "yuyu_rustc_wrap", "-o"])
                .arg(&partial)
                .arg(&source)
                .output()?;
            if !output.status.success() {
                return Err(anyhow!("L'intermédiaire de rustc ne compile pas : {}", String::from_utf8_lossy(&output.stderr)));
            }
            std::fs::rename(&partial, &exe)?;
        }
        Ok(Self { wrapper: exe, target_dir: work.join("registry-target") })
    }

    /// Où cargo range les paquets qu'il construit pour la cible du jeu : la
    /// route engine en a besoin pour compiler les copies de fonctions qui
    /// appellent ces mods.
    pub fn deps_dir(&self) -> PathBuf {
        self.target_dir.join(TARGET).join("release").join("deps")
    }
}

/// Ce contre quoi les crates du mod se lient : le jeu, les mods dont il
/// dépend, la façade de ses internes (route engine).
struct Links<'a> {
    ctx: &'a Ctx,
    deps: &'a [(String, bool, PathBuf)],
    internals: Option<&'a Path>,
}

/// Les noms sous lesquels une dépendance peut désigner une crate (`a-b` et `a_b`).
fn same(a: &str, b: &str) -> bool {
    a.replace('-', "_") == b.replace('-', "_")
}

/// Ce qu'une crate du mod demande au jeu, une fois ses dépendances retirées.
#[derive(Default)]
struct Rewritten {
    externs: Vec<(String, PathBuf)>,
    /// Facultatives allumées : `--cfg feature="…"`, comme sur la route directe.
    features: Vec<String>,
}

/// Retire d'une table de dépendances les paquets du SDK et les autres mods,
/// et note ce qu'ils deviennent : des `--extern` vers le jeu ou leurs DLL.
fn strip_table(table: &mut toml::Table, links: &Links, removed: &mut Vec<String>, out: &mut Rewritten) -> std::result::Result<(), Reason> {
    let keys: Vec<String> = table.keys().cloned().collect();
    for key in keys {
        let renamed = table[&key].get("package").and_then(|p| p.as_str()).map(str::to_string);
        let package = renamed.clone().unwrap_or_else(|| key.clone());
        // Pour cargo, une dépendance non renommée porte le nom de la
        // bibliothèque du paquet (`horizon-mod-api` → `mod_api`).
        let name_for = |lib: &str| if renamed.is_some() { key.replace('-', "_") } else { lib.to_string() };
        if let Some((_, lib)) = SDK_PACKAGES.iter().find(|(p, _)| *p == package) {
            let path = library_in(&links.ctx.libs, lib).ok_or_else(|| Reason::Compile { log: format!("le jeu précompilé n'a pas {lib}") })?;
            out.externs.push((name_for(lib), path));
        } else if package == INTERNALS_PACKAGE {
            let path = links.internals.ok_or_else(|| Reason::Engine { error: "la façade de ses internes n'a pas été préparée".into() })?;
            out.externs.push((name_for("internals"), path.to_path_buf()));
        } else if let Some((id, optional, path)) = links.deps.iter().find(|(id, _, _)| same(id, &key) || same(id, &package)) {
            out.externs.push((key.replace('-', "_"), path.clone()));
            if *optional {
                out.features.push(id.clone());
            }
        } else if package.starts_with("horizon-") && table[&key].get("path").is_some() {
            // Une autre crate du SDK par son chemin `.horizon/sdk/…` : le jeu
            // ne la fournit pas aux mods.
            return Err(Reason::Crates { names: vec![key] });
        } else {
            continue;
        }
        table.remove(&key);
        removed.push(key);
    }
    Ok(())
}

/// Les fonctionnalités qui nomment une dépendance retirée n'ont plus de sens.
fn strip_features(manifest: &mut toml::Table, removed: &[String]) {
    let Some(features) = manifest.get_mut("features").and_then(|f| f.as_table_mut()) else {
        return;
    };
    for (_, list) in features.iter_mut() {
        if let Some(list) = list.as_array_mut() {
            list.retain(|v| {
                let Some(s) = v.as_str() else { return true };
                let name = s.trim_start_matches("dep:");
                let name = name.split('/').next().unwrap_or(name).trim_end_matches('?');
                !removed.iter().any(|r| same(r, name))
            });
        }
    }
}

/// Réécrit un `Cargo.toml` du mod. `root` : celui du mod lui-même.
fn rewrite(manifest: &mut toml::Table, root: bool, krate: &str, links: &Links) -> std::result::Result<Rewritten, Reason> {
    let mut out = Rewritten::default();
    let mut removed = Vec::new();
    manifest.remove("dev-dependencies");
    if let Some(table) = manifest.get_mut("dependencies").and_then(|t| t.as_table_mut()) {
        strip_table(table, links, &mut removed, &mut out)?;
    }
    if let Some(targets) = manifest.get_mut("target").and_then(|t| t.as_table_mut()) {
        for (_, target) in targets.iter_mut() {
            if let Some(target) = target.as_table_mut() {
                target.remove("dev-dependencies");
                if let Some(table) = target.get_mut("dependencies").and_then(|t| t.as_table_mut()) {
                    strip_table(table, links, &mut removed, &mut out)?;
                }
            }
        }
    }
    strip_features(manifest, &removed);
    // Seul le jeu donne des correctifs de paquets.
    manifest.remove("patch");
    manifest.remove("replace");
    if root {
        let workspace = manifest.entry("workspace").or_insert_with(|| toml::Table::new().into());
        if let Some(workspace) = workspace.as_table_mut() {
            if let Some(table) = workspace.get_mut("dependencies").and_then(|t| t.as_table_mut()) {
                let mut ignored = Rewritten::default();
                strip_table(table, links, &mut removed, &mut ignored)?;
            }
        }
        for target in ["bin", "example", "test", "bench"] {
            manifest.remove(target);
        }
        let lib = manifest.entry("lib").or_insert_with(|| toml::Table::new().into());
        if let Some(lib) = lib.as_table_mut() {
            lib.insert("crate-type".into(), vec![toml::Value::from("dylib")].into());
            lib.insert("name".into(), krate.into());
        }
        let mut release = toml::Table::new();
        release.insert("opt-level".into(), 3.into());
        release.insert("debug".into(), false.into());
        let mut profile = toml::Table::new();
        profile.insert("release".into(), release.into());
        manifest.insert("profile".into(), profile.into());
    } else {
        manifest.remove("workspace");
        manifest.remove("profile");
    }
    Ok(out)
}

/// Compile un mod par cargo. Rend sa DLL, posée dans `out` comme sur la route directe.
#[allow(clippy::too_many_arguments)]
pub(super) fn compile(
    rust: &Toolchain,
    ctx: &Ctx,
    out: &Path,
    bundle: &Bundle,
    deps: &[(String, bool, PathBuf)],
    internals: Option<&Path>,
    slots: &[String],
    template: &str,
    commit: &str,
    tools: Option<&std::result::Result<Tools, String>>,
) -> std::result::Result<PathBuf, Reason> {
    let links = Links { ctx, deps, internals };
    let tools = match tools {
        Some(Ok(tools)) => tools,
        Some(Err(e)) => return Err(Reason::Compile { log: e.clone() }),
        None => return Err(Reason::Compile { log: "outils de cargo non préparés".into() }),
    };
    let krate = bundle.crate_name();
    let lib = out.join(format!("{krate}.dll"));
    let entry = entry_source(template, bundle, commit, slots);

    let mut hash = Sha256::new();
    hash.update(&bundle.sha256);
    hash.update(&entry);
    hash.update(RECIPE.to_le_bytes());
    hash.update(tools.wrapper.to_string_lossy().as_bytes());
    for (_, _, dep) in deps {
        hash.update(std::fs::read(dep).unwrap_or_default());
    }
    if let Some(facade) = internals {
        hash.update(std::fs::read(facade).unwrap_or_default());
    }
    for folder in &ctx.search {
        hash.update(folder.to_string_lossy().as_bytes());
    }
    let fingerprint = format!("{:x}", hash.finalize());
    let saved = lib.with_extension("fingerprint");
    if lib.is_file() && std::fs::read_to_string(&saved).ok().as_deref() == Some(&fingerprint) {
        return Ok(lib);
    }

    let io = |e: std::io::Error| Reason::Compile { log: e.to_string() };
    // Le dossier du mod ne change pas d'une compilation à l'autre : cargo
    // reprend ce qu'il a déjà construit.
    let staged = out.join("registry").join(&bundle.id);
    let _ = std::fs::remove_dir_all(&staged);
    for (name, bytes) in &bundle.files {
        let to = staged.join(name);
        std::fs::create_dir_all(to.parent().unwrap_or(&staged)).map_err(io)?;
        std::fs::write(&to, bytes).map_err(io)?;
    }

    // Chaque Cargo.toml du mod, puis un fichier d'arguments par crate qui en a besoin.
    let mut plan = format!("target\t{TARGET}\n");
    let args_dir = out.join("registry-args");
    std::fs::create_dir_all(&args_dir).map_err(io)?;
    let manifests: Vec<String> = bundle
        .files
        .iter()
        .map(|(name, _)| name.clone())
        .filter(|name| (name == "Cargo.toml" || name.ends_with("/Cargo.toml")) && !name.starts_with(".horizon/") && !name.contains("/target/"))
        .collect();
    if !manifests.iter().any(|n| n == "Cargo.toml") {
        return Err(Reason::Compile { log: "le mod n'a pas de Cargo.toml".into() });
    }
    for name in &manifests {
        let path = staged.join(name);
        let root = name == "Cargo.toml";
        let text = std::fs::read_to_string(&path).map_err(io)?;
        let mut manifest: toml::Table = text.parse().map_err(|e| Reason::Compile { log: format!("{name} : {e}") })?;
        let mut rewritten = rewrite(&mut manifest, root, &krate, &links)?;
        std::fs::write(&path, manifest.to_string()).map_err(io)?;
        if root {
            // Le point d'entrée nomme toujours ces trois-là.
            for name in ["mod_api", "mod_vocab", "mod_points"] {
                if !rewritten.externs.iter().any(|(n, _)| n == name) {
                    let path = library_in(&ctx.libs, name).ok_or_else(|| Reason::Compile { log: format!("le jeu précompilé n'a pas {name}") })?;
                    rewritten.externs.push((name.to_string(), path));
                }
            }
        }
        if rewritten.externs.is_empty() {
            continue;
        }
        let mut args = Vec::new();
        for folder in &ctx.search {
            args.push(format!("-L\ndependency={}", folder.display()));
        }
        if let Some(native) = &ctx.native {
            args.push(format!("-L\nnative={}", native.display()));
        }
        args.push("-C\nprefer-dynamic".into());
        for (name, path) in &rewritten.externs {
            args.push(format!("--extern\n{name}={}", path.display()));
        }
        for feature in &rewritten.features {
            args.push(format!("--cfg\nfeature=\"{feature}\""));
        }
        let text = args.join("\n");
        // Nommé par son contenu : cargo voit un argument différent quand il
        // change, et recompile.
        let file = args_dir.join(format!("{:x}.txt", Sha256::digest(text.as_bytes())));
        std::fs::write(&file, &text).map_err(io)?;
        let dir = path.parent().unwrap_or(&staged);
        plan.push_str(&format!("crate\t{}\t{}\n", dir.display(), file.display()));
    }

    let source = staged.join(bundle.lib_path());
    let mut root = std::fs::read_to_string(&source).map_err(io)?;
    if forbids_unsafe(&root) {
        return Err(Reason::ForbidsUnsafe);
    }
    std::fs::write(source.with_file_name("__horizon_entry.rs"), &entry).map_err(io)?;
    root.push_str("\n#[allow(unsafe_code)]\n#[path = \"__horizon_entry.rs\"] mod __horizon_entry;\n");
    std::fs::write(&source, root).map_err(io)?;

    let plan_file = out.join(format!("registry-{}.plan", bundle.id));
    std::fs::write(&plan_file, plan).map_err(io)?;
    let mut cmd = rust.program(&rust.cargo());
    cmd.args(["build", "--lib", "--release", "--message-format", "short", "--target", TARGET, "--manifest-path"])
        .arg(staged.join("Cargo.toml"))
        .env("CARGO_TARGET_DIR", &tools.target_dir)
        .env("RUSTC_WRAPPER", &tools.wrapper)
        .env("YF_WRAP_PLAN", &plan_file)
        .env("CARGO_TERM_COLOR", "never")
        .current_dir(&staged);
    let output = cmd.output().map_err(|e| Reason::Compile { log: format!("cargo ne démarre pas : {e}") })?;
    let built = tools.target_dir.join(TARGET).join("release").join(format!("{krate}.dll"));
    if !output.status.success() || !built.is_file() {
        let text = String::from_utf8_lossy(&output.stderr);
        tracing::warn!("[Horizon] compilation par cargo de {} échouée :\n{text}", bundle.id);
        let lines: Vec<&str> = text.lines().filter(|l| !l.trim().is_empty()).collect();
        // Les lignes d'erreur d'abord : cargo finit par un résumé peu parlant.
        let errors: Vec<&str> = lines.iter().copied().filter(|l| l.contains("error")).collect();
        let shown = if errors.is_empty() { &lines[lines.len().saturating_sub(12)..] } else { &errors[errors.len().saturating_sub(12)..] };
        return Err(Reason::Compile { log: shown.join("\n") });
    }
    std::fs::copy(&built, &lib).map_err(io)?;
    let _ = std::fs::write(&saved, fingerprint);
    Ok(lib)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx(dir: &Path) -> Ctx {
        Ctx { search: vec![dir.to_path_buf()], native: None, libs: dir.to_path_buf(), facades: dir.to_path_buf() }
    }

    /// Les paquets du SDK et les autres mods sortent du Cargo.toml et
    /// deviennent des `--extern` ; les paquets de crates.io et les crates
    /// locales restent à cargo.
    #[test]
    fn reecriture_du_cargo_toml() {
        let dir = std::env::temp_dir().join("yf-registry-test");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("mod_api.dll"), b"").unwrap();
        std::fs::write(dir.join("mod_points.dll"), b"").unwrap();
        let mut manifest: toml::Table = r#"
[package]
name = "mod-earshot"
edition = "2024"
[dependencies]
horizon-mod-api = { path = ".horizon/sdk/api/crates/horizon-mod-api" }
points = { package = "horizon-mod-points", path = ".horizon/sdk/api/crates/horizon-mod-points" }
earshot-core = { path = "src/core" }
game = { package = "horizon-internals", path = ".horizon/internals" }
serde_json = "1"
other_mod = { path = "../other", optional = true }
[dev-dependencies]
tempfile = "3"
[features]
extra = ["dep:other_mod", "serde_json/std"]
[[bin]]
name = "tool"
[patch.crates-io]
serde = { path = "x" }
"#
        .parse()
        .unwrap();
        let deps = vec![("other_mod".to_string(), true, dir.join("other_mod.dll"))];
        let ctx = ctx(&dir);
        let facade = dir.join("libinternals_m.rlib");
        let links = Links { ctx: &ctx, deps: &deps, internals: Some(&facade) };
        let out = rewrite(&mut manifest, true, "mod_earshot", &links).unwrap();
        let mut names: Vec<&str> = out.externs.iter().map(|(n, _)| n.as_str()).collect();
        names.sort();
        assert_eq!(names, ["game", "mod_api", "other_mod", "points"]);
        assert_eq!(out.features, ["other_mod"]);
        let deps = manifest["dependencies"].as_table().unwrap();
        assert!(deps.contains_key("earshot-core") && deps.contains_key("serde_json"));
        assert_eq!(deps.len(), 2);
        assert!(!manifest.contains_key("dev-dependencies") && !manifest.contains_key("patch") && !manifest.contains_key("bin"));
        assert_eq!(manifest["features"]["extra"].as_array().unwrap().len(), 1);
        assert_eq!(manifest["lib"]["crate-type"][0].as_str(), Some("dylib"));
        assert_eq!(manifest["lib"]["name"].as_str(), Some("mod_earshot"));
        assert!(manifest.contains_key("workspace"));
    }
}
