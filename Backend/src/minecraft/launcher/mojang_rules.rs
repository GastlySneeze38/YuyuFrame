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
