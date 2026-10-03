//! Partager une instance sans rien héberger (2026-10-02).
//!
//! Une instance est presque entièrement **reconstructible** : ses mods, packs
//! de ressources et shaders viennent de Modrinth ou de CurseForge. On ne
//! partage donc pas les fichiers, mais **ce qu'il faut pour les retrouver**,
//! et rien ne passe par nos serveurs. Deux formes :
//!
//! - **Un fichier `.mrpack`** (format Modrinth, lu aussi par Prism, ATLauncher
//!   et la Modrinth App) : chaque fichier connu de Modrinth ou de CurseForge
//!   devient une ligne avec son adresse et ses empreintes, tout le reste (mod
//!   fait maison, `config/`, options, mondes choisis) est **embarqué** dans
//!   `overrides/`. Le pack pèse le poids de ce qui n'est nulle part ailleurs.
//! - **Un lien `yuyuframe://instance/…`** (`crate::share_link`), sans fichier
//!   du tout, quand tout ce qui est choisi est sur Modrinth : version du jeu,
//!   loader, l'identifiant de version Modrinth de chaque fichier, et la
//!   configuration Java. Une instance de 22 mods avec ses drapeaux JVM tient
//!   en moins de 300 caractères (test `lien_instance_compact`). Ni configs ni
//!   fichiers locaux : c'est le rôle du fichier.
//!
//! ── Ce qui se partage ───────────────────────────────────────────────────────
//! L'inventaire part du **contenu réel** du dossier, pas d'une liste figée :
//! un mod de minicarte qui range ses points de passage dans `xaero/` doit
//! apparaître, même si on ne le connaît pas. Trois règles seulement :
//! - **Jamais proposé** (`NEVER_SHARED`, plus tout nom commençant par un
//!   point) : caches (`.fabric`, `.voxy`, `downloads/`…), journaux, rapports
//!   de plantage, et ce qui touche au compte ou à la vie privée —
//!   `usercache.json`, l'historique des commandes, `essential/` (le mod
//!   Essential y garde des jetons de connexion), et les fichiers internes du
//!   launcher (`meta.json`, `modpack.json`).
//! - **Coché d'office** : mods, packs de ressources, shaders et les fichiers
//!   d'options — c'est ce qu'on attend en partageant une instance. `config/`
//!   est proposé décoché : il pèse vite plus que tout le reste et contient la
//!   configuration personnelle de chaque mod.
//! - **Proposé, décoché** : la liste des serveurs (elle peut contenir
//!   l'adresse d'un serveur privé), chaque monde (lourd, et rarement voulu),
//!   et tout le reste.
//!
//! ── Ce qui se reçoit ────────────────────────────────────────────────────────
//! Un pack reçu vient de quelqu'un d'autre, donc tout y est vérifié : chemins
//! relatifs sans `..` ni lecteur, aucune écriture sur les fichiers internes du
//! launcher (un `meta.json` posé par un pack pourrait désigner un exécutable
//! Java arbitraire), téléchargements limités aux domaines des plateformes, et
//! chaque fichier téléchargé comparé à l'empreinte annoncée avant d'être écrit.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::{LazyLock, Mutex};

use serde::{Deserialize, Serialize};
use sha1::{Digest, Sha1};
use sha2::Sha512;

use super::settings::agent_options::{agent_options_read, agent_options_write};
use super::crud::{instance_dir, row_to_instance, user_id, Instance};
use super::mods::sha1_cached;
use super::settings::options::{mc_options_write, McOption};
use super::settings::options_share::{from_text, keep_client, keep_game, to_text};
use crate::db;
use crate::security::safe_paths::{join_relative, safe_relative};
use crate::minecraft::mod_files::{is_disabled_jar, is_jar_file};
use crate::state::SharedState;

const USER_AGENT: &str = "YuyuFrame/1.0 (yuyuframe.eu)";

/// Noms de premier niveau jamais proposés (comparés en minuscules). Les noms
/// commençant par un point sont écartés à part : ce sont des caches.
const NEVER_SHARED: &[&str] = &[
    // Fichiers internes du launcher.
    "meta.json",
    "modpack.json",
    "modpack_backup.json",
    // Caches, journaux, diagnostics.
    "logs",
    "crash-reports",
    "debug",
    "downloads",
    "natives",
    "debug-profile.json",
    // Compte et vie privée.
    "usercache.json",
    "usernamecache.json",
    "realms_persistence.json",
    "command_history.txt",
    "essential",
    "servers.dat_old",
    "servers.essential.dat",
    "whitelist.json",
    "banned-players.json",
    "banned-ips.json",
    "ops.json",
];

/// Fichiers d'options à la racine de l'instance.
const SETTINGS_FILES: &[&str] = &["options.txt", "optionsof.txt", "optionsshaders.txt"];


/// Domaines d'où un pack reçu peut faire télécharger : ceux qu'autorise le
/// format Modrinth, plus le CDN de CurseForge (nos packs l'utilisent pour les
/// mods qui ne sont que là-bas).
const ALLOWED_DOWNLOADS: &[&str] = &[
    "https://cdn.modrinth.com/",
    "https://github.com/",
    "https://raw.githubusercontent.com/",
    "https://gitlab.com/",
    "https://edge.forgecdn.net/",
    "https://media.forgecdn.net/",
    "https://mediafilez.forgecdn.net/",
];

const CURSEFORGE_CDN: &[&str] = &[
    "https://edge.forgecdn.net/",
    "https://media.forgecdn.net/",
    "https://mediafilez.forgecdn.net/",
];

/// Téléchargements simultanés à l'import.
const PARALLEL_DOWNLOADS: usize = 6;

/// Fichier propre à YuyuFrame à la racine du `.mrpack` : le format Modrinth
/// n'a rien pour la JVM, et les autres launchers ignorent ce qu'ils ne
/// connaissent pas à cet endroit.
const YUYU_FILE: &str = "yuyuframe.json";

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct YuyuExtras {
    format_version: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    jvm: Option<JvmShare>,
    /// Options du client intégré (`agent/module-config/<instance>.properties`) :
    /// hors du dossier de l'instance, donc hors d'`overrides/`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    client: Option<Vec<(String, String)>>,
}

/// `options.txt` passe par les mêmes règles que le partage d'options
/// (`settings/options_share.rs`) : sans `lastServer`, sans ligne piégée.
const OPTIONS_FILE: &str = "options.txt";

fn shared_options_txt(abs: &Path) -> Option<String> {
    let content = std::fs::read_to_string(abs).ok()?;
    let options: Vec<McOption> = from_text(&content, ':').into_iter().filter(keep_game).collect();
    Some(to_text(&options, ':') + "\n")
}

pub mod export;
pub mod import;
pub mod inventory;
pub mod java_config;
pub mod link;
pub mod loader;
pub mod pack;
pub mod scan;
pub mod sources;

use export::*;
#[cfg(test)]
use import::*;
use inventory::*;
use java_config::*;
use link::*;
use loader::*;
use pack::*;
use scan::*;
use sources::*;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn caches_comptes_et_fichiers_internes_jamais_proposes() {
        for name in ["logs", ".fabric", ".voxy", "essential", "usercache.json", "meta.json", "Command_History.txt"] {
            assert_eq!(top_level_group(name, true), None, "{name}");
            assert_eq!(top_level_group(name, false), None, "{name}");
        }
    }

    #[test]
    fn classement_des_dossiers_et_fichiers() {
        assert_eq!(top_level_group("mods", true), Some(Group::Mods));
        assert_eq!(top_level_group("config", true), Some(Group::Settings));
        assert_eq!(top_level_group("options.txt", false), Some(Group::Settings));
        assert_eq!(top_level_group("servers.dat", false), Some(Group::Servers));
        assert_eq!(top_level_group("saves", true), Some(Group::Saves));
        // Un dossier de mod inconnu reste proposable, décoché.
        assert_eq!(top_level_group("xaero", true), Some(Group::Other));
        assert!(!Group::Other.selected_by_default());
        assert!(!Group::Servers.selected_by_default());
        assert!(Group::Mods.selected_by_default());
    }

    #[test]
    fn chemins_dangereux_refuses() {
        assert_eq!(safe_relative("mods/a.jar").as_deref(), Some("mods/a.jar"));
        assert_eq!(safe_relative("config\\x\\y.json").as_deref(), Some("config/x/y.json"));
        for bad in ["../a", "mods/../../a", "/etc/x", "\\x", "C:/x", "mods/C:x", "meta.json", "META.JSON", "", "./"] {
            assert_eq!(safe_relative(bad), None, "{bad}");
        }
        // Un meta.json plus bas dans l'arborescence n'est pas celui du launcher.
        assert!(safe_relative("config/meta.json").is_some());
    }

    #[test]
    fn jeton_de_lien_aller_retour() {
        // Avec empreinte (version à plusieurs fichiers), sans, puis encore avec.
        let a = encode_token('M', "AbCd1234", "DEADbeef00", true).unwrap();
        let b = encode_token('r', "zzzzzzzz", "0123abcd", false).unwrap();
        let c = encode_token('s', "9f9f9f9f", "abcdef01", true).unwrap();
        assert_eq!(a.len(), 13);
        assert_eq!(b.len(), 9);
        let decoded = decode_tokens(&format!("{a}{b}{c}")).unwrap();
        assert_eq!(decoded.len(), 3);
        assert_eq!((decoded[0].kind, decoded[0].version_id.as_str(), decoded[0].hash_prefix.as_str()), ('M', "AbCd1234", "dead"));
        assert_eq!(dir_of(decoded[0].kind), Some(("mods", true)));
        assert_eq!((decoded[1].kind, decoded[1].hash_prefix.as_str()), ('r', ""));
        assert_eq!((decoded[2].version_id.as_str(), decoded[2].hash_prefix.as_str()), ("9f9f9f9f", "abcd"));
    }

    #[test]
    fn jeton_de_lien_invalide() {
        assert!(encode_token('m', "trop-long-id", "dead", false).is_none());
        assert!(decode_tokens("mAbCd123").is_err()); // identifiant tronqué
        assert!(decode_tokens("xAbCd1234").is_err()); // famille inconnue
        assert!(decode_tokens("mAbCd12/4").is_err()); // identifiant
        assert!(decode_tokens("mAbCd1234zz").is_err()); // ni empreinte ni jeton suivant
        assert!(decode_tokens("").unwrap().is_empty());
    }

    /// Le lien de ton instance CocoWorld (22 mods, packs et config Java) :
    /// dans le format d'avant, 1 523 caractères.
    #[test]
    fn lien_instance_compact() {
        let tokens = "mL6Sv1iN2mAfA2Emwm89mGFM8zmJ832fm3dmX6ou16c3mUdiBeac7mRystERKEmMwcLS51SmYo9xOcemw8P6TokGmOqq8TOAVmFItuNokSmZJ6YTrMYm4H8A03wameRJU33HpmiFNRLrRBm7RYVKQJmmgjsLvJfWmpX4mxVAvmkWf58HtHmIYPINJuwrBX6pU42frWWLpy1hrR5ZGSF8ArRGIzA5emrxeIjARlrryEg1LARqryQdcUfnrrkqcBpfhrrI4ivyUVarrJtYoNiksyCCduG4sy6zWED9ssgUv7fBPsWcoEHPPx";
        let args = "-XX:+UnlockExperimentalVMOptions -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:+PerfDisableSharedMem -XX:+AlwaysActAsServerClassMachine -XX:+UseCriticalJavaThreadPriority -XX:MetaspaceSize=256m -XX:-UseG1GC -XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational -XX:+ParallelRefProcEnabled -XX:ShenandoahGuaranteedGCInterval=1000000 -XX:-ShenandoahUncommit -XX:-DontCompileHugeMethods -XX:MaxNodeLimit=240000 -XX:NodeLimitFudgeFactor=8000 -XX:NmethodSweepActivity=1 -XX:ReservedCodeCacheSize=400M -XX:NonNMethodCodeHeapSize=12M";
        let mut data = Vec::new();
        push_record(&mut data, REC_HEADER, format!("CocoWorld 1.5\n26.1.2\nfabric\n0.19.5\n{tokens}").as_bytes());
        push_record(&mut data, REC_JVM, format!("6144 temurin g1 replace\n{args}").as_bytes());
        let links = crate::share_link::build(LINK_KIND, &data).unwrap();
        assert_eq!(links.len(), 1);
        let length = links[0].chars().count();
        assert!(length < 300, "{length} caractères");
        assert_eq!(crate::share_link::read(&links[0], LINK_KIND).unwrap(), data);
    }

    /// Un lien fabriqué à la main : options filtrées à la lecture, serveurs,
    /// fichier copié, et un chemin qui sort de l'instance refusé.
    #[tokio::test]
    async fn lecture_d_un_lien_complet() {
        let mut data = Vec::new();
        push_record(&mut data, REC_HEADER, b"Test\n1.21.11\nvanilla\n\n");
        push_record(&mut data, REC_OPTIONS, b"fov:0.5\nlastServer:prive.example");
        push_record(&mut data, REC_CLIENT, b"zoom.enabled=true\nmacros.setting.logins=secret");
        push_record(&mut data, REC_SERVERS, b"Mon serveur\tmc.example.org\nSans adresse\t");
        push_record(&mut data, REC_FILE, b"config/a.json\0{\"x\":1}");
        push_record(&mut data, REC_FILE, b"../../evil.txt\0boom");
        push_record(&mut data, b'Z', b"etiquette future, ignoree");
        let link = crate::share_link::build(LINK_KIND, &data).unwrap().join("\n");

        let pack = read_link(&link).await.unwrap();
        assert_eq!(pack.name, "Test");
        assert_eq!(pack.options, vec![McOption { key: "fov".into(), value: "0.5".into() }]);
        assert_eq!(pack.client, vec![McOption { key: "zoom.enabled".into(), value: "true".into() }]);
        assert_eq!(pack.servers, vec![("Mon serveur".to_string(), "mc.example.org".to_string())]);
        assert_eq!(pack.inline, vec![("config/a.json".to_string(), b"{\"x\":1}".to_vec())]);
        assert_eq!(pack.rejected, vec!["../../evil.txt".to_string()]);
    }

    #[test]
    fn enregistrements_aller_retour() {
        let mut data = Vec::new();
        push_record(&mut data, REC_HEADER, b"abc");
        push_record(&mut data, REC_FILE, &vec![7u8; 300]);
        let parsed = records(&data).unwrap();
        assert_eq!(parsed.len(), 2);
        assert_eq!(parsed[1].1.len(), 300);
        assert!(records(&data[..data.len() - 1]).is_none());
    }

    #[test]
    fn loader_depuis_les_dependances() {
        let mut deps = BTreeMap::new();
        deps.insert("minecraft".to_string(), "1.21.4".to_string());
        assert_eq!(loader_from_dependencies(&deps), ("vanilla".into(), String::new()));
        deps.insert("fabric-loader".to_string(), "0.16.9".to_string());
        assert_eq!(loader_from_dependencies(&deps), ("fabric".into(), "0.16.9".into()));
    }

    #[test]
    fn verification_des_empreintes() {
        let data = b"hello";
        let sha1 = format!("{:x}", Sha1::digest(data));
        let sha512 = format!("{:x}", Sha512::digest(data));
        assert!(matches_hashes(data, Some(&sha1), None));
        assert!(matches_hashes(data, Some("0000"), Some(&sha512)));
        assert!(!matches_hashes(data, Some(&sha1), Some("00")));
        assert!(!matches_hashes(b"other", Some(&sha1), None));
    }

    /// Des réglages courants, tirés des jeux de drapeaux qui circulent
    /// (Aikar, Graal, ZGC) : ils doivent passer.
    #[test]
    fn arguments_jvm_de_reglage_acceptes() {
        for arg in [
            "-Xmx6G", "-Xms4096m", "-Xss2m", "-XX:+UseG1GC", "-XX:+UnlockExperimentalVMOptions",
            "-XX:MaxGCPauseMillis=50", "-XX:G1NewSizePercent=30", "-XX:-DontCompileHugeMethods",
            "-XX:+UseZGC", "-XX:+ZGenerational", "-XX:+AlwaysPreTouch", "-Xgcpolicy:gencon",
            "-Xdisableexplicitgc", "-Dfml.ignoreInvalidMinecraftCertificates=true",
            "-Dlog4j2.formatMsgNoLookups=true",
        ] {
            assert!(jvm_arg_allowed(arg), "{arg}");
        }
    }

    /// De quoi faire exécuter du code ou écrire un fichier : jamais.
    #[test]
    fn arguments_jvm_dangereux_refuses() {
        for arg in [
            "-javaagent:C:/evil.jar", "-agentlib:jdwp=transport=dt_socket", "-agentpath:x.dll",
            "-XX:OnOutOfMemoryError=calc.exe", "-XX:OnError=cmd", "-XX:ErrorFile=C:/x.log",
            "-XX:HeapDumpPath=C:/Users/moi", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:Flags=x",
            "-XX:VMOptionsFile=x", "-XX:CompileCommand=x", "-XX:StartFlightRecording=filename=x",
            "-Djava.library.path=mods", "-Dorg.lwjgl.librarypath=x", "-Djava.system.class.loader=Evil",
            "-Dlog4j.configurationFile=http://x", "-Dfoo=C:/bar", "-Dfoo=a/b", "-cp", "evil.jar",
            "@argfile", "--add-opens=java.base/java.lang=ALL-UNNAMED", "-jar", "-Xmx4GG", "-Xbootclasspath/a:x",
        ] {
            assert!(!jvm_arg_allowed(arg), "{arg}");
        }
    }

    #[test]
    fn configuration_jvm_assainie() {
        let (clean, rejected) = sanitize_jvm(JvmShare {
            ram_mb: 999_999,
            vendor: "custom".into(),
            gc_policy: "g1 ; rm".into(),
            args_mode: "n'importe quoi".into(),
            args: vec!["-XX:+UseG1GC".into(), "-javaagent:x.jar".into()],
        });
        assert_eq!(clean.ram_mb, 65_536);
        assert_eq!(clean.vendor, "auto");
        assert_eq!(clean.gc_policy, "auto");
        assert_eq!(clean.args_mode, "append");
        assert_eq!(clean.args, vec!["-XX:+UseG1GC".to_string()]);
        assert_eq!(rejected, vec!["-javaagent:x.jar".to_string()]);
    }

    #[test]
    fn domaines_autorises() {
        assert!(allowed_download("https://cdn.modrinth.com/data/x/y.jar"));
        assert!(allowed_download("https://edge.forgecdn.net/files/1/2/x.jar"));
        assert!(!allowed_download("http://cdn.modrinth.com/x"));
        assert!(!allowed_download("https://cdn.modrinth.com.evil.example/x"));
        assert!(!allowed_download("https://example.com/x.jar"));
    }
}
