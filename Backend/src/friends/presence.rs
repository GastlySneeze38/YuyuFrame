//! Le signal de présence des amis (LauncherAPI `PUT /v1/friends/presence`) :
//! « le launcher est ouvert, et voici ce que je fais ».
//!
//! Une seule tâche pour tout le launcher, lancée au démarrage. Toutes les 30 s
//! (la cadence que donne le serveur), et tout de suite quand quelque chose
//! change — une partie démarre ou s'arrête, le jeu annonce un serveur ou un
//! duel —, elle envoie l'activité du moment. Sans compte YuyuFrame, elle ne
//! fait qu'attendre.
//!
//! Ne pas confondre avec `play::presence` : celui-là sert à être reconnu EN
//! JEU par les autres joueurs YuyuFrame (empreintes de comptes Minecraft) ;
//! celui-ci dit à ses AMIS que l'on est là. Ce que voient les amis se règle
//! sur le serveur (statut, invisible, qui voit l'activité, qui voit le
//! serveur — personne par défaut).
//!
//! Ce qui part : en jeu ou non, version de Minecraft et loader de la partie,
//! duel en cours (classé ou non, mode), et le serveur que le jeu a annoncé
//! (`game_link`, question `activity`). Jamais le nom de l'instance ni ses mods.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use serde_json::{json, Value};
use tokio::sync::Notify;

use crate::db;
use crate::server as api;
use crate::state::SharedState;

/// Cadence tant que le serveur n'a pas donné la sienne (`renew_in`).
const DEFAULT_RENEW: Duration = Duration::from_secs(30);
/// Bornes de la cadence annoncée : le serveur compte un launcher parti au bout de 75 s.
const MIN_RENEW_SECS: u64 = 10;
const MAX_RENEW_SECS: u64 = 60;
/// Après un échec (réseau, serveur sans la route), on réessaie moins souvent.
const RETRY: Duration = Duration::from_secs(60);
/// Sans compte YuyuFrame : on regarde de temps en temps si l'on s'est connecté.
const SIGNED_OUT_CHECK: Duration = Duration::from_secs(10);

/// Ce que le jeu d'une partie a annoncé (`game_link`, `activity`).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct GameActivity {
    pub server: Option<String>,
    /// Classé ou non, et mode (`mace`, `sword`, `other`).
    pub duel: Option<(bool, String)>,
}

static GAMES: Mutex<Option<HashMap<String, GameActivity>>> = Mutex::new(None);

fn games() -> std::sync::MutexGuard<'static, Option<HashMap<String, GameActivity>>> {
    GAMES.lock().unwrap_or_else(|p| p.into_inner())
}

fn wake() -> &'static Notify {
    static WAKE: OnceLock<Notify> = OnceLock::new();
    WAKE.get_or_init(Notify::new)
}

/// Quelque chose a changé (partie lancée ou arrêtée) : le signal part tout de suite.
pub fn poke() {
    wake().notify_one();
}

/// La ligne `activity⇥<serveur ou ->⇥<- | casual | ranked>⇥<mode ou ->` du jeu.
pub fn parse_game_line(fields: &[&str]) -> Option<GameActivity> {
    let [server, duel, mode] = fields else { return None };
    let server = (*server != "-" && !server.is_empty()).then(|| server.to_string());
    let duel = match *duel {
        "ranked" => Some((true, mode.to_string())),
        "casual" => Some((false, mode.to_string())),
        _ => None,
    };
    Some(GameActivity { server, duel })
}

/// Le jeu de cette partie a annoncé ce qu'il fait.
pub fn set_game(instance_id: &str, activity: GameActivity) {
    let changed = games().get_or_insert_with(HashMap::new).insert(instance_id.to_string(), activity.clone()) != Some(activity);
    if changed {
        poke();
    }
}

/// La partie s'arrête : ce qu'elle avait annoncé disparaît avec elle.
pub fn clear_game(instance_id: &str) {
    if let Some(map) = games().as_mut() {
        map.remove(instance_id);
    }
    poke();
}

/// L'activité à annoncer : la première partie en cours, sinon « dans le launcher ».
async fn current(state: &SharedState) -> Value {
    let (running, user_id, db) = {
        let s = state.read().await;
        let mut running: Vec<String> = s.running_instances.iter().cloned().collect();
        running.sort();
        (running, s.current_yuyu_user_id().unwrap_or(0), s.db.clone())
    };
    for id in running {
        let row = {
            let conn = db.lock().await;
            db::instance_get(&conn, &id, user_id).ok().flatten()
        };
        let Some(row) = row else { continue };
        let game = games().as_ref().and_then(|m| m.get(&id).cloned()).unwrap_or_default();
        return json!({
            "kind": "game",
            "mc_version": row.mc_version,
            "loader": row.loader,
            "duel": game.duel.map(|(ranked, mode)| json!({ "ranked": ranked, "mode": mode })),
            "server": game.server,
        });
    }
    json!({ "kind": "launcher" })
}

/// Lance le signal. Une seule fois, au démarrage. Par le moteur de Tauri :
/// appelée depuis `setup`, hors de tout contexte Tokio, `tokio::spawn` panique.
pub fn spawn(state: SharedState) {
    tauri::async_runtime::spawn(async move {
        let mut renew = DEFAULT_RENEW;
        loop {
            let pause = if state.read().await.yuyu_session.is_none() {
                SIGNED_OUT_CHECK
            } else {
                let activity = current(&state).await;
                match api::put(&state, "/friends/presence", activity).await {
                    Ok(resp) => {
                        if let Some(secs) = resp.get("renew_in").and_then(|v| v.as_u64()) {
                            renew = Duration::from_secs(secs.clamp(MIN_RENEW_SECS, MAX_RENEW_SECS));
                        }
                        renew
                    }
                    Err(e) => {
                        tracing::debug!("signal des amis refusé ({}) : {}", e.code, e.message);
                        RETRY
                    }
                }
            };
            tokio::select! {
                _ = tokio::time::sleep(pause) => {}
                _ = wake().notified() => {
                    // Plusieurs changements d'affilée (lancement : partie, puis serveur) n'en font qu'un.
                    tokio::time::sleep(Duration::from_millis(500)).await;
                }
            }
        }
    });
}

/// Le launcher se ferme : hors ligne tout de suite, sans attendre 75 s.
/// Au mieux en une seconde ; sinon le serveur s'en apercevra seul.
pub async fn leave(state: &SharedState) {
    if state.read().await.yuyu_session.is_none() {
        return;
    }
    let _ = tokio::time::timeout(Duration::from_secs(1), api::delete(state, "/friends/presence")).await;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn la_ligne_du_jeu_se_lit_strictement() {
        assert_eq!(
            parse_game_line(&["play.exemple.fr:25565", "ranked", "mace"]),
            Some(GameActivity { server: Some("play.exemple.fr:25565".into()), duel: Some((true, "mace".into())) })
        );
        assert_eq!(parse_game_line(&["-", "-", "-"]), Some(GameActivity::default()));
        assert_eq!(parse_game_line(&["-", "casual", "sword"]).unwrap().duel, Some((false, "sword".into())));
        assert_eq!(parse_game_line(&["seul"]), None);
    }

    #[test]
    fn une_partie_arretee_n_annonce_plus_rien() {
        set_game("instance-amis", GameActivity { server: Some("a.fr:25565".into()), duel: None });
        assert!(games().as_ref().unwrap().contains_key("instance-amis"));
        clear_game("instance-amis");
        assert!(!games().as_ref().unwrap().contains_key("instance-amis"));
    }
}
