//! Le canal entre le launcher et le jeu qu'il vient de lancer : un tube nommé,
//! à double sens, que le jeu ouvre depuis `content_core.dll`
//! (`Launcher-Agent/content-core/src/link/`).
//!
//! Il remplace trois bricolages à sens unique : l'événement Windows « jeu
//! prêt », le jeton du compte YuyuFrame écrit sur l'entrée standard du jeu, et
//! — pour changer de compte Minecraft en jeu — une ligne guettée dans sa
//! sortie, qui n'aurait donné ni réponse ni erreur.
//!
//! Le jeu pose une question, le launcher répond : une ligne dans chaque sens,
//! des champs séparés par des tabulations (ni l'un ni l'autre bout n'a besoin
//! d'un analyseur JSON, et aucune valeur transportée ne contient de
//! tabulation). Le launcher ne parle jamais le premier : le jeu redemande le
//! jeton à intervalle régulier.
//!
//! | question          | réponse                                                    |
//! |-------------------|------------------------------------------------------------|
//! | `ready`           | `ok`                                                       |
//! | `token`           | `token⇥<visible 0/1>⇥<jeton ou ->`                         |
//! | `accounts`        | `accounts⇥<uuid en jeu>⇥(<uuid>⇥<pseudo>⇥<hors ligne 0/1>)…` |
//! | `switch⇥<uuid>`   | `account⇥<uuid>⇥<pseudo>⇥<hors ligne 0/1>⇥<jeton Minecraft>` |
//! | (tout)            | `error⇥<message>`                                          |
//!
//! Qui peut s'y brancher : le tube porte un nom tiré au hasard, n'accepte
//! qu'une connexion, refuse le réseau, et le launcher vérifie que le processus
//! à l'autre bout est bien celui du jeu qu'il a lancé — un autre programme qui
//! aurait deviné le nom est renvoyé. Rien n'est écrit sur le disque ni sur la
//! ligne de commande ; seul le NOM du tube est dans l'environnement du jeu.
//!
//! Windows seulement : `content_core.dll` n'existe que là.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use tauri::Emitter;
use tokio::sync::watch;

use crate::state::{MinecraftSession, SharedState};

/// Nom du tube, posé dans l'environnement du jeu. Le même dans
/// `content-core/src/link/mod.rs`.
pub const PIPE_ENV: &str = "YUYU_GAME_PIPE";

// ── Le compte Minecraft d'une partie en cours ───────────────────────────────
//
// Il peut changer pendant la partie (liste déroulante du client intégré) : ce
// qui en dépend — le signal de présence — le suit par ce registre plutôt que
// de garder le compte du lancement.

static ACCOUNTS: Mutex<Option<HashMap<String, watch::Sender<MinecraftSession>>>> = Mutex::new(None);

fn accounts() -> std::sync::MutexGuard<'static, Option<HashMap<String, watch::Sender<MinecraftSession>>>> {
    ACCOUNTS.lock().unwrap_or_else(|p| p.into_inner())
}

/// Déclare la partie qui démarre et le compte avec lequel elle part. Le
/// récepteur voit chaque changement de compte fait en jeu.
pub fn track(instance_id: &str, session: MinecraftSession) -> watch::Receiver<MinecraftSession> {
    let (tx, rx) = watch::channel(session);
    accounts().get_or_insert_with(HashMap::new).insert(instance_id.to_string(), tx);
    rx
}

pub fn untrack(instance_id: &str) {
    if let Some(map) = accounts().as_mut() {
        map.remove(instance_id);
    }
}

fn current_uuid(instance_id: &str) -> Option<String> {
    accounts().as_ref()?.get(instance_id).map(|tx| tx.borrow().uuid.clone())
}

fn publish(instance_id: &str, session: MinecraftSession) {
    if let Some(tx) = accounts().as_ref().and_then(|map| map.get(instance_id)) {
        let _ = tx.send(session);
    }
}

// ── Le protocole, sans le tube ──────────────────────────────────────────────

/// Ce dont une réponse a besoin, en dehors de la question.
pub struct Context {
    pub state: SharedState,
    pub app: tauri::AppHandle,
    pub instance_id: String,
    /// Partagé avec le repli par la sortie du jeu : « prêt » n'est émis qu'une fois.
    pub ready_sent: Arc<AtomicBool>,
    pub launch_start: std::time::Instant,
}

/// Une valeur à mettre dans une réponse : jamais de tabulation ni de retour à
/// la ligne, qui décaleraient les champs.
fn field(text: &str) -> String {
    text.chars().filter(|c| !matches!(c, '\t' | '\r' | '\n')).collect()
}

fn error(message: &str) -> String {
    format!("error\t{}", field(message))
}

async fn answer(ctx: &Context, question: &str) -> String {
    let mut parts = question.split('\t');
    match parts.next().unwrap_or("") {
        "ready" => {
            if ctx.ready_sent.compare_exchange(false, true, Ordering::Relaxed, Ordering::Relaxed).is_ok() {
                crate::integrations::analytics::capture("launch_completed", serde_json::json!({
                    "instance_id": &ctx.instance_id,
                    "duration_ms": ctx.launch_start.elapsed().as_millis() as u64,
                }));
                let _ = ctx.app.emit("game_ready", serde_json::json!({ "instance_id": &ctx.instance_id }));
            }
            "ok".to_string()
        }
        "token" => {
            let visible = super::presence::visible(&ctx.state).await;
            let token = match super::presence::signed_in(&ctx.state).await {
                true => crate::server::valid_access_token(&ctx.state).await.ok(),
                false => None,
            };
            format!("token\t{}\t{}", visible as u8, token.as_deref().unwrap_or("-"))
        }
        "accounts" => match crate::account::minecraft::list_accounts(&ctx.state).await {
            Ok(list) => {
                let mut line = format!("accounts\t{}", current_uuid(&ctx.instance_id).as_deref().unwrap_or("-"));
                for account in list {
                    line.push_str(&format!("\t{}\t{}\t{}", field(&account.mc_uuid), field(&account.mc_username), account.is_offline as u8));
                }
                line
            }
            Err(e) => error(&e),
        },
        "switch" => match parts.next() {
            Some(uuid) if !uuid.is_empty() => switch_account(ctx, uuid).await,
            _ => error("Compte non précisé"),
        },
        _ => error("Question inconnue"),
    }
}

/// Le joueur a choisi un autre compte dans le jeu. Le jeton est rafraîchi
/// ICI : le jeton de rafraîchissement Microsoft ne quitte jamais le launcher.
/// Le compte devient aussi le compte actif du launcher — c'est le même geste
/// que de le choisir sur l'écran Comptes.
async fn switch_account(ctx: &Context, uuid: &str) -> String {
    let session = match crate::account::minecraft::fresh_session(&ctx.state, uuid).await {
        Ok(session) => session,
        Err(e) => return error(&e),
    };
    if let Err(e) = crate::account::minecraft::activate_account(&ctx.state, uuid).await {
        tracing::warn!("compte choisi en jeu non activé dans le launcher : {e}");
    }
    let offline = session.refresh_token.is_none();
    let _ = ctx.app.emit("mc_account_changed", serde_json::json!({
        "mc_uuid": &session.uuid,
        "mc_username": &session.username,
        "is_offline": offline,
        "is_active": true,
    }));
    let line = format!(
        "account\t{}\t{}\t{}\t{}",
        field(&session.uuid),
        field(&session.username),
        offline as u8,
        field(&session.access_token),
    );
    publish(&ctx.instance_id, session);
    line
}

// ── Le tube ─────────────────────────────────────────────────────────────────

#[cfg(target_os = "windows")]
mod pipe {
    use std::os::windows::io::AsRawHandle;

    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
    use tokio::net::windows::named_pipe::{NamedPipeServer, ServerOptions};

    use super::{answer, Context};

    /// Une question fait quelques dizaines d'octets ; au-delà, ce n'est pas le jeu.
    const MAX_QUESTION: usize = 512;

    #[link(name = "kernel32")]
    extern "system" {
        fn GetNamedPipeClientProcessId(pipe: *mut std::ffi::c_void, client_process_id: *mut u32) -> i32;
    }

    pub struct GameLink {
        name: String,
        server: NamedPipeServer,
    }

    /// Crée le tube. À faire AVANT de lancer le jeu, qui reçoit son nom.
    pub fn open() -> Option<GameLink> {
        let name = format!(r"\\.\pipe\yuyuframe-game-{}", uuid::Uuid::new_v4());
        match ServerOptions::new().first_pipe_instance(true).reject_remote_clients(true).max_instances(1).create(&name) {
            Ok(server) => Some(GameLink { name, server }),
            Err(e) => {
                tracing::warn!("canal du jeu non créé : {e}");
                None
            }
        }
    }

    fn client_pid(server: &NamedPipeServer) -> Option<u32> {
        let mut pid = 0u32;
        let ok = unsafe { GetNamedPipeClientProcessId(server.as_raw_handle() as *mut std::ffi::c_void, &mut pid) };
        (ok != 0).then_some(pid)
    }

    impl GameLink {
        pub fn name(&self) -> &str {
            &self.name
        }

        /// Sert le jeu `game_pid` jusqu'à ce qu'il ferme le tube. À appeler
        /// une fois le jeu lancé ; la tâche s'arrête seule avec lui (`abort`
        /// sur la poignée rendue pour un arrêt immédiat).
        pub fn serve(self, game_pid: Option<u32>, ctx: Context) -> tokio::task::JoinHandle<()> {
            tokio::spawn(async move {
                let ctx = &ctx;
                self.run(game_pid, |question| async move { answer(ctx, &question).await }).await;
            })
        }

        /// Le tube sans le protocole : attend le jeu, puis une réponse par
        /// question jusqu'à ce qu'il raccroche.
        async fn run<F, Fut>(self, game_pid: Option<u32>, respond: F)
        where
            F: Fn(String) -> Fut,
            Fut: std::future::Future<Output = String>,
        {
            let mut server = self.server;
            // N'importe quel programme de la session peut tenter d'ouvrir
            // le tube : seul le jeu est écouté.
            loop {
                if let Err(e) = server.connect().await {
                    tracing::warn!("canal du jeu : attente de connexion interrompue : {e}");
                    return;
                }
                match (client_pid(&server), game_pid) {
                    (Some(client), Some(game)) if client != game => {
                        tracing::warn!("canal du jeu : connexion d'un autre processus ({client}) refusée");
                        if server.disconnect().is_err() {
                            return;
                        }
                    }
                    _ => break,
                }
            }
            let (reader, mut writer) = tokio::io::split(&mut server);
            let mut lines = BufReader::new(reader).lines();
            loop {
                let question = match lines.next_line().await {
                    Ok(Some(line)) => line,
                    // Tube fermé : le jeu s'est arrêté.
                    _ => return,
                };
                let mut reply = match question.len() <= MAX_QUESTION {
                    true => respond(question).await,
                    false => super::error("Question trop longue"),
                };
                reply.push('\n');
                if writer.write_all(reply.as_bytes()).await.is_err() || writer.flush().await.is_err() {
                    return;
                }
            }
        }
    }

    #[cfg(test)]
    mod tests {
        use std::io::{BufRead, BufReader, Write};

        use super::*;

        /// Le jeu, tel que `content_core.dll` s'y prend : un fichier ordinaire
        /// ouvert en lecture-écriture, une ligne écrite, une ligne lue.
        fn ask(pipe: &mut std::fs::File, question: &str) -> std::io::Result<String> {
            pipe.write_all(format!("{question}\n").as_bytes())?;
            let mut reply = String::new();
            BufReader::new(pipe.try_clone()?).read_line(&mut reply)?;
            Ok(reply.trim_end().to_string())
        }

        #[tokio::test(flavor = "multi_thread")]
        async fn le_jeu_pose_ses_questions_et_recoit_ses_reponses() {
            let link = open().expect("tube créé");
            let name = link.name().to_string();
            // Le « jeu » est ce processus-ci : son identifiant est celui qu'on attend.
            let server = tokio::spawn(link.run(Some(std::process::id()), |question| async move { format!("echo\t{question}") }));
            let replies = tokio::task::spawn_blocking(move || {
                let mut pipe = std::fs::OpenOptions::new().read(true).write(true).open(&name).expect("tube ouvert");
                let first = ask(&mut pipe, "accounts").unwrap();
                let second = ask(&mut pipe, "switch\tabc").unwrap();
                let long = ask(&mut pipe, &"x".repeat(MAX_QUESTION + 1)).unwrap();
                (first, second, long)
            })
            .await
            .unwrap();
            assert_eq!(replies.0, "echo\taccounts");
            assert_eq!(replies.1, "echo\tswitch\tabc");
            assert_eq!(replies.2, "error\tQuestion trop longue");
            // Le jeu a fermé le tube : le service s'arrête de lui-même.
            server.await.unwrap();
        }

        #[tokio::test(flavor = "multi_thread")]
        async fn un_autre_processus_est_renvoye() {
            let link = open().expect("tube créé");
            let name = link.name().to_string();
            // On attend un jeu dont l'identifiant n'est pas le nôtre : notre connexion doit être refusée.
            let server = tokio::spawn(link.run(Some(std::process::id().wrapping_add(1)), |q| async move { q }));
            let refused = tokio::task::spawn_blocking(move || {
                let mut pipe = std::fs::OpenOptions::new().read(true).write(true).open(&name).expect("tube ouvert");
                ask(&mut pipe, "accounts")
            })
            .await
            .unwrap();
            assert!(!matches!(refused.as_deref(), Ok("accounts")), "aucune réponse pour un intrus");
            server.abort();
        }
    }
}

#[cfg(not(target_os = "windows"))]
mod pipe {
    use super::Context;

    pub struct GameLink;

    pub fn open() -> Option<GameLink> {
        None
    }

    impl GameLink {
        pub fn name(&self) -> &str {
            ""
        }

        pub fn serve(self, _game_pid: Option<u32>, _ctx: Context) -> tokio::task::JoinHandle<()> {
            tokio::spawn(async {})
        }
    }
}

pub use pipe::open;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn un_champ_ne_peut_pas_decaler_les_suivants() {
        assert_eq!(field("Ste\tve\r\n"), "Steve");
        assert_eq!(error("pas\tça"), "error\tpasça");
    }

    #[test]
    fn le_compte_d_une_partie_suit_les_changements() {
        let session = |uuid: &str| MinecraftSession {
            username: "Steve".into(),
            uuid: uuid.into(),
            access_token: "offline".into(),
            refresh_token: None,
            expires_at: 0,
        };
        let mut rx = track("instance-de-test", session("a"));
        assert_eq!(current_uuid("instance-de-test").as_deref(), Some("a"));
        publish("instance-de-test", session("b"));
        assert!(rx.has_changed().unwrap());
        assert_eq!(rx.borrow_and_update().uuid, "b");
        untrack("instance-de-test");
        assert_eq!(current_uuid("instance-de-test"), None);
        // Publier pour une partie terminée ne fait rien.
        publish("instance-de-test", session("c"));
    }
}
