//! Les `[[inject]]` d'un mod natif : un écouteur posé à un point que le jeu a
//! déjà compilé (`points.json` du SDK). Le jeu n'est jamais recompilé pour ça :
//! le point d'entrée du mod reçoit une expression par ligne, à la place du
//! marqueur `MOD_SLOTS` de son modèle.
//!
//! Porté de leur code (`horizon-mod-point-emit`, `launcher/src/native_mods/
//! slots.rs` et `manifest/inject.rs`, build `f037744`) : mêmes règles, mêmes
//! expressions. Une ligne qui ne trouve pas son point, dont la fonction a
//! changé depuis l'empreinte qu'elle épingle, ou dont la forme n'existe pas à
//! ce point laisse le mod de côté avec la raison.

use serde::Deserialize;
use std::path::Path;

/// Un point du jeu, tel que `points.json` le liste.
#[derive(Deserialize, Clone, Debug)]
pub struct Point {
    pub name: String,
    pub target: String,
    #[serde(default)]
    pub at_return: bool,
    #[serde(default)]
    pub api_refusal: Option<String>,
    #[serde(default)]
    pub returns: String,
    #[serde(default)]
    pub host: String,
    #[serde(default)]
    pub return_ty: Option<String>,
}

#[derive(Deserialize)]
struct Internal {
    path: String,
    kind: String,
    hash: String,
    #[serde(default)]
    shape_only: bool,
}

/// Les points et les fonctions du jeu d'un build.
pub struct Catalog {
    points: Vec<Point>,
    internals: Vec<Internal>,
}

impl Catalog {
    /// `points.json` et `internals.json` de la tranche `api` du SDK.
    pub fn read(api: &Path, commit: &str) -> anyhow::Result<Self> {
        #[derive(Deserialize)]
        struct Build {
            commit: String,
        }
        #[derive(Deserialize)]
        struct Points {
            build: Build,
            points: Vec<Point>,
        }
        #[derive(Deserialize)]
        struct Internals {
            build: Build,
            internals: Vec<Internal>,
        }
        let points: Points = serde_json::from_slice(&std::fs::read(api.join("points.json"))?)?;
        let internals: Internals = serde_json::from_slice(&std::fs::read(api.join("internals.json"))?)?;
        if points.build.commit != commit || internals.build.commit != commit {
            return Err(anyhow::anyhow!("les catalogues du SDK sont ceux d'un autre build"));
        }
        Ok(Self { points: points.points, internals: internals.internals })
    }

    fn function_hash(&self, at: &str) -> Option<&str> {
        self.internals
            .iter()
            .find(|i| !i.shape_only && plain_path(&i.path) == plain_path(at) && matches!(i.kind.as_str(), "fn" | "method" | "struct"))
            .map(|i| i.hash.as_str())
    }
}

/// Une ligne `[[inject]]`.
#[derive(Deserialize, Clone, Debug)]
#[serde(deny_unknown_fields)]
pub struct Inject {
    pub id: String,
    pub at: String,
    pub kind: String,
    pub with: String,
    #[serde(default)]
    pub priority: i32,
    #[serde(default)]
    pub hash: Option<String>,
}

const RESERVED: &str = concat!(
    "_ as async await break const continue crate dyn else enum extern false fn for if ",
    "impl in let loop match mod move mut pub ref return self Self static struct super ",
    "trait true type unsafe use where while abstract become box do final gen macro ",
    "override priv try typeof unsized virtual yield"
);

/// Les lignes `[[inject]]` du manifeste, vérifiées une à une.
pub fn rows(manifest: &toml::Table, mod_id: &str) -> Result<Vec<Inject>, String> {
    let Some(value) = manifest.get("inject") else {
        return Ok(Vec::new());
    };
    let table = value.as_array().ok_or("`inject` n'est pas une liste de lignes")?;
    let mut rows = Vec::new();
    let mut ids = std::collections::HashSet::new();
    for row in table {
        if row.get("access").is_some() {
            return Err("champ `access` retiré par Horizon".into());
        }
        let row: Inject = row.clone().try_into().map_err(|e| format!("ligne [[inject]] illisible : {e}"))?;
        let name = format!("{mod_id}:{}", row.id);
        if !matches!(row.kind.as_str(), "head" | "return" | "replace") {
            return Err(format!("{name} : genre `{}` inconnu ou retiré", row.kind));
        }
        if row.id.trim().is_empty() || !ids.insert(row.id.clone()) {
            return Err(format!("{name} : identifiant vide ou en double"));
        }
        if row.at.trim().is_empty() {
            return Err(format!("{name} : `at` vide"));
        }
        if row.hash.as_ref().is_some_and(|h| h.trim().is_empty()) {
            return Err(format!("{name} : `hash` vide"));
        }
        let with = row.with.as_bytes();
        if RESERVED.split_whitespace().any(|w| w == row.with)
            || with.is_empty()
            || with[0].is_ascii_digit()
            || !with.iter().all(|b| b.is_ascii_alphanumeric() || *b == b'_')
        {
            return Err(format!("{name} : `with` doit nommer une fonction à la racine du mod"));
        }
        rows.push(row);
    }
    Ok(rows)
}

/// Retire les arguments de type et les espaces, comme leur launcher pour
/// comparer deux chemins.
pub fn plain_path(path: &str) -> String {
    let mut depth = 0usize;
    path.chars()
        .filter(|&c| match c {
            '<' => {
                depth += 1;
                false
            }
            '>' => {
                depth = depth.saturating_sub(1);
                false
            }
            _ => depth == 0 && !c.is_whitespace(),
        })
        .collect()
}

fn replaceable(name: &str, at: &str, point: &Point) -> Result<(), String> {
    if point.host.is_empty() {
        return Err(format!("{name} : le catalogue ne dit pas qui reçoit l'écouteur à {at}"));
    }
    let ty = match point.returns.as_str() {
        "result" => return Ok(()),
        "cancel" => match point.return_ty.as_deref() {
            Some("()") => return Ok(()),
            Some(ty) => format!("`{ty}`"),
            None => return Err(format!("{name} : le catalogue ne dit pas ce que rend {at}")),
        },
        _ => point.return_ty.as_ref().map_or("son type de retour".to_owned(), |ty| format!("`{ty}`")),
    };
    Err(format!("{name} : pas de `replace` à {at}, {ty} n'a pas de forme dans l'API"))
}

/// Les expressions à poser à la place de `MOD_SLOTS` pour ce mod, dans l'ordre
/// du manifeste ; `prefix` précède chaque fonction `with` (`crate::`).
pub fn slots(catalog: &Catalog, mod_id: &str, rows: &[Inject], prefix: &str) -> Result<Vec<String>, String> {
    rows.iter()
        .map(|row| {
            let name = format!("{mod_id}:{}", row.id);
            let point = catalog
                .points
                .iter()
                .find(|p| plain_path(&p.target) == plain_path(&row.at))
                .ok_or_else(|| format!("{name} : {} n'est pas un point de ce build", row.at))?;
            let hash = catalog
                .function_hash(&row.at)
                .ok_or_else(|| format!("{name} : {} n'a pas de signature dans ce build", row.at))?;
            if let Some(pinned) = row.hash.as_deref().filter(|h| *h != hash) {
                return Err(format!("{name} : {} a changé depuis que le mod l'a épinglée ({pinned} → {hash})", row.at));
            }
            if let Some(why) = &point.api_refusal {
                return Err(format!("{name} : pas d'écouteur possible à {} : {why}", row.at));
            }
            if row.kind == "return" && !point.at_return {
                return Err(format!("{name} : pas de point de retour à {}", row.at));
            }
            if row.kind == "replace" {
                replaceable(&name, &row.at, point)?;
            }
            let place = if row.kind == "return" { format!("{}_return", point.name) } else { point.name.clone() };
            let listener = format!("{prefix}{}", row.with);
            let constructor = format!("mod_points::listeners::{place}");
            Ok(if row.kind == "replace" {
                let host = if point.host == "none" { "none" } else { "host" };
                format!(
                    "mod_points::replace!({constructor}, {}, {host}, {mod_id:?}, {}, {:?}, {listener})",
                    point.returns, row.priority, row.id
                )
            } else {
                format!("{constructor}({mod_id:?}, {}, {:?}, {listener})", row.priority, row.id)
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn catalog() -> Catalog {
        let point = |name: &str, target: &str, returns: &str, ty: &str| Point {
            name: name.into(),
            target: target.into(),
            at_return: true,
            api_refusal: None,
            returns: returns.into(),
            host: "both".into(),
            return_ty: Some(ty.into()),
        };
        Catalog {
            points: vec![
                point("tick", "horizon_client::game::Game<'_>::tick", "cancel", "()"),
                point("score", "horizon_client::game::Game<'_>::score", "cancel", "u32"),
            ],
            internals: vec![
                Internal { path: "horizon_client::game::Game<'_>::tick".into(), kind: "method".into(), hash: "aa".into(), shape_only: false },
                Internal { path: "horizon_client::game::Game<'_>::score".into(), kind: "method".into(), hash: "bb".into(), shape_only: false },
            ],
        }
    }

    fn manifest(rows: &str) -> toml::Table {
        format!("id = \"m\"\n{rows}").parse().unwrap()
    }

    /// Les mêmes expressions que leur `horizon-mod-point-emit`.
    #[test]
    fn expressions_de_leur_emetteur() {
        let m = manifest(
            "[[inject]]\nid = \"before\"\nat = \"horizon_client::game::Game<'_>::tick\"\nkind = \"head\"\nwith = \"head\"\npriority = 12\n\
             [[inject]]\nid = \"after\"\nat = \"horizon_client::game::Game::tick\"\nkind = \"return\"\nwith = \"tail\"\n\
             [[inject]]\nid = \"swap\"\nat = \"horizon_client::game::Game::tick\"\nkind = \"replace\"\nwith = \"swap\"\nhash = \"aa\"\n",
        );
        let rows = rows(&m, "m").unwrap();
        let out = slots(&catalog(), "m", &rows, "crate::").unwrap();
        assert_eq!(out[0], "mod_points::listeners::tick(\"m\", 12, \"before\", crate::head)");
        assert_eq!(out[1], "mod_points::listeners::tick_return(\"m\", 0, \"after\", crate::tail)");
        assert_eq!(out[2], "mod_points::replace!(mod_points::listeners::tick, cancel, host, \"m\", 0, \"swap\", crate::swap)");
    }

    #[test]
    fn refus() {
        let c = catalog();
        let one = |row: &str| slots(&c, "m", &rows(&manifest(row), "m").unwrap(), "crate::");
        // Empreinte épinglée qui ne correspond plus.
        assert!(one("[[inject]]\nid = \"a\"\nat = \"horizon_client::game::Game::tick\"\nkind = \"head\"\nwith = \"f\"\nhash = \"zz\"\n").is_err());
        // Point absent.
        assert!(one("[[inject]]\nid = \"a\"\nat = \"horizon_client::nope\"\nkind = \"head\"\nwith = \"f\"\n").is_err());
        // Un `replace` à un point qui rend autre chose que `()`.
        assert!(one("[[inject]]\nid = \"a\"\nat = \"horizon_client::game::Game::score\"\nkind = \"replace\"\nwith = \"f\"\n").is_err());
        // Genre retiré, `with` réservé.
        assert!(rows(&manifest("[[inject]]\nid = \"a\"\nat = \"x\"\nkind = \"modify_arg\"\nwith = \"f\"\n"), "m").is_err());
        assert!(rows(&manifest("[[inject]]\nid = \"a\"\nat = \"x\"\nkind = \"head\"\nwith = \"fn\"\n"), "m").is_err());
    }
}
