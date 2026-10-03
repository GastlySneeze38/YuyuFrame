//! Configuration Java qui voyage avec l'instance, arguments filtrés par liste
//! blanche à l'envoi comme à la réception.

use super::*;

// ── Configuration Java ──────────────────────────────────────────────────────
//
// Partagée avec l'instance : RAM, JVM, ramasse-miettes, mode et arguments —
// ce que le lancement emploie réellement, donc la config JVM reliée si
// l'instance en a une (`play/launch.rs` : elle remplace intégralement le bloc de
// l'instance). Jamais le chemin d'un Java personnalisé : il n'existe que sur
// la machine de l'expéditeur.
//
// **Les arguments passent par une liste blanche**, à l'envoi comme à la
// réception. Un argument JVM n'est pas un réglage anodin : `-javaagent:`,
// `-XX:OnOutOfMemoryError=…` ou `-Djava.library.path=` (pointé vers une DLL
// glissée dans le pack) suffisent à faire exécuter n'importe quoi par la
// machine qui importe. Ne passe que du réglage — tailles mémoire, drapeaux
// `-XX:` sans fichier ni commande, propriétés `-D` sans chemin — et ce qui
// est écarté est montré, des deux côtés. À l'envoi, le filtre protège aussi
// l'expéditeur : un `-XX:HeapDumpPath=C:\Users\<nom>\…` ne part pas.

/// Configuration Java telle qu'elle voyage.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct JvmShare {
    pub ram_mb: u32,
    /// auto | temurin | openj9 | graal
    pub vendor: String,
    pub gc_policy: String,
    /// append | replace — voir `merge_jvm_args`.
    pub args_mode: String,
    pub args: Vec<String>,
}

pub(super) const JVM_VENDORS: &[&str] = &["auto", "temurin", "openj9", "graal"];
pub(super) const RAM_RANGE: std::ops::RangeInclusive<u32> = 512..=65_536;

/// Morceaux de nom de drapeau `-XX:` refusés : tout ce qui lance une commande,
/// lit ou écrit un fichier, ou charge du code. Pas « options » : il bloquait
/// `-XX:+UnlockExperimentalVMOptions`, présent dans presque tous les jeux de
/// drapeaux, et `VMOptionsFile` tombe déjà sur « file ».
pub(super) const XX_BLOCKED: &[&str] = &[
    "onerror", "onoutofmemory", "file", "path", "log", "dump", "flags", "command",
    "library", "agent", "recording", "archive", "exec", "script",
];

/// Préfixes de propriétés `-D` refusés : celles de la JVM et de LWJGL règlent
/// le chargement des classes et des bibliothèques natives.
pub(super) const D_BLOCKED_PREFIXES: &[&str] = &["java.", "jdk.", "sun.", "javax.", "com.sun.", "org.lwjgl."];
/// Morceaux de nom de propriété `-D` refusés (même raison que `XX_BLOCKED`).
pub(super) const D_BLOCKED: &[&str] = &[
    "path", "file", "dir", "home", "class", "agent", "library", "loader", "security",
    "ssl", "config", "proxy", "url", "host",
];

pub(super) fn plain_value(v: &str) -> bool {
    !v.is_empty() && v.len() <= 64 && v.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | ',' | '_' | '+' | '-'))
}

pub(super) fn plain_name(v: &str) -> bool {
    !v.is_empty() && v.len() <= 80 && v.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'))
}

/// `4G`, `512m`, `1024` — une taille mémoire de la JVM.
pub(super) fn memory_size(v: &str) -> bool {
    let digits = v.trim_end_matches(['k', 'K', 'm', 'M', 'g', 'G', 't', 'T']);
    !digits.is_empty() && digits.len() + 1 >= v.len() && digits.chars().all(|c| c.is_ascii_digit())
}

/// Un argument JVM peut-il venir de quelqu'un d'autre ?
pub(super) fn jvm_arg_allowed(arg: &str) -> bool {
    if let Some(rest) = arg.strip_prefix("-XX:") {
        let (name, value) = match rest.strip_prefix(['+', '-']) {
            Some(flag) => (flag, None),
            None => match rest.split_once('=') {
                Some((n, v)) => (n, Some(v)),
                None => return false,
            },
        };
        let lower = name.to_ascii_lowercase();
        return name.chars().all(|c| c.is_ascii_alphanumeric())
            && !name.is_empty()
            && value.map_or(true, plain_value)
            && !XX_BLOCKED.iter().any(|b| lower.contains(b));
    }
    if let Some(rest) = arg.strip_prefix("-D") {
        let (key, value) = match rest.split_once('=') {
            Some((k, v)) => (k, Some(v)),
            None => (rest, None),
        };
        let lower = key.to_ascii_lowercase();
        return plain_name(key)
            && value.map_or(true, plain_value)
            && !D_BLOCKED_PREFIXES.iter().any(|p| lower.starts_with(p))
            && !D_BLOCKED.iter().any(|b| lower.contains(b));
    }
    for prefix in ["-Xmx", "-Xms", "-Xmn", "-Xss"] {
        if let Some(size) = arg.strip_prefix(prefix) {
            return memory_size(size);
        }
    }
    for prefix in ["-Xgcpolicy:", "-Xtune:"] {
        if let Some(word) = arg.strip_prefix(prefix) {
            return word.chars().all(|c| c.is_ascii_alphanumeric()) && !word.is_empty();
        }
    }
    matches!(arg, "-Xdisableexplicitgc" | "-Xnoclassgc")
}

/// Ramène une configuration à ce qui peut voyager. Rend aussi les arguments
/// écartés, pour les montrer.
pub(super) fn sanitize_jvm(raw: JvmShare) -> (JvmShare, Vec<String>) {
    let (args, rejected): (Vec<String>, Vec<String>) = raw.args.into_iter().partition(|a| jvm_arg_allowed(a));
    let clean = JvmShare {
        ram_mb: raw.ram_mb.clamp(*RAM_RANGE.start(), *RAM_RANGE.end()),
        vendor: if JVM_VENDORS.contains(&raw.vendor.as_str()) { raw.vendor } else { "auto".into() },
        gc_policy: if raw.gc_policy.len() <= 32 && raw.gc_policy.chars().all(|c| c.is_ascii_alphanumeric()) && !raw.gc_policy.is_empty() {
            raw.gc_policy
        } else {
            "auto".into()
        },
        args_mode: if raw.args_mode == "replace" { "replace".into() } else { "append".into() },
        args,
    };
    (clean, rejected)
}

/// La configuration que le lancement emploierait (voir `play/launch.rs`).
pub(super) fn effective_jvm(instance: &Instance, profile: Option<&db::JvmProfileRow>) -> JvmShare {
    use crate::minecraft::launcher::parse_user_jvm_args;
    match profile {
        Some(p) => JvmShare {
            ram_mb: p.ram_mb.unwrap_or(instance.ram_mb),
            vendor: p.jvm_vendor.clone(),
            gc_policy: p.gc_policy.clone(),
            args_mode: p.args_mode.clone(),
            args: parse_user_jvm_args(&p.all_args()),
        },
        None => JvmShare {
            ram_mb: instance.ram_mb,
            vendor: instance.jvm_vendor.clone(),
            gc_policy: instance.gc_policy.clone(),
            args_mode: instance.jvm_args_mode.clone(),
            args: parse_user_jvm_args(&instance.jvm_extra_args),
        },
    }
}
