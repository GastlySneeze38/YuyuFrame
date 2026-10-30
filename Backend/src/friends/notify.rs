//! Les notifications Windows des amis et du support, et la pastille sur
//! l'icône de la barre des tâches.
//!
//! **Pourquoi ici et pas dans l'interface** : pendant une partie, le launcher
//! se masque et sa fenêtre est même fermée (`app::tray`) — l'interface
//! n'existe plus, et c'est justement là qu'il faut prévenir. Le flux des amis
//! (`stream.rs`), lui, tourne toujours : chaque événement y passe par
//! [`on_event`], qui décide d'une notification Windows.
//!
//! Une notification ne part que si :
//! - le réglage « Notifications Windows » est allumé (l'interface le donne au
//!   démarrage et à chaque changement, [`notifications_configure`]) ;
//! - je ne suis pas en « Ne pas déranger » ;
//! - le launcher n'est pas devant moi (fenêtre fermée, réduite ou sans le
//!   focus) — sinon l'interface prévient déjà, dans la fenêtre.
//!
//! « X est en ligne » suit en plus le réglage des notifications de connexion,
//! et ne repart pas pour un ami vu en ligne il y a moins de 10 minutes : une
//! coupure réseau ne doit pas faire sonner deux fois.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use serde_json::Value;
use tauri::Manager;
use tauri_plugin_notification::NotificationExt;

/// Un ami revenu en ligne moins de ce temps après l'avoir quitté : pas de nouvelle notification.
pub const FLAP_WINDOW: Duration = Duration::from_secs(10 * 60);

static OS_ENABLED: AtomicBool = AtomicBool::new(true);
static FRIEND_ONLINE: AtomicBool = AtomicBool::new(true);

#[derive(Default)]
struct Memory {
    /// Mon statut choisi (`dnd` coupe tout).
    my_status: String,
    /// Identifiant public → pseudo, relevés dans la liste d'amis.
    names: HashMap<String, String>,
    /// Identifiant public → en ligne, d'après le dernier état connu.
    online: HashMap<String, bool>,
    /// Identifiant public → quand il est passé hors ligne.
    went_offline: HashMap<String, Instant>,
}

static MEMORY: Mutex<Option<Memory>> = Mutex::new(None);

fn with_memory<T>(f: impl FnOnce(&mut Memory) -> T) -> T {
    let mut guard = MEMORY.lock().unwrap_or_else(|p| p.into_inner());
    f(guard.get_or_insert_with(Memory::default))
}

/// Réglages de l'interface : notifications Windows, et « X est en ligne ».
#[tauri::command]
pub fn notifications_configure(os: bool, friend_online: bool) {
    OS_ENABLED.store(os, Ordering::Relaxed);
    FRIEND_ONLINE.store(friend_online, Ordering::Relaxed);
}

/// La liste d'amis vient d'être lue (`friends_list`) : pseudos, présences et
/// mon statut, pour savoir de qui parle un événement.
pub fn remember_list(list: &Value) {
    with_memory(|m| {
        if let Some(status) = list.pointer("/me/status").and_then(Value::as_str) {
            m.my_status = status.to_string();
        }
        for friend in list.get("friends").and_then(Value::as_array).into_iter().flatten() {
            let (Some(id), Some(name)) = (friend.get("id").and_then(Value::as_str), friend.get("username").and_then(Value::as_str)) else { continue };
            m.names.insert(id.to_string(), name.to_string());
            let state = friend.pointer("/presence/state").and_then(Value::as_str).unwrap_or("offline");
            m.online.entry(id.to_string()).or_insert(state != "offline");
        }
    });
}

/// Mon statut choisi a changé (depuis ce launcher).
pub fn remember_my_status(status: &str) {
    with_memory(|m| m.my_status = status.to_string());
}

/// Ce qu'un événement du flux mérite comme notification : `None` pour rien.
/// Tient à jour la mémoire au passage (présences, mon statut).
fn decide(event: &Value, now: Instant) -> Option<(Kind, String)> {
    let kind = event.get("type")?.as_str()?;
    with_memory(|m| match kind {
        "my_status" => {
            if let Some(status) = event.get("status").and_then(Value::as_str) {
                m.my_status = status.to_string();
            }
            None
        }
        "presence" => {
            let id = event.get("id")?.as_str()?.to_string();
            let online = event.pointer("/presence/state").and_then(Value::as_str).is_some_and(|s| s != "offline");
            let was = m.online.insert(id.clone(), online);
            if !online {
                if was == Some(true) {
                    m.went_offline.insert(id, now);
                }
                return None;
            }
            // Seule l'arrivée compte : un changement de statut d'un ami déjà là n'en est pas une.
            if was != Some(false) {
                return None;
            }
            if m.went_offline.get(&id).is_some_and(|at| now.duration_since(*at) < FLAP_WINDOW) {
                return None;
            }
            let name = m.names.get(&id)?.clone();
            Some((Kind::FriendOnline, name))
        }
        "request_received" => Some((Kind::Request, event.pointer("/request/username")?.as_str()?.to_string())),
        "friend_added" if event.get("by_you").and_then(Value::as_bool) != Some(true) => {
            let name = event.get("username")?.as_str()?.to_string();
            if let Some(id) = event.get("id").and_then(Value::as_str) {
                m.names.insert(id.to_string(), name.clone());
            }
            Some((Kind::FriendAdded, name))
        }
        "server_requested" => Some((Kind::ServerRequested, event.get("username")?.as_str()?.to_string())),
        "support_changed" => Some((Kind::Support, String::new())),
        _ => None,
    })
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Kind {
    FriendOnline,
    Request,
    FriendAdded,
    ServerRequested,
    Support,
}

/// Un événement du flux : une notification Windows s'il le faut.
pub fn on_event(app: &tauri::AppHandle, event: &Value) {
    let Some((kind, name)) = decide(event, Instant::now()) else { return };
    show(app, kind, &name);
}

/// Atelier de développement (Ctrl+Maj+M) : rejoue un événement d'ami comme
/// s'il venait du flux, après `delay_secs` — le temps de réduire le launcher
/// pour voir la notification Windows. L'interface le reçoit aussi
/// (`friends_event`), donc la notice dans la fenêtre part par le vrai chemin.
/// Sans effet dans la version publiée.
#[tauri::command]
pub async fn friends_demo_event(app: tauri::AppHandle, event: Value, name: String, delay_secs: u64) {
    if !cfg!(debug_assertions) {
        return;
    }
    tokio::time::sleep(Duration::from_secs(delay_secs.min(30))).await;
    let kind = match event.get("type").and_then(Value::as_str) {
        Some("presence") => Some(Kind::FriendOnline),
        Some("server_requested") => Some(Kind::ServerRequested),
        Some("request_received") => Some(Kind::Request),
        _ => None,
    };
    // Les amis de démonstration ne sont pas dans la mémoire du Rust (la liste
    // vient de l'interface) : on passe directement à la notification.
    if let Some(kind) = kind {
        show(&app, kind, &name);
    }
    let _ = tauri::Emitter::emit(&app, "friends_event", event);
}

/// Montre la notification Windows, si les réglages et le moment le permettent.
fn show(app: &tauri::AppHandle, kind: Kind, name: &str) {
    if !OS_ENABLED.load(Ordering::Relaxed) || with_memory(|m| m.my_status == "dnd") {
        return;
    }
    if kind == Kind::FriendOnline && !FRIEND_ONLINE.load(Ordering::Relaxed) {
        return;
    }
    if launcher_in_front(app) {
        return;
    }
    let (title, body) = text(kind, name, crate::app::locale::ui_language().as_deref().unwrap_or("en"));
    if let Err(e) = app.notification().builder().title(title).body(body).show() {
        tracing::debug!("notification Windows impossible : {e}");
    }
}

/// La fenêtre du launcher est ouverte, visible et a le focus.
fn launcher_in_front(app: &tauri::AppHandle) -> bool {
    let Some(window) = app.get_webview_window("main") else { return false };
    window.is_visible().unwrap_or(false) && !window.is_minimized().unwrap_or(false) && window.is_focused().unwrap_or(false)
}

/// Titre et texte, dans la langue de l'interface (anglais à défaut).
fn text(kind: Kind, name: &str, lang: &str) -> (String, String) {
    let pick = |fr: &str, en: &str, es: &str, de: &str, it: &str, pt: &str, pl: &str, ru: &str| -> String {
        match lang {
            "fr" => fr,
            "es" => es,
            "de" => de,
            "it" => it,
            "pt" => pt,
            "pl" => pl,
            "ru" => ru,
            _ => en,
        }
        .replace("{name}", name)
    };
    match kind {
        Kind::FriendOnline => (
            name.to_string(),
            pick("est en ligne", "is online", "está en línea", "ist online", "è online", "está online", "jest online", "в сети"),
        ),
        Kind::Request => (
            pick("Demande d'ami", "Friend request", "Solicitud de amistad", "Freundschaftsanfrage", "Richiesta di amicizia", "Pedido de amizade", "Zaproszenie do znajomych", "Заявка в друзья"),
            pick(
                "{name} veut t'ajouter en ami",
                "{name} wants to add you as a friend",
                "{name} quiere añadirte como amigo",
                "{name} möchte dich als Freund hinzufügen",
                "{name} vuole aggiungerti agli amici",
                "{name} quer te adicionar como amigo",
                "{name} chce dodać cię do znajomych",
                "{name} хочет добавить тебя в друзья",
            ),
        ),
        Kind::FriendAdded => (
            pick("Nouvel ami", "New friend", "Nuevo amigo", "Neuer Freund", "Nuovo amico", "Novo amigo", "Nowy znajomy", "Новый друг"),
            pick(
                "{name} est maintenant ton ami",
                "{name} is now your friend",
                "{name} ahora es tu amigo",
                "{name} ist jetzt dein Freund",
                "{name} ora è tuo amico",
                "{name} agora é seu amigo",
                "{name} jest teraz twoim znajomym",
                "{name} теперь твой друг",
            ),
        ),
        Kind::ServerRequested => (
            name.to_string(),
            pick(
                "aimerait voir sur quel serveur tu joues",
                "would like to see which server you're on",
                "quiere ver en qué servidor juegas",
                "möchte sehen, auf welchem Server du spielst",
                "vorrebbe vedere su quale server giochi",
                "gostaria de ver em qual servidor você joga",
                "chce zobaczyć, na jakim serwerze grasz",
                "хочет увидеть, на каком сервере ты играешь",
            ),
        ),
        Kind::Support => (
            pick("Support YuyuFrame", "YuyuFrame support", "Soporte de YuyuFrame", "YuyuFrame-Support", "Supporto YuyuFrame", "Suporte YuyuFrame", "Wsparcie YuyuFrame", "Поддержка YuyuFrame"),
            pick(
                "Du nouveau sur ta demande de support",
                "There's news on your support request",
                "Hay novedades en tu solicitud de soporte",
                "Neuigkeiten zu deiner Support-Anfrage",
                "Novità sulla tua richiesta di supporto",
                "Novidades na sua solicitação de suporte",
                "Nowości w twoim zgłoszeniu do wsparcia",
                "Есть новости по твоему обращению в поддержку",
            ),
        ),
    }
}

// ── La pastille de la barre des tâches ───────────────────────────────────

/// Le nombre de choses qui attendent (demandes d'ami, réponses du support…)
/// sur l'icône du launcher dans la barre des tâches, comme Discord. 0 : rien.
#[tauri::command]
pub fn set_unread_badge(app: tauri::AppHandle, count: u32) {
    let Some(window) = app.get_webview_window("main") else { return };
    #[cfg(windows)]
    {
        let icon = (count > 0).then(|| tauri::image::Image::new_owned(badge_pixels(count), BADGE, BADGE));
        if let Err(e) = window.set_overlay_icon(icon) {
            tracing::debug!("pastille de la barre des tâches impossible : {e}");
        }
    }
    #[cfg(not(windows))]
    {
        let _ = window.set_badge_count((count > 0).then_some(i64::from(count)));
    }
}

/// Côté de la pastille, en pixels (Windows la pose en bas à droite de l'icône).
const BADGE: u32 = 16;

/// Chiffres 3 × 5, une ligne par octet (bits de gauche à droite), puis « + ».
const GLYPHS: [[u8; 5]; 11] = [
    [0b111, 0b101, 0b101, 0b101, 0b111],
    [0b010, 0b110, 0b010, 0b010, 0b111],
    [0b111, 0b001, 0b111, 0b100, 0b111],
    [0b111, 0b001, 0b111, 0b001, 0b111],
    [0b101, 0b101, 0b111, 0b001, 0b001],
    [0b111, 0b100, 0b111, 0b001, 0b111],
    [0b111, 0b100, 0b111, 0b101, 0b111],
    [0b111, 0b001, 0b010, 0b010, 0b010],
    [0b111, 0b101, 0b111, 0b101, 0b111],
    [0b111, 0b101, 0b111, 0b001, 0b111],
    [0b000, 0b010, 0b111, 0b010, 0b000],
];

/// Un rond rouge (celui des notifications) avec le nombre en blanc : 1 à 9,
/// puis « + ». RGBA, `BADGE` × `BADGE`.
fn badge_pixels(count: u32) -> Vec<u8> {
    let size = BADGE as i32;
    let mut px = vec![0u8; (BADGE * BADGE * 4) as usize];
    let center = (size as f32 - 1.0) / 2.0;
    let radius = size as f32 / 2.0;
    for y in 0..size {
        for x in 0..size {
            let d = ((x as f32 - center).powi(2) + (y as f32 - center).powi(2)).sqrt();
            // Bord adouci sur un pixel.
            let alpha = (radius - d).clamp(0.0, 1.0);
            let i = ((y * size + x) * 4) as usize;
            px[i..i + 4].copy_from_slice(&[0xed, 0x42, 0x45, (alpha * 255.0) as u8]);
        }
    }
    let glyph = GLYPHS[if count > 9 { 10 } else { count as usize }];
    // 3 × 5 agrandi deux fois : 6 × 10, centré.
    let (ox, oy) = ((size - 6) / 2, (size - 10) / 2);
    for (row, bits) in glyph.iter().enumerate() {
        for col in 0..3 {
            if bits & (0b100 >> col) == 0 {
                continue;
            }
            for dy in 0..2 {
                for dx in 0..2 {
                    let (x, y) = (ox + col * 2 + dx, oy + row as i32 * 2 + dy);
                    let i = ((y * size + x) * 4) as usize;
                    px[i..i + 4].copy_from_slice(&[0xff, 0xff, 0xff, 0xff]);
                }
            }
        }
    }
    px
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    /// La mémoire est globale : les tests qui la touchent passent un par un.
    static SERIAL: Mutex<()> = Mutex::new(());

    fn reset(list: Value) {
        *MEMORY.lock().unwrap_or_else(|p| p.into_inner()) = None;
        remember_list(&list);
    }

    #[test]
    fn un_ami_qui_revient_vite_ne_sonne_pas_deux_fois() {
        let _serial = SERIAL.lock().unwrap_or_else(|p| p.into_inner());
        reset(json!({ "me": { "status": "online" }, "friends": [{ "id": "a", "username": "Dream", "presence": { "state": "offline" } }] }));
        let t0 = Instant::now();
        let online = json!({ "type": "presence", "id": "a", "presence": { "state": "online" } });
        let offline = json!({ "type": "presence", "id": "a", "presence": { "state": "offline" } });
        assert_eq!(decide(&online, t0), Some((Kind::FriendOnline, "Dream".into())));
        // Un changement de statut d'un ami déjà là : rien.
        assert_eq!(decide(&json!({ "type": "presence", "id": "a", "presence": { "state": "away" } }), t0), None);
        assert_eq!(decide(&offline, t0 + Duration::from_secs(60)), None);
        // Revenu 2 minutes plus tard (coupure réseau) : rien.
        assert_eq!(decide(&online, t0 + Duration::from_secs(180)), None);
        assert_eq!(decide(&offline, t0 + Duration::from_secs(200)), None);
        // Revenu plus de 10 minutes après : de nouveau.
        assert_eq!(decide(&online, t0 + Duration::from_secs(200) + FLAP_WINDOW), Some((Kind::FriendOnline, "Dream".into())));
    }

    #[test]
    fn ce_que_j_ai_fait_moi_meme_ne_me_previent_pas() {
        let _serial = SERIAL.lock().unwrap_or_else(|p| p.into_inner());
        reset(json!({ "me": { "status": "online" }, "friends": [] }));
        assert_eq!(decide(&json!({ "type": "friend_added", "id": "b", "username": "Grian", "by_you": true }), Instant::now()), None);
        assert_eq!(decide(&json!({ "type": "friend_added", "id": "b", "username": "Grian", "by_you": false }), Instant::now()), Some((Kind::FriendAdded, "Grian".into())));
        // Mon statut suit les événements.
        decide(&json!({ "type": "my_status", "status": "dnd" }), Instant::now());
        assert!(with_memory(|m| m.my_status == "dnd"));
    }

    #[test]
    fn la_pastille_est_un_rond_avec_le_nombre() {
        let px = badge_pixels(3);
        assert_eq!(px.len(), (BADGE * BADGE * 4) as usize);
        // Le coin est transparent, le centre opaque.
        assert_eq!(px[3], 0);
        let center = ((8 * BADGE + 8) * 4 + 3) as usize;
        assert_eq!(px[center], 255);
        assert_ne!(badge_pixels(3), badge_pixels(12));
        assert_eq!(badge_pixels(12), badge_pixels(40), "au-delà de 9, « + »");
    }
}
