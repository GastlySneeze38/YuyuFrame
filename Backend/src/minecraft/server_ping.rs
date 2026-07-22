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

fn parse_address(address: &str) -> (String, u16) {
    match address.rsplit_once(':') {
        Some((h, p)) if p.chars().all(|c| c.is_ascii_digit()) => {
            (h.to_string(), p.parse().unwrap_or(25565))
        }
        _ => (address.to_string(), 25565),
    }
}

/// `address` — `host` ou `host:port` (port par défaut 25565, comme
/// servers.dat côté vanilla — voir `super::launcher::servers`).
pub async fn ping_server(address: &str) -> Result<ServerPingInfo, String> {
    let (host, port) = parse_address(address);

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
        players_online: json.pointer("/players/online").and_then(|v| v.as_i64()).unwrap_or(0),
        players_max: json.pointer("/players/max").and_then(|v| v.as_i64()).unwrap_or(0),
        version_name: json.pointer("/version/name").and_then(|v| v.as_str()).unwrap_or("").to_string(),
        favicon: json.get("favicon").and_then(|v| v.as_str()).map(|s| s.to_string()),
        latency_ms,
    })
}
