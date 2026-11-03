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
//! | `token`           | `token⇥<visible 0/1>⇥<jeton ou ->⇥<uuid du compte Minecraft en jeu ou ->` |
//! | `accounts`        | `accounts⇥<uuid en jeu>⇥(<uuid>⇥<pseudo>⇥<hors ligne 0/1>)…` |
//! | `switch⇥<uuid>`   | `account⇥<uuid>⇥<pseudo>⇥<hors ligne 0/1>⇥<jeton Minecraft>` |
//! | `skin`            | `skin⇥<modèle ou ->⇥<adresse chez Mojang ou ->⇥<chemin du PNG ou ->` |
//! | `skin⇥<uuid>`     | la même chose pour un autre compte de la liste (sa tête, dans le jeu) |
//! | `activity⇥<serveur ou ->⇥<- / casual / ranked>⇥<mode ou ->` | `ok` — ce que fait le joueur, pour ses amis (`friends::presence`) |
//! | `friends`         | `friends⇥(<id>⇥<pseudo>⇥<online/away/dnd/offline>⇥<en jeu 0/1>)…` — les amis, les présents d'abord |
//! | `invite⇥<id>`     | `ok` — invitation envoyée à cet ami sur le serveur où l'on joue |
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
    // Les amis voient « en jeu » sans attendre le prochain signal.
    crate::friends::presence::poke();
    rx
}

pub fn untrack(instance_id: &str) {
    if let Some(map) = accounts().as_mut() {
        map.remove(instance_id);
    }
    crate::friends::presence::clear_game(instance_id);
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

/// Un identifiant public d'ami (UUID) : il part dans une adresse.
fn is_public_id(id: &str) -> bool {
    id.len() == 36 && id.chars().all(|c| c.is_ascii_hexdigit() || c == '-')
}

/// Liste d'amis de démonstration posée par l'interface de dev (interrupteur
/// « données de démonstration » de l'atelier, `lib/friendsDemo.ts`) : le jeu
/// la reçoit à la place de la vraie, et une invitation y répond « ok » sans
/// rien envoyer. `None` = les vraies données.
static DEMO_FRIENDS: Mutex<Option<serde_json::Value>> = Mutex::new(None);

/// Pose ou retire la liste de démonstration. Sans effet en build release :
/// rien de factice ne doit jamais atteindre le jeu d'un joueur.
#[tauri::command]
pub fn game_friends_demo(list: Option<serde_json::Value>) {
    if !cfg!(debug_assertions) {
        return;
    }
    if let Ok(mut demo) = DEMO_FRIENDS.lock() {
        *demo = list;
    }
}

fn demo_friends() -> Option<serde_json::Value> {
    DEMO_FRIENDS.lock().ok().and_then(|d| d.clone())
}

/// Un ami lu dans la liste du serveur (`GET /friends`), avec ce que le jeu
/// affiche. Les champs absents sont des chaînes vides.
struct GameFriend<'a> {
    id: &'a str,
    name: &'a str,
    state: &'a str,
    in_game: bool,
    /// Le compte Minecraft principal (pour la tête), sinon vide.
    mc: &'a str,
    /// Statut personnalisé encore valable.
    custom: String,
    /// `game`, `launcher` ou vide.
    activity: &'a str,
    version: &'a str,
    loader: &'a str,
    /// `ranked:<mode>` ou `casual:<mode>`, vide hors duel.
    duel: String,
    /// « hôte:port », seulement si l'ami le montre.
    server: &'a str,
    /// Hors ligne : dernière présence, en secondes Unix (0 = inconnue).
    last_seen: i64,
}

fn text<'a>(v: &'a serde_json::Value, path: &str) -> &'a str {
    v.pointer(path).and_then(|s| s.as_str()).unwrap_or("")
}

fn read_friends(list: &serde_json::Value) -> Vec<GameFriend<'_>> {
    let now = chrono::Utc::now();
    let friends = list.get("friends").and_then(|f| f.as_array()).map(Vec::as_slice).unwrap_or_default();
    let mut rows: Vec<GameFriend> = friends
        .iter()
        .filter_map(|f| {
            let id = f.get("id")?.as_str().filter(|id| is_public_id(id))?;
            let name = f.get("username")?.as_str()?;
            let state = match f.pointer("/presence/state").and_then(|s| s.as_str()) {
                Some(s @ ("online" | "away" | "dnd")) => s,
                _ => "offline",
            };
            let online = state != "offline";
            let activity = if online { text(f, "/presence/activity/kind") } else { "" };
            let activity = if matches!(activity, "game" | "launcher") { activity } else { "" };
            let accounts = f.get("minecraft").and_then(|m| m.as_array()).map(Vec::as_slice).unwrap_or_default();
            let mc = accounts
                .iter()
                .find(|a| a.get("main").and_then(|m| m.as_bool()) == Some(true))
                .or_else(|| accounts.first())
                .and_then(|a| a.get("uuid")?.as_str())
                .filter(|u| u.chars().all(|c| c.is_ascii_hexdigit() || c == '-'))
                .unwrap_or("");
            let expired = f
                .pointer("/presence/custom/expires_at")
                .and_then(|e| e.as_str())
                .and_then(|e| chrono::DateTime::parse_from_rfc3339(e).ok())
                .is_some_and(|e| e <= now);
            let custom = if expired { String::new() } else { field(text(f, "/presence/custom/text")) };
            let duel = match f.pointer("/presence/activity/duel") {
                Some(d) if d.is_object() && activity == "game" => format!(
                    "{}:{}",
                    if d.get("ranked").and_then(|r| r.as_bool()) == Some(true) { "ranked" } else { "casual" },
                    text(d, "/mode")
                ),
                _ => String::new(),
            };
            let last_seen = f
                .pointer("/presence/last_seen")
                .and_then(|e| e.as_str())
                .and_then(|e| chrono::DateTime::parse_from_rfc3339(e).ok())
                .map(|e| e.timestamp())
                .unwrap_or(0);
            Some(GameFriend {
                id,
                name,
                state,
                in_game: activity == "game",
                mc,
                custom,
                activity,
                version: if activity == "game" { text(f, "/presence/activity/mc_version") } else { "" },
                loader: if activity == "game" { text(f, "/presence/activity/loader") } else { "" },
                duel,
                server: if activity == "game" { text(f, "/presence/activity/server") } else { "" },
                last_seen,
            })
        })
        .collect();
    // En jeu, en ligne, hors ligne ; par pseudo, et les plus récemment vus
    // d'abord parmi les hors ligne — l'ordre du panneau du launcher.
    let order = |f: &GameFriend| if f.in_game { 0 } else if f.state != "offline" { 1 } else { 2 };
    rows.sort_by(|a, b| {
        order(a)
            .cmp(&order(b))
            .then_with(|| if order(a) == 2 { b.last_seen.cmp(&a.last_seen) } else { std::cmp::Ordering::Equal })
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
    });
    rows
}

/// La réponse à `friends` : `friends⇥(<id>⇥<pseudo>⇥<état>⇥<en jeu 0/1>)…`.
fn friends_line(list: &serde_json::Value) -> String {
    let mut line = String::from("friends");
    for f in read_friends(list) {
        line.push_str(&format!("\t{}\t{}\t{}\t{}", f.id, field(f.name), f.state, f.in_game as u8));
    }
    line
}

/// La réponse à `friends2`, ce qu'affiche le menu YuyuFrame en jeu (bulle
/// comprise) : `friends2⇥(<id>⇥<pseudo>⇥<état>⇥<en jeu 0/1>⇥<uuid Minecraft>⇥
/// <statut perso>⇥<activité>⇥<version>⇥<loader>⇥<duel>⇥<serveur>⇥<vu, s Unix>)…`,
/// douze champs par ami, vides quand ils n'ont pas de valeur.
fn friends_line_v2(list: &serde_json::Value) -> String {
    let mut line = String::from("friends2");
    for f in read_friends(list) {
        let fields = [
            f.id.to_string(),
            field(f.name),
            f.state.to_string(),
            (f.in_game as u8).to_string(),
            f.mc.to_string(),
            f.custom,
            f.activity.to_string(),
            field(f.version),
            field(f.loader),
            field(&f.duel),
            field(f.server),
            if f.last_seen > 0 { f.last_seen.to_string() } else { String::new() },
        ];
        for value in fields {
            line.push('\t');
            line.push_str(&value);
        }
    }
    line
}

/// Missions de démonstration posées par l'interface de dev (même interrupteur
/// que les amis, `lib/competitive.ts` `demoData`), au format de l'interface
/// (`endsAt` en millisecondes). `None` = la vraie route `/pass`.
static DEMO_MISSIONS: Mutex<Option<serde_json::Value>> = Mutex::new(None);

/// Pose ou retire les missions de démonstration. Sans effet en build release.
#[tauri::command]
pub fn game_missions_demo(list: Option<serde_json::Value>) {
    if !cfg!(debug_assertions) {
        return;
    }
    if let Ok(mut demo) = DEMO_MISSIONS.lock() {
        *demo = list;
    }
}

/// La réponse à `missions`, l'onglet Missions du menu YuyuFrame en jeu :
/// `missions⇥(<période>⇥<sorte>⇥<mode>⇥<cible>⇥<progression>⇥<but>⇥<fin, ms>⇥<xp>)…`,
/// huit champs par mission (mode vide = tous). `challenges` est la liste de
/// `GET /pass` (`ends_at`) ou celle de démonstration (`endsAt`).
fn missions_line(challenges: &[serde_json::Value]) -> String {
    let num = |c: &serde_json::Value, keys: &[&str]| {
        keys.iter().find_map(|k| c.get(*k).and_then(|v| v.as_i64())).unwrap_or(0)
    };
    let mut line = String::from("missions");
    for c in challenges {
        let period = text(c, "/period");
        let kind = text(c, "/kind");
        if !matches!(period, "daily" | "weekly") || kind.is_empty() {
            continue;
        }
        let fields = [
            period.to_string(),
            field(kind),
            field(text(c, "/mode")),
            num(c, &["target"]).to_string(),
            num(c, &["progress"]).to_string(),
            num(c, &["goal"]).to_string(),
            num(c, &["ends_at", "endsAt"]).to_string(),
            num(c, &["xp"]).to_string(),
        ];
        for value in fields {
            line.push('\t');
            line.push_str(&value);
        }
    }
    line
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
            // Le compte Minecraft de la partie : le jeu lit son classement sous ce compte.
            let account = current_uuid(&ctx.instance_id).filter(|u| !u.is_empty() && !u.contains('\t'));
            format!("token\t{}\t{}\t{}", visible as u8, token.as_deref().unwrap_or("-"), account.as_deref().unwrap_or("-"))
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
        "skin" => {
            // Sans argument : le compte en jeu. Avec un UUID : un autre compte
            // de la liste, dont le jeu veut afficher la tête.
            let skin = match parts.next().filter(|uuid| !uuid.is_empty()) {
                Some(uuid) => crate::account::skins::host::game_skin(&ctx.state, uuid, false).await,
                None => match current_uuid(&ctx.instance_id) {
                    Some(uuid) => crate::account::skins::host::game_skin(&ctx.state, &uuid, true).await,
                    None => None,
                },
            };
            match skin {
                Some(skin) => format!(
                    "skin\t{}\t{}\t{}",
                    field(&skin.variant),
                    skin.hosted.as_deref().map(field).unwrap_or_else(|| "-".into()),
                    skin.path.map(|p| field(&p.to_string_lossy())).unwrap_or_else(|| "-".into()),
                ),
                None => "skin\t-\t-\t-".to_string(),
            }
        }
        // Le jeu annonce son serveur et son duel ; le signal des amis part aussitôt.
        "activity" => {
            let fields: Vec<&str> = parts.collect();
            match crate::friends::presence::parse_game_line(&fields) {
                Some(activity) => {
                    crate::friends::presence::set_game(&ctx.instance_id, activity);
                    "ok".to_string()
                }
                None => error("Activité illisible"),
            }
        }
        "switch" => match parts.next() {
            Some(uuid) if !uuid.is_empty() => switch_account(ctx, uuid).await,
            _ => error("Compte non précisé"),
        },
        "friends" => match demo_friends() {
            Some(demo) => friends_line(&demo),
            None => match crate::server::get(&ctx.state, "/friends", &[]).await {
                Ok(list) => friends_line(&list),
                Err(e) => error(&e.message),
            },
        },
        // Redemandée toutes les 2 s par le jeu : servie depuis la liste gardée
        // par le launcher, rechargée quand le flux temps réel signale un changement.
        "friends2" => match demo_friends() {
            Some(demo) => friends_line_v2(&demo),
            None => match crate::friends::current_list(&ctx.state).await {
                Ok(list) => friends_line_v2(&list),
                Err(e) => error(&e.message),
            },
        },
        "invite" if demo_friends().is_some() => "ok".to_string(),
        // L'onglet Missions du menu en jeu : les mêmes missions que l'écran
        // Missions du launcher (`GET /pass`), ou celles de démonstration.
        "missions" => {
            let demo = DEMO_MISSIONS.lock().ok().and_then(|d| d.clone());
            match demo {
                Some(list) => missions_line(list.as_array().map(Vec::as_slice).unwrap_or_default()),
                None => match crate::server::get(&ctx.state, "/pass", &[]).await {
                    Ok(pass) => missions_line(pass.get("challenges").and_then(|c| c.as_array()).map(Vec::as_slice).unwrap_or_default()),
                    Err(e) => error(&e.message),
                },
            }
        }
        // Le bouton « Inviter » du jeu : même invitation que celle du
        // launcher (le serveur la lit dans notre présence, et l'écrit aussi
        // en message privé Discord à l'ami qui l'a demandé).
        "invite" => match parts.next().filter(|id| is_public_id(id)) {
            Some(id) => match crate::server::post(&ctx.state, &format!("/friends/{id}/invite"), serde_json::json!({})).await {
                Ok(_) => "ok".to_string(),
                Err(e) => error(&e.message),
            },
            None => error("Ami non précisé"),
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
    fn la_liste_detaillee_des_amis_garde_douze_champs_par_ami() {
        let list = serde_json::json!({ "friends": [
            { "id": "00000000-0000-4000-8000-000000000005", "username": "Philza",
              "minecraft": [], "presence": { "state": "offline", "custom": { "text": "En\tvacances", "expires_at": null },
              "activity": null, "last_seen": "2026-11-01T10:00:00Z" } },
            { "id": "00000000-0000-4000-8000-000000000001", "username": "Technoblade",
              "minecraft": [{ "uuid": "b876ec32e396476ba1158438d83c67d4", "name": "Technoblade", "main": true }],
              "presence": { "state": "online", "custom": null, "activity": { "kind": "game", "mc_version": "1.21.11",
              "loader": "fabric", "duel": { "ranked": true, "mode": "mace" }, "server": "eu.minemen.club:25565" }, "last_seen": null } },
        ]});
        let line = friends_line_v2(&list);
        let parts: Vec<&str> = line.split('\t').collect();
        assert_eq!(parts[0], "friends2");
        assert_eq!(parts.len(), 1 + 2 * 12);
        // L'ami en jeu passe devant, avec tout ce que la bulle affiche.
        assert_eq!(&parts[1..13], &[
            "00000000-0000-4000-8000-000000000001", "Technoblade", "online", "1", "b876ec32e396476ba1158438d83c67d4",
            "", "game", "1.21.11", "fabric", "ranked:mace", "eu.minemen.club:25565", "",
        ]);
        // Hors ligne : ni activité ni serveur, le statut perso sans sa tabulation, la dernière présence.
        assert_eq!(&parts[13..25], &[
            "00000000-0000-4000-8000-000000000005", "Philza", "offline", "0", "", "Envacances", "", "", "", "", "", "1793527200",
        ]);
    }

    #[test]
    fn les_missions_lisent_le_serveur_comme_la_demo() {
        // `GET /pass` écrit `ends_at`, la démonstration de l'interface `endsAt`.
        let api = serde_json::json!([{ "id": 7, "kind": "winDuels", "period": "daily", "mode": "mace", "target": 5,
            "progress": 3, "goal": 5, "done": false, "ends_at": 1793577600000_i64, "xp": 1000 }]);
        let demo = serde_json::json!([{ "id": "w1", "kind": "streak", "period": "weekly", "mode": null, "target": 5,
            "progress": 3, "goal": 5, "endsAt": 1793577600000_i64, "xp": 4500 },
            { "kind": "inconnue", "period": "mensuelle" }]);
        assert_eq!(missions_line(api.as_array().unwrap()), "missions\tdaily\twinDuels\tmace\t5\t3\t5\t1793577600000\t1000");
        // Une période inconnue est écartée plutôt que de décaler les champs.
        assert_eq!(missions_line(demo.as_array().unwrap()), "missions\tweekly\tstreak\t\t5\t3\t5\t1793577600000\t4500");
    }

    #[test]
    fn un_champ_ne_peut_pas_decaler_les_suivants() {
        assert_eq!(field("Ste\tve\r\n"), "Steve");
        assert_eq!(error("pas\tça"), "error\tpasça");
    }

    #[test]
    fn la_liste_d_amis_met_les_joueurs_en_jeu_devant() {
        let a = "0f8fad5b-d9cb-469f-a165-70867728950e";
        let b = "1f8fad5b-d9cb-469f-a165-70867728950e";
        let c = "2f8fad5b-d9cb-469f-a165-70867728950e";
        let list = serde_json::json!({ "friends": [
            { "id": a, "username": "Zed", "presence": { "state": "offline" } },
            { "id": b, "username": "Alex", "presence": { "state": "online" } },
            { "id": c, "username": "Mo\tla", "presence": { "state": "dnd", "activity": { "kind": "game" } } },
            { "id": "../x", "username": "Pirate", "presence": { "state": "online" } },
        ] });
        assert_eq!(friends_line(&list), format!("friends\t{c}\tMola\tdnd\t1\t{b}\tAlex\tonline\t0\t{a}\tZed\toffline\t0"));
        assert_eq!(friends_line(&serde_json::json!({})), "friends");
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
