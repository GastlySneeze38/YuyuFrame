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
}

/// Version DTO exposée au frontend (commande `list_saved_servers`).
#[derive(Serialize, Clone)]
pub struct SavedServer {
    pub name: String,
    pub ip: String,
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
        .map(|s| SavedServer { name: s.name, ip: s.ip })
        .collect())
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
