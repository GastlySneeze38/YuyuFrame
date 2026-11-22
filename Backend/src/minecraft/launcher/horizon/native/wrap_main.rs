//! `yuyu-rustc-wrap` : l'intermédiaire de rustc que cargo appelle
//! (`RUSTC_WRAPPER`) pour les mods natifs qui ont leurs propres paquets.
//!
//! Ce fichier n'est pas compilé avec le launcher : il est inclus comme texte
//! (`registry.rs`) puis compilé seul par le rustc des mods, sans dépendance.
//!
//! Il ne fait qu'une chose : quand cargo compile une crate du mod (son dossier
//! est dans le plan `YF_WRAP_PLAN`) pour la cible du jeu, il ajoute le fichier
//! d'arguments de cette crate — les bibliothèques du jeu (`--extern mod_api=…`)
//! et où les trouver. Toute autre compilation (paquets de crates.io, scripts
//! de build, macros procédurales, questions de cargo à rustc) passe telle quelle.
//!
//! Plan, une ligne par entrée, champs séparés par des tabulations :
//! `target⇥<cible>` et `crate⇥<dossier du Cargo.toml>⇥<fichier d'arguments>`.

use std::ffi::OsString;
use std::process::Command;

fn normalize(path: &str) -> String {
    path.trim_start_matches(r"\\?\").replace('/', "\\").trim_end_matches('\\').to_lowercase()
}

fn main() {
    let mut args: Vec<OsString> = std::env::args_os().skip(1).collect();
    if args.is_empty() {
        eprintln!("yuyu-rustc-wrap : aucun compilateur donné");
        std::process::exit(2);
    }
    let rustc = args.remove(0);
    if let (Some(plan), Some(dir)) = (std::env::var_os("YF_WRAP_PLAN"), std::env::var_os("CARGO_MANIFEST_DIR")) {
        let text = match std::fs::read_to_string(&plan) {
            Ok(text) => text,
            Err(e) => {
                eprintln!("yuyu-rustc-wrap : plan illisible : {e}");
                std::process::exit(2);
            }
        };
        let mut target = None;
        let mut crates = Vec::new();
        for line in text.lines() {
            let fields: Vec<&str> = line.split('\t').collect();
            match fields.as_slice() {
                ["target", t] => target = Some(t.to_string()),
                ["crate", d, f] => crates.push((normalize(d), f.to_string())),
                _ => {}
            }
        }
        let value = |key: &str| {
            args.windows(2)
                .find(|pair| pair[0] == key)
                .map(|pair| pair[1].to_string_lossy().into_owned())
        };
        let for_game = target.is_some() && value("--target") == target;
        let proc_macro = args.windows(2).any(|pair| pair[0] == "--crate-type" && pair[1] == "proc-macro");
        if for_game && !proc_macro {
            let dir = normalize(&dir.to_string_lossy());
            if let Some((_, file)) = crates.iter().find(|(d, _)| *d == dir) {
                let mut arg = OsString::from("@");
                arg.push(file);
                args.push(arg);
            }
        }
    }
    match Command::new(&rustc).args(&args).env_remove("YF_WRAP_PLAN").status() {
        Ok(status) => std::process::exit(status.code().unwrap_or(1)),
        Err(e) => {
            eprintln!("yuyu-rustc-wrap : {e}");
            std::process::exit(1);
        }
    }
}
