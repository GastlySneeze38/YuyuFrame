//! Essai de compatibilité : on lance vraiment la JVM, et on la coupe dès que
//! le jeu est debout.
//!
//! Tout le reste du launcher raisonne sur des métadonnées — la version
//! publiée par le loader, la plage de versions déclarée par un mod, le SHA1
//! d'un fichier. Ça suffit à savoir si une installation est **saine**, jamais
//! à savoir si elle **démarre** : la résolution de dépendances de Fabric, la
//! table des dépendances obligatoires de Forge et l'application des Mixins ne
//! se jouent qu'au démarrage, dans la JVM, avec les mods réellement présents.
//! Les reproduire ici donnerait une deuxième implémentation, toujours en
//! retard d'une version de loader sur la vraie.
//!
//! D'où le choix : on démarre le jeu exactement comme un lancement normal
//! (même classpath, mêmes drapeaux, même loader — voir `download_and_launch`,
//! qui ne sait pas qu'il s'agit d'un essai), et on l'arrête au premier
//! marqueur de réussite. Ce qui est testé est donc ce qui sera joué, par
//! construction.
//!
//! Ce qu'on n'a pas : un mode sans fenêtre. Minecraft n'en a pas. La fenêtre
//! du jeu apparaît donc brièvement avant d'être refermée, et l'interface le
//! dit plutôt que de le laisser surprendre.
//!
//! ── Lire l'échec ───────────────────────────────────────────────────────────
//! Quand ça rate, le loader a déjà écrit le diagnostic : il nomme le mod, la
//! dépendance manquante et la version attendue. `diagnose` ne fait que le
//! traduire en gestes que le launcher sait proposer — changer la version du
//! jeu, épingler une version de loader, désactiver un mod, en installer un
//! autre. Ce qu'il n'a pas compris reste affiché **tel quel** : une ligne de
//! loader non reconnue vaut mieux qu'un « problème inconnu ».

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

use serde::Serialize;
use tauri::Emitter;
use tokio::sync::watch;

/// Temps laissé à la JVM, à partir de sa première ligne de journal (donc hors
/// téléchargements). Large : un modpack de 300 mods met plusieurs minutes à
/// atteindre son menu principal sur une machine modeste, et conclure « ça ne
/// démarre pas » parce qu'on n'a pas attendu serait le pire des verdicts.
pub const MAX_RUN_SECS: u64 = 420;

/// Lignes gardées. Au-delà de la fenêtre, on perd le début du journal, où
/// vivent justement les erreurs de résolution — d'où une fenêtre plus large
/// que celle d'un rapport de plantage.
const LOG_LINES: usize = 2000;

/// Marqueurs de réussite : le jeu est **debout**, donc tout ce qui pouvait
/// refuser de se charger s'est chargé.
///
/// Volontairement tardifs. S'arrêter à la fin de la résolution de dépendances
/// attraperait les mods manquants mais pas les Mixins, qui s'appliquent au
/// chargement des classes du jeu, bien après — et c'est la deuxième cause
/// d'incompatibilité. Ces trois lignes sont écrites au tout dernier moment du
/// démarrage, juste avant ou pendant le menu principal :
/// `[YUYUFRAME_READY]` vient du client intégré (quand il est là),
/// le moteur sonore et le narrateur sont vanilla et existent depuis toujours.
const READY_MARKERS: [&str; 3] = [
    "[YUYUFRAME_READY]",
    "Sound engine started",
    "Narrator library for x64 successfully loaded",
];

/// Un geste que le launcher sait proposer pour régler le problème.
///
/// Il ne porte pas de texte : l'interface le rédige dans la langue de
/// l'utilisateur. Le Rust ne décide que de ce qui est possible.
#[derive(Serialize, Clone, Debug, PartialEq)]
pub struct Fix {
    /// `mc_version` (aller changer la version du jeu) · `loader_version`
    /// (épingler une version de loader) · `disable_mod` · `install_mod` ·
    /// `ram` · `java`.
    pub action: String,
    /// Ce que porte le geste : la version visée, le fichier du mod à
    /// désactiver, l'identifiant du mod à installer. Vide quand l'action
    /// n'en demande pas.
    pub value: String,
}

impl Fix {
    fn new(action: &str, value: impl Into<String>) -> Self {
        Self { action: action.into(), value: value.into() }
    }
}

/// Un problème lu dans le journal du loader.
#[derive(Serialize, Clone, Debug, PartialEq)]
pub struct Problem {
    /// `mc_mismatch` · `missing_dep` · `dep_version` · `loader_too_old` ·
    /// `mod_conflict` · `mixin` · `java` · `oom` · `unknown`.
    pub kind: String,
    /// Le mod en cause, tel que le loader le nomme. Vide si la ligne ne
    /// désigne personne.
    pub subject: String,
    /// Ce dont il a besoin (mod, `minecraft`, loader). Vide si sans objet.
    pub target: String,
    /// La version attendue, telle que le loader l'écrit. Vide si sans objet.
    pub expected: String,
    /// La ligne du loader, intacte. Jamais traduite : c'est la preuve, et
    /// c'est elle qu'on recopie dans une recherche ou un ticket.
    pub detail: String,
    pub fixes: Vec<Fix>,
}

impl Problem {
    fn new(kind: &str, detail: &str) -> Self {
        Self {
            kind: kind.into(),
            subject: String::new(),
            target: String::new(),
            expected: String::new(),
            detail: detail.trim().to_string(),
            fixes: Vec::new(),
        }
    }
    fn about(mut self, subject: impl Into<String>) -> Self {
        self.subject = subject.into();
        self
    }
    fn needing(mut self, target: impl Into<String>, expected: impl Into<String>) -> Self {
        self.target = target.into();
        self.expected = expected.into();
        self
    }
    fn fix(mut self, fix: Fix) -> Self {
        self.fixes.push(fix);
        self
    }
}

// ── La sonde ─────────────────────────────────────────────────────────────────

/// Ce que le lancement remplit pendant qu'il tourne.
///
/// Elle est passée à `download_and_launch`, qui lui donne chaque ligne de
/// sortie. Deux responsabilités, et pas une de plus : garder le journal, et
/// couper la JVM dès que le jeu est debout — en empruntant le canal
/// d'annulation déjà en place (`cancel_launch`), plutôt qu'en ajoutant un
/// second chemin d'arrêt qui finirait par diverger du premier.
pub struct Probe {
    lines: Mutex<VecDeque<String>>,
    /// De quoi montrer l'essai pendant qu'il tourne. Son propre événement et
    /// pas `game_log` : celui-là appartient à la console de jeu, et les deux
    /// peuvent très bien tourner en même temps sur deux instances
    /// différentes.
    app: tauri::AppHandle,
    instance_id: String,
    reached: AtomicBool,
    started: AtomicBool,
    /// Instant de la première ligne — le départ du compte à rebours. Zéro
    /// tant que la JVM n'a rien écrit : les téléchargements ne doivent pas
    /// manger le temps laissé au jeu.
    start: Mutex<Option<std::time::Instant>>,
    timed_out: AtomicBool,
    stop: watch::Sender<bool>,
}

impl Probe {
    pub fn new(stop: watch::Sender<bool>, app: tauri::AppHandle, instance_id: String) -> Self {
        Self {
            lines: Mutex::new(VecDeque::with_capacity(LOG_LINES)),
            app,
            instance_id,
            reached: AtomicBool::new(false),
            started: AtomicBool::new(false),
            start: Mutex::new(None),
            timed_out: AtomicBool::new(false),
            stop,
        }
    }

    /// Une ligne de la JVM. Appelée depuis les pompes de sortie du lancement,
    /// donc en contexte synchrone et sur le chemin chaud : rien de coûteux ici.
    pub fn feed(&self, line: &str) {
        if !self.started.swap(true, Ordering::Relaxed) {
            *self.start.lock().unwrap() = Some(std::time::Instant::now());
        }
        {
            let mut lines = self.lines.lock().unwrap();
            if lines.len() == LOG_LINES {
                lines.pop_front();
            }
            lines.push_back(line.to_string());
        }
        let _ = self.app.emit(
            "compat_log",
            serde_json::json!({ "instance_id": &self.instance_id, "line": line }),
        );
        if is_ready_marker(line) && !self.reached.swap(true, Ordering::Relaxed) {
            tracing::info!("[Compat] jeu debout — arrêt de la JVM");
            let _ = self.stop.send(true);
        }
    }

    pub fn reached(&self) -> bool {
        self.reached.load(Ordering::Relaxed)
    }

    pub fn timed_out(&self) -> bool {
        self.timed_out.load(Ordering::Relaxed)
    }

    /// Le temps écoulé depuis la première ligne, `None` tant que la JVM n'a
    /// rien écrit.
    pub fn running_for(&self) -> Option<std::time::Duration> {
        self.start.lock().unwrap().map(|t| t.elapsed())
    }

    /// Coupe la JVM parce que le temps imparti est passé. Le verdict qui en
    /// découle n'est pas « incompatible » mais « on ne sait pas » : un
    /// démarrage très lent n'est pas un échec.
    pub fn give_up(&self) {
        self.timed_out.store(true, Ordering::Relaxed);
        let _ = self.stop.send(true);
    }

    pub fn log(&self) -> String {
        let lines = self.lines.lock().unwrap();
        lines.iter().cloned().collect::<Vec<_>>().join("\n")
    }
}

pub fn is_ready_marker(line: &str) -> bool {
    READY_MARKERS.iter().any(|m| line.contains(m))
}

// ── Garder la fenêtre du jeu hors de vue ─────────────────────────────────────

/// Masque les fenêtres du processus de jeu, tant qu'il tourne.
///
/// Minecraft n'a pas de mode sans affichage : pour aller jusqu'au menu
/// principal — donc jusqu'après l'application des Mixins — il faut bien que
/// GLFW crée sa fenêtre. On ne l'empêche pas, on la **cache**, par son
/// propriétaire : seules les fenêtres de haut niveau appartenant à ce PID sont
/// touchées, jamais celles du launcher ni d'une autre partie en cours.
///
/// Masquée n'est pas réduite : le jeu continue de dessiner et de charger
/// exactement comme s'il était visible, et c'est ce qui permet à l'essai de
/// rester un vrai démarrage plutôt qu'une simulation.
///
/// La surveillance est répétée et ne s'arrête qu'avec le processus : la
/// fenêtre est créée longtemps après le lancement de la JVM (chargement des
/// mods d'abord), et le jeu la réaffiche de lui-même en sortant de son écran
/// de chargement. Un seul passage au bon moment serait un pari.
pub async fn keep_hidden(pid: u32, stop: std::sync::Arc<AtomicBool>) {
    #[cfg(not(target_os = "windows"))]
    {
        // Ailleurs, l'essai reste correct — la fenêtre se voit, c'est tout.
        let _ = (pid, &stop);
    }
    #[cfg(target_os = "windows")]
    while !stop.load(Ordering::Relaxed) {
        windows_hide::hide_once(pid);
        // Assez court pour que la fenêtre n'ait pas le temps d'être vue,
        // assez espacé pour que le parcours des fenêtres du bureau ne coûte
        // rien : il se compte en microsecondes.
        tokio::time::sleep(std::time::Duration::from_millis(40)).await;
    }
}

#[cfg(target_os = "windows")]
mod windows_hide {
    use std::ffi::c_void;

    #[link(name = "user32")]
    extern "system" {
        fn EnumWindows(cb: extern "system" fn(*mut c_void, isize) -> i32, lparam: isize) -> i32;
        fn GetWindowThreadProcessId(hwnd: *mut c_void, pid: *mut u32) -> u32;
        fn ShowWindow(hwnd: *mut c_void, cmd: i32) -> i32;
        fn IsWindowVisible(hwnd: *mut c_void) -> i32;
    }

    const SW_HIDE: i32 = 0;

    /// Rappelée pour chaque fenêtre de haut niveau du bureau. Le PID visé
    /// voyage dans `lparam`, faute de pouvoir capturer quoi que ce soit : la
    /// signature est imposée par Win32.
    extern "system" fn visit(hwnd: *mut c_void, lparam: isize) -> i32 {
        let target = lparam as u32;
        let mut owner: u32 = 0;
        unsafe {
            GetWindowThreadProcessId(hwnd, &mut owner);
            // Le test de visibilité évite d'appeler ShowWindow quarante fois
            // par seconde sur une fenêtre déjà masquée.
            if owner == target && IsWindowVisible(hwnd) != 0 {
                ShowWindow(hwnd, SW_HIDE);
            }
        }
        1 // continuer l'énumération
    }

    pub fn hide_once(pid: u32) {
        unsafe {
            EnumWindows(visit, pid as isize);
        }
    }
}

// ── Lecture du journal ───────────────────────────────────────────────────────

/// Ce que le loader propose lui-même, quand il le propose.
///
/// Fabric écrit depuis la 0.15 un bloc « A potential solution has been
/// determined » qui nomme la version exacte à installer. Il est recopié tel
/// quel plutôt que réinterprété : personne ne connaît mieux que le loader la
/// version qui réglerait son propre refus.
pub fn loader_suggestions(log: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut taking = false;
    for raw in log.lines() {
        let line = strip_log_prefix(raw);
        if line.contains("potential solution has been determined")
            || line.contains("potential solutions have been determined")
        {
            taking = true;
            continue;
        }
        if !taking {
            continue;
        }
        let item = line.trim().trim_start_matches('-').trim();
        if item.is_empty() || !line.trim().starts_with('-') {
            // Le bloc s'arrête à la première ligne qui n'est plus une puce —
            // typiquement « Unmet dependency listing: », qu'on lit ailleurs.
            if !item.is_empty() {
                taking = false;
            }
            continue;
        }
        if !out.iter().any(|x| x == item) {
            out.push(item.to_string());
        }
    }
    out
}

/// Retire l'horodatage et la source d'une ligne de log Minecraft
/// (`[12:04:31] [main/ERROR]: …`), pour que les analyses ci-dessous ne
/// travaillent que sur le message. Rend la ligne telle quelle si elle n'a pas
/// cette forme — la sortie d'un loader qui plante avant log4j2 n'en a pas.
fn strip_log_prefix(line: &str) -> &str {
    match line.find("]: ") {
        Some(i) => &line[i + 3..],
        None => line,
    }
}

/// Le texte entre la première paire de délimiteurs, et ce qui suit.
fn between<'a>(s: &'a str, open: &str, close: &str) -> Option<(&'a str, &'a str)> {
    let start = s.find(open)? + open.len();
    let rest = &s[start..];
    let end = rest.find(close)?;
    Some((&rest[..end], &rest[end + close.len()..]))
}

/// Tout ce que le journal dit d'exploitable, dédoublonné.
///
/// L'ordre compte : les causes les plus précises d'abord. Un mod qui exige
/// une autre version de Minecraft explique à lui seul le reste du journal, et
/// l'afficher après trois erreurs de Mixin qu'il a provoquées noierait la
/// seule ligne utile.
pub fn diagnose(log: &str) -> Vec<Problem> {
    let mut out: Vec<Problem> = Vec::new();

    for raw in log.lines() {
        let line = strip_log_prefix(raw);
        if let Some(p) = fabric_dependency(line) {
            push(&mut out, p);
        } else if let Some(p) = forge_dependency(line) {
            push(&mut out, p);
        }
    }

    // Les Mixins ensuite : un échec d'application est presque toujours la
    // conséquence d'un mod prévu pour une autre version du jeu, donc une
    // information de deuxième rang par rapport à une dépendance non résolue.
    for raw in log.lines() {
        let line = strip_log_prefix(raw);
        if let Some(p) = mixin_failure(line) {
            push(&mut out, p);
        }
    }

    // Enfin ce qui ne concerne pas les mods mais la machine. Ces deux-là ne
    // sont pas des problèmes de compatibilité, mais ils empêchent le test de
    // conclure, et leur remède est à deux onglets d'ici.
    if crate::minecraft::crash::is_java_failure(log) {
        push(
            &mut out,
            Problem::new("java", &first_line_containing(log, &["UnsupportedClassVersionError", "java.dll", "Java SE Runtime"]).unwrap_or_default())
                .fix(Fix::new("java", "")),
        );
    }
    if log.contains("java.lang.OutOfMemoryError") {
        push(
            &mut out,
            Problem::new("oom", &first_line_containing(log, &["java.lang.OutOfMemoryError"]).unwrap_or_default())
                .fix(Fix::new("ram", "")),
        );
    }

    out
}

/// Deux lignes qui nomment le même mod pour la même raison sont le même
/// problème : un loader peut répéter son diagnostic plusieurs fois (une fois
/// à la résolution, une fois dans la trace d'exception).
fn push(out: &mut Vec<Problem>, p: Problem) {
    if out
        .iter()
        .any(|x| x.kind == p.kind && x.subject == p.subject && x.target == p.target)
    {
        return;
    }
    out.push(p);
}

fn first_line_containing(log: &str, needles: &[&str]) -> Option<String> {
    log.lines()
        .find(|l| needles.iter().any(|n| l.contains(n)))
        .map(|l| strip_log_prefix(l).trim().to_string())
}

/// Les identifiants que les loaders se donnent à eux-mêmes dans leurs listes
/// de dépendances. Ce ne sont pas des mods : exiger « fabricloader 0.16 »
/// demande d'épingler une version de loader, pas d'installer quelque chose.
const LOADER_IDS: [&str; 6] = [
    "fabricloader",
    "quilt_loader",
    "quiltloader",
    "forge",
    "neoforge",
    "minecraftforge",
];

fn kind_for(target: &str, missing: bool) -> &'static str {
    if target == "minecraft" {
        "mc_mismatch"
    } else if LOADER_IDS.contains(&target) {
        "loader_too_old"
    } else if target == "java" {
        "java"
    } else if missing {
        "missing_dep"
    } else {
        "dep_version"
    }
}

fn fixes_for(target: &str, expected: &str, missing: bool) -> Vec<Fix> {
    match kind_for(target, missing) {
        "mc_mismatch" => {
            let mut fixes = Vec::new();
            // La version n'est proposée que si elle en est une : Fabric écrit
            // aussi bien `1.20.1` que `1.20.x` ou `>=1.20 <1.21`, et un
            // bouton « passer en 1.20.x » mènerait à un menu déroulant où
            // cette entrée n'existe pas.
            if let Some(v) = exact_version(expected) {
                fixes.push(Fix::new("mc_version", v));
            } else {
                fixes.push(Fix::new("mc_version", ""));
            }
            fixes
        }
        "loader_too_old" => vec![Fix::new("loader_version", "")],
        "java" => vec![Fix::new("java", "")],
        "missing_dep" => vec![Fix::new("install_mod", target)],
        // Une dépendance présente à la mauvaise version se règle dans les
        // mods, pas ici : on ne sait pas laquelle des deux a tort.
        _ => vec![Fix::new("install_mod", target)],
    }
}

/// `version 1.20.1` → `1.20.1`. Rend `None` dès que ce n'est pas un numéro
/// franc (plage, joker, comparateur) — voir `fixes_for`.
fn exact_version(expected: &str) -> Option<String> {
    let v = expected
        .trim()
        .trim_start_matches("version ")
        .trim()
        .to_string();
    if v.is_empty() || v.len() > 24 {
        return None;
    }
    let ok = v
        .chars()
        .all(|c| c.is_ascii_digit() || c == '.' || c == '-' || c.is_ascii_lowercase())
        && v.chars().next().is_some_and(|c| c.is_ascii_digit())
        && !v.contains("x");
    ok.then_some(v)
}

/// Une ligne de la liste de dépendances non satisfaites de Fabric ou Quilt.
///
/// Les formes rencontrées (fabric-loader 0.14 → 0.17, quilt-loader 0.19+) :
/// ```text
/// - Mod 'Sodium' (sodium) 0.5.8 requires version 1.20.1 of minecraft, but only 1.21.4 is present!
/// - Mod 'Iris' (iris) 1.6.0 requires any version of sodium, which is missing!
/// - Mod 'X' (x) 1.0 requires version 0.16.0 or later of fabricloader, but only 0.15.11 is present!
/// - Mod 'A' (a) 1.0 is incompatible with any version of b, but 2.0 is present!
/// ```
fn fabric_dependency(line: &str) -> Option<Problem> {
    let trimmed = line.trim();
    let body = trimmed.strip_prefix('-').unwrap_or(trimmed).trim();
    if !body.starts_with("Mod '") {
        return None;
    }
    let (name, after_name) = between(body, "Mod '", "'")?;
    let id = between(after_name, "(", ")").map(|(id, _)| id).unwrap_or("");

    // Une incompatibilité déclarée se lit à l'envers d'une dépendance : les
    // deux mods sont là, et c'est leur cohabitation qui est refusée.
    if let Some(i) = body.find(" is incompatible with ").or_else(|| body.find(" conflicts with ")) {
        let tail = &body[i..];
        let other = tail
            .split(" of ")
            .nth(1)
            .or_else(|| tail.split(" with ").nth(1))
            .unwrap_or("")
            .split(&[',', '!'][..])
            .next()
            .unwrap_or("")
            .trim()
            .trim_matches('\'')
            .to_string();
        let subject = if id.is_empty() { name.to_string() } else { id.to_string() };
        let mut p = Problem::new("mod_conflict", trimmed)
            .about(subject.clone())
            .needing(other.clone(), "")
            .fix(Fix::new("disable_mod", subject));
        if !other.is_empty() {
            p = p.fix(Fix::new("disable_mod", other));
        }
        return Some(p);
    }

    let requires = body.find(" requires ")? + " requires ".len();
    let tail = &body[requires..];
    // `tail` vaut par exemple « version 1.20.1 of minecraft, but only 1.21.4
    // is present! » : la dépendance est le dernier mot avant la virgule, et
    // tout ce qui précède « of » est la version attendue.
    let head = tail.split(',').next().unwrap_or(tail);
    let of = head.rfind(" of ")?;
    let expected = head[..of].trim().to_string();
    let target = head[of + 4..].trim().trim_end_matches('!').to_string();
    if target.is_empty() {
        return None;
    }
    let missing = tail.contains("which is missing") || tail.contains("is not present");

    let subject = if id.is_empty() { name.to_string() } else { id.to_string() };
    let mut p = Problem::new(kind_for(&target, missing), trimmed)
        .about(subject.clone())
        .needing(target.clone(), expected.clone());
    for fix in fixes_for(&target, &expected, missing) {
        p = p.fix(fix);
    }
    // Désactiver le mod fautif est toujours une sortie, et parfois la seule
    // (un mod abandonné sur une vieille version du jeu).
    if p.kind != "java" {
        p = p.fix(Fix::new("disable_mod", subject));
    }
    Some(p)
}

/// Une ligne de la table des dépendances obligatoires de Forge et NeoForge :
/// ```text
/// Mod ID: 'jei', Requested by: 'mekanism', Expected range: '[15.2.0.22,)', Actual version: '[MISSING]'
/// ```
fn forge_dependency(line: &str) -> Option<Problem> {
    if !line.contains("Mod ID: '") || !line.contains("Expected range: '") {
        return None;
    }
    let (target, _) = between(line, "Mod ID: '", "'")?;
    let by = between(line, "Requested by: '", "'").map(|(v, _)| v).unwrap_or("");
    let (range, _) = between(line, "Expected range: '", "'")?;
    let actual = between(line, "Actual version: '", "'").map(|(v, _)| v).unwrap_or("");
    let missing = actual.is_empty() || actual.contains("MISSING");

    let expected = range_floor(range).unwrap_or_else(|| range.to_string());
    let mut p = Problem::new(kind_for(target, missing), line.trim())
        .about(by)
        .needing(target, expected.clone());
    for fix in fixes_for(target, &expected, missing) {
        p = p.fix(fix);
    }
    if !by.is_empty() && p.kind != "java" {
        p = p.fix(Fix::new("disable_mod", by));
    }
    Some(p)
}

/// `[15.2.0.22,)` → `15.2.0.22`. La borne basse est la seule information
/// utilisable : c'est la version minimale qui satisferait la demande.
fn range_floor(range: &str) -> Option<String> {
    let inner = range.trim().trim_start_matches(['[', '(']).trim_end_matches([']', ')']);
    let first = inner.split(',').next()?.trim();
    (!first.is_empty()).then(|| first.to_string())
}

/// Un Mixin qui n'a pas pu s'appliquer. C'est la signature d'un mod écrit
/// pour une autre version du jeu : il a été chargé, il a trouvé sa cible
/// absente ou changée, et il a interrompu le démarrage.
///
/// Le nom du mod se lit dans celui de sa configuration (`sodium.mixins.json`),
/// seule chose nommable dans ces traces — la classe cible, elle, est une
/// classe de Minecraft.
fn mixin_failure(line: &str) -> Option<Problem> {
    if !line.contains(".mixins.json") {
        return None;
    }
    if !line.contains("Mixin apply failed")
        && !line.contains("MixinApplyError")
        && !line.contains("InjectionError")
        && !line.contains("Critical injection failure")
    {
        return None;
    }
    let token = line
        .split_whitespace()
        .find(|w| w.contains(".mixins.json"))?;
    let file = token.rsplit(['/', '\\']).next().unwrap_or(token);
    let id = file.split(".mixins.json").next().unwrap_or("").trim();
    if id.is_empty() {
        return None;
    }
    Some(
        Problem::new("mixin", line.trim())
            .about(id)
            .fix(Fix::new("disable_mod", id)),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn marqueur_de_reussite_tardif() {
        assert!(is_ready_marker("[12:04:31] [Render thread/INFO]: Sound engine started"));
        assert!(is_ready_marker("[YUYUFRAME_READY]"));
        // Le chargement des mods n'est pas une réussite : les Mixins
        // s'appliquent après.
        assert!(!is_ready_marker("[main/INFO]: Loading 142 mods"));
        assert!(!is_ready_marker("[main/INFO]: Setting user: Ghasty"));
    }

    #[test]
    fn fabric_version_de_jeu() {
        let line = "- Mod 'Sodium' (sodium) 0.5.8 requires version 1.20.1 of minecraft, but only 1.21.4 is present!";
        let p = fabric_dependency(line).unwrap();
        assert_eq!(p.kind, "mc_mismatch");
        assert_eq!(p.subject, "sodium");
        assert_eq!(p.target, "minecraft");
        // La version part dans le geste : c'est elle qui rend le bouton utile.
        assert!(p.fixes.contains(&Fix::new("mc_version", "1.20.1")));
    }

    #[test]
    fn fabric_dependance_manquante() {
        let line = "\t - Mod 'Iris Shaders' (iris) 1.6.0 requires any version of sodium, which is missing!";
        let p = fabric_dependency(line).unwrap();
        assert_eq!(p.kind, "missing_dep");
        assert_eq!(p.subject, "iris");
        assert_eq!(p.target, "sodium");
        assert!(p.fixes.contains(&Fix::new("install_mod", "sodium")));
    }

    #[test]
    fn fabric_loader_trop_ancien() {
        let line = "- Mod 'X' (x) 1.0 requires version 0.16.0 or later of fabricloader, but only 0.15.11 is present!";
        let p = fabric_dependency(line).unwrap();
        assert_eq!(p.kind, "loader_too_old");
        assert_eq!(p.target, "fabricloader");
        assert!(p.fixes.contains(&Fix::new("loader_version", "")));
        // Pas de version proposée : « 0.16.0 or later » n'est pas un numéro,
        // et un bouton qui l'annoncerait mentirait.
        assert!(!p.fixes.iter().any(|f| f.action == "mc_version"));
    }

    #[test]
    fn fabric_incompatibilite() {
        let line = "- Mod 'A' (a) 1.0 is incompatible with any version of b, but 2.0 is present!";
        let p = fabric_dependency(line).unwrap();
        assert_eq!(p.kind, "mod_conflict");
        // Les deux mods sont proposés à la désactivation : le launcher ne
        // peut pas savoir lequel compte pour la personne.
        assert!(p.fixes.contains(&Fix::new("disable_mod", "a")));
        assert!(p.fixes.contains(&Fix::new("disable_mod", "b")));
    }

    #[test]
    fn forge_dependance_manquante() {
        let line = "\tMod ID: 'jei', Requested by: 'mekanism', Expected range: '[15.2.0.22,)', Actual version: '[MISSING]'";
        let p = forge_dependency(line).unwrap();
        assert_eq!(p.kind, "missing_dep");
        assert_eq!(p.subject, "mekanism");
        assert_eq!(p.target, "jei");
        assert_eq!(p.expected, "15.2.0.22");
    }

    #[test]
    fn forge_version_de_jeu() {
        let line = "Mod ID: 'minecraft', Requested by: 'create', Expected range: '[1.20.1,1.20.1]', Actual version: '1.21.1'";
        let p = forge_dependency(line).unwrap();
        assert_eq!(p.kind, "mc_mismatch");
        assert!(p.fixes.contains(&Fix::new("mc_version", "1.20.1")));
    }

    #[test]
    fn mixin_nomme_le_mod() {
        let line = "[main/ERROR]: Mixin apply failed sodium.mixins.json:features.RenderMixin -> net.minecraft.class_757: org.spongepowered.asm.mixin.injection.throwables.InjectionError";
        let p = mixin_failure(line).unwrap();
        assert_eq!(p.kind, "mixin");
        assert_eq!(p.subject, "sodium");
    }

    #[test]
    fn une_trace_ordinaire_n_est_pas_un_mixin() {
        // Sans échec d'application, une ligne qui cite un fichier de Mixin
        // est un simple message de chargement — en faire un problème
        // remplirait l'écran de faux positifs.
        assert!(mixin_failure("[main/INFO]: Loading sodium.mixins.json").is_none());
    }

    #[test]
    fn suggestions_du_loader_recopiees() {
        let log = "\
[main/ERROR]: Incompatible mods found!
A potential solution has been determined:
\t - Replace Sodium 0.4.4 with version 0.5.0 or later.
Unmet dependency listing:
\t - Mod 'Sodium' (sodium) 0.4.4 requires version 1.20.4 of minecraft, but only 1.21.4 is present!";
        let s = loader_suggestions(log);
        assert_eq!(s, vec!["Replace Sodium 0.4.4 with version 0.5.0 or later."]);
        // Et la liste des dépendances, elle, est bien lue comme un problème.
        let problems = diagnose(log);
        assert_eq!(problems.len(), 1);
        assert_eq!(problems[0].kind, "mc_mismatch");
    }

    #[test]
    fn un_journal_sain_ne_produit_rien() {
        // Le filet qui compte : un démarrage normal ne doit pas inventer de
        // problème, sans quoi l'écran crierait au loup à chaque essai réussi.
        let log = "\
[main/INFO]: Loading Minecraft 1.21.4 with Fabric Loader 0.16.9
[main/INFO]: Loading 142 mods
[Render thread/INFO]: Setting user: Ghasty
[Render thread/INFO]: Sound engine started";
        assert!(diagnose(log).is_empty());
        assert!(loader_suggestions(log).is_empty());
    }

    #[test]
    fn le_meme_probleme_n_est_compte_qu_une_fois() {
        let line = "- Mod 'Iris' (iris) 1.6.0 requires any version of sodium, which is missing!";
        let log = format!("{line}\n[main/ERROR]: {line}");
        assert_eq!(diagnose(&log).len(), 1);
    }

    #[test]
    fn memoire_et_java_remontent_avec_leur_remede() {
        let oom = diagnose("[main/ERROR]: java.lang.OutOfMemoryError: Java heap space");
        assert_eq!(oom.len(), 1);
        assert_eq!(oom[0].kind, "oom");
        assert_eq!(oom[0].fixes, vec![Fix::new("ram", "")]);

        let java = diagnose("Error: LinkageError occurred: java.lang.UnsupportedClassVersionError: net/minecraft/client/main/Main has been compiled by a more recent version of the Java Runtime");
        assert!(java.iter().any(|p| p.kind == "java"));
    }
}
