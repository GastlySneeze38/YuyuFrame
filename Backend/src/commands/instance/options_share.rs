//! Les options du client intégré en fichier, et toutes les options en lien
//! (2026-10-02). Onglet « Paramètres du jeu ».
//!
//! - **Fichier `.properties`** : les réglages des modules du client YuyuFrame
//!   (`agent/module-config/<instance>.properties`, voir `agent_options.rs`),
//!   à garder ou à redonner. L'import **fusionne** : une clé du fichier
//!   remplace la sienne, les autres restent — un fichier qui ne parle que du
//!   HUD ne remet pas le reste à zéro.
//! - **Lien `yuyuframe://options?…`** (`crate::share_link`) : `options.txt`
//!   et/ou les options du client, compressés dans le lien. Rien d'hébergé ;
//!   on le colle dans le launcher (onglet « Paramètres du jeu » d'une
//!   instance) ou on le clique. Avec un `options.txt` complet et un client
//!   réglé, compter ~5 600 caractères : trop pour un message Discord (2 000),
//!   assez pour un copier-coller.
//!
//! ── Ce qui ne part jamais ───────────────────────────────────────────────────
//! - `macros.setting.logins` : les connexions automatiques du module Macros,
//!   **mots de passe compris**, en clair. Ni dans le fichier, ni dans le lien,
//!   et ignoré à la réception.
//! - `macros.setting.macros` hors du fichier : une macro tape une commande à
//!   la place du joueur. On peut sauvegarder les siennes dans un fichier ; on
//!   n'en reçoit pas d'un lien venu de quelqu'un d'autre.
//! - `lastServer` d'`options.txt` : l'adresse du dernier serveur rejoint.
//!
//! Le reste est du réglage. Une valeur avec un retour à la ligne est refusée :
//! elle ajouterait une ligne de plus dans le fichier écrit.

use serde::{Deserialize, Serialize};

use super::agent_options::{agent_options_read, agent_options_write, from_properties, to_properties};
use super::options::{mc_options_read, mc_options_write, McOption};

const LINK_KIND: &str = "options";
const LINK_VERSION: u32 = 1;

/// Mots de passe : jamais exportés, jamais importés.
const CLIENT_PRIVATE: &[&str] = &["macros.setting.logins"];
/// Commandes tapées à la place du joueur : gardées dans un fichier qu'on
/// s'exporte, jamais dans un lien.
const CLIENT_LINK_EXCLUDED: &[&str] = &["macros.setting.macros"];
const GAME_PRIVATE: &[&str] = &["lastServer"];

/// Au-delà, ce n'est plus un fichier d'options.
const MAX_ENTRIES: usize = 5_000;
const MAX_FILE: u64 = 1024 * 1024;

fn clean_entry(key: &str, value: &str) -> bool {
    !key.is_empty() && key.len() <= 200 && !key.contains(['\n', '\r']) && !value.contains(['\n', '\r'])
}

fn keep_client(option: &McOption, for_link: bool) -> bool {
    clean_entry(&option.key, &option.value)
        && !CLIENT_PRIVATE.contains(&option.key.as_str())
        && !(for_link && CLIENT_LINK_EXCLUDED.contains(&option.key.as_str()))
}

fn keep_game(option: &McOption) -> bool {
    clean_entry(&option.key, &option.value) && !GAME_PRIVATE.contains(&option.key.as_str())
}

// ── Fichier .properties du client ───────────────────────────────────────────

#[tauri::command]
pub async fn instance_client_options_export(instance_id: String, path: String) -> Result<u32, String> {
    let settings: Vec<McOption> = agent_options_read(instance_id)
        .await?
        .into_iter()
        .filter(|o| keep_client(o, false))
        .collect();
    if settings.is_empty() {
        return Err("Aucune option du client YuyuFrame pour cette instance : lance-la une fois pour qu'elles existent.".into());
    }
    tokio::fs::write(&path, to_properties(&settings))
        .await
        .map_err(|e| format!("Écriture du fichier : {e}"))?;
    Ok(settings.len() as u32)
}

#[tauri::command]
pub async fn instance_client_options_import(instance_id: String, path: String) -> Result<u32, String> {
    let size = tokio::fs::metadata(&path).await.map_err(|e| format!("Lecture du fichier : {e}"))?.len();
    if size > MAX_FILE {
        return Err("Ce fichier est trop gros pour être un fichier d'options".into());
    }
    let bytes = tokio::fs::read(&path).await.map_err(|e| format!("Lecture du fichier : {e}"))?;
    let settings: Vec<McOption> = from_properties(&bytes)
        .into_iter()
        .filter(|o| keep_client(o, false))
        .take(MAX_ENTRIES)
        .collect();
    // Un `.properties` quelconque se lit aussi : on n'accepte que ce qui a la
    // forme des clés de l'agent (`<module>.<…>`).
    if settings.is_empty() || !settings.iter().all(|o| o.key.contains('.')) {
        return Err("Ce fichier ne contient pas d'options du client YuyuFrame".into());
    }
    let count = settings.len() as u32;
    agent_options_write(instance_id, settings).await?;
    Ok(count)
}

// ── Lien de partage ─────────────────────────────────────────────────────────

/// Ce que transporte le lien. Des paires plutôt que `McOption` : moins de
/// texte avant compression, et c'est tout ce qu'il faut.
#[derive(Serialize, Deserialize, Default)]
struct OptionsPayload {
    /// `options.txt`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    g: Option<Vec<(String, String)>>,
    /// Options du client intégré.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    c: Option<Vec<(String, String)>>,
}

fn to_pairs(options: Vec<McOption>) -> Vec<(String, String)> {
    options.into_iter().map(|o| (o.key, o.value)).collect()
}

fn to_options(pairs: Vec<(String, String)>) -> Vec<McOption> {
    pairs.into_iter().map(|(key, value)| McOption { key, value }).collect()
}

/// Lit un lien et ne garde que ce qui a le droit d'entrer.
fn read_link(link: &str) -> Result<(Option<Vec<McOption>>, Option<Vec<McOption>>), String> {
    let params = crate::share_link::parse(link, LINK_KIND, LINK_VERSION)?;
    let data = params.get(crate::share_link::DATA_PARAM).ok_or("Lien de partage abîmé")?;
    let payload: OptionsPayload = crate::share_link::unpack(data)?;
    let game = payload
        .g
        .map(|g| to_options(g).into_iter().filter(keep_game).take(MAX_ENTRIES).collect::<Vec<_>>())
        .filter(|g| !g.is_empty());
    let client = payload
        .c
        .map(|c| to_options(c).into_iter().filter(|o| keep_client(o, true)).take(MAX_ENTRIES).collect::<Vec<_>>())
        .filter(|c| !c.is_empty());
    Ok((game, client))
}

/// Ce qu'un lien contient, ou ce qui a été appliqué : nombre de réglages de
/// chaque côté, `None` quand il n'y en a pas.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OptionsLinkInfo {
    pub game: Option<u32>,
    pub client: Option<u32>,
}

#[tauri::command]
pub async fn instance_options_link(instance_id: String, game: bool, client: bool) -> Result<String, String> {
    let mut payload = OptionsPayload::default();
    if game {
        let options: Vec<McOption> = mc_options_read(instance_id.clone()).await?.into_iter().filter(keep_game).collect();
        payload.g = Some(to_pairs(options)).filter(|g| !g.is_empty());
    }
    if client {
        let options: Vec<McOption> = agent_options_read(instance_id)
            .await?
            .into_iter()
            .filter(|o| keep_client(o, true))
            .collect();
        payload.c = Some(to_pairs(options)).filter(|c| !c.is_empty());
    }
    if payload.g.is_none() && payload.c.is_none() {
        return Err("Rien à partager : cette instance n'a pas encore d'options (lance-la une fois).".into());
    }
    let data = crate::share_link::pack(&payload)?;
    Ok(crate::share_link::build(LINK_KIND, LINK_VERSION, &[(crate::share_link::DATA_PARAM, &data)]))
}

#[tauri::command]
pub async fn options_link_preview(link: String) -> Result<OptionsLinkInfo, String> {
    let (game, client) = read_link(&link)?;
    Ok(OptionsLinkInfo {
        game: game.map(|g| g.len() as u32),
        client: client.map(|c| c.len() as u32),
    })
}

/// Applique un lien à une instance. Fusion, comme le fichier : chaque réglage
/// reçu remplace le sien, le reste ne bouge pas.
#[tauri::command]
pub async fn instance_options_from_link(
    instance_id: String,
    link: String,
    game: bool,
    client: bool,
) -> Result<OptionsLinkInfo, String> {
    let (game_options, client_options) = read_link(&link)?;
    let mut applied = OptionsLinkInfo { game: None, client: None };
    if let Some(options) = game_options.filter(|_| game) {
        applied.game = Some(options.len() as u32);
        mc_options_write(instance_id.clone(), options).await?;
    }
    if let Some(options) = client_options.filter(|_| client) {
        applied.client = Some(options.len() as u32);
        agent_options_write(instance_id, options).await?;
    }
    Ok(applied)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn opt(key: &str, value: &str) -> McOption {
        McOption { key: key.into(), value: value.into() }
    }

    #[test]
    fn mots_de_passe_jamais_partages() {
        let logins = opt("macros.setting.logins", "serveur|motdepasse");
        assert!(!keep_client(&logins, false));
        assert!(!keep_client(&logins, true));
    }

    #[test]
    fn macros_dans_le_fichier_mais_pas_dans_le_lien() {
        let macros = opt("macros.setting.macros", "F6|/home");
        assert!(keep_client(&macros, false));
        assert!(!keep_client(&macros, true));
        assert!(keep_client(&opt("fps.enabled", "true"), true));
    }

    #[test]
    fn dernier_serveur_et_retours_a_la_ligne_ecartes() {
        assert!(!keep_game(&opt("lastServer", "mon.serveur.prive:25565")));
        assert!(!keep_game(&opt("fov", "0.5\nkey_key.attack:key.mouse.left")));
        assert!(keep_game(&opt("fov", "0.5")));
    }

    #[test]
    fn lien_aller_retour_filtre() {
        let payload = OptionsPayload {
            g: Some(vec![("fov".into(), "0.5".into()), ("lastServer".into(), "x".into())]),
            c: Some(vec![("fps.enabled".into(), "true".into()), ("macros.setting.logins".into(), "secret".into())]),
        };
        let link = crate::share_link::build(LINK_KIND, LINK_VERSION, &[("d", &crate::share_link::pack(&payload).unwrap())]);
        let (game, client) = read_link(&link).unwrap();
        assert_eq!(game.unwrap(), vec![opt("fov", "0.5")]);
        assert_eq!(client.unwrap(), vec![opt("fps.enabled", "true")]);
    }
}
