//! Détection et rapport de plantage.
//!
//! Jusqu'ici, un plantage laissait la personne devant une fenêtre qui se
//! ferme : le journal partait avec la console, le fichier `crash-reports/` de
//! Minecraft restait dans un dossier qu'elle ne trouve pas, et le support
//! commençait toujours par la même demi-heure de questions. Ce module capture
//! tout pendant que le jeu tourne, et au moment où la JVM s'arrête mal, écrit
//! un rapport complet à côté du launcher.
//!
//! Ce qu'il y met est ce qu'on redemanderait de toute façon : la machine, la
//! JVM et ses drapeaux exacts, la version et le loader, la liste des mods avec
//! leur état, la trace d'exception entière et la fin du journal. Rien n'en
//! part vers nos serveurs tant que la personne n'a pas cliqué : le rapport est
//! montré tel quel dans l'onglet Support, et c'est elle qui l'envoie.
//!
//! Les secrets sont retirés à l'écriture, pas à l'envoi : un rapport qui
//! dort sur un disque ne doit pas contenir de jeton de session non plus.

use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::commands::support::redact;

/// Lignes de journal gardées. Large : une trace Mixin ou un échec de
/// résolution Forge tient rarement en vingt lignes, et c'est précisément ce
/// qu'on vient chercher.
const LOG_LINES: usize = 900;
/// Rapports gardés sur le disque. Au-delà, les plus anciens partent : ce
/// dossier ne doit jamais devenir un problème de place.
const KEEP: usize = 25;
/// Trace d'exception gardée, en caractères.
const STACK_MAX: usize = 60_000;

// ── Le rapport ───────────────────────────────────────────────────────────────

#[derive(Serialize, Deserialize, Clone)]
pub struct ModEntry {
    pub name: String,
    pub enabled: bool,
    pub size: Option<i64>,
}

/// Un rapport complet. Sérialisé tel quel sur le disque ET envoyé tel quel à
/// `POST /v1/crashes` : deux formes différentes finiraient par diverger, et
/// c'est justement le genre d'écart qui se découvre le jour où on a besoin du
/// rapport.
#[derive(Serialize, Deserialize, Clone)]
pub struct CrashReport {
    /// Identifiant local (nom du fichier).
    pub id: String,
    pub instance_id: String,
    pub instance_name: String,

    pub signature: String,
    pub title: String,
    /// exit | crash_report | oom | loader | launcher
    pub kind: String,
    pub exit_code: Option<i32>,

    pub launcher_version: String,
    pub os: String,
    pub os_version: Option<String>,
    pub arch: String,
    pub cpu: Option<String>,
    pub gpu: Option<String>,
    pub ram_total_mb: Option<i64>,

    pub mc_version: String,
    pub loader: String,
    pub java_version: Option<String>,
    pub java_path: Option<String>,
    pub ram_alloc_mb: Option<i32>,
    pub jvm_args: Vec<String>,
    pub mods: Vec<ModEntry>,
    pub uptime_ms: i64,

    pub stack_trace: Option<String>,
    pub log_tail: String,
    pub occurred_at: DateTime<Utc>,

    /// Renseignés une fois le rapport envoyé — c'est ce qui permet d'aller
    /// relire son statut côté serveur.
    #[serde(default)]
    pub sent_id: Option<String>,
    #[serde(default)]
    pub sent_public_id: Option<String>,
    #[serde(default)]
    pub sent_at: Option<DateTime<Utc>>,
}

// ── Capture pendant le lancement ─────────────────────────────────────────────

/// Le contexte d'un lancement en cours, et la fenêtre glissante de son
/// journal. Partagé entre les deux pompes de sortie (stdout, stderr) et le
/// code qui attend la fin du processus.
pub struct LaunchWatch {
    pub instance_id: String,
    pub instance_name: String,
    pub mc_version: String,
    pub loader: String,
    pub game_dir: PathBuf,
    pub java_path: String,
    pub java_version: Option<String>,
    pub ram_alloc_mb: u32,
    pub jvm_args: Vec<String>,
    pub started_at: DateTime<Utc>,
    started: std::time::Instant,
    lines: Mutex<VecDeque<String>>,
}

impl LaunchWatch {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        instance_id: String,
        instance_name: String,
        mc_version: String,
        loader: String,
        game_dir: PathBuf,
        java_path: String,
        java_version: Option<String>,
        ram_alloc_mb: u32,
        jvm_args: Vec<String>,
    ) -> Self {
        Self {
            instance_id,
            instance_name,
            mc_version,
            loader,
            game_dir,
            java_path,
            java_version,
            ram_alloc_mb,
            // Nettoyés à l'entrée, pas à l'envoi : le jeton de session passe
            // dans la ligne de commande du jeu, et un rapport qui dort sur un
            // disque ne doit pas le contenir non plus.
            jvm_args: jvm_args.iter().map(|a| redact(a)).collect(),
            started_at: Utc::now(),
            started: std::time::Instant::now(),
            lines: Mutex::new(VecDeque::with_capacity(LOG_LINES)),
        }
    }

    /// Une ligne de sortie du jeu. Appelée depuis les pompes, sur le chemin
    /// chaud : rien de coûteux ici, juste un push borné.
    pub fn record(&self, line: &str) {
        let Ok(mut lines) = self.lines.lock() else { return };
        if lines.len() == LOG_LINES {
            lines.pop_front();
        }
        lines.push_back(redact(line));
    }

    fn log_tail(&self) -> String {
        self.lines.lock().map(|l| l.iter().cloned().collect::<Vec<_>>().join("\n")).unwrap_or_default()
    }
}

// ── Reconnaissance de la cause ───────────────────────────────────────────────

/// Le mot qui trahit un manque de mémoire. Pas une exception comme les
/// autres : la trace pointe un endroit au hasard du code, jamais la cause, et
/// la réponse à donner (augmenter la RAM allouée) n'a rien à voir avec elle.
fn is_oom(log: &str) -> bool {
    log.contains("OutOfMemoryError") || log.contains("Could not reserve enough space for") || log.contains("There is insufficient memory")
}

/// Échec du chargeur de mods avant même que le jeu démarre : Fabric et
/// NeoForge le disent explicitement, et ce n'est pas un bug du jeu mais une
/// liste de mods qui ne tient pas debout.
fn is_loader_failure(log: &str) -> bool {
    const MARKERS: [&str; 6] = [
        "Incompatible mods found",
        "Mod resolution failed",
        "ModResolutionException",
        "Missing or unsupported mandatory dependencies",
        "Mixin apply failed",
        "MixinApplyError",
    ];
    MARKERS.iter().any(|m| log.contains(m))
}

/// La machine Java elle-même n'a pas démarré, ou n'est pas la bonne.
///
/// Un cas à part, et c'est tout l'intérêt de le distinguer : ce n'est ni le
/// jeu, ni les mods, et aucun rapport de plantage n'existe — la JVM s'arrête
/// avant d'avoir chargé quoi que ce soit. L'utilisateur voit deux lignes
/// d'anglais incompréhensibles (« could not find java.dll ») et n'a aucune
/// raison de deviner que la réponse est dans les réglages Java de son
/// instance. C'est précisément là que l'écran l'envoie.
///
/// Les trois premiers marqueurs sont ceux d'un lanceur Java qui ne trouve pas
/// sa bibliothèque de machine virtuelle — installation incomplète ou
/// déplacée. Le dernier est l'inverse : la JVM a bien démarré, mais elle est
/// trop ancienne pour les classes du jeu. Deux causes, un même remède.
///
/// Partagée avec l'essai de compatibilité (`minecraft::compat`), qui lit le
/// même journal avec les mêmes yeux : une JVM qui ne démarre pas n'est pas un
/// problème de mods, et il n'y a aucune raison que les deux écrans tombent
/// d'accord sur le reste mais pas sur ce cas-là.
pub(crate) fn is_java_failure(log: &str) -> bool {
    const MARKERS: [&str; 5] = [
        "could not find java.dll",
        "Could not find Java SE Runtime Environment",
        "Error: opening registry key",
        "UnsupportedClassVersionError",
        "has been compiled by a more recent version of the Java Runtime",
    ];
    let lower = log.to_lowercase();
    MARKERS.iter().any(|m| lower.contains(&m.to_lowercase()))
}

/// La première ligne qui ressemble à une exception Java.
fn exception_line(log: &str) -> Option<&str> {
    log.lines().map(str::trim).find(|l| {
        let head = l.strip_prefix("Caused by: ").unwrap_or(l);
        let class = head.split([':', ' ']).next().unwrap_or("");
        class.contains('.')
            && (class.ends_with("Exception") || class.ends_with("Error"))
            && class.chars().all(|c| c.is_alphanumeric() || matches!(c, '.' | '_' | '$'))
    })
}

/// Empreinte de la cause : même plantage = même empreinte, sur toutes les
/// machines. Volontairement construite sur la classe d'exception et les
/// premières lignes d'appel SANS leurs numéros de ligne — un même bug reste
/// le même bug après une version de mod qui décale le fichier de trois
/// lignes, et c'est le groupement qui compte côté back-office.
///
/// Le message de l'exception est écarté : il contient presque toujours un
/// chemin, un identifiant ou un nombre qui change à chaque plantage et qui
/// ferait une empreinte différente par joueur.
pub fn signature(loader: &str, kind: &str, trace: Option<&str>, exit_code: Option<i32>) -> String {
    if kind == "oom" {
        return "oom:heap".to_string();
    }
    let material = trace.and_then(|t| {
        let line = exception_line(t)?;
        let head = line.strip_prefix("Caused by: ").unwrap_or(line);
        let class = head.split([':', ' ']).next().unwrap_or("").to_string();
        let frames: Vec<String> = t
            .lines()
            .map(str::trim)
            .filter_map(|l| l.strip_prefix("at "))
            // « net.minecraft.Foo.bar(Foo.java:42) » → « net.minecraft.Foo.bar »
            .map(|f| f.split('(').next().unwrap_or(f).trim().to_string())
            .take(4)
            .collect();
        if class.is_empty() && frames.is_empty() {
            return None;
        }
        Some(format!("{class}|{}", frames.join("|")))
    });

    match material {
        Some(m) => {
            let digest = Sha256::digest(m.as_bytes());
            format!("{loader}:{}", hex12(&digest))
        }
        // Rien d'exploitable : l'empreinte ne vaut que par le code de sortie.
        // Elle groupera large, ce qui reste mieux qu'un rapport isolé.
        None => format!("{loader}:exit{}", exit_code.unwrap_or(-1)),
    }
}

fn hex12(bytes: &[u8]) -> String {
    bytes.iter().take(6).map(|b| format!("{b:02x}")).collect()
}

/// Titre lisible : ce que la personne lit dans sa liste, et ce que l'équipe
/// voit en premier dans le back-office.
fn title_for(kind: &str, trace: Option<&str>, log: &str, exit_code: Option<i32>) -> String {
    if kind == "oom" {
        return "Mémoire insuffisante (OutOfMemoryError)".to_string();
    }
    // Un titre écrit pour être lu, et pas la ligne brute de la JVM : « could
    // not find java.dll » ne dit rien à qui ne connaît pas Java, alors que
    // c'est l'un des rares plantages qui se répare en trois clics.
    if kind == "java" {
        return "Java n'a pas pu démarrer".to_string();
    }
    // « Description: » du rapport de Minecraft : écrit pour être lu, souvent
    // plus parlant que la classe d'exception.
    if let Some(t) = trace {
        if let Some(d) = t.lines().find_map(|l| l.trim().strip_prefix("Description: ")) {
            if !d.trim().is_empty() {
                return d.trim().chars().take(200).collect();
            }
        }
    }
    if let Some(line) = trace.and_then(exception_line).or_else(|| exception_line(log)) {
        return line.chars().take(200).collect();
    }
    match exit_code {
        Some(c) => format!("Le jeu s'est arrêté avec le code {c}"),
        None => "Le jeu s'est arrêté de façon inattendue".to_string(),
    }
}

// ── Pièces du rapport ────────────────────────────────────────────────────────

/// Le rapport que Minecraft écrit lui-même, s'il l'a fait pendant CETTE
/// session. Le filtre sur la date compte : un dossier `crash-reports/` garde
/// des mois d'historique, et joindre le plantage de la semaine dernière
/// enverrait l'équipe sur une fausse piste.
fn game_crash_report(game_dir: &Path, since: DateTime<Utc>) -> Option<String> {
    let dir = game_dir.join("crash-reports");
    let entries = std::fs::read_dir(dir).ok()?;
    let mut best: Option<(std::time::SystemTime, PathBuf)> = None;
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("txt") {
            continue;
        }
        let Ok(modified) = entry.metadata().and_then(|m| m.modified()) else { continue };
        // Une seconde de marge : l'horodatage du fichier et l'horloge du
        // launcher ne viennent pas de la même source.
        if DateTime::<Utc>::from(modified) + chrono::Duration::seconds(1) < since {
            continue;
        }
        if best.as_ref().is_none_or(|(t, _)| modified > *t) {
            best = Some((modified, path));
        }
    }
    let (_, path) = best?;
    let content = std::fs::read_to_string(&path).ok()?;
    Some(redact(&content).chars().take(STACK_MAX).collect())
}

/// La trace reconstituée depuis la sortie du jeu, quand Minecraft n'a pas eu
/// le temps d'écrire son propre rapport (plantage de la JVM, échec du loader,
/// crash natif). On part de la première ligne d'exception et on garde tout
/// ce qui suit.
fn trace_from_log(log: &str) -> Option<String> {
    let start = log.lines().position(|l| {
        let l = l.trim();
        exception_line(l).is_some() || l.starts_with("# A fatal error has been detected")
    })?;
    let trace: String = log.lines().skip(start).collect::<Vec<_>>().join("\n");
    (!trace.trim().is_empty()).then(|| trace.chars().take(STACK_MAX).collect())
}

/// Carte graphique. Deux sources, dans cet ordre : celle que le jeu a
/// RÉELLEMENT utilisée (il l'écrit dans son journal et dans son rapport de
/// plantage), puis le pilote déclaré par Windows. La première est la bonne
/// sur un portable à deux cartes, où l'écart entre les deux EST souvent le
/// problème.
fn gpu(log: &str, trace: Option<&str>) -> Option<String> {
    // Les marqueurs sont cherchés N'IMPORTE OÙ dans la ligne, pas à son
    // début : une ligne de journal commence toujours par son horodatage et
    // son thread (« [12:04:31] [Render thread/INFO]: OpenGL: … »).
    for text in [trace.unwrap_or(""), log] {
        for line in text.lines() {
            for marker in ["Graphics card #0 name: ", "GL_RENDERER: ", "OpenGL: "] {
                let Some(at) = line.find(marker) else { continue };
                let value = &line[at + marker.len()..];
                // « OpenGL: <carte> GL version 4.6.0 … » : la version du
                // pilote suit sur la même ligne, elle ne fait pas partie du nom.
                let value = value.split(" GL version").next().unwrap_or(value).trim();
                if !value.is_empty() {
                    return Some(value.chars().take(150).collect());
                }
            }
        }
    }
    gpu_from_system()
}

#[cfg(target_os = "windows")]
fn gpu_from_system() -> Option<String> {
    // Le registre plutôt que WMI : la réponse est immédiate, alors qu'un
    // `Get-CimInstance` demande une seconde de PowerShell — pendant laquelle
    // on fait attendre quelqu'un qui vient déjà de perdre sa partie.
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    let out = std::process::Command::new("reg")
        .args([
            "query",
            r"HKLM\SYSTEM\CurrentControlSet\Control\Class\{4d36e968-e325-11ce-bfc1-08002be10318}\0000",
            "/v",
            "DriverDesc",
        ])
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .ok()?;
    let text = String::from_utf8_lossy(&out.stdout);
    let value = text.lines().find_map(|l| l.trim().strip_prefix("DriverDesc"))?;
    let name = value.split_once("REG_SZ")?.1.trim();
    (!name.is_empty()).then(|| name.chars().take(150).collect())
}

#[cfg(not(target_os = "windows"))]
fn gpu_from_system() -> Option<String> {
    None
}

/// Les mods de l'instance, avec leur état et leur taille. La liste complète :
/// c'est presque toujours elle la coupable, et savoir qu'un mod est désactivé
/// change la lecture de la trace.
fn mods_of(instance_id: &str) -> Vec<ModEntry> {
    let dir = crate::commands::instance::crud::instance_mods_dir(instance_id);
    let Ok(entries) = std::fs::read_dir(dir) else { return Vec::new() };
    let mut mods: Vec<ModEntry> = entries
        .flatten()
        .filter_map(|e| {
            let name = e.file_name().to_str()?.to_string();
            let lower = name.to_ascii_lowercase();
            let enabled = if lower.ends_with(".jar") {
                true
            } else if lower.ends_with(".jar.disabled") {
                false
            } else {
                return None;
            };
            let size = e.metadata().ok().map(|m| m.len() as i64);
            Some(ModEntry { name, enabled, size })
        })
        .collect();
    mods.sort_by(|a, b| a.name.cmp(&b.name));
    mods
}

// ── Construction ─────────────────────────────────────────────────────────────

/// Décide s'il y a plantage et, le cas échéant, construit le rapport.
///
/// Deux signaux, pas un : le code de sortie (une JVM qui meurt mal ne rend
/// jamais 0) ET la présence d'un rapport écrit par le jeu pendant la session.
/// Le second existe parce que Minecraft rend parfois 0 après avoir pourtant
/// planté proprement — la fermeture « normale » qui suit l'écran de crash.
pub fn build(watch: &LaunchWatch, exit_code: Option<i32>, launcher_version: &str) -> Option<CrashReport> {
    let log = watch.log_tail();
    let game_report = game_crash_report(&watch.game_dir, watch.started_at);
    let clean_exit = exit_code == Some(0);
    if clean_exit && game_report.is_none() {
        return None;
    }

    let trace = game_report.or_else(|| trace_from_log(&log));
    let scan = format!("{}\n{}", trace.as_deref().unwrap_or(""), log);
    // L'ordre est celui du diagnostic : une JVM qui n'a pas démarré se
    // reconnaît à des messages très particuliers, et aucun des autres genres
    // ne peut les produire. La tester en premier évite qu'un
    // `UnsupportedClassVersionError` parte en « plantage du jeu », où rien ne
    // mènerait à la bonne page.
    let kind = if is_java_failure(&scan) {
        "java"
    } else if is_oom(&scan) {
        "oom"
    } else if is_loader_failure(&scan) {
        "loader"
    } else if trace.is_some() {
        "crash_report"
    } else {
        "exit"
    };

    let mut sys = sysinfo::System::new();
    sys.refresh_memory();
    sys.refresh_cpu_usage();

    Some(CrashReport {
        id: uuid::Uuid::new_v4().to_string(),
        instance_id: watch.instance_id.clone(),
        instance_name: watch.instance_name.clone(),
        signature: signature(&watch.loader, kind, trace.as_deref(), exit_code),
        title: title_for(kind, trace.as_deref(), &log, exit_code),
        kind: kind.to_string(),
        exit_code,
        launcher_version: launcher_version.to_string(),
        os: std::env::consts::OS.to_string(),
        os_version: sysinfo::System::long_os_version(),
        arch: std::env::consts::ARCH.to_string(),
        cpu: sys.cpus().first().map(|c| format!("{} ({} cœurs logiques)", c.brand().trim(), sys.cpus().len())),
        gpu: gpu(&log, trace.as_deref()),
        ram_total_mb: Some((sys.total_memory() / 1024 / 1024) as i64),
        mc_version: watch.mc_version.clone(),
        loader: watch.loader.clone(),
        java_version: watch.java_version.clone(),
        java_path: Some(watch.java_path.clone()),
        ram_alloc_mb: Some(watch.ram_alloc_mb as i32),
        jvm_args: watch.jvm_args.clone(),
        mods: mods_of(&watch.instance_id),
        uptime_ms: watch.started.elapsed().as_millis() as i64,
        stack_trace: trace,
        log_tail: log,
        occurred_at: Utc::now(),
        sent_id: None,
        sent_public_id: None,
        sent_at: None,
    })
}

/// Ce qu'on sait d'une session dont le launcher n'a pas vu la fin.
pub struct Recovered {
    pub instance_id: String,
    pub instance_name: String,
    pub mc_version: String,
    pub loader: String,
    pub java_version: Option<String>,
    pub jvm_args: Vec<String>,
    pub ram_alloc_mb: Option<i32>,
    pub started_at: DateTime<Utc>,
    pub ended_at: DateTime<Utc>,
}

/// Reconstruit un rapport après coup, quand le launcher n'était plus là au
/// moment du plantage (fermé, tué, coupure de courant).
///
/// Il est forcément incomplet : le journal vivait dans la mémoire du launcher
/// et est parti avec lui. Ce qui reste — la trace que le jeu a écrite lui-même,
/// les mods, la machine, la configuration JVM relue en base — suffit le plus
/// souvent. Le rapport le dit au lieu de faire comme si de rien n'était : un
/// journal vide et un journal perdu ne se lisent pas pareil.
///
/// Rend `None` quand le jeu n'a laissé aucune trace : une session orpheline
/// n'est pas un plantage, c'est le plus souvent quelqu'un qui a fermé son PC.
pub fn build_recovered(info: Recovered, launcher_version: &str) -> Option<CrashReport> {
    let game_dir = crate::commands::instance::crud::instance_dir(&info.instance_id);
    let trace = game_crash_report(&game_dir, info.started_at)?;

    let kind = if is_oom(&trace) {
        "oom"
    } else if is_loader_failure(&trace) {
        "loader"
    } else {
        "crash_report"
    };

    let mut sys = sysinfo::System::new();
    sys.refresh_memory();
    sys.refresh_cpu_usage();

    Some(CrashReport {
        id: uuid::Uuid::new_v4().to_string(),
        instance_id: info.instance_id.clone(),
        instance_name: info.instance_name,
        signature: signature(&info.loader, kind, Some(&trace), None),
        title: title_for(kind, Some(&trace), "", None),
        kind: kind.to_string(),
        exit_code: None,
        launcher_version: launcher_version.to_string(),
        os: std::env::consts::OS.to_string(),
        os_version: sysinfo::System::long_os_version(),
        arch: std::env::consts::ARCH.to_string(),
        cpu: sys.cpus().first().map(|c| format!("{} ({} cœurs logiques)", c.brand().trim(), sys.cpus().len())),
        gpu: gpu("", Some(&trace)),
        ram_total_mb: Some((sys.total_memory() / 1024 / 1024) as i64),
        mc_version: info.mc_version,
        loader: info.loader,
        java_version: info.java_version,
        java_path: None,
        ram_alloc_mb: info.ram_alloc_mb,
        jvm_args: info.jvm_args,
        mods: mods_of(&info.instance_id),
        uptime_ms: (info.ended_at - info.started_at).num_milliseconds().max(0),
        stack_trace: Some(trace),
        log_tail: "[Le launcher était fermé au moment du plantage : le journal \
                   de la session n'a pas pu être conservé. La trace ci-dessus \
                   vient du rapport écrit par le jeu lui-même.]"
            .to_string(),
        occurred_at: info.ended_at,
        sent_id: None,
        sent_public_id: None,
        sent_at: None,
    })
}

// ── Stockage local ───────────────────────────────────────────────────────────

pub fn dir() -> PathBuf {
    crate::paths::root().join("crash-reports")
}

fn path_of(id: &str) -> PathBuf {
    dir().join(format!("{id}.json"))
}

/// Écrit le rapport et fait le ménage. Une erreur d'écriture n'est jamais
/// fatale : on vient déjà de perdre une session, ce n'est pas le moment de
/// remonter une deuxième panne.
pub fn store(report: &CrashReport) -> std::io::Result<()> {
    let dir = dir();
    std::fs::create_dir_all(&dir)?;
    std::fs::write(path_of(&report.id), serde_json::to_vec_pretty(report)?)?;
    prune();
    Ok(())
}

fn prune() {
    let Ok(entries) = std::fs::read_dir(dir()) else { return };
    let mut files: Vec<(std::time::SystemTime, PathBuf)> = entries
        .flatten()
        .filter(|e| e.path().extension().and_then(|x| x.to_str()) == Some("json"))
        .filter_map(|e| Some((e.metadata().ok()?.modified().ok()?, e.path())))
        .collect();
    if files.len() <= KEEP {
        return;
    }
    files.sort_by(|a, b| b.0.cmp(&a.0));
    for (_, path) in files.into_iter().skip(KEEP) {
        let _ = std::fs::remove_file(path);
    }
}

/// Tous les rapports du disque, du plus récent au plus ancien. Un fichier
/// illisible (rapport d'une version antérieure du launcher, écriture coupée)
/// est ignoré, pas fatal.
pub fn list() -> Vec<CrashReport> {
    let Ok(entries) = std::fs::read_dir(dir()) else { return Vec::new() };
    let mut reports: Vec<CrashReport> = entries
        .flatten()
        .filter_map(|e| serde_json::from_slice(&std::fs::read(e.path()).ok()?).ok())
        .collect();
    reports.sort_by(|a: &CrashReport, b| b.occurred_at.cmp(&a.occurred_at));
    reports
}

pub fn get(id: &str) -> Option<CrashReport> {
    serde_json::from_slice(&std::fs::read(path_of(id)).ok()?).ok()
}

pub fn remove(id: &str) -> std::io::Result<()> {
    match std::fs::remove_file(path_of(id)) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        other => other,
    }
}

/// Note l'envoi dans le fichier local : c'est ce qui permet de retrouver le
/// statut donné par l'équipe, et d'éviter un second envoi du même rapport.
pub fn mark_sent(id: &str, remote_id: &str, public_id: &str) -> std::io::Result<()> {
    let Some(mut report) = get(id) else { return Ok(()) };
    report.sent_id = Some(remote_id.to_string());
    report.sent_public_id = Some(public_id.to_string());
    report.sent_at = Some(Utc::now());
    std::fs::write(path_of(id), serde_json::to_vec_pretty(&report)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    const TRACE: &str = "---- Minecraft Crash Report ----\n\
        Description: Rendering overlay\n\
        java.lang.NullPointerException: Cannot invoke \"net.minecraft.Foo.bar()\"\n\
        \tat net.minecraft.client.Screen.render(Screen.java:128)\n\
        \tat net.minecraft.client.Minecraft.run(Minecraft.java:942)\n";

    /// Les deux lignes que Windows écrit quand la JVM ne trouve pas sa
    /// bibliothèque — c'est tout ce que l'utilisateur voit, et c'est à partir
    /// de là qu'il faut l'amener aux réglages Java.
    #[test]
    fn une_jvm_qui_ne_demarre_pas_est_reconnue() {
        assert!(is_java_failure("Error: could not find java.dll"));
        assert!(is_java_failure("Error: Could not find Java SE Runtime Environment."));
        assert!(is_java_failure("Error: opening registry key 'Software\\JavaSoft\\Java Runtime Environment'"));
    }

    /// L'autre moitié du même problème : la JVM a démarré, mais elle est trop
    /// ancienne pour les classes du jeu. Même remède, donc même genre.
    #[test]
    fn une_jvm_trop_ancienne_est_reconnue() {
        assert!(is_java_failure(
            "java.lang.UnsupportedClassVersionError: net/minecraft/client/main/Main has been compiled by a more recent version of the Java Runtime"
        ));
    }

    /// Un plantage ordinaire ne doit pas être pris pour un problème de Java :
    /// l'écran enverrait vers une page qui n'y peut rien.
    #[test]
    fn un_plantage_ordinaire_n_est_pas_un_probleme_de_java() {
        assert!(!is_java_failure(TRACE));
        assert!(!is_java_failure("java.lang.OutOfMemoryError: Java heap space"));
        assert!(!is_java_failure("Incompatible mods found"));
        assert!(!is_java_failure(""));
    }

    #[test]
    fn same_cause_gives_the_same_signature_across_machines() {
        let other_machine = TRACE.replace("Screen.java:128", "Screen.java:131");
        assert_eq!(
            signature("fabric", "crash_report", Some(TRACE), Some(1)),
            signature("fabric", "crash_report", Some(&other_machine), Some(1)),
            "un décalage de numéro de ligne n'est pas une autre cause",
        );
    }

    #[test]
    fn different_causes_do_not_collide() {
        let other = TRACE.replace("NullPointerException", "IllegalStateException");
        assert_ne!(signature("fabric", "crash_report", Some(TRACE), Some(1)), signature("fabric", "crash_report", Some(&other), Some(1)));
    }

    #[test]
    fn memory_is_its_own_family() {
        assert_eq!(signature("forge", "oom", Some(TRACE), Some(1)), "oom:heap");
        assert!(is_oom("java.lang.OutOfMemoryError: Java heap space"));
        assert!(!is_oom("java.lang.NullPointerException"));
    }

    #[test]
    fn the_title_prefers_what_the_game_wrote_for_humans() {
        assert_eq!(title_for("crash_report", Some(TRACE), "", Some(1)), "Rendering overlay");
        assert!(title_for("exit", None, "", Some(134)).contains("134"));
        assert_eq!(
            title_for("crash_report", None, "java.lang.NoSuchMethodError: blah\n", Some(1)),
            "java.lang.NoSuchMethodError: blah",
        );
    }

    #[test]
    fn loader_failures_are_told_apart_from_game_crashes() {
        assert!(is_loader_failure("[main] ERROR: Incompatible mods found!"));
        assert!(!is_loader_failure("[main] INFO: Loading 42 mods"));
    }

    #[test]
    fn the_gpu_the_game_used_wins_over_the_driver() {
        let log = "[Render thread] OpenGL: NVIDIA GeForce RTX 3060/PCIe/SSE2 GL version 4.6.0";
        assert_eq!(gpu(log, None).as_deref(), Some("NVIDIA GeForce RTX 3060/PCIe/SSE2"));
        assert_eq!(gpu("", Some("Graphics card #0 name: AMD Radeon RX 6600\n")).as_deref(), Some("AMD Radeon RX 6600"));
    }

    #[test]
    fn a_trace_is_rebuilt_when_the_game_wrote_nothing() {
        let log = "[main] INFO: démarrage\njava.lang.NoClassDefFoundError: org/foo/Bar\n\tat org.foo.Init.start(Init.java:12)";
        let trace = trace_from_log(log).unwrap();
        assert!(trace.starts_with("java.lang.NoClassDefFoundError"));
        assert!(!trace.contains("démarrage"), "le bruit d'avant le plantage n'est pas la trace");
        assert!(trace_from_log("[main] INFO: tout va bien").is_none());
    }

    #[test]
    fn the_log_window_slides_and_keeps_the_end() {
        let watch = LaunchWatch::new(
            "i".into(), "Instance".into(), "1.21.1".into(), "fabric".into(),
            PathBuf::from("."), "java".into(), None, 4096, vec![],
        );
        for i in 0..(LOG_LINES + 50) {
            watch.record(&format!("ligne {i}"));
        }
        let tail = watch.log_tail();
        assert!(tail.contains(&format!("ligne {}", LOG_LINES + 49)));
        assert!(!tail.contains("ligne 0\n"));
        assert_eq!(tail.lines().count(), LOG_LINES);
    }

    #[test]
    fn secrets_never_reach_the_stored_report() {
        let watch = LaunchWatch::new(
            "i".into(), "Instance".into(), "1.21.1".into(), "fabric".into(),
            PathBuf::from("."), "java".into(), None, 4096,
            vec!["-Xmx4G".into(), "yfr_secret_refresh_token".into()],
        );
        watch.record("connecté avec joueur@example.com");
        assert!(watch.jvm_args.iter().any(|a| a.contains("[jeton masqué]")));
        assert!(watch.log_tail().contains("[adresse masquée]"));
    }
}
