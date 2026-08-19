//! Évaluateur du format `rules` des manifestes Mojang — utilisé aussi bien
//! pour décider si une lib doit être téléchargée (`classpath::should_download_library`)
//! que pour filtrer les entrées conditionnelles de `arguments.jvm`/`arguments.game`
//! (`jvm_args::extract_mojang_jvm_args`). Même schéma JSON dans les deux cas
//! (`[{"action": "allow"|"disallow", "os": {"name", "arch", ...}}]`), donc un
//! seul évaluateur plutôt que deux copies légèrement différentes.

/// `true` si `rules` autorise l'entrée sur l'OS/arch courants. Absence de
/// contrainte `os` sur une règle = elle s'applique à toutes les plateformes.
/// Ne vérifie pas `os.version` (regex, apparaît uniquement sur le fix
/// `-Dos.name` de Windows 10+ chez Mojang) : l'appliquer aussi sur d'anciens
/// Windows est inoffensif (ça ne fait que renseigner une system property,
/// jamais lue par le jeu lui-même), pas la peine d'une dépendance regex pour ça.
pub(super) fn rules_allow(rules: &[serde_json::Value]) -> bool {
    let os_name = if cfg!(target_os = "windows") { "windows" } else if cfg!(target_os = "macos") { "osx" } else { "linux" };
    let os_arch = if cfg!(target_arch = "x86_64") { "x86_64" } else if cfg!(target_arch = "aarch64") { "arm64" } else { "x86" };

    let mut allowed = true;
    for rule in rules {
        let action = rule.get("action").and_then(|a| a.as_str()).unwrap_or("allow");
        // L-4 (audit pipeline) : Mojang utilise aussi des règles à base de
        // `features` (ex: {"action":"allow","features":{"is_demo_user":true}}),
        // sans clé "os". Sans ce garde-fou, une telle règle tombait dans la
        // branche ci-dessous et était acceptée INCONDITIONNELLEMENT — piège de
        // régression : le jour où P2-1 honore réellement `arguments.jvm`/
        // `arguments.game` via cet évaluateur (au lieu de les jeter en silence),
        // ça injecterait "--demo" dans les arguments de jeu et le launcher
        // lancerait Minecraft en mode démo. Refusée explicitement tant que le
        // launcher n'expose pas ces options (résolution d'écran custom, demo...).
        if rule.get("features").is_some() {
            if action == "allow" { allowed = false; }
            continue;
        }
        let Some(os) = rule.get("os") else {
            allowed = action == "allow";
            continue;
        };
        let name_matches = os.get("name").and_then(|n| n.as_str()).is_none_or(|n| n == os_name);
        let arch_matches = os.get("arch").and_then(|a| a.as_str()).is_none_or(|a| a == os_arch);
        if name_matches && arch_matches {
            allowed = action == "allow";
        } else if action == "allow" {
            allowed = false;
        }
    }
    allowed
}

/// Extrait les chaînes d'un tableau JSON brut d'`arguments.jvm`/`arguments.game`
/// (format Mojang, partagé par le vanilla ET tous les profils de loader —
/// Fabric, Quilt, Forge, NeoForge), en honorant les règles `rules` des entrées
/// conditionnelles (`{"rules": [...], "value": "..."|[...]}`) au lieu de les
/// jeter en silence (P2-1, audit launcher) — `extract_mojang_jvm_args` gérait
/// déjà correctement ce cas pour le vanilla, `loader_setup::json_str_array`
/// avait sa propre implémentation divergente qui sautait purement et
/// simplement toute entrée objet. Un seul évaluateur pour les deux, comme déjà
/// le cas pour `rules_allow`/`should_download_library`.
pub(super) fn extract_conditional_args(values: &[serde_json::Value]) -> Vec<String> {
    values.iter()
        .flat_map(|entry| match entry {
            serde_json::Value::String(s) => vec![s.clone()],
            serde_json::Value::Object(_) => {
                let applies = entry.get("rules")
                    .and_then(|r| r.as_array())
                    .is_none_or(|r| rules_allow(r));
                if !applies { return vec![]; }
                match entry.get("value") {
                    Some(serde_json::Value::String(s)) => vec![s.clone()],
                    Some(serde_json::Value::Array(arr)) => {
                        arr.iter().filter_map(|v| v.as_str().map(String::from)).collect()
                    }
                    _ => vec![],
                }
            }
            _ => vec![],
        })
        .collect()
}
