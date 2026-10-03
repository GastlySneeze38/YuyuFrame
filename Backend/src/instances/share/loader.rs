//! Version du loader écrite dans le pack, épinglée à l'import.

use super::*;

/// Clé du loader dans les dépendances d'un `.mrpack`.
pub(super) fn loader_dependency(loader: &str) -> Option<&'static str> {
    match loader {
        "fabric" => Some("fabric-loader"),
        "quilt" => Some("quilt-loader"),
        "forge" => Some("forge"),
        "neoforge" => Some("neoforge"),
        _ => None,
    }
}

/// Le loader d'un pack, d'après ses dépendances.
pub(super) fn loader_from_dependencies(deps: &BTreeMap<String, String>) -> (String, String) {
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
pub(super) async fn loader_version_of(instance: &Instance) -> Result<String, String> {
    if loader_dependency(&instance.loader).is_none() {
        return Ok(String::new());
    }
    if !instance.loader_version.is_empty() {
        return Ok(instance.loader_version.clone());
    }
    let versions = crate::instances::versions::loader_versions(instance.loader.clone(), instance.mc_version.clone()).await?;
    versions
        .into_iter()
        .find(|v| v.recommended)
        .map(|v| v.version)
        .ok_or_else(|| "Impossible de trouver la version du loader de cette instance (connexion à Internet ?)".to_string())
}
