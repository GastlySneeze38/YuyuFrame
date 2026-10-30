//! Le flux temps réel des amis (LauncherAPI `GET /v1/friends/events`, en SSE)
//! relayé à l'interface : chaque événement devient un événement Tauri
//! `friends_event`, dont la charge est le JSON du serveur (`{ type, … }`).
//!
//! Le serveur commence par `ready` — l'interface recharge alors sa liste,
//! pour ne rien rater de ce qui s'est passé avant la connexion — puis envoie
//! les changements : présence d'un ami, demande reçue, nouvel ami, liste à
//! recharger, et `resync` quand il a dû en sauter.
//!
//! Il ferme le flux au bout de 14 minutes (la durée d'un jeton d'accès) : on
//! se reconnecte aussitôt avec un jeton neuf. Une coupure se rattrape avec un
//! délai qui double à chaque échec, jusqu'à une minute ; une API sans la
//! route (pas encore déployée) n'est redemandée que toutes les 10 minutes.
//!
//! Le client HTTP est le sien : celui du launcher coupe toute requête au bout
//! de 30 s, ce qui tuerait un flux fait pour rester ouvert. Le silence, lui,
//! est surveillé ici : le serveur parle au moins toutes les 20 s.

use std::time::Duration;

use serde_json::{json, Value};
use tauri::Emitter;

use crate::server as api;
use crate::state::SharedState;

/// Rien reçu depuis ce temps (le serveur parle toutes les 20 s) : connexion morte.
const SILENCE: Duration = Duration::from_secs(60);
const FIRST_RETRY: Duration = Duration::from_secs(2);
const MAX_RETRY: Duration = Duration::from_secs(60);
const ROUTE_MISSING_RETRY: Duration = Duration::from_secs(600);
const SIGNED_OUT_CHECK: Duration = Duration::from_secs(10);
/// Un événement fait quelques centaines d'octets ; au-delà, ce n'est pas notre serveur.
const MAX_BUFFER: usize = 256 * 1024;

enum Stop {
    /// Fermeture normale (durée de vie atteinte, déconnexion) : on rouvre tout de suite.
    Closed,
    /// La route n'existe pas sur cette API.
    Missing,
    Failed,
}

/// Lance l'écoute. Une seule fois, au démarrage (par le moteur de Tauri, voir
/// `presence::spawn`).
pub fn spawn(state: SharedState, app: tauri::AppHandle) {
    tauri::async_runtime::spawn(async move {
        let client = match reqwest::Client::builder().user_agent("YuyuFrame/1.0").connect_timeout(Duration::from_secs(10)).build() {
            Ok(client) => client,
            Err(e) => {
                tracing::warn!("flux des amis indisponible : {e}");
                return;
            }
        };
        let mut retry = FIRST_RETRY;
        loop {
            if state.read().await.yuyu_session.is_none() {
                tokio::time::sleep(SIGNED_OUT_CHECK).await;
                continue;
            }
            match listen(&state, &app, &client, &mut retry).await {
                Stop::Closed => tokio::time::sleep(FIRST_RETRY).await,
                Stop::Missing => tokio::time::sleep(ROUTE_MISSING_RETRY).await,
                Stop::Failed => {
                    tokio::time::sleep(retry).await;
                    retry = (retry * 2).min(MAX_RETRY);
                }
            }
        }
    });
}

async fn listen(state: &SharedState, app: &tauri::AppHandle, client: &reqwest::Client, retry: &mut Duration) -> Stop {
    let Ok(token) = api::valid_access_token(state).await else { return Stop::Failed };
    let resp = match client
        .get(format!("{}/friends/events", api::base()))
        .bearer_auth(token)
        .header(reqwest::header::ACCEPT, "text/event-stream")
        .send()
        .await
    {
        Ok(resp) => resp,
        Err(_) => return Stop::Failed,
    };
    match resp.status().as_u16() {
        200 => {}
        // Jeton refusé : un neuf, et on recommence vite.
        401 => {
            let _ = api::refresh(state).await;
            return Stop::Closed;
        }
        404 => return Stop::Missing,
        _ => return Stop::Failed,
    }

    let mut resp = resp;
    let mut buffer = String::new();
    // Octets reçus pas encore lisibles : un caractère (l'emoji d'un statut)
    // peut être coupé entre deux morceaux.
    let mut raw: Vec<u8> = Vec::new();
    loop {
        let chunk = match tokio::time::timeout(SILENCE, resp.chunk()).await {
            Ok(Ok(Some(chunk))) => chunk,
            Ok(Ok(None)) => return Stop::Closed,
            Ok(Err(_)) | Err(_) => return Stop::Failed,
        };
        raw.extend_from_slice(&chunk);
        let readable = match std::str::from_utf8(&raw) {
            Ok(text) => text.len(),
            // Un caractère commencé, la suite au prochain morceau.
            Err(e) if e.error_len().is_none() => e.valid_up_to(),
            Err(_) => return Stop::Failed,
        };
        buffer.push_str(std::str::from_utf8(&raw[..readable]).unwrap_or_default());
        raw.drain(..readable);
        if buffer.len() > MAX_BUFFER {
            return Stop::Failed;
        }
        for (event, data) in take_events(&mut buffer) {
            if event == "ready" {
                // La connexion tient : le prochain échec repartira du délai le plus court.
                *retry = FIRST_RETRY;
            }
            if let Some(payload) = payload(&event, &data) {
                // Notification Windows d'abord : l'interface peut ne pas exister
                // (fenêtre fermée pendant une partie).
                super::notify::on_event(app, &payload);
                let _ = app.emit("friends_event", payload);
            }
        }
        // Déconnecté entre-temps : on lâche le flux.
        if state.read().await.yuyu_session.is_none() {
            return Stop::Closed;
        }
    }
}

/// Retire du tampon les événements complets (terminés par une ligne vide) :
/// `(nom, données)`. Les commentaires (`:`) sont ignorés.
fn take_events(buffer: &mut String) -> Vec<(String, String)> {
    let normalized = buffer.replace("\r\n", "\n");
    let Some(end) = normalized.rfind("\n\n") else {
        *buffer = normalized;
        return Vec::new();
    };
    let (complete, rest) = normalized.split_at(end + 2);
    let events = complete
        .split("\n\n")
        .filter_map(|block| {
            let (mut event, mut data) = (String::from("message"), Vec::new());
            for line in block.lines() {
                if let Some(value) = line.strip_prefix("event:") {
                    event = value.trim().to_string();
                } else if let Some(value) = line.strip_prefix("data:") {
                    data.push(value.strip_prefix(' ').unwrap_or(value).to_string());
                }
            }
            (!data.is_empty()).then(|| (event, data.join("\n")))
        })
        .collect();
    *buffer = rest.to_string();
    events
}

/// Ce qui part vers l'interface, `None` pour ce qu'elle n'a pas à connaître.
fn payload(event: &str, data: &str) -> Option<Value> {
    match event {
        "ready" => Some(json!({ "type": "ready" })),
        "friend" => serde_json::from_str::<Value>(data).ok().filter(|v| v.get("type").is_some_and(Value::is_string)),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn les_evenements_se_lisent_par_blocs_complets() {
        let mut buffer = String::from("event: ready\ndata: {}\n\n: garde\n\nevent: friend\r\ndata: {\"type\":\"resync\"}\r\n\r\nevent: friend\ndata: {\"ty");
        let events = take_events(&mut buffer);
        assert_eq!(events, vec![("ready".into(), "{}".into()), ("friend".into(), "{\"type\":\"resync\"}".into())]);
        // La fin incomplète attend la suite.
        assert_eq!(buffer, "event: friend\ndata: {\"ty");
        buffer.push_str("pe\":\"friends_changed\"}\n\n");
        assert_eq!(take_events(&mut buffer), vec![("friend".into(), "{\"type\":\"friends_changed\"}".into())]);
        assert!(buffer.is_empty());
    }

    #[test]
    fn seuls_les_evenements_connus_passent() {
        assert_eq!(payload("ready", "{}"), Some(json!({ "type": "ready" })));
        assert_eq!(payload("friend", "{\"type\":\"resync\"}"), Some(json!({ "type": "resync" })));
        assert_eq!(payload("friend", "pas du json"), None);
        assert_eq!(payload("friend", "{\"sans\":\"type\"}"), None);
        assert_eq!(payload("autre", "{\"type\":\"x\"}"), None);
    }
}
