use serde::Serialize;

use crate::minecraft::loaders::LoaderVersion;
use crate::minecraft::versions;

#[derive(Serialize)]
pub struct VersionEntry {
    pub id: String,
    pub version_type: String,
    pub url: String,
}

#[tauri::command]
pub async fn list_versions() -> Result<Vec<VersionEntry>, String> {
    versions::fetch_version_list()
        .await
        .map(|list| {
            list.into_iter()
                .map(|v| VersionEntry { id: v.id, version_type: v.version_type, url: v.url })
                .collect()
        })
        .map_err(|e| e.to_string())
}

/// Les versions de loader disponibles pour un couple (loader, version MC).
///
/// Jusqu'ici le launcher prenait toujours la dernière et ne la gardait nulle
/// part. Depuis que l'instance peut en épingler une (`instances.loader_version`),
/// il faut pouvoir montrer le choix — et la liste vient de chez chaque loader,
/// qui a sa propre façon de la publier (voir `minecraft::loaders`).
///
/// Une liste vide plutôt qu'une erreur pour « vanilla » : il n'y a pas de
/// loader à versionner, et l'interface n'a rien de spécial à traiter.
#[tauri::command]
pub async fn loader_versions(loader: String, mc_version: String) -> Result<Vec<LoaderVersion>, String> {
    use crate::minecraft::loaders::{fabric, forge, neoforge, quilt};
    match loader.as_str() {
        "fabric" => fabric::list_versions(&mc_version).await,
        "quilt" => quilt::list_versions(&mc_version).await,
        "forge" => forge::list_versions(&mc_version).await,
        "neoforge" => neoforge::list_versions(&mc_version).await,
        _ => Ok(Vec::new()),
    }
    .map_err(|e| e.to_string())
}
