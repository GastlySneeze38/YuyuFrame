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

/// Les loaders qui ont au moins une version pour cette version du jeu.
///
/// Sert à ne pas proposer un choix qui ne mène nulle part : NeoForge n'existe
/// pas avant Minecraft 1.20.2, Quilt et Fabric s'arrêtent vers 1.14, et rien
/// dans leur nom ne le dit. « vanilla » y est toujours, n'ayant aucun loader à
/// installer.
///
/// Les quatre interrogations partent **ensemble**, et chacune passe par la
/// route la plus légère de son loader (voir les `supports` respectifs) : cette
/// commande se déclenche à chaque changement de version dans un menu.
///
/// Un loader dont on ne sait rien est **proposé** : une panne de réseau ne doit
/// pas retirer un choix légitime, et l'écran garde un message d'erreur en
/// dernier recours si la liste de versions revient vide.
#[tauri::command]
pub async fn loader_availability(mc_version: String) -> Result<Vec<String>, String> {
    use crate::minecraft::loaders::{fabric, forge, neoforge, quilt};

    let (fab, qui, forg, neo) = tokio::join!(
        fabric::supports(&mc_version),
        quilt::supports(&mc_version),
        forge::supports(&mc_version),
        neoforge::supports(&mc_version),
    );

    let mut available = vec!["vanilla".to_string()];
    for (name, result) in [("fabric", fab), ("quilt", qui), ("forge", forg), ("neoforge", neo)] {
        if result.unwrap_or(true) {
            available.push(name.to_string());
        }
    }
    Ok(available)
}

/// Les versions du jeu utilisables avec ce loader.
///
/// La question inverse de [`loader_availability`], et elle se pose là où le
/// loader n'est pas un choix : une instance qu'on duplique garde le sien, un
/// modpack impose le sien. Proposer alors une version du jeu que le loader ne
/// connaît pas ne mène qu'à un lancement qui échoue.
///
/// « vanilla » rend une liste vide, qui se lit « aucune restriction » —
/// l'appelant n'a rien à filtrer.
#[tauri::command]
pub async fn loader_game_versions(loader: String) -> Result<Vec<String>, String> {
    use crate::minecraft::loaders::{fabric, forge, neoforge, quilt};
    match loader.as_str() {
        "fabric" => fabric::game_versions().await,
        "quilt" => quilt::game_versions().await,
        "forge" => forge::game_versions().await,
        "neoforge" => neoforge::game_versions().await,
        _ => Ok(Vec::new()),
    }
    .map_err(|e| e.to_string())
}
