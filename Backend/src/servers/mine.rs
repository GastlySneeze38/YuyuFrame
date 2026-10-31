//! « Mes serveurs » : les listes multijoueur des instances (`servers.dat`).
//!
//! Il n'y a pas de liste de serveurs propre au launcher : la liste d'une
//! instance est celle que le jeu affiche dans son menu Multijoueur, et c'est
//! elle qu'on lit et qu'on modifie. Un serveur ajouté depuis le launcher
//! apparaît donc aussi en jeu, et inversement — une seule source.
//!
//! Le fichier est réécrit en gardant ce que le launcher ne connaît pas
//! (icônes, `acceptTextures`…), voir `minecraft::launcher::servers`.

use serde::Serialize;

use crate::db;
use crate::instances::crud::instance_dir;
use crate::minecraft::launcher::{self, ResourcePacks, SavedServer};
use crate::state::SharedState;

const NAME_MAX: usize = 64;
const ADDRESS_MAX: usize = 253 + 6;

#[derive(Serialize)]
pub struct InstanceServers {
    pub instance_id: String,
    pub instance_name: String,
    pub mc_version: String,
    pub loader: String,
    pub servers: Vec<SavedServer>,
    /// `servers.dat` illisible : la liste n'est pas vide, elle est inconnue.
    pub error: Option<String>,
}

/// Une adresse de serveur telle que le jeu l'accepte : un nom d'hôte ou une
/// IPv4, suivi d'un port facultatif. Rendue nettoyée (espaces retirés,
/// minuscules), ou une erreur à afficher.
pub fn clean_address(address: &str) -> Result<String, String> {
    let a = address.trim().to_lowercase();
    if a.is_empty() {
        return Err("Adresse vide".into());
    }
    if a.len() > ADDRESS_MAX {
        return Err("Adresse trop longue".into());
    }
    let (host, port) = match a.rsplit_once(':') {
        Some((h, p)) => (h, Some(p)),
        None => (a.as_str(), None),
    };
    if let Some(p) = port {
        match p.parse::<u16>() {
            Ok(n) if n > 0 => {}
            _ => return Err("Port invalide".into()),
        }
    }
    let host_ok = !host.is_empty()
        && !host.starts_with('.')
        && !host.ends_with('.')
        && !host.contains("..")
        && host.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_'));
    if !host_ok {
        return Err("Adresse invalide".into());
    }
    Ok(a)
}

/// Le nom affiché dans la liste : sans caractère de contrôle, borné. Vide =
/// l'adresse, comme le fait le jeu.
fn clean_name(name: &str, address: &str) -> String {
    let n: String = name.chars().filter(|c| !c.is_control()).take(NAME_MAX).collect();
    let n = n.trim();
    if n.is_empty() { address.to_string() } else { n.to_string() }
}

/// Le dossier d'une instance du compte courant, ou une erreur : on n'écrit
/// pas dans le dossier d'une instance que la liste ne montre pas.
async fn owned_dir(state: &SharedState, instance_id: &str) -> Result<std::path::PathBuf, String> {
    let s = state.read().await;
    let uid = s.current_yuyu_user_id().unwrap_or(0);
    let conn = s.db.lock().await;
    match db::instance_get(&conn, instance_id, uid).map_err(|e| e.to_string())? {
        Some(_) => Ok(instance_dir(instance_id)),
        None => Err("Instance introuvable".into()),
    }
}

/// Les serveurs de toutes les instances, dans l'ordre des instances.
#[tauri::command]
pub async fn servers_all(state: tauri::State<'_, SharedState>) -> Result<Vec<InstanceServers>, String> {
    let rows = {
        let s = state.read().await;
        let uid = s.current_yuyu_user_id().unwrap_or(0);
        let conn = s.db.lock().await;
        db::instance_list(&conn, uid).map_err(|e| e.to_string())?
    };
    Ok(rows
        .into_iter()
        .map(|row| {
            let (servers, error) = match launcher::read_saved_servers(&instance_dir(&row.id)) {
                Ok(list) => (list, None),
                Err(e) => (Vec::new(), Some(e)),
            };
            InstanceServers {
                instance_id: row.id,
                instance_name: row.name,
                mc_version: row.mc_version,
                loader: row.loader,
                servers,
                error,
            }
        })
        .collect())
}

/// Ajoute un serveur à la liste d'une instance. Rend faux s'il y était déjà
/// (même adresse) : rien n'est dupliqué.
#[tauri::command]
pub async fn server_add(
    state: tauri::State<'_, SharedState>,
    instance_id: String,
    name: String,
    address: String,
    resource_packs: Option<ResourcePacks>,
) -> Result<bool, String> {
    let dir = owned_dir(&state, &instance_id).await?;
    let ip = clean_address(&address)?;
    let server = SavedServer { name: clean_name(&name, &ip), ip, resource_packs: resource_packs.unwrap_or_default() };
    Ok(launcher::merge_saved_servers(&dir, &[server])? > 0)
}

#[tauri::command]
pub async fn server_remove(state: tauri::State<'_, SharedState>, instance_id: String, address: String) -> Result<bool, String> {
    let dir = owned_dir(&state, &instance_id).await?;
    launcher::remove_saved_server(&dir, address.trim())
}

/// Renomme un serveur et/ou change son adresse.
#[tauri::command]
pub async fn server_edit(
    state: tauri::State<'_, SharedState>,
    instance_id: String,
    address: String,
    new_name: String,
    new_address: String,
    resource_packs: Option<ResourcePacks>,
) -> Result<bool, String> {
    let dir = owned_dir(&state, &instance_id).await?;
    let ip = clean_address(&new_address)?;
    let server = SavedServer { name: clean_name(&new_name, &ip), ip, resource_packs: resource_packs.unwrap_or_default() };
    launcher::edit_saved_server(&dir, address.trim(), &server)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn les_adresses_se_nettoient_ou_se_refusent() {
        assert_eq!(clean_address("  Mc.Hypixel.NET ").unwrap(), "mc.hypixel.net");
        assert_eq!(clean_address("play.example.com:25566").unwrap(), "play.example.com:25566");
        assert_eq!(clean_address("192.168.1.10").unwrap(), "192.168.1.10");
        for bad in ["", "a b.net", "x.net:0", "x.net:99999", "x.net:", ".x.net", "x..net", "x.net/", "\"x\""] {
            assert!(clean_address(bad).is_err(), "{bad:?} devait être refusée");
        }
    }

    #[test]
    fn un_nom_vide_prend_l_adresse() {
        assert_eq!(clean_name("  ", "a.net"), "a.net");
        assert_eq!(clean_name("Mon\nserveur", "a.net"), "Monserveur");
        assert_eq!(clean_name(&"x".repeat(100), "a.net").len(), NAME_MAX);
    }
}
