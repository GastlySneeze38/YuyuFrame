//! Lien `yuyuframe://instance/…` : le même contenu que le fichier, en enregistrements.

use super::*;

// Le lien porte tout ce que porte le fichier, compressé par `crate::share_link`
// (et découpé en parties s'il le faut). Les données sont une suite
// d'enregistrements « étiquette (1 octet), longueur (LEB128), contenu » :
//
//   H  en-tête, une information par ligne : nom, version de Minecraft,
//      loader, version du loader, jetons des fichiers Modrinth (collés)
//   J  configuration Java : « RAM JVM GC mode », puis les arguments
//   O  `options.txt`, en lignes `clé:valeur` filtrées (`settings/options_share.rs`)
//   C  options du client intégré, en lignes `clé=valeur` filtrées
//   S  serveurs : « nom<TAB>adresse » par ligne (ni icône ni rien d'autre)
//   R  fichier à télécharger hors Modrinth (CurseForge) : chemin, adresse,
//      SHA-1, séparés par NUL
//   F  fichier copié dans le lien : chemin, NUL, contenu
//
// Les options et les serveurs voyagent en texte, sous la forme du
// dictionnaire de compression, et sont fusionnés à l'arrivée — exactement
// comme le partage d'options. Seuls les fichiers `F` pèsent vraiment : c'est
// eux que l'interface nomme quand un lien dépasse `MAX_PARTS`.
//
// Un jeton = la famille (`m`, `M` pour un mod désactivé, `r`, `s`), les 8
// caractères de l'identifiant de version Modrinth, puis 4 chiffres hexa du
// SHA-1 **seulement** si cette version publie plusieurs fichiers. Pas
// d'ambiguïté à la lecture : aucune famille n'est un chiffre hexa.

pub(super) const REC_HEADER: u8 = b'H';
pub(super) const REC_JVM: u8 = b'J';
pub(super) const REC_OPTIONS: u8 = b'O';
pub(super) const REC_CLIENT: u8 = b'C';
pub(super) const REC_SERVERS: u8 = b'S';
pub(super) const REC_REMOTE: u8 = b'R';
pub(super) const REC_FILE: u8 = b'F';

pub(super) fn push_record(out: &mut Vec<u8>, tag: u8, body: &[u8]) {
    out.push(tag);
    let mut n = body.len();
    loop {
        let byte = (n & 0x7F) as u8;
        n >>= 7;
        if n == 0 {
            out.push(byte);
            break;
        }
        out.push(byte | 0x80);
    }
    out.extend_from_slice(body);
}

pub(super) fn records(mut data: &[u8]) -> Option<Vec<(u8, &[u8])>> {
    let mut out = Vec::new();
    while let Some((&tag, rest)) = data.split_first() {
        let (mut len, mut shift, mut pos) = (0usize, 0u32, 0usize);
        loop {
            let byte = *rest.get(pos)?;
            pos += 1;
            len |= ((byte & 0x7F) as usize).checked_shl(shift)?;
            if byte & 0x80 == 0 {
                break;
            }
            shift += 7;
            if shift > 28 {
                return None;
            }
        }
        let body = rest.get(pos..pos + len)?;
        out.push((tag, body));
        data = &rest[pos + len..];
    }
    Some(out)
}

/// Une valeur sur une ligne : ni retour à la ligne ni tabulation (séparateurs).
pub(super) fn one_line(s: &str) -> String {
    s.replace(['\n', '\r', '\t', '\0'], " ")
}

pub(super) const LINK_KIND: &str = "instance";
/// Longueur d'un identifiant de version Modrinth.
pub(super) const VERSION_ID_LEN: usize = 8;
/// Début du SHA-1, quand une version publie plusieurs fichiers (un jar
/// Fabric et un jar Forge publiés ensemble, par exemple).
pub(super) const HASH_PREFIX_LEN: usize = 4;

/// Famille d'un fichier dans le lien. Majuscule = mod désactivé.
pub(super) fn kind_of(group: Group, disabled: bool) -> Option<char> {
    match (group, disabled) {
        (Group::Mods, false) => Some('m'),
        (Group::Mods, true) => Some('M'),
        (Group::Resourcepacks, _) => Some('r'),
        (Group::Shaderpacks, _) => Some('s'),
        _ => None,
    }
}

pub(super) fn dir_of(kind: char) -> Option<(&'static str, bool)> {
    match kind {
        'm' => Some(("mods", false)),
        'M' => Some(("mods", true)),
        'r' => Some(("resourcepacks", false)),
        's' => Some(("shaderpacks", false)),
        _ => None,
    }
}

pub(super) fn valid_version_id(id: &str) -> bool {
    id.len() == VERSION_ID_LEN && id.chars().all(|c| c.is_ascii_alphanumeric())
}

pub(super) struct LinkToken {
    pub(super) kind: char,
    pub(super) version_id: String,
    /// Vide quand la version n'a qu'un fichier.
    pub(super) hash_prefix: String,
}

pub(super) fn encode_token(kind: char, version_id: &str, sha1: &str, ambiguous: bool) -> Option<String> {
    if !valid_version_id(version_id) || sha1.len() < HASH_PREFIX_LEN {
        return None;
    }
    let prefix = if ambiguous { &sha1[..HASH_PREFIX_LEN] } else { "" };
    Some(format!("{kind}{version_id}{}", prefix.to_ascii_lowercase()))
}

pub(super) fn decode_tokens(raw: &str) -> Result<Vec<LinkToken>, String> {
    let broken = || "Lien de partage abîmé".to_string();
    if !raw.is_ascii() {
        return Err(broken());
    }
    let bytes = raw.as_bytes();
    let is_hex = |b: &u8| b.is_ascii_digit() || (b'a'..=b'f').contains(b);
    let mut tokens = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
        let kind = bytes[i] as char;
        let id = raw.get(i + 1..i + 1 + VERSION_ID_LEN).ok_or_else(broken)?;
        if dir_of(kind).is_none() || !valid_version_id(id) {
            return Err(broken());
        }
        i += 1 + VERSION_ID_LEN;
        let prefix = match bytes.get(i..i + HASH_PREFIX_LEN) {
            Some(p) if p.iter().all(is_hex) => {
                i += HASH_PREFIX_LEN;
                std::str::from_utf8(p).map_err(|_| broken())?.to_string()
            }
            _ => String::new(),
        };
        tokens.push(LinkToken { kind, version_id: id.to_string(), hash_prefix: prefix });
    }
    Ok(tokens)
}

#[tauri::command]
pub async fn instance_share_link(
    state: tauri::State<'_, SharedState>,
    instance_id: String,
    paths: Vec<String>,
    include_jvm: bool,
    include_client: bool,
) -> Result<Vec<String>, String> {
    let scanned = scan(&state, &instance_id).await?;
    let loader_version = loader_version_of(&scanned.instance).await?;
    let dir = instance_dir(&instance_id);

    let mut tokens = String::new();
    let mut body: Vec<u8> = Vec::new();
    // Taille de ce qui est copié dans le lien, par élément : si le lien
    // déborde, on dit lesquels décocher.
    let mut heavy: Vec<(String, u64)> = Vec::new();

    for (entry, sha1) in selection(&scanned, &paths) {
        let name = entry.path.rsplit('/').next().unwrap_or_default();
        let remote = sha1.as_deref().and_then(cached);
        // Modrinth : un jeton de quelques caractères.
        if let (Some(r), Some(h)) = (&remote, sha1.as_deref()) {
            let token = kind_of(entry.group, is_disabled_jar(name))
                .zip(r.modrinth_version.as_deref())
                .and_then(|(kind, version)| encode_token(kind, version, h, r.ambiguous));
            if let Some(t) = token {
                tokens.push_str(&t);
                continue;
            }
            // Ailleurs (CurseForge) : l'adresse et l'empreinte.
            let record = format!("{}\0{}\0{}", entry.path, r.url, h);
            push_record(&mut body, REC_REMOTE, record.as_bytes());
            continue;
        }
        if entry.path == OPTIONS_FILE {
            if let Some(text) = shared_options_txt(&entry.abs) {
                push_record(&mut body, REC_OPTIONS, text.trim_end().as_bytes());
            }
            continue;
        }
        if entry.group == Group::Servers {
            let servers = crate::minecraft::launcher::read_saved_servers(&dir).unwrap_or_default();
            let text = servers
                .iter()
                .map(|s| format!("{}\t{}", one_line(&s.name), one_line(&s.ip)))
                .collect::<Vec<_>>()
                .join("\n");
            if !text.is_empty() {
                push_record(&mut body, REC_SERVERS, text.as_bytes());
            }
            continue;
        }
        // Le reste est copié tel quel.
        let mut files = Vec::new();
        files_of(&entry.abs, &entry.path, &mut files);
        let mut size = 0u64;
        for (abs, rel) in files {
            let content = std::fs::read(&abs).map_err(|e| format!("{rel} : {e}"))?;
            size += content.len() as u64;
            let mut record = rel.into_bytes();
            record.push(0);
            record.extend_from_slice(&content);
            push_record(&mut body, REC_FILE, &record);
        }
        heavy.push((entry.path.clone(), size));
    }

    if include_jvm {
        let jvm = &scanned.jvm;
        let text = format!("{} {} {} {}\n{}", jvm.ram_mb, jvm.vendor, jvm.gc_policy, jvm.args_mode, jvm.args.join(" "));
        push_record(&mut body, REC_JVM, text.as_bytes());
    }
    if include_client && !scanned.client_options.is_empty() {
        push_record(&mut body, REC_CLIENT, to_text(&scanned.client_options, '=').as_bytes());
    }

    let header = [one_line(&scanned.instance.name), scanned.instance.mc_version.clone(), scanned.instance.loader.clone(), loader_version, tokens]
        .join("\n");
    let mut data = Vec::new();
    push_record(&mut data, REC_HEADER, header.as_bytes());
    data.extend_from_slice(&body);

    crate::share_link::build(LINK_KIND, &data).map_err(|e| match e {
        crate::share_link::LinkError::TooLarge { parts } => {
            heavy.sort_by(|a, b| b.1.cmp(&a.1));
            let names: Vec<&str> = heavy.iter().take(3).map(|(p, _)| p.as_str()).collect();
            if names.is_empty() {
                String::from(crate::share_link::LinkError::TooLarge { parts })
            } else {
                format!(
                    "Trop volumineux pour un lien ({parts} parties, {} au plus). Les éléments copiés les plus lourds : {}. Décoche-les, ou partage le fichier.",
                    crate::share_link::MAX_PARTS,
                    names.join(", ")
                )
            }
        }
        other => String::from(other),
    })
}
