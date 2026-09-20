use std::cmp::Ordering;

/// Composants numériques en tête de version ("0.8.13-beta.1+mc1.21.11" → [0, 8, 13]).
/// Le suffixe pré-release/build est ignoré pour la comparaison : il suffit pour
/// exclure une version d'une plage qui se termine juste avant son cœur numérique.
pub fn version_core(v: &str) -> Vec<u64> {
    v.split(['.', '-', '+'])
        .take_while(|p| p.chars().all(|c| c.is_ascii_digit()) && !p.is_empty())
        .map(|p| p.parse().unwrap_or(0))
        .collect()
}

pub fn cmp_core(a: &[u64], b: &[u64]) -> Ordering {
    let len = a.len().max(b.len());
    for i in 0..len {
        let x = a.get(i).copied().unwrap_or(0);
        let y = b.get(i).copied().unwrap_or(0);
        match x.cmp(&y) {
            Ordering::Equal => continue,
            other => return other,
        }
    }
    Ordering::Equal
}

fn is_wildcard_component(s: &str) -> bool {
    matches!(s.to_ascii_lowercase().as_str(), "x" | "*" | "")
}

/// Un seul jeton de contrainte (">=0.8.4", "0.8.12", "0.8.x", "*"...).
pub fn token_satisfied(core: &[u64], token: &str) -> bool {
    let token = token.trim();
    if token.is_empty() || token == "*" {
        return true;
    }
    for op in [">=", "<=", ">", "<", "^", "~", "="] {
        if let Some(rest) = token.strip_prefix(op) {
            let target = version_core(rest);
            return match op {
                ">=" => cmp_core(core, &target) != Ordering::Less,
                "<=" => cmp_core(core, &target) != Ordering::Greater,
                ">" => cmp_core(core, &target) == Ordering::Greater,
                "<" => cmp_core(core, &target) == Ordering::Less,
                "^" => core.first() == target.first() && cmp_core(core, &target) != Ordering::Less,
                "~" => {
                    core.first() == target.first()
                        && core.get(1) == target.get(1)
                        && cmp_core(core, &target) != Ordering::Less
                }
                "=" => cmp_core(core, &target) == Ordering::Equal,
                _ => unreachable!(),
            };
        }
    }

    // x-range ("0.8.x", "1.x.x") : les composants avant le premier joker
    // doivent correspondre exactement, le reste est libre.
    let components: Vec<&str> = token.split('.').collect();
    if components.iter().any(|c| is_wildcard_component(c)) {
        let prefix: Vec<u64> = components
            .iter()
            .take_while(|c| !is_wildcard_component(c))
            .map(|c| c.parse().unwrap_or(0))
            .collect();
        return core.iter().take(prefix.len()).copied().collect::<Vec<_>>() == prefix;
    }

    // Pas d'opérateur ni de joker : version exacte
    cmp_core(core, &version_core(token)) == Ordering::Equal
}

/// Normalise un numéro de version qui peut porter la version MC et le loader
/// en préfixe (format Modrinth `mc1.21.11-0.8.12-fabric`) ou en suffixe
/// (format fabric.mod.json `0.8.12+mc1.21.11`) — extrait le cœur ("0.8.12")
/// commun aux deux conventions pour que les comparaisons soient cohérentes
/// quelle que soit la source (API Modrinth vs jar local).
pub fn normalize_version(raw: &str, mc_version: &str, loader: &str) -> String {
    let mut s = raw.to_string();

    for prefix in [format!("mc{}-", mc_version), format!("{}-", mc_version)] {
        if let Some(rest) = s.strip_prefix(prefix.as_str()) {
            s = rest.to_string();
            break;
        }
    }

    // Boucle plutôt qu'un seul passage figé (mc puis loader) : certains mods
    // empilent les deux dans un seul suffixe (ex: "+1.21.11-fabric") plutôt
    // que dans l'ordre attendu par deux passages séparés. Un seul passage
    // ratait alors le suffixe mc — déjà "mangé" par le -fabric terminal —
    // laissant des chiffres de la version MC dans le "cœur" comparé, ce qui
    // faisait échouer à tort une comparaison de version exacte sur un mod
    // pourtant déjà à la bonne version (et donc le remplaçait pour rien).
    let suffixes = [
        format!("+mc{}", mc_version),
        format!("-mc{}", mc_version),
        format!("+{}", mc_version),
        format!("-{}", loader),
        format!("+{}", loader),
    ];
    while let Some(matched) = suffixes.iter().find(|suf| s.ends_with(suf.as_str())) {
        s = s.strip_suffix(matched.as_str()).unwrap().to_string();
    }

    s
}

/// Transforme la valeur JSON de `depends` en groupes OR de jetons ANDés.
/// Une chaîne (">=1.0.0 <2.0.0") = un groupe AND. Un tableau = plusieurs
/// groupes alternatifs (OR), chacun éventuellement lui-même composé de jetons AND.
pub fn parse_predicate_groups(value: &serde_json::Value) -> Vec<Vec<String>> {
    match value {
        serde_json::Value::String(s) => {
            vec![s.split_whitespace().map(|t| t.to_string()).collect()]
        }
        serde_json::Value::Array(arr) => arr
            .iter()
            .filter_map(|v| v.as_str())
            .map(|s| s.split_whitespace().map(|t| t.to_string()).collect())
            .collect(),
        _ => Vec::new(),
    }
}

/// Pour `depends` : pas de contrainte déclarée (groupes vides) = tout passe.
pub fn predicate_satisfied(version: &str, groups: &[Vec<String>]) -> bool {
    if groups.is_empty() {
        return true;
    }
    matches_any_group(version, groups)
}

/// Pour `breaks` : pas de plage cassée déclarée (groupes vides) = rien n'est cassé.
/// Inverse de `predicate_satisfied` sur le cas vide — ne pas les confondre.
fn breaks_match(version: &str, groups: &[Vec<String>]) -> bool {
    if groups.is_empty() {
        return false;
    }
    matches_any_group(version, groups)
}

fn matches_any_group(version: &str, groups: &[Vec<String>]) -> bool {
    let core = version_core(version);
    groups
        .iter()
        .any(|group| group.iter().all(|tok| token_satisfied(&core, tok)))
}

/// `depends` doit être satisfait ET `breaks` ne doit matcher aucune version
/// (un mod peut déclarer une plage `depends` large mais exclure certains
/// correctifs cassés via `breaks`, ex: Voxy accepte "0.8.x" sauf 0.8.13).
pub fn version_allowed(version: &str, depends_groups: &[Vec<String>], breaks_groups: &[Vec<String>]) -> bool {
    predicate_satisfied(version, depends_groups) && !breaks_match(version, breaks_groups)
}

/// Lit `fabric.mod.json` d'un jar. Retourne `None` si absent ou invalide.
pub fn read_fabric_mod_json(jar_path: &std::path::Path) -> Option<FabricModJson> {
    use std::io::Read;
    let bytes = std::fs::read(jar_path).ok()?;
    let cursor = std::io::Cursor::new(bytes);
    let mut archive = zip::ZipArchive::new(cursor).ok()?;
    let mut entry = archive.by_name("fabric.mod.json").ok()?;
    let mut content = String::new();
    entry.read_to_string(&mut content).ok()?;
    serde_json::from_str(&content).ok()
}

#[derive(serde::Deserialize, Default)]
pub struct FabricModJson {
    pub id: String,
    #[serde(default)]
    pub version: String,
    #[serde(default)]
    pub depends: std::collections::HashMap<String, serde_json::Value>,
    #[serde(default)]
    pub breaks: std::collections::HashMap<String, serde_json::Value>,
    #[serde(default)]
    pub jars: Vec<NestedJarEntry>,
}

#[derive(serde::Deserialize, Default)]
pub struct NestedJarEntry {
    pub file: String,
}

/// Lit `fabric.mod.json` d'un jar, ainsi que les IDs des jars imbriqués
/// (jar-in-jar, champ `jars`) qu'il embarque — ex: Fabric API qui empaquette
/// fabric-networking-api-v1, fabric-resource-loader-v1... comme jars séparés
/// plutôt que de tout déclarer sous le seul ID "fabric-api". Sans ça, un mod
/// qui dépend directement d'un de ces sous-modules le voit comme manquant
/// alors qu'il est bien présent (imbriqué), et le résolveur tente en vain de
/// le télécharger séparément depuis Modrinth (échec systématique → cooldown).
pub fn read_fabric_mod_json_with_nested(
    jar_path: &std::path::Path,
) -> Option<(FabricModJson, Vec<(String, String)>)> {
    use std::io::Read;
    let bytes = std::fs::read(jar_path).ok()?;
    let cursor = std::io::Cursor::new(bytes);
    let mut archive = zip::ZipArchive::new(cursor).ok()?;

    let mut content = String::new();
    {
        let mut entry = archive.by_name("fabric.mod.json").ok()?;
        entry.read_to_string(&mut content).ok()?;
    }
    let meta: FabricModJson = serde_json::from_str(&content).ok()?;

    let mut nested_ids = Vec::new();
    for nested in &meta.jars {
        let nested_bytes = {
            let Ok(mut nested_entry) = archive.by_name(&nested.file) else { continue };
            let mut buf = Vec::new();
            if nested_entry.read_to_end(&mut buf).is_err() {
                continue;
            }
            buf
        };
        let Ok(mut nested_archive) = zip::ZipArchive::new(std::io::Cursor::new(nested_bytes)) else {
            continue;
        };
        let Ok(mut nested_mod_json) = nested_archive.by_name("fabric.mod.json") else { continue };
        let mut nested_content = String::new();
        if nested_mod_json.read_to_string(&mut nested_content).is_err() {
            continue;
        }
        drop(nested_mod_json);
        if let Ok(nested_meta) = serde_json::from_str::<FabricModJson>(&nested_content) {
            // La VERSION du module imbriqué compte autant que son identifiant :
            // sans elle, une contrainte comme `fabric-networking-api-v1 >=6.3.0`
            // était évaluée contre une version vide, donc jamais satisfaite.
            nested_ids.push((nested_meta.id, nested_meta.version));
        }
    }

    Some((meta, nested_ids))
}

// ── Métadonnées unifiées Fabric / Quilt / Forge / NeoForge ───────────────────

/// Ce qu'un mod déclare sur un autre mod, ramené à une forme commune.
///
/// Fabric et Forge ne décrivent pas leurs dépendances de la même façon : le
/// premier en prédicats semver, le second en plages Maven. Les comparer avec
/// deux moteurs séparés voulait dire maintenir deux fois la même logique —
/// tout est donc converti ici vers les groupes de jetons de `token_satisfied`.
#[derive(Debug, Clone)]
pub struct ModRequirement {
    /// Identifiant du mod visé.
    pub id: String,
    /// Versions acceptées. Vide = aucune contrainte.
    pub depends: Vec<Vec<String>>,
    /// Versions explicitement cassées. Vide = rien n'est cassé.
    pub breaks: Vec<Vec<String>>,
}

/// Un mod installé, quel que soit son loader.
#[derive(Debug, Clone, Default)]
pub struct ModMeta {
    pub id: String,
    pub version: String,
    /// Jars imbriqués (jar-in-jar) embarqués : identifiant et version.
    pub nested: Vec<(String, String)>,
    pub requirements: Vec<ModRequirement>,
}

/// Convertit une plage Maven (`[1.0,2.0)`, `[43,)`, `(,2.0]`, `[1.0]`) en
/// jetons compréhensibles par `token_satisfied`. Une valeur nue (« 1.0 ») est
/// une préférence en Maven, pas une exigence : elle ne contraint donc rien.
pub fn maven_range_to_tokens(range: &str) -> Vec<String> {
    let range = range.trim();
    if range.is_empty() {
        return Vec::new();
    }
    let opens = range.starts_with('[') || range.starts_with('(');
    if !opens {
        return Vec::new();
    }
    let inclusive_low = range.starts_with('[');
    let inclusive_high = range.ends_with(']');
    let inner = range
        .trim_start_matches(['[', '('])
        .trim_end_matches([']', ')'])
        .trim();

    // Pas de virgule : version unique, « [1.0] » = exactement 1.0.
    let Some((low, high)) = inner.split_once(',') else {
        return if inner.is_empty() { Vec::new() } else { vec![format!("={inner}")] };
    };

    let mut tokens = Vec::new();
    let low = low.trim();
    if !low.is_empty() {
        tokens.push(format!("{}{low}", if inclusive_low { ">=" } else { ">" }));
    }
    let high = high.trim();
    if !high.is_empty() {
        tokens.push(format!("{}{high}", if inclusive_high { "<=" } else { "<" }));
    }
    tokens
}

/// Lit les métadonnées d'un jar, quel que soit son loader.
///
/// Ordre d'essai : `fabric.mod.json` (Fabric, Quilt), puis
/// `META-INF/neoforge.mods.toml` (NeoForge ≥ 1.20.5), puis
/// `META-INF/mods.toml` (Forge, NeoForge plus anciens).
pub fn read_mod_meta(jar_path: &std::path::Path) -> Option<ModMeta> {
    if let Some((fabric, nested)) = read_fabric_mod_json_with_nested(jar_path) {
        // Les deux tables sont parcourues séparément : un `breaks` visant un
        // mod dont on ne dépend PAS est le cas normal d'une incompatibilité
        // (Sodium en déclare une trentaine), et il était jusqu'ici ignoré.
        let mut by_id: std::collections::HashMap<String, ModRequirement> =
            std::collections::HashMap::new();
        for (id, value) in &fabric.depends {
            by_id
                .entry(id.clone())
                .or_insert_with(|| ModRequirement { id: id.clone(), depends: Vec::new(), breaks: Vec::new() })
                .depends = parse_predicate_groups(value);
        }
        for (id, value) in &fabric.breaks {
            by_id
                .entry(id.clone())
                .or_insert_with(|| ModRequirement { id: id.clone(), depends: Vec::new(), breaks: Vec::new() })
                .breaks = parse_predicate_groups(value);
        }
        return Some(ModMeta {
            id: fabric.id,
            version: fabric.version,
            nested,
            requirements: by_id.into_values().collect(),
        });
    }

    read_mods_toml(jar_path)
}

/// Forge et NeoForge : `[[mods]]` pour l'identité, `[[dependencies.<modid>]]`
/// pour les contraintes. Pas d'équivalent de `breaks` dans ce format — une
/// incompatibilité s'y exprime par une plage de versions exclue.
fn read_mods_toml(jar_path: &std::path::Path) -> Option<ModMeta> {
    use std::io::Read;
    let bytes = std::fs::read(jar_path).ok()?;
    let mut archive = zip::ZipArchive::new(std::io::Cursor::new(bytes)).ok()?;

    let mut content = String::new();
    let mut found = false;
    for candidate in ["META-INF/neoforge.mods.toml", "META-INF/mods.toml"] {
        if let Ok(mut entry) = archive.by_name(candidate) {
            content.clear();
            if entry.read_to_string(&mut content).is_ok() {
                found = true;
                break;
            }
        }
    }
    if !found {
        return None;
    }

    let root: toml::Value = toml::from_str(&content).ok()?;
    let first_mod = root.get("mods")?.as_array()?.first()?;
    let id = first_mod.get("modId")?.as_str()?.to_string();
    let version = first_mod.get("version").and_then(|v| v.as_str()).unwrap_or_default().to_string();

    let requirements = root
        .get("dependencies")
        .and_then(|d| d.get(&id))
        .and_then(|d| d.as_array())
        .map(|deps| {
            deps.iter()
                .filter_map(|dep| {
                    let dep_id = dep.get("modId")?.as_str()?.to_string();
                    let tokens = dep
                        .get("versionRange")
                        .and_then(|r| r.as_str())
                        .map(maven_range_to_tokens)
                        .unwrap_or_default();
                    if tokens.is_empty() {
                        return None;
                    }
                    Some(ModRequirement { id: dep_id, depends: vec![tokens], breaks: Vec::new() })
                })
                .collect()
        })
        .unwrap_or_default();

    Some(ModMeta { id, version, nested: Vec::new(), requirements })
}

#[cfg(test)]
mod meta_tests {
    use super::*;

    #[test]
    fn maven_ranges_become_tokens() {
        assert_eq!(maven_range_to_tokens("[1.0,2.0)"), vec![">=1.0", "<2.0"]);
        assert_eq!(maven_range_to_tokens("[43,)"), vec![">=43"]);
        assert_eq!(maven_range_to_tokens("(,2.0]"), vec!["<=2.0"]);
        assert_eq!(maven_range_to_tokens("[1.0]"), vec!["=1.0"]);
        // Valeur nue : préférence Maven, pas une exigence.
        assert!(maven_range_to_tokens("1.0").is_empty());
        assert!(maven_range_to_tokens("").is_empty());
    }

    #[test]
    fn maven_bounds_are_respected() {
        let tokens = maven_range_to_tokens("[1.0,2.0)");
        let groups = vec![tokens];
        assert!(version_allowed("1.5", &groups, &[]));
        assert!(!version_allowed("2.0", &groups, &[]));
        assert!(!version_allowed("0.9", &groups, &[]));
    }
}
