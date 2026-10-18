//! Le partage des duels pour la calibration de la note (docs/scoring/partage.md).
//!
//! Seulement si le joueur l'a activé (réglage « Partager mes duels » du module
//! Score PvP, éteint par défaut) et seulement avec un compte YuyuFrame
//! connecté. À la fermeture de chaque duel, le noyau prépare un paquet — TOUT
//! le calcul est fait ici, le serveur ne fait que ranger :
//!
//!   - le journal du duel, ANONYMISÉ puis compressé : l'adresse du serveur
//!     devient une empreinte (on compte les serveurs, on ne les nomme pas), le
//!     pseudo et l'UUID de chaque joueur deviennent un pseudonyme propre à
//!     cette installation ;
//!   - les cinq tableaux du relevé de calibration pour ce duel (A), calculés
//!     sur ce journal anonymisé, et son résumé (versions, mode, résultat, note).
//!
//! Le paquet attend dans `yuyuframe/outbox/duels/` de l'instance. Un fil à
//! lui (`yf-duel-share`) l'envoie à LauncherAPI (`POST /v1/pvp/duels`) avec le
//! jeton du compte, et le retire une fois reçu. Désactiver le partage vide la
//! file : rien de ce qui attendait ne part.

use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use ring::digest::{Context, SHA256};

/// Format du paquet : à incrémenter si sa forme change, le back-office trie dessus.
const PACKET_FORMAT: u32 = 1;
/// Paquets en attente au plus, par instance : au-delà, les plus anciens partent à la corbeille.
const OUTBOX_MAX: usize = 300;
/// Un paquet plus gros n'est pas gardé (journal anormal) — la route le refuserait.
const PACKET_MAX_BYTES: usize = 4 * 1024 * 1024;
/// Le fil regarde la file à cette cadence.
const SCAN_EVERY: Duration = Duration::from_secs(20);
/// Après un refus de quota ou une panne, il attend plus longtemps.
const BACKOFF: Duration = Duration::from_secs(10 * 60);
/// Sépare l'empreinte d'un serveur de tout autre SHA-256. La même pour tous
/// les joueurs : c'est ce qui permet de compter les serveurs distincts.
const SERVER_DOMAIN: &[u8] = b"yuyuframe-duel-server-v1";

static ENABLED: AtomicBool = AtomicBool::new(false);
static AGENT: Mutex<String> = Mutex::new(String::new());
static OUTBOXES: Mutex<Vec<PathBuf>> = Mutex::new(Vec::new());

/// Le réglage du joueur, et la version de l'agent à joindre aux paquets.
/// Le passage à « désactivé » vide les files connues.
pub fn set_enabled(enabled: bool, agent: &str) {
    *AGENT.lock().unwrap_or_else(|p| p.into_inner()) = agent.chars().take(64).collect();
    let was = ENABLED.swap(enabled, Ordering::Relaxed);
    if was && !enabled {
        for dir in OUTBOXES.lock().unwrap_or_else(|p| p.into_inner()).iter() {
            purge(dir);
        }
    }
    if enabled {
        start_worker();
    }
}

pub fn enabled() -> bool {
    ENABLED.load(Ordering::Relaxed)
}

fn outbox_of(instance: &Path) -> PathBuf {
    instance.join("yuyuframe").join("outbox").join("duels")
}

/// Entrée dans un monde de cette instance : sa file sera surveillée, ce qui
/// envoie aussi ce qu'une partie précédente n'avait pas eu le temps d'envoyer.
pub fn watch(instance: &Path) {
    let dir = outbox_of(instance);
    let mut list = OUTBOXES.lock().unwrap_or_else(|p| p.into_inner());
    if !list.contains(&dir) {
        list.push(dir.clone());
    }
    drop(list);
    if !enabled() {
        // Partage désactivé : ce qui reste d'une session où il l'était ne part pas.
        purge(&dir);
    }
}

fn purge(dir: &Path) {
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        if let Err(e) = std::fs::remove_file(entry.path()) {
            eprintln!("[YuyuFrame] partage des duels : {} non supprimé : {e}", entry.path().display());
        }
    }
}

/// Un duel vient de se fermer : `journal_path` est son fichier dans
/// `yuyuframe/matches/`, `text` son journal en clair.
pub fn on_duel_closed(journal_path: &Path, text: &str) {
    if !enabled() {
        return;
    }
    let Some(instance) = journal_path.parent().and_then(Path::parent).and_then(Path::parent) else { return };
    let key = match install_key(instance) {
        Ok(key) => key,
        Err(e) => {
            eprintln!("[YuyuFrame] partage des duels : clé de pseudonymes indisponible : {e}");
            return;
        }
    };
    let Some(packet) = packet(text, &key) else { return };
    if packet.1.len() > PACKET_MAX_BYTES {
        eprintln!("[YuyuFrame] partage des duels : paquet de {} octets, trop gros, non gardé", packet.1.len());
        return;
    }
    let dir = outbox_of(instance);
    let written = std::fs::create_dir_all(&dir).and_then(|_| std::fs::write(dir.join(format!("{}.json", packet.0)), &packet.1));
    if let Err(e) = written {
        eprintln!("[YuyuFrame] partage des duels : paquet non écrit : {e}");
        return;
    }
    trim(&dir);
    watch(instance);
    start_worker();
}

/// Garde les `OUTBOX_MAX` paquets les plus récents.
fn trim(dir: &Path) {
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    let mut files: Vec<_> = entries.flatten().filter_map(|e| Some((e.metadata().ok()?.modified().ok()?, e.path()))).collect();
    if files.len() <= OUTBOX_MAX {
        return;
    }
    files.sort();
    for (_, path) in files.iter().take(files.len() - OUTBOX_MAX) {
        if let Err(e) = std::fs::remove_file(path) {
            eprintln!("[YuyuFrame] partage des duels : {} non supprimé : {e}", path.display());
        }
    }
}

// ── Le paquet ────────────────────────────────────────────────────────────

/// Le nom du paquet (empreinte du journal anonymisé, qui sert aussi de nom
/// au duel dans les tableaux) et son JSON. `None` si le texte n'est pas un journal.
fn packet(text: &str, key: &[u8; 32]) -> Option<(String, String)> {
    let anonymous = anonymize(text, key);
    let id = hex(&sha256(&[anonymous.as_bytes()]))[..16].to_string();
    let (tables, summary) = super::score::calibration_tables(&id, &anonymous)?;
    let header = anonymous.lines().next().and_then(|l| serde_json::from_str::<serde_json::Value>(l).ok()).unwrap_or_default();
    let compressed = zstd::bulk::compress(anonymous.as_bytes(), 9).ok()?;
    let agent = AGENT.lock().unwrap_or_else(|p| p.into_inner()).clone();
    let json = serde_json::json!({
        "format": PACKET_FORMAT,
        "duel": id,
        "time": header["time"],
        "telemetry": header["v"],
        "extractor": super::score::EXTRACTOR_VERSION,
        "score_version": super::score::SCORE_VERSION,
        "mc": header["mc"],
        "agent": agent,
        "server": header["server"],
        "summary": summary,
        "tables": tables,
        "journal": base64(&compressed),
    });
    Some((id, json.to_string()))
}

/// Le journal sans ce qui désigne quelqu'un ou quelque part : l'adresse du
/// serveur devient son empreinte, l'UUID et le pseudo de chaque joueur un
/// pseudonyme stable pour cette installation (le même adversaire d'un duel à
/// l'autre, sans jamais pouvoir remonter à lui). Le reste est intact.
fn anonymize(text: &str, key: &[u8; 32]) -> String {
    let mut out = String::with_capacity(text.len());
    for line in text.lines() {
        let Ok(mut value) = serde_json::from_str::<serde_json::Value>(line) else { continue };
        match value["k"].as_str() {
            Some("h") => {
                let server = value["server"].as_str().unwrap_or("").trim().to_ascii_lowercase();
                value["server"] = serde_json::Value::String(server_print(&server));
            }
            Some("p") => {
                let uuid = value["uuid"].as_str().unwrap_or("").replace('-', "").to_ascii_lowercase();
                let pseudonym = hex(&sha256(&[key, uuid.as_bytes()]));
                value["uuid"] = serde_json::Value::String(pseudonym[..32].to_string());
                value["name"] = serde_json::Value::String(format!("joueur-{}", &pseudonym[32..38]));
            }
            _ => {}
        }
        out.push_str(&value.to_string());
        out.push('\n');
    }
    out
}

/// Empreinte d'une adresse de serveur, la même chez tous les joueurs ; vide en solo.
fn server_print(server: &str) -> String {
    if server.is_empty() {
        return String::new();
    }
    format!("srv-{}", &hex(&sha256(&[SERVER_DOMAIN, server.as_bytes()]))[..16])
}

/// La clé des pseudonymes de cette installation : tirée au hasard une fois,
/// gardée dans l'instance. Sans elle, l'UUID d'un joueur se retrouverait en
/// essayant tous les UUID connus ; avec elle, seul ce poste sait refaire le lien.
fn install_key(instance: &Path) -> std::io::Result<[u8; 32]> {
    let path = instance.join("yuyuframe").join("duel-share.key");
    if let Ok(bytes) = std::fs::read(&path) {
        if bytes.len() == 32 {
            let mut key = [0u8; 32];
            key.copy_from_slice(&bytes);
            return Ok(key);
        }
    }
    let mut key = [0u8; 32];
    ring::rand::SecureRandom::fill(&ring::rand::SystemRandom::new(), &mut key)
        .map_err(|_| std::io::Error::other("générateur aléatoire indisponible"))?;
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(&path, key)?;
    Ok(key)
}

fn sha256(parts: &[&[u8]]) -> [u8; 32] {
    let mut ctx = Context::new(&SHA256);
    for part in parts {
        ctx.update(part);
    }
    let mut out = [0u8; 32];
    out.copy_from_slice(ctx.finish().as_ref());
    out
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Base64 standard, avec remplissage — le journal compressé voyage dans du JSON.
fn base64(bytes: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let n = (chunk[0] as u32) << 16 | (*chunk.get(1).unwrap_or(&0) as u32) << 8 | *chunk.get(2).unwrap_or(&0) as u32;
        out.push(TABLE[(n >> 18) as usize & 63] as char);
        out.push(TABLE[(n >> 12) as usize & 63] as char);
        out.push(if chunk.len() > 1 { TABLE[(n >> 6) as usize & 63] as char } else { '=' });
        out.push(if chunk.len() > 2 { TABLE[n as usize & 63] as char } else { '=' });
    }
    out
}

// ── L'envoi ──────────────────────────────────────────────────────────────

#[derive(Debug, PartialEq, Eq)]
enum Sent {
    /// Reçu (ou déjà reçu) : le paquet peut partir à la corbeille.
    Done,
    /// Refusé pour de bon (forme, taille) : inutile de le renvoyer.
    Rejected,
    /// Jeton refusé : le launcher en enverra un autre.
    Refused,
    /// Quota, panne, réseau : plus tard.
    Later,
    /// La route n'existe pas sur cette API : rien à faire pendant cette partie.
    Unsupported,
}

fn start_worker() {
    static STARTED: OnceLock<()> = OnceLock::new();
    STARTED.get_or_init(|| {
        if let Err(e) = std::thread::Builder::new().name("yf-duel-share".into()).spawn(run) {
            eprintln!("[YuyuFrame] partage des duels : fil non démarré : {e}");
        }
    });
}

fn run() {
    let Some(base) = crate::presence::client::api_base() else {
        eprintln!("[YuyuFrame] partage des duels : adresse de l'API refusée, rien ne sera envoyé");
        return;
    };
    let url = format!("{base}/v1/pvp/duels");
    let agent = ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(5)).timeout(Duration::from_secs(30)).build();
    let tokens = crate::presence::token::store();
    loop {
        std::thread::sleep(SCAN_EVERY);
        if !enabled() {
            continue;
        }
        let Some(token) = tokens.get() else { continue };
        let dirs: Vec<PathBuf> = OUTBOXES.lock().unwrap_or_else(|p| p.into_inner()).clone();
        let mut pause = false;
        'dirs: for dir in dirs {
            let Ok(entries) = std::fs::read_dir(&dir) else { continue };
            let mut seen = HashSet::new();
            for entry in entries.flatten() {
                let path = entry.path();
                if !seen.insert(path.clone()) || path.extension().is_none_or(|e| e != "json") {
                    continue;
                }
                let Ok(body) = std::fs::read_to_string(&path) else { continue };
                // Désactivé entre-temps : on s'arrête là, la file a été vidée.
                if !enabled() {
                    break 'dirs;
                }
                match send(&agent, &url, &token, &body) {
                    Sent::Done | Sent::Rejected => {
                        if let Err(e) = std::fs::remove_file(&path) {
                            eprintln!("[YuyuFrame] partage des duels : {} non supprimé : {e}", path.display());
                        }
                    }
                    Sent::Refused => break 'dirs,
                    Sent::Later => {
                        pause = true;
                        break 'dirs;
                    }
                    Sent::Unsupported => {
                        eprintln!("[YuyuFrame] partage des duels : l'API ne connaît pas encore cette route");
                        return;
                    }
                }
            }
        }
        if pause {
            std::thread::sleep(BACKOFF);
        }
    }
}

fn send(agent: &ureq::Agent, url: &str, token: &str, body: &str) -> Sent {
    let result = agent
        .post(url)
        .set("Authorization", &format!("Bearer {token}"))
        .set("Content-Type", "application/json")
        .set("User-Agent", "YuyuFrame-LauncherAgent/0.1")
        .send_string(body);
    match result {
        Ok(_) => Sent::Done,
        // Déjà reçu : un même duel renvoyé après une coupure.
        Err(ureq::Error::Status(409, _)) => Sent::Done,
        Err(ureq::Error::Status(400 | 413 | 422, response)) => {
            eprintln!("[YuyuFrame] partage des duels : paquet refusé ({}) : {}", response.status(), response.into_string().unwrap_or_default());
            Sent::Rejected
        }
        Err(ureq::Error::Status(401 | 403, _)) => Sent::Refused,
        Err(ureq::Error::Status(404 | 405, _)) => Sent::Unsupported,
        Err(e) => {
            eprintln!("[YuyuFrame] partage des duels : envoi reporté : {e}");
            Sent::Later
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEY: [u8; 32] = [7u8; 32];

    #[test]
    fn le_journal_partage_ne_nomme_ni_les_joueurs_ni_le_serveur() {
        let text = "{\"k\":\"h\",\"v\":3,\"server\":\"Minemen.Club:25565\",\"mc\":\"26.3\",\"time\":1000,\"me\":1,\"t\":0}\n\
                    {\"k\":\"p\",\"t\":0,\"id\":7,\"uuid\":\"0123456789abcdef0123456789abcdef\",\"name\":\"Rival\"}\n\
                    {\"k\":\"d\",\"t\":3,\"entity\":1,\"cause\":7,\"direct\":7,\"type\":\"mace_smash\"}\n";
        let out = anonymize(text, &KEY);
        assert!(!out.contains("Rival"));
        assert!(!out.contains("0123456789abcdef"));
        assert!(!out.to_lowercase().contains("minemen"));
        assert!(out.contains("\"server\":\"srv-"));
        assert!(out.contains("\"name\":\"joueur-"));
        // Le reste du journal est intact.
        assert!(out.contains("mace_smash"));
        // Le même joueur garde le même pseudonyme, et le même serveur la même
        // empreinte quelle que soit la casse : c'est ce qui permet de les compter.
        assert_eq!(out, anonymize(text, &KEY));
        assert_eq!(server_print("minemen.club:25565"), server_print(&"Minemen.Club:25565".to_ascii_lowercase()));
        // Une autre installation donne d'autres pseudonymes.
        assert_ne!(out, anonymize(text, &[9u8; 32]));
    }

    #[test]
    fn un_vrai_duel_donne_un_paquet_complet() {
        let text = include_str!("score/fixtures/duel_masse_defaite.ndjson");
        let (id, json) = packet(text, &KEY).expect("un vrai journal");
        assert_eq!(id.len(), 16);
        let value: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(value["format"], PACKET_FORMAT);
        assert_eq!(value["duel"], id.as_str());
        for table in ["duels", "parties", "opportunites", "smashs", "techniques"] {
            assert!(value["tables"][table].as_str().unwrap().lines().count() >= 1, "{table}");
        }
        assert!(value["tables"]["duels"].as_str().unwrap().contains(&id), "le duel porte son nom de paquet");
        assert!(!value["journal"].as_str().unwrap().is_empty());
    }

    #[test]
    fn base64_standard() {
        assert_eq!(base64(b""), "");
        assert_eq!(base64(b"f"), "Zg==");
        assert_eq!(base64(b"fo"), "Zm8=");
        assert_eq!(base64(b"foo"), "Zm9v");
        assert_eq!(base64(b"foobar"), "Zm9vYmFy");
    }
}
