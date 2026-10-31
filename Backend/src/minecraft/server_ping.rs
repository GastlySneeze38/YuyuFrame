// Server List Ping (protocole moderne 1.7+, voir wiki.vg/Server_List_Ping) —
// connexion TCP brute, aucune lib tierce : handshake (next_state=status) +
// status request → réponse JSON (MOTD, joueurs, favicon), puis un
// aller-retour ping/pong pour mesurer la latence affichée à l'utilisateur
// (mêmes barres de signal que le client vanilla).

use serde::Serialize;
use serde_json::Value;
use std::io::{Cursor, Read};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::{timeout, Instant};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const IO_TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Serialize, Clone)]
pub struct ServerPingInfo {
    pub motd: String,
    pub players_online: i64,
    pub players_max: i64,
    pub version_name: String,
    /// Data URI `data:image/png;base64,...` (64×64), tel que renvoyé par le
    /// serveur — directement utilisable comme `src` d'un `<img>` côté frontend.
    pub favicon: Option<String>,
    pub latency_ms: u64,
    /// Le MOTD avec ses couleurs et ses styles, morceau par morceau, comme le
    /// jeu l'affiche dans sa liste de serveurs (`motd` reste le texte brut).
    pub motd_spans: Vec<MotdSpan>,
}

/// Un morceau du MOTD d'un même style. `color` est en `#rrggbb`, `None` =
/// la couleur par défaut du jeu (gris clair).
#[derive(Serialize, Clone, Debug, PartialEq, Default)]
pub struct MotdSpan {
    pub text: String,
    pub color: Option<String>,
    pub bold: bool,
    pub italic: bool,
    pub underlined: bool,
    pub strikethrough: bool,
}

/// Les 16 couleurs du jeu, dans l'ordre des codes `§0`…`§f`, avec leur nom
/// dans les composants JSON.
const COLORS: [(&str, &str); 16] = [
    ("black", "#000000"),
    ("dark_blue", "#0000AA"),
    ("dark_green", "#00AA00"),
    ("dark_aqua", "#00AAAA"),
    ("dark_red", "#AA0000"),
    ("dark_purple", "#AA00AA"),
    ("gold", "#FFAA00"),
    ("gray", "#AAAAAA"),
    ("dark_gray", "#555555"),
    ("blue", "#5555FF"),
    ("green", "#55FF55"),
    ("aqua", "#55FFFF"),
    ("red", "#FF5555"),
    ("light_purple", "#FF55FF"),
    ("yellow", "#FFFF55"),
    ("white", "#FFFFFF"),
];

/// Au-delà, le reste du MOTD n'est plus affiché : une liste de serveurs n'en
/// montre que deux lignes.
const MOTD_MAX_CHARS: usize = 400;

fn named_color(name: &str) -> Option<String> {
    if let Some(hex) = name.strip_prefix('#') {
        return (hex.len() == 6 && hex.chars().all(|c| c.is_ascii_hexdigit())).then(|| format!("#{}", hex.to_ascii_uppercase()));
    }
    COLORS.iter().find(|(n, _)| *n == name).map(|(_, hex)| hex.to_string())
}

fn push_span(out: &mut Vec<MotdSpan>, text: &mut String, style: &MotdSpan, budget: &mut usize) {
    if text.is_empty() || *budget == 0 {
        text.clear();
        return;
    }
    let kept: String = text.chars().take(*budget).collect();
    *budget -= kept.chars().count();
    text.clear();
    // Un morceau du même style que le précédent le prolonge.
    if let Some(last) = out.last_mut() {
        if last.color == style.color
            && last.bold == style.bold
            && last.italic == style.italic
            && last.underlined == style.underlined
            && last.strikethrough == style.strikethrough
        {
            last.text.push_str(&kept);
            return;
        }
    }
    out.push(MotdSpan { text: kept, ..style.clone() });
}

/// Un texte qui peut contenir des codes `§` : une couleur remet les styles
/// à zéro, `§r` revient au style du composant parent.
fn legacy_spans(text: &str, base: &MotdSpan, out: &mut Vec<MotdSpan>, budget: &mut usize) {
    let mut style = base.clone();
    let mut buf = String::new();
    let mut chars = text.chars();
    while let Some(c) = chars.next() {
        if c != '§' {
            buf.push(c);
            continue;
        }
        let Some(code) = chars.next() else { break };
        push_span(out, &mut buf, &style, budget);
        let code = code.to_ascii_lowercase();
        if let Some(i) = code.to_digit(16) {
            style = MotdSpan { color: Some(COLORS[i as usize].1.to_string()), ..Default::default() };
        } else {
            match code {
                'l' => style.bold = true,
                'o' => style.italic = true,
                'n' => style.underlined = true,
                'm' => style.strikethrough = true,
                'r' => style = base.clone(),
                _ => {}
            }
        }
    }
    push_span(out, &mut buf, &style, budget);
}

fn walk_motd(value: &Value, parent: &MotdSpan, out: &mut Vec<MotdSpan>, budget: &mut usize) {
    match value {
        Value::String(s) => legacy_spans(s, parent, out, budget),
        Value::Array(items) => {
            for item in items {
                walk_motd(item, parent, out, budget);
            }
        }
        Value::Object(_) => {
            let mut style = parent.clone();
            style.text.clear();
            if let Some(c) = value.get("color").and_then(Value::as_str).and_then(named_color) {
                style.color = Some(c);
            }
            let flag = |key: &str, current: bool| value.get(key).and_then(Value::as_bool).unwrap_or(current);
            style.bold = flag("bold", style.bold);
            style.italic = flag("italic", style.italic);
            style.underlined = flag("underlined", style.underlined);
            style.strikethrough = flag("strikethrough", style.strikethrough);
            if let Some(t) = value.get("text").and_then(Value::as_str) {
                legacy_spans(t, &style, out, budget);
            }
            if let Some(extra) = value.get("extra") {
                walk_motd(extra, &style, out, budget);
            }
        }
        _ => {}
    }
}

fn motd_spans(value: &Value) -> Vec<MotdSpan> {
    let mut out = Vec::new();
    let mut budget = MOTD_MAX_CHARS;
    walk_motd(value, &MotdSpan::default(), &mut out, &mut budget);
    out
}

#[cfg(test)]
mod motd_tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn un_port_ecrit_coupe_la_recherche_srv() {
        assert_eq!(parse_address("play.example.com"), ("play.example.com".into(), 25565, false));
        assert_eq!(parse_address("play.example.com:25570"), ("play.example.com".into(), 25570, true));
        assert_eq!(parse_address("play.example.com:"), ("play.example.com:".into(), 25565, false));
    }

    /// Réseau : `cargo test --lib ping_suit_le_srv -- --ignored --nocapture`.
    #[tokio::test]
    #[ignore]
    async fn ping_suit_le_srv() {
        assert_eq!(resolve_srv("unstablehmp.net").await.map(|(_, p)| p), Some(25659));
        let info = ping_server("unstablehmp.net").await.expect("le serveur répond par son SRV");
        println!("{} joueurs, {}", info.players_online, info.version_name);
    }

    #[test]
    fn les_codes_de_couleur_se_lisent() {
        let spans = motd_spans(&json!("§6§lMinemen §rClub §7| EU"));
        assert_eq!(spans.len(), 3);
        assert_eq!((spans[0].text.as_str(), spans[0].color.as_deref(), spans[0].bold), ("Minemen ", Some("#FFAA00"), true));
        assert_eq!((spans[1].text.as_str(), spans[1].color.as_deref(), spans[1].bold), ("Club ", None, false));
        assert_eq!((spans[2].text.as_str(), spans[2].color.as_deref()), ("| EU", Some("#AAAAAA")));
    }

    #[test]
    fn les_composants_heritent_du_style_parent() {
        let spans = motd_spans(&json!({
            "text": "", "color": "aqua",
            "extra": [ { "text": "Hypixel", "bold": true }, " ", { "text": "Network", "color": "#ff00AA" } ]
        }));
        assert_eq!(spans[0], MotdSpan { text: "Hypixel".into(), color: Some("#55FFFF".into()), bold: true, ..Default::default() });
        assert_eq!(spans[1].color.as_deref(), Some("#55FFFF"));
        assert_eq!(spans[2].color.as_deref(), Some("#FF00AA"));
        assert_eq!(named_color("#12"), None);
    }

    #[test]
    fn un_motd_demesure_est_coupe() {
        let spans = motd_spans(&json!("x".repeat(5000)));
        assert_eq!(spans.iter().map(|s| s.text.chars().count()).sum::<usize>(), MOTD_MAX_CHARS);
    }
}

fn write_varint(buf: &mut Vec<u8>, value: i32) {
    let mut value = value as u32;
    loop {
        let mut byte = (value & 0x7F) as u8;
        value >>= 7;
        if value != 0 {
            byte |= 0x80;
        }
        buf.push(byte);
        if value == 0 {
            break;
        }
    }
}

fn write_string(buf: &mut Vec<u8>, s: &str) {
    write_varint(buf, s.len() as i32);
    buf.extend_from_slice(s.as_bytes());
}

async fn read_varint_async(stream: &mut TcpStream) -> std::io::Result<i32> {
    let mut result: i32 = 0;
    let mut shift = 0u32;
    loop {
        let mut byte = [0u8; 1];
        stream.read_exact(&mut byte).await?;
        result |= ((byte[0] & 0x7F) as i32) << shift;
        if byte[0] & 0x80 == 0 {
            break;
        }
        shift += 7;
        if shift >= 32 {
            return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "varint trop long"));
        }
    }
    Ok(result)
}

fn read_varint_sync(cursor: &mut Cursor<&[u8]>) -> std::io::Result<i32> {
    let mut result: i32 = 0;
    let mut shift = 0u32;
    loop {
        let mut byte = [0u8; 1];
        Read::read_exact(cursor, &mut byte)?;
        result |= ((byte[0] & 0x7F) as i32) << shift;
        if byte[0] & 0x80 == 0 {
            break;
        }
        shift += 7;
        if shift >= 32 {
            return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "varint trop long"));
        }
    }
    Ok(result)
}

async fn write_packet(stream: &mut TcpStream, body: &[u8]) -> std::io::Result<()> {
    let mut framed = Vec::with_capacity(body.len() + 5);
    write_varint(&mut framed, body.len() as i32);
    framed.extend_from_slice(body);
    stream.write_all(&framed).await
}

async fn read_packet(stream: &mut TcpStream) -> std::io::Result<Vec<u8>> {
    let len = read_varint_async(stream).await? as usize;
    let mut buf = vec![0u8; len];
    stream.read_exact(&mut buf).await?;
    Ok(buf)
}

fn strip_color_codes(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c == '§' {
            chars.next();
            continue;
        }
        out.push(c);
    }
    out
}

/// `description` (MOTD) peut être une simple chaîne, ou un chat component
/// riche `{ text, extra: [...], color, bold, ... }` — on aplatit récursivement
/// en ne gardant que le texte (le style visuel n'a pas sa place dans une
/// carte de 9px de haut).
fn flatten_motd(value: &Value) -> String {
    match value {
        Value::String(s) => strip_color_codes(s),
        Value::Object(_) => {
            let mut out = String::new();
            if let Some(t) = value.get("text").and_then(|v| v.as_str()) {
                out.push_str(t);
            }
            if let Some(extra) = value.get("extra").and_then(|v| v.as_array()) {
                for e in extra {
                    out.push_str(&flatten_motd(e));
                }
            }
            strip_color_codes(&out)
        }
        _ => String::new(),
    }
}

/// `(hôte, port, port écrit dans l'adresse ?)`.
fn parse_address(address: &str) -> (String, u16, bool) {
    match address.rsplit_once(':') {
        Some((h, p)) if !p.is_empty() && p.chars().all(|c| c.is_ascii_digit()) => {
            (h.to_string(), p.parse().unwrap_or(25565), true)
        }
        _ => (address.to_string(), 25565, false),
    }
}

/// Délai de la requête DNS SRV : au-delà, on tente le port par défaut.
const SRV_TIMEOUT: Duration = Duration::from_secs(3);

/// L'enregistrement SRV `_minecraft._tcp.<hôte>`, que le jeu consulte avant
/// de se connecter : beaucoup de serveurs y renvoient vers une autre machine
/// ou un autre port (`unstablehmp.net` → port 25659). Sans lui, le ping
/// visait le port 25565 et annonçait hors ligne un serveur bien en ligne.
/// `None` : pas d'enregistrement, ou DNS muet — on garde l'adresse telle quelle.
async fn resolve_srv(host: &str) -> Option<(String, u16)> {
    let name = format!("_minecraft._tcp.{host}");
    let found = timeout(SRV_TIMEOUT, tokio::task::spawn_blocking(move || srv_records(&name)))
        .await
        .ok()?
        .ok()?;
    // Comme le jeu : la priorité la plus basse gagne.
    let (target, port, _) = found.into_iter().min_by_key(|(_, _, priority)| *priority)?;
    let target = target.trim_end_matches('.').to_string();
    (!target.is_empty() && port != 0).then_some((target, port))
}

/// Sous Windows, la requête passe par **le service DNS du système**
/// (`DnsQuery_W`) et non par un client DNS à nous : celui-ci ouvrait un port
/// UDP, et Windows prenait le launcher pour un programme qui attend des
/// connexions — d'où la fenêtre « Autoriser l'accès au réseau » du pare-feu.
/// Ici, aucun port n'est ouvert par le launcher. Rend `(cible, port, priorité)`.
#[cfg(target_os = "windows")]
fn srv_records(name: &str) -> Vec<(String, u16, u16)> {
    use std::ffi::c_void;

    #[repr(C)]
    struct DnsSrvData {
        target: *const u16,
        priority: u16,
        weight: u16,
        port: u16,
        pad: u16,
    }
    /// `DNS_RECORDW`, réduit à ce qu'on lit : l'en-tête puis la variante SRV
    /// de l'union `Data` (même alignement que le reste de l'union).
    #[repr(C)]
    struct DnsRecordW {
        next: *const DnsRecordW,
        name: *const u16,
        kind: u16,
        data_length: u16,
        flags: u32,
        ttl: u32,
        reserved: u32,
        srv: DnsSrvData,
    }

    #[link(name = "dnsapi")]
    extern "system" {
        fn DnsQuery_W(name: *const u16, kind: u16, options: u32, extra: *mut c_void, results: *mut *mut DnsRecordW, reserved: *mut c_void) -> i32;
        fn DnsFree(data: *mut c_void, free_type: i32);
    }
    const DNS_TYPE_SRV: u16 = 33;
    const DNS_QUERY_STANDARD: u32 = 0;
    const DNS_FREE_RECORD_LIST: i32 = 1;

    unsafe fn wide_to_string(p: *const u16) -> String {
        if p.is_null() {
            return String::new();
        }
        let mut len = 0;
        while *p.add(len) != 0 && len < 1024 {
            len += 1;
        }
        String::from_utf16_lossy(std::slice::from_raw_parts(p, len))
    }

    let wide: Vec<u16> = name.encode_utf16().chain(std::iter::once(0)).collect();
    let mut results: *mut DnsRecordW = std::ptr::null_mut();
    let status = unsafe { DnsQuery_W(wide.as_ptr(), DNS_TYPE_SRV, DNS_QUERY_STANDARD, std::ptr::null_mut(), &mut results, std::ptr::null_mut()) };
    if status != 0 || results.is_null() {
        return Vec::new();
    }
    let mut out = Vec::new();
    let mut cur: *const DnsRecordW = results;
    // SAFETY : liste rendue par DnsQuery_W, parcourue en lecture seule puis
    // libérée une seule fois par DnsFree ; on ne lit l'union SRV que pour les
    // enregistrements de type SRV (la réponse peut contenir des CNAME…).
    unsafe {
        while !cur.is_null() {
            let r = &*cur;
            if r.kind == DNS_TYPE_SRV {
                out.push((wide_to_string(r.srv.target), r.srv.port, r.srv.priority));
            }
            cur = r.next;
        }
        DnsFree(results as *mut c_void, DNS_FREE_RECORD_LIST);
    }
    out
}

/// Hors Windows, pas de fenêtre de pare-feu à craindre : un client DNS
/// ordinaire suffit.
#[cfg(not(target_os = "windows"))]
fn srv_records(name: &str) -> Vec<(String, u16, u16)> {
    use hickory_resolver::Resolver;
    let Ok(resolver) = Resolver::from_system_conf() else { return Vec::new() };
    match resolver.srv_lookup(format!("{name}.")) {
        Ok(lookup) => lookup.iter().map(|r| (r.target().to_utf8(), r.port(), r.priority())).collect(),
        Err(_) => Vec::new(),
    }
}

/// `address` — `host` ou `host:port`. Sans port écrit, l'enregistrement SRV
/// du domaine est suivi s'il existe (comme le fait le jeu), sinon 25565.
pub async fn ping_server(address: &str) -> Result<ServerPingInfo, String> {
    let (mut host, mut port, explicit) = parse_address(address);
    if !explicit && host.parse::<std::net::IpAddr>().is_err() {
        if let Some((target, srv_port)) = resolve_srv(&host).await {
            host = target;
            port = srv_port;
        }
    }

    let mut stream = timeout(CONNECT_TIMEOUT, TcpStream::connect((host.as_str(), port)))
        .await
        .map_err(|_| "Connexion au serveur expirée".to_string())?
        .map_err(|e| format!("Connexion échouée : {}", e))?;

    let result: Result<(Value, u64), std::io::Error> = timeout(IO_TIMEOUT, async {
        // Handshake — protocol_version -1 ("peu importe") accepté par tous
        // les serveurs modernes pour une simple requête de statut.
        let mut handshake = Vec::new();
        write_varint(&mut handshake, 0x00);
        write_varint(&mut handshake, -1);
        write_string(&mut handshake, &host);
        handshake.extend_from_slice(&port.to_be_bytes());
        write_varint(&mut handshake, 1);
        write_packet(&mut stream, &handshake).await?;

        // Status Request — packet_id 0x00, corps vide.
        write_packet(&mut stream, &[0x00]).await?;

        let body = read_packet(&mut stream).await?;
        let mut cursor = Cursor::new(&body[..]);
        let _packet_id = read_varint_sync(&mut cursor)?;
        let json_len = read_varint_sync(&mut cursor)? as usize;
        let pos = cursor.position() as usize;
        let json_bytes = body
            .get(pos..pos + json_len)
            .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::InvalidData, "réponse status tronquée"))?;
        let json: Value = serde_json::from_slice(json_bytes)
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e.to_string()))?;

        // Ping/Pong — mesure la latence réelle (round-trip), séparément du
        // temps pris par le status request qui inclut le poids du JSON/favicon.
        let mut ping = Vec::new();
        write_varint(&mut ping, 0x01);
        ping.extend_from_slice(&chrono::Utc::now().timestamp_millis().to_be_bytes());
        let ping_sent_at = Instant::now();
        write_packet(&mut stream, &ping).await?;
        let _pong = read_packet(&mut stream).await?;
        let latency_ms = ping_sent_at.elapsed().as_millis() as u64;

        Ok((json, latency_ms))
    })
    .await
    .map_err(|_| "Le serveur n'a pas répondu à temps".to_string())?;

    let (json, latency_ms) = result.map_err(|e| format!("Erreur de protocole : {}", e))?;

    Ok(ServerPingInfo {
        motd: json.get("description").map(flatten_motd).unwrap_or_default(),
        motd_spans: json.get("description").map(motd_spans).unwrap_or_default(),
        players_online: json.pointer("/players/online").and_then(|v| v.as_i64()).unwrap_or(0),
        players_max: json.pointer("/players/max").and_then(|v| v.as_i64()).unwrap_or(0),
        version_name: json.pointer("/version/name").and_then(|v| v.as_str()).unwrap_or("").to_string(),
        favicon: json.get("favicon").and_then(|v| v.as_str()).map(|s| s.to_string()),
        latency_ms,
    })
}
