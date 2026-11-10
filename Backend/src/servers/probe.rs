//! Quelles versions du jeu un serveur accepte-t-il ? (2026-11-10)
//!
//! La fenêtre « Rejoindre » (`JoinServerModal`) proposait seulement les
//! instances de la version et du loader de l'ami. Or beaucoup de serveurs
//! acceptent d'autres versions (ViaVersion, proxys), et le loader de l'ami
//! ne dit rien du serveur : on joue en Fabric sur un serveur vanilla.
//!
//! On demande donc au serveur, **une version à la fois** : le ping de statut
//! commence par annoncer la version réseau du client (« protocole »). Un
//! proxy (Velocity, BungeeCord) ou ViaVersion répond avec **ce même numéro**
//! quand il accepte cette version, et avec le sien sinon ; un serveur seul
//! répond toujours avec le sien. Un numéro rendu égal au nôtre = accepté.
//!
//! Relevé sur de vrais serveurs le 2026-11-10 :
//! - **Hypixel** répond comme prévu ;
//! - **Wynncraft** renvoie n'importe quel numéro, même `-1` (« peu importe ») :
//!   d'où la première question (`server_probe`) — un `-1` rendu tel quel veut
//!   dire que les réponses ne prouvent rien, tout reste « on ne sait pas » ;
//! - **CubeCraft** coupe la connexion après trois pings rapprochés, puis
//!   plusieurs secondes : d'où une version par appel (`server_probe_version`),
//!   posées l'une après l'autre par l'interface, et une nouvelle tentative
//!   après une pause quand la connexion est coupée ;
//! - CubeCraft et Wynncraft annoncent un « FML » sans aucun mod (`modList`
//!   vide), pour laisser entrer les clients Forge : ce n'est pas un serveur
//!   moddé.
//!
//! Plusieurs versions partagent un même protocole (1.21.7 et 1.21.8) : elles
//! se jouent l'une pour l'autre en réseau.
//!
//! Le même ping dit si le serveur exige Forge ou NeoForge (`forgeData`,
//! `modinfo` avec de vrais mods). Fabric et Quilt ne se signalent pas : un
//! serveur Fabric passe pour vanilla.

use std::time::Duration;

use serde::Serialize;
use serde_json::Value;

use crate::minecraft::server_ping;

/// Pauses avant chaque nouvelle tentative quand le serveur coupe (limite de
/// connexions) : CubeCraft en laisse passer une toutes les quelques secondes.
const RETRY_PAUSES: [Duration; 2] = [Duration::from_millis(1500), Duration::from_millis(3000)];

/// Version du jeu → version réseau, pour les versions finales depuis la 1.7.2
/// (le ping moderne n'existe pas avant). Relevée dans minecraft-data
/// (`data/pc/common/protocolVersions.json`, PrismarineJS). **À compléter à
/// chaque nouvelle version de Minecraft** : une version absente reste « on ne
/// sait pas », jamais « refusée ».
const PROTOCOLS: &[(&str, i32)] = &[
    ("26.3", 777), ("26.2", 776), ("26.1.2", 775), ("26.1.1", 775), ("26.1", 775), ("1.21.11", 774),
    ("1.21.10", 773), ("1.21.9", 773), ("1.21.8", 772), ("1.21.7", 772), ("1.21.6", 771),
    ("1.21.5", 770), ("1.21.4", 769), ("1.21.3", 768), ("1.21.2", 768), ("1.21.1", 767),
    ("1.21", 767), ("1.20.6", 766), ("1.20.5", 766), ("1.20.4", 765), ("1.20.3", 765),
    ("1.20.2", 764), ("1.20.1", 763), ("1.20", 763), ("1.19.4", 762), ("1.19.3", 761),
    ("1.19.2", 760), ("1.19.1", 760), ("1.19", 759), ("1.18.2", 758), ("1.18.1", 757),
    ("1.18", 757), ("1.17.1", 756), ("1.17", 755), ("1.16.5", 754), ("1.16.4", 754),
    ("1.16.3", 753), ("1.16.2", 751), ("1.16.1", 736), ("1.16", 735), ("1.15.2", 578),
    ("1.15.1", 575), ("1.15", 573), ("1.14.4", 498), ("1.14.3", 490), ("1.14.2", 485),
    ("1.14.1", 480), ("1.14", 477), ("1.13.2", 404), ("1.13.1", 401), ("1.13", 393),
    ("1.12.2", 340), ("1.12.1", 338), ("1.12", 335), ("1.11.2", 316), ("1.11.1", 316),
    ("1.11", 315), ("1.10.2", 210), ("1.10.1", 210), ("1.10", 210), ("1.9.4", 110), ("1.9.3", 110),
    ("1.9.2", 109), ("1.9.1", 108), ("1.9", 107), ("1.8.9", 47), ("1.8.8", 47), ("1.8.7", 47),
    ("1.8.6", 47), ("1.8.5", 47), ("1.8.4", 47), ("1.8.3", 47), ("1.8.2", 47), ("1.8.1", 47),
    ("1.8", 47), ("1.7.10", 5), ("1.7.9", 5), ("1.7.8", 5), ("1.7.7", 5), ("1.7.6", 5),
    ("1.7.5", 4), ("1.7.4", 4), ("1.7.2", 4),
];

/// La version réseau d'une version du jeu, si on la connaît.
pub fn protocol_of(version: &str) -> Option<i32> {
    PROTOCOLS.iter().find(|(v, _)| *v == version).map(|(_, p)| *p)
}

/// Ce que le serveur dit de lui, avant de lui demander version par version.
#[derive(Debug, Serialize)]
pub struct ServerProbe {
    /// Le serveur a répondu. Sinon, aucune conclusion à tirer.
    pub reachable: bool,
    /// Il renvoie n'importe quel numéro : ses réponses par version ne
    /// prouvent rien, inutile de les demander.
    pub echoes_all: bool,
    /// `forge` / `neoforge` quand le serveur exige des mods, `modded` s'il le
    /// dit sans préciser ; `null` = rien d'exigé (vanilla, Paper, Fabric…).
    pub modded: Option<String>,
    /// Ce que le serveur affiche comme version (« Paper 1.21.4 »).
    pub server_version: String,
}

/// Les identifiants qui viennent avec le loader lui-même : un serveur qui
/// n'annonce qu'eux n'exige aucun mod (un client vanilla y entre).
const BASE_MODS: [&str; 6] = ["minecraft", "forge", "neoforge", "fml", "mcp", "fabric"];

fn has_real_mods(list: Option<&Value>, key: &str) -> bool {
    list.and_then(Value::as_array).is_some_and(|mods| {
        mods.iter().any(|m| {
            m.get(key).and_then(Value::as_str).is_some_and(|id| !BASE_MODS.contains(&id.to_ascii_lowercase().as_str()))
        })
    })
}

/// Le loader exigé par la réponse de statut, s'il y en a un.
fn modded(json: &Value) -> Option<String> {
    if let Some(data) = json.get("forgeData") {
        let neo = data
            .get("mods")
            .and_then(Value::as_array)
            .is_some_and(|mods| mods.iter().any(|m| m.get("modId").and_then(Value::as_str) == Some("neoforge")));
        // `d` : la liste compressée des versions récentes, illisible ici —
        // elle n'est là que s'il y a des mods à décrire.
        let packed = data.get("d").and_then(Value::as_str).is_some_and(|d| !d.is_empty());
        if packed || has_real_mods(data.get("mods"), "modId") {
            return Some(if neo { "neoforge" } else { "forge" }.into());
        }
        return None;
    }
    if json.pointer("/modinfo/type").and_then(Value::as_str) == Some("FML") {
        return has_real_mods(json.pointer("/modinfo/modList"), "modid").then(|| "forge".into());
    }
    None
}

/// Le numéro rendu par le serveur.
fn echoed(json: &Value) -> Option<i64> {
    json.pointer("/version/protocol").and_then(Value::as_i64)
}

/// Le statut, avec de nouvelles tentatives quand le serveur coupe : une
/// limite de connexions se lève en quelques secondes.
async fn status_patiently(address: &str, protocol: i32) -> Option<Value> {
    for pause in std::iter::once(None).chain(RETRY_PAUSES.iter().copied().map(Some)) {
        if let Some(pause) = pause {
            tokio::time::sleep(pause).await;
        }
        if let Ok((json, _)) = server_ping::status(address, protocol, false).await {
            return Some(json);
        }
    }
    None
}

/// La première question : le serveur répond-il, exige-t-il des mods, et
/// renvoie-t-il n'importe quel numéro ? (`-1` = « peu importe » : un serveur
/// honnête répond avec le sien.)
#[tauri::command]
pub async fn server_probe(address: String) -> ServerProbe {
    match status_patiently(&address, -1).await {
        None => ServerProbe { reachable: false, echoes_all: false, modded: None, server_version: String::new() },
        Some(json) => ServerProbe {
            reachable: true,
            echoes_all: echoed(&json) == Some(-1),
            modded: modded(&json),
            server_version: json.pointer("/version/name").and_then(Value::as_str).unwrap_or("").to_string(),
        },
    }
}

/// Le serveur accepte-t-il cette version ? `null` = on ne sait pas (version
/// inconnue de la table, pas de réponse, pas de numéro rendu).
#[tauri::command]
pub async fn server_probe_version(address: String, version: String) -> Option<bool> {
    let protocol = protocol_of(&version)?;
    let json = status_patiently(&address, protocol).await?;
    echoed(&json).map(|back| back == protocol as i64)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn table_connue() {
        assert_eq!(protocol_of("1.8.9"), Some(47));
        assert_eq!(protocol_of("1.21.4"), Some(769));
        assert_eq!(protocol_of("1.21.8"), protocol_of("1.21.7"));
        assert_eq!(protocol_of("1.6.4"), None);
        // Pas deux fois la même version dans la table.
        let mut names: Vec<&str> = PROTOCOLS.iter().map(|(v, _)| *v).collect();
        names.sort_unstable();
        names.dedup();
        assert_eq!(names.len(), PROTOCOLS.len());
    }

    #[test]
    fn serveurs_moddes() {
        assert_eq!(modded(&json!({ "forgeData": { "mods": [{ "modId": "neoforge" }, { "modId": "create" }] } })).as_deref(), Some("neoforge"));
        assert_eq!(modded(&json!({ "forgeData": { "mods": [{ "modId": "forge" }, { "modId": "jei" }] } })).as_deref(), Some("forge"));
        assert_eq!(modded(&json!({ "forgeData": { "mods": [], "d": "ȅ\u{0}\u{0}" } })).as_deref(), Some("forge"));
        assert_eq!(modded(&json!({ "modinfo": { "type": "FML", "modList": [{ "modid": "mcp" }, { "modid": "ic2" }] } })).as_deref(), Some("forge"));
        assert_eq!(modded(&json!({ "version": { "name": "Paper 1.21.4" } })), None);
    }

    /// CubeCraft et Wynncraft : un « FML » sans mod pour laisser entrer les
    /// clients Forge, pas un serveur moddé.
    #[test]
    fn fml_sans_mod_nest_pas_modde() {
        assert_eq!(modded(&json!({ "modinfo": { "modList": [], "type": "FML" } })), None);
        assert_eq!(modded(&json!({ "forgeData": { "mods": [{ "modId": "minecraft" }, { "modId": "forge" }] } })), None);
    }

    /// Sur un vrai serveur :
    /// `YF_PROBE=play.exemple.fr cargo test --lib sonde_reelle -- --ignored --nocapture`.
    #[tokio::test]
    #[ignore]
    async fn sonde_reelle() {
        let address = std::env::var("YF_PROBE").expect("YF_PROBE");
        println!("{:?}", server_probe(address.clone()).await);
        for v in ["1.8.9", "1.12.2", "1.16.5", "1.20.1", "1.21.1", "1.21.4", "1.21.11", "26.1.2", "26.3"] {
            println!("{v} => {:?}", server_probe_version(address.clone(), v.into()).await);
        }
    }
}
