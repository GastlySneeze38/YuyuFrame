// Lecture de servers.dat (liste des serveurs multijoueur enregistrés côté
// vanilla, format NBT non compressé) + construction des arguments de lancement
// pour rejoindre directement l'un d'eux, sans passer par le menu multijoueur.
//
// NBT racine : compound { servers: [ { name, ip, ... }, ... ] } — mêmes
// conventions de struct que P2P-Server/rust-core/src/world/anvil.rs (serde +
// fastnbt), mais ici aucune décompression gzip/zlib : servers.dat est écrit
// tel quel par le client vanilla.

use serde::{Deserialize, Serialize};
use std::path::Path;

use super::super::versions::predicate::{cmp_core, version_core};

#[derive(Deserialize)]
struct ServersDatNbt {
    #[serde(default)]
    servers: Vec<ServerEntryNbt>,
}

#[derive(Deserialize)]
struct ServerEntryNbt {
    name: String,
    ip: String,
    /// « Packs de ressources » du jeu : absent = demander, 1 = activés,
    /// 0 = désactivés.
    #[serde(rename = "acceptTextures", default)]
    accept_textures: Option<i8>,
}

/// Version DTO exposée au frontend (commande `list_saved_servers`).
#[derive(Serialize, Clone)]
pub struct SavedServer {
    pub name: String,
    pub ip: String,
    /// Le réglage « Packs de ressources » de l'écran du jeu : `prompt`
    /// (demander, le défaut), `enabled` ou `disabled`.
    pub resource_packs: ResourcePacks,
}

#[derive(Serialize, serde::Deserialize, Clone, Copy, Debug, Default, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ResourcePacks {
    #[default]
    Prompt,
    Enabled,
    Disabled,
}

impl ResourcePacks {
    fn from_nbt(v: Option<i8>) -> Self {
        match v {
            Some(0) => Self::Disabled,
            Some(_) => Self::Enabled,
            None => Self::Prompt,
        }
    }

    /// Écrit le réglage dans une entrée : « demander » est l'absence du champ.
    fn write(self, entry: &mut std::collections::HashMap<String, fastnbt::Value>) {
        match self {
            Self::Prompt => {
                entry.remove("acceptTextures");
            }
            Self::Enabled => {
                entry.insert("acceptTextures".into(), fastnbt::Value::Byte(1));
            }
            Self::Disabled => {
                entry.insert("acceptTextures".into(), fastnbt::Value::Byte(0));
            }
        }
    }
}

impl SavedServer {
    pub fn new(name: impl Into<String>, ip: impl Into<String>) -> Self {
        Self { name: name.into(), ip: ip.into(), resource_packs: ResourcePacks::Prompt }
    }
}

/// Lit `<game_dir>/servers.dat`. Retourne une liste vide (pas une erreur) si
/// le fichier n'existe pas encore — cas normal d'une instance qui n'a jamais
/// ouvert le menu multijoueur.
pub fn read_saved_servers(game_dir: &Path) -> Result<Vec<SavedServer>, String> {
    let path = game_dir.join("servers.dat");
    if !path.exists() {
        return Ok(Vec::new());
    }
    let bytes = std::fs::read(&path).map_err(|e| format!("Lecture servers.dat : {}", e))?;
    let parsed: ServersDatNbt =
        fastnbt::from_bytes(&bytes).map_err(|e| format!("Parsing NBT servers.dat : {}", e))?;
    Ok(parsed
        .servers
        .into_iter()
        .map(|s| SavedServer { name: s.name, ip: s.ip, resource_packs: ResourcePacks::from_nbt(s.accept_textures) })
        .collect())
}

/// Ajoute des serveurs à `<game_dir>/servers.dat` (partage d'instance) et rend
/// le nombre réellement ajoutés.
///
/// **Fusion** : un serveur déjà présent (même adresse) n'est pas dupliqué, et
/// le fichier est relu en NBT générique (`fastnbt::Value`) puis réécrit tel
/// quel : les champs que le launcher ne connaît pas (icône, `hidden`,
/// `hidden`…) survivent. Seuls le nom et l'adresse arrivent pour un serveur
/// ajouté — l'icône se régénère au premier affichage de la liste.
pub fn merge_saved_servers(game_dir: &Path, servers: &[SavedServer]) -> Result<usize, String> {
    use fastnbt::Value;
    use std::collections::HashMap;

    let path = game_dir.join("servers.dat");
    let mut root = match std::fs::read(&path) {
        Ok(bytes) => fastnbt::from_bytes::<Value>(&bytes).map_err(|e| format!("Parsing NBT servers.dat : {}", e))?,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Value::Compound(HashMap::new()),
        Err(e) => return Err(format!("Lecture servers.dat : {}", e)),
    };
    let Value::Compound(fields) = &mut root else {
        return Err("servers.dat inattendu".into());
    };
    let list = fields.entry("servers".to_string()).or_insert_with(|| Value::List(Vec::new()));
    let Value::List(entries) = list else {
        return Err("servers.dat inattendu".into());
    };
    let known: std::collections::HashSet<String> = entries
        .iter()
        .filter_map(|e| match e {
            Value::Compound(c) => match c.get("ip") {
                Some(Value::String(ip)) => Some(ip.to_lowercase()),
                _ => None,
            },
            _ => None,
        })
        .collect();

    let mut added = 0;
    for server in servers {
        if known.contains(&server.ip.to_lowercase()) {
            continue;
        }
        let mut entry = HashMap::new();
        entry.insert("name".to_string(), Value::String(server.name.clone()));
        entry.insert("ip".to_string(), Value::String(server.ip.clone()));
        server.resource_packs.write(&mut entry);
        entries.push(Value::Compound(entry));
        added += 1;
    }
    if added > 0 {
        let bytes = fastnbt::to_bytes(&root).map_err(|e| format!("Écriture NBT servers.dat : {}", e))?;
        std::fs::create_dir_all(game_dir).map_err(|e| e.to_string())?;
        std::fs::write(&path, bytes).map_err(|e| format!("Écriture servers.dat : {}", e))?;
    }
    Ok(added)
}

/// Applique `change` à la liste de `<game_dir>/servers.dat`, relue en NBT
/// générique comme pour la fusion : ce que le launcher ne connaît pas survit.
/// Le fichier n'est réécrit que si `change` rend vrai. Fichier absent = liste
/// vide, rien à modifier.
fn edit_list(
    game_dir: &Path,
    change: impl FnOnce(&mut Vec<fastnbt::Value>) -> Result<bool, String>,
) -> Result<bool, String> {
    use fastnbt::Value;

    let path = game_dir.join("servers.dat");
    let mut root = match std::fs::read(&path) {
        Ok(bytes) => fastnbt::from_bytes::<Value>(&bytes).map_err(|e| format!("Parsing NBT servers.dat : {}", e))?,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(false),
        Err(e) => return Err(format!("Lecture servers.dat : {}", e)),
    };
    let Value::Compound(fields) = &mut root else {
        return Err("servers.dat inattendu".into());
    };
    let Some(Value::List(entries)) = fields.get_mut("servers") else {
        return Ok(false);
    };
    if !change(entries)? {
        return Ok(false);
    }
    let bytes = fastnbt::to_bytes(&root).map_err(|e| format!("Écriture NBT servers.dat : {}", e))?;
    std::fs::write(&path, bytes).map_err(|e| format!("Écriture servers.dat : {}", e))?;
    Ok(true)
}

/// L'adresse d'une entrée de `servers.dat`, en minuscules (comparaison).
fn entry_ip(entry: &fastnbt::Value) -> Option<String> {
    match entry {
        fastnbt::Value::Compound(c) => match c.get("ip") {
            Some(fastnbt::Value::String(ip)) => Some(ip.to_lowercase()),
            _ => None,
        },
        _ => None,
    }
}

/// Retire le serveur d'adresse `ip` (casse ignorée). Rend vrai s'il existait.
pub fn remove_saved_server(game_dir: &Path, ip: &str) -> Result<bool, String> {
    let target = ip.to_lowercase();
    edit_list(game_dir, |entries| {
        let before = entries.len();
        entries.retain(|e| entry_ip(e).as_deref() != Some(target.as_str()));
        Ok(entries.len() != before)
    })
}

/// Renomme le serveur d'adresse `ip` et change son adresse. Les autres champs
/// restent, sauf l'icône quand l'adresse change : elle était celle de
/// l'ancien serveur, le jeu en redemandera une au prochain affichage.
/// Refuse une nouvelle adresse déjà prise par un autre serveur de la liste.
pub fn edit_saved_server(game_dir: &Path, ip: &str, new: &SavedServer) -> Result<bool, String> {
    use fastnbt::Value;

    let target = ip.to_lowercase();
    let new_ip = new.ip.to_lowercase();
    edit_list(game_dir, |entries| {
        if new_ip != target && entries.iter().any(|e| entry_ip(e).as_deref() == Some(new_ip.as_str())) {
            return Err("Ce serveur est déjà dans la liste".into());
        }
        let Some(Value::Compound(entry)) = entries.iter_mut().find(|e| entry_ip(e).as_deref() == Some(target.as_str())) else {
            return Ok(false);
        };
        entry.insert("name".to_string(), Value::String(new.name.clone()));
        new.resource_packs.write(entry);
        if new_ip != target {
            entry.insert("ip".to_string(), Value::String(new.ip.clone()));
            entry.remove("icon");
        }
        Ok(true)
    })
}

/// MC 1.20+ (quick play) sait rejoindre un serveur directement en passant par
/// `--quickPlayMultiplayer host:port`, qui affiche un bref écran de connexion
/// avant le menu principal. Les versions antérieures n'ont pas cette option
/// de lancement : `--server host --port port` saute carrément le menu et
/// connecte le joueur avant même l'affichage du titre.
fn supports_quick_play(version_id: &str) -> bool {
    cmp_core(&version_core(version_id), &[1, 20]) != std::cmp::Ordering::Less
}

/// Construit les arguments de jeu à ajouter pour rejoindre `address`
/// (`host` ou `host:port`, port par défaut 25565) au lancement de `version_id`.
/// `pub(super)` : uniquement consommé par orchestrator.rs (même parent
/// `launcher`), pas exposé aux commandes Tauri — voir `read_saved_servers`
/// pour la liste exposée au frontend.
pub(super) fn build_server_connect_args(version_id: &str, address: &str) -> Vec<String> {
    let (host, port) = match address.rsplit_once(':') {
        Some((h, p)) if p.chars().all(|c| c.is_ascii_digit()) => (h.to_string(), p.to_string()),
        _ => (address.to_string(), "25565".to_string()),
    };

    if supports_quick_play(version_id) {
        vec!["--quickPlayMultiplayer".to_string(), format!("{}:{}", host, port)]
    } else {
        vec!["--server".to_string(), host, "--port".to_string(), port]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dir(name: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("yf-servers-{name}-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    fn server(name: &str, ip: &str) -> SavedServer {
        SavedServer::new(name, ip)
    }

    #[test]
    fn ajouter_retirer_modifier_gardent_le_reste() {
        let d = dir("edit");
        merge_saved_servers(&d, &[server("A", "a.net"), server("B", "b.net")]).unwrap();

        // Un champ inconnu du launcher sur B doit survivre aux modifications.
        edit_list(&d, |entries| {
            if let Some(fastnbt::Value::Compound(c)) = entries.get_mut(1) {
                c.insert("hidden".into(), fastnbt::Value::Byte(1));
            }
            Ok(true)
        })
        .unwrap();

        assert!(edit_saved_server(&d, "B.NET", &server("Bê", "b.net")).unwrap());
        assert!(remove_saved_server(&d, "a.net").unwrap());
        assert!(!remove_saved_server(&d, "a.net").unwrap());

        let list = read_saved_servers(&d).unwrap();
        assert_eq!(list.len(), 1);
        assert_eq!((list[0].name.as_str(), list[0].ip.as_str()), ("Bê", "b.net"));
        let raw: fastnbt::Value = fastnbt::from_bytes(&std::fs::read(d.join("servers.dat")).unwrap()).unwrap();
        assert!(format!("{raw:?}").contains("hidden"));
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn le_reglage_des_packs_de_ressources_suit_le_jeu() {
        let d = dir("packs");
        let mut a = server("A", "a.net");
        a.resource_packs = ResourcePacks::Disabled;
        merge_saved_servers(&d, &[a, server("B", "b.net")]).unwrap();
        let list = read_saved_servers(&d).unwrap();
        assert_eq!(list[0].resource_packs, ResourcePacks::Disabled);
        assert_eq!(list[1].resource_packs, ResourcePacks::Prompt);

        let mut b = server("B", "b.net");
        b.resource_packs = ResourcePacks::Enabled;
        edit_saved_server(&d, "b.net", &b).unwrap();
        // Revenir à « demander » retire le champ, comme le fait le jeu.
        edit_saved_server(&d, "a.net", &server("A", "a.net")).unwrap();
        let list = read_saved_servers(&d).unwrap();
        assert_eq!(list[0].resource_packs, ResourcePacks::Prompt);
        assert_eq!(list[1].resource_packs, ResourcePacks::Enabled);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn une_adresse_deja_prise_est_refusee() {
        let d = dir("dup");
        merge_saved_servers(&d, &[server("A", "a.net"), server("B", "b.net")]).unwrap();
        assert!(edit_saved_server(&d, "a.net", &server("A", "B.net")).is_err());
        // Sans fichier, rien à retirer ni à modifier.
        let vide = dir("vide");
        assert!(!remove_saved_server(&vide, "a.net").unwrap());
        assert!(!edit_saved_server(&vide, "a.net", &server("A", "c.net")).unwrap());
        let _ = std::fs::remove_dir_all(&d);
        let _ = std::fs::remove_dir_all(&vide);
    }
}
