use std::path::Path;

use crate::minecraft::versions::VersionDetails;
use crate::state::MinecraftSession;
use super::mojang_rules::extract_conditional_args;

/// P1-6 (audit launcher, Phase 6) : le vendeur de JVM choisi pour un profil.
/// `Temurin` reste le défaut et le seul chemin testé en profondeur — les
/// trois autres activent un comportement différent dans `ensure_java`
/// (résolution/téléchargement) et `build_jvm_args` (syntaxe des flags GC,
/// radicalement différente sur OpenJ9).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum JvmVendor {
    Temurin,
    OpenJ9,
    /// Volontairement une option manuelle neutre, jamais un défaut — voir
    /// doc jvm-config : gains JIT inconsistants sur Minecraft, warmup souvent
    /// plus long qu'avec C2, meilleures optimisations réservées à
    /// l'Enterprise. Nécessite un `custom_path` fourni par l'utilisateur : le
    /// launcher ne télécharge jamais GraalVM lui-même.
    Graal,
    /// Aucune résolution/téléchargement automatique — un `custom_path` est
    /// obligatoire (voir `ensure_java`). Traité comme HotSpot pour le choix
    /// des flags GC (le cas très majoritaire des JVM "custom" en pratique).
    /// Note : `custom_path` n'est plus réservé à ce vendeur — n'importe quel
    /// vendeur peut aussi en fournir un pour épingler une install précise
    /// (voir `ensure_java`) ; `Custom` reste utile pour dire explicitement
    /// "je fournis tout, ne devine rien" sans se rattacher à Temurin/OpenJ9/Graal.
    Custom,
}

impl JvmVendor {
    pub(super) fn parse(s: &str) -> Self {
        match s {
            "openj9" => JvmVendor::OpenJ9,
            "graal" => JvmVendor::Graal,
            "custom" => JvmVendor::Custom,
            _ => JvmVendor::Temurin,
        }
    }

    pub(super) fn as_str(&self) -> &'static str {
        match self {
            JvmVendor::Temurin => "temurin",
            JvmVendor::OpenJ9 => "openj9",
            JvmVendor::Graal => "graal",
            JvmVendor::Custom => "custom",
        }
    }
}

/// Retour utilisateur : "Auto" doit couvrir toute la config (vendeur ET GC),
/// pas seulement le GC — c'est la lecture correcte de la grille jvm-config
/// ("Sélecteur de GC... Option Auto qui applique LA GRILLE ci-dessous selon
/// la RAM"), que l'implémentation initiale avait restreinte à tort au seul
/// GC. `"auto"` n'est PAS une variante de `JvmVendor` (le reste du code n'a
/// jamais besoin de savoir "auto" a existé, seulement le résultat concret) —
/// résolu une fois ici, tôt, par l'appelant (`download_and_launch`,
/// `preview_jvm_config`), avant tout usage de `JvmVendor::parse`.
///
/// ~2 Go → OpenJ9 (empreinte de base plus faible, la marge nécessaire sur un
/// budget serré) ; au-delà → Temurin (le collecteur exact — Shenandoah ou G1
/// — est décidé par `pick_gc`, sur la version de Java, le nombre de cœurs et
/// ce que la JVM obtenue sait faire).
///
/// La borne est `<= 2048`, pas `< 3072` : la grille jvm-config n'a QUE deux
/// paliers de ce côté — "~2 Go → OpenJ9/gencon" et "3-4 Go → Temurin/G1GC".
/// L'ancien seuil faisait basculer toute la bande 2-3 Go sur OpenJ9, donc
/// une config 2560 Mo partait sur OpenJ9 alors que la grille la met sur
/// Temurin. Corrigé le 2026-09-01 : la grille fait foi, tout écart est un
/// bug d'implémentation.
pub(super) fn resolve_auto_vendor(ram_mb: u32) -> &'static str {
    if ram_mb <= 2048 { "openj9" } else { "temurin" }
}

/// Extrait juste la paire `--tweakClass <classe>` d'une `minecraftArguments`
/// legacy (le reste de la chaîne ne fait que dupliquer les placeholders déjà
/// substitués par les args vanilla de base).
pub(super) fn extract_tweak_class_args(mc_args: &str) -> Vec<String> {
    let tokens: Vec<&str> = mc_args.split_whitespace().collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < tokens.len() {
        if tokens[i] == "--tweakClass" && i + 1 < tokens.len() {
            out.push(tokens[i].to_string());
            out.push(tokens[i + 1].to_string());
            i += 2;
        } else {
            i += 1;
        }
    }
    out
}

/// Point d'entrée public — dispatch selon le vendeur (P1-6, Phase 6). La
/// syntaxe des flags GC d'OpenJ9 (`-Xgcpolicy:*`) n'a rien à voir avec celle
/// de la famille HotSpot (`-XX:+Use*GC`) : deux générateurs séparés plutôt
/// qu'une seule fonction avec des branches qui se marcheraient dessus.
/// Temurin/Graal/Custom partagent le même générateur HotSpot — Graal CE et
/// la plupart des JVM "custom" en pratique sont HotSpot-compatibles côté
/// flags GC (voir doc de `JvmVendor::Graal`/`JvmVendor::Custom`).
///
/// `extra_args`/`args_mode` viennent de l'écran "Configuration JVM" (drapeaux
/// tapés à la main) et sont appliqués en dernier — voir `merge_jvm_args`.
pub(super) fn build_jvm_args(ram_mb: u32, natives_dir: &Path, java_major: u32, vendor: JvmVendor, gc_policy: &str, extra_args: &str, args_mode: &str, env: GcEnv) -> Vec<String> {
    let mut generated = match vendor {
        JvmVendor::OpenJ9 => build_openj9_jvm_args(ram_mb, natives_dir, gc_policy),
        JvmVendor::Temurin | JvmVendor::Graal | JvmVendor::Custom => build_hotspot_jvm_args(ram_mb, natives_dir, java_major, gc_policy, env),
    };
    if let Some(flag) = native_access_arg(java_major) {
        generated.push(flag);
    }
    if let Some(flag) = jndi_dns_export_arg(java_major) {
        generated.push(flag);
    }
    merge_jvm_args(generated, extra_args, args_mode)
}

/// Rend `com.sun.jndi.dns` visible au classpath — `None` avant Java 9.
///
/// Les versions legacy (1.8.9…) résolvent les enregistrements SRV
/// (`_minecraft._tcp.<domaine>`) par JNDI, et patchy (liste de serveurs
/// bloqués de Mojang, dans `com.mojang:netty`) s'interpose en instanciant
/// lui-même `DnsContextFactory` par `Class.forName(…).newInstance()`. Depuis
/// Java 17 le paquet n'étant pas exporté, cette instanciation échoue, la
/// résolution SRV aussi, et le jeu se connecte au domaine nu : uhcworld.fr,
/// mcpvp.club… injoignables (constaté le 2026-09-29). Voir aussi
/// `ServerAddressSrvMixin189` côté agent, pour le second problème (DNS IPv6
/// sous `preferIPv4Stack`). Le drapeau n'existe pas en Java 8.
fn jndi_dns_export_arg(java_major: u32) -> Option<String> {
    (java_major >= 9).then(|| "--add-exports=jdk.naming.dns/com.sun.jndi.dns=ALL-UNNAMED".to_string())
}

/// Autorise l'accès natif au code du classpath (LWJGL charge ses DLL par
/// `System.load`) — `None` avant Java 22.
///
/// Depuis Java 24, sans ce drapeau, chaque bibliothèque qui charge du natif
/// fait écrire à la JVM quatre lignes « WARNING: A restricted method in
/// java.lang.System has been called » au lancement (vues en 1.8.9 et 26.1.2,
/// 2026-09-15), et une future version bloquera l'appel. Le drapeau n'existe
/// qu'à partir de Java 22 : passé à une JVM plus ancienne (Java 8 des
/// versions legacy), elle refuserait de démarrer.
fn native_access_arg(java_major: u32) -> Option<String> {
    (java_major >= 22).then(|| "--enable-native-access=ALL-UNNAMED".to_string())
}

/// Découpe le texte libre de l'écran "Configuration JVM" en drapeaux. Une
/// ligne vide ou commençant par `#` est ignorée (commentaires : indispensable
/// pour garder plusieurs jeux de flags dans le champ pendant un benchmark et
/// n'en activer qu'un).
///
/// Le découpage se fait sur les espaces, pas seulement sur les retours à la
/// ligne : un jeu de flags communautaire (Aikar, brucethemoose...) se copie
/// toujours sur une seule ligne, et le retaper une ligne par drapeau serait
/// une corvée. Conséquence assumée : un drapeau contenant une espace (un
/// `-D` pointant vers un chemin Windows non échappé) serait coupé en deux —
/// les drapeaux de tuning JVM n'en contiennent jamais.
pub(crate) fn parse_user_jvm_args(raw: &str) -> Vec<String> {
    raw.lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .flat_map(str::split_whitespace)
        .map(str::to_string)
        .collect()
}

/// Identité d'un drapeau, pour qu'un drapeau utilisateur REMPLACE son
/// homologue généré au lieu de s'y ajouter : `-XX:MaxGCPauseMillis=37` doit
/// effacer le `-XX:MaxGCPauseMillis=100` généré, et `-XX:-AlwaysPreTouch`
/// doit effacer `-XX:+AlwaysPreTouch` (d'où le `trim_start_matches` sur le
/// signe : c'est le même réglage, pas deux drapeaux différents).
fn arg_key(arg: &str) -> String {
    for prefix in ["-Xmx", "-Xms", "-Xmn", "-Xss", "-Xgcpolicy:"] {
        if arg.starts_with(prefix) {
            return prefix.trim_end_matches(':').to_string();
        }
    }
    if let Some(rest) = arg.strip_prefix("-XX:") {
        let name = rest.trim_start_matches(['+', '-']);
        return format!("-XX:{}", name.split('=').next().unwrap_or(name));
    }
    if let Some(rest) = arg.strip_prefix("-D") {
        return format!("-D{}", rest.split('=').next().unwrap_or(rest));
    }
    arg.to_string()
}

/// Sélecteur de ramasse-miettes (`-XX:+UseG1GC`, `-XX:+UseZGC`,
/// `-XX:+UseShenandoahGC`, `-XX:+UseParallelGC`...). Deux sélecteurs présents
/// en même temps font refuser le démarrage de la JVM ("Multiple garbage
/// collectors selected") — dès que l'utilisateur en pose un, celui qui a été
/// généré doit disparaître, sinon le simple fait de taper `-XX:+UseShenandoahGC`
/// rendrait l'instance impossible à lancer.
fn is_gc_selector(key: &str) -> bool {
    key.starts_with("-XX:Use") && key.ends_with("GC")
}

/// Réglage propre à un collecteur précis (`-XX:G1*`, `-XX:Z*`,
/// `-XX:Shenandoah*`) : inutile sous un autre collecteur, et carrément
/// rejeté au démarrage pour ceux marqués `experimental`.
fn is_collector_specific(key: &str) -> bool {
    key.starts_with("-XX:G1") || key.starts_with("-XX:Z") || key.starts_with("-XX:Shenandoah")
}

/// Ce qui survit au mode "replace". Sans `-Xmx`/`-Xms` le heap retombe au
/// défaut de la JVM (la RAM choisie dans le launcher ne servirait plus à
/// rien), et sans les deux library path LWJGL ne trouve pas ses natives —
/// le jeu ne démarre pas du tout. Ces quatre-là ne sont donc jamais un choix
/// de tuning, mais l'utilisateur peut quand même les redéfinir : un `-Xmx`
/// tapé à la main écrase le généré par `arg_key` comme n'importe quel autre.
/// `--enable-native-access` non plus n'est pas du tuning (voir
/// `native_access_arg`) : il reste aussi, comme l'export JNDI DNS (voir
/// `jndi_dns_export_arg`).
fn is_mandatory_base(arg: &str) -> bool {
    arg.starts_with("-Xmx")
        || arg.starts_with("-Xms")
        || arg.starts_with("-Djava.library.path=")
        || arg.starts_with("-Dorg.lwjgl.librarypath=")
        || arg.starts_with("--enable-native-access=")
        || arg.starts_with("--add-exports=jdk.naming.dns/")
}

/// Fusionne les drapeaux générés et ceux tapés dans l'écran "Configuration
/// JVM".
///
/// - `"append"` (défaut) : tout le tuning généré est gardé, les drapeaux
///   utilisateur s'ajoutent à la fin et écrasent leurs homologues.
/// - `"replace"` : seule la base obligatoire reste (voir `is_mandatory_base`),
///   tout le reste vient de l'utilisateur — le mode à utiliser pour tester un
///   jeu de flags communautaire tel quel, sans que le nôtre s'y mélange.
///
/// Dans les deux cas, poser un sélecteur de GC retire aussi tout le bloc de
/// réglages du collecteur généré (voir `is_gc_selector`).
pub(super) fn merge_jvm_args(generated: Vec<String>, extra_args: &str, args_mode: &str) -> Vec<String> {
    let user = parse_user_jvm_args(extra_args);
    let replace = args_mode == "replace";
    if user.is_empty() && !replace {
        return generated;
    }
    let user_keys: std::collections::HashSet<String> = user.iter().map(|a| arg_key(a)).collect();
    let user_picks_gc = user_keys.iter().any(|k| is_gc_selector(k));

    let mut out: Vec<String> = generated
        .into_iter()
        .filter(|arg| {
            if replace && !is_mandatory_base(arg) {
                return false;
            }
            let key = arg_key(arg);
            if user_keys.contains(&key) {
                return false;
            }
            !(user_picks_gc && (is_gc_selector(&key) || is_collector_specific(&key)))
        })
        .collect();
    out.extend(user);
    out
}

/// P1-6 (audit launcher, Phase 6) : grille RAM/GC OpenJ9, alternative à
/// `build_hotspot_jvm_args` pour le palier "~2 Go" de la grille jvm-config —
/// OpenJ9 a une empreinte mémoire de base nettement plus faible que HotSpot,
/// la marge nécessaire sur un budget aussi serré. `gencon` (par défaut,
/// "auto") est adapté aux objets à durée de vie courte typiques de Minecraft
/// (entités, particules, paquets réseau) ; les autres policies restent
/// sélectionnables explicitement (voir sélecteur GC des paramètres avancés).
/// Volontairement minimal : contrairement à HotSpot, aucun flag `-XX:*`
/// documenté ici n'a été vérifié en conditions réelles (voir P1-3 — un flag
/// inconnu empêche la JVM de démarrer), on se limite donc au strict
/// nécessaire plutôt que de porter tout le tuning HotSpot en devinant.
fn build_openj9_jvm_args(ram_mb: u32, natives_dir: &Path, gc_policy: &str) -> Vec<String> {
    let reserve_mb = (ram_mb / 10).max(200).min(ram_mb.saturating_sub(256));
    let heap_mb = ram_mb - reserve_mb;

    let policy = match gc_policy {
        "optthruput" | "optavgpause" | "balanced" | "metronome" => gc_policy,
        _ => "gencon",
    };

    vec![
        format!("-Xmx{}m", heap_mb),
        format!("-Xms{}m", heap_mb),
        format!("-Djava.library.path={}", natives_dir.display()),
        format!("-Dorg.lwjgl.librarypath={}", natives_dir.display()),
        format!("-Xgcpolicy:{}", policy),
        "-XX:+UseCompressedOops".into(),
        "-Xdisableexplicitgc".into(), // équivalent OpenJ9 de -XX:+DisableExplicitGC (System.gc() ignoré)
    ]
}

/// Ce que la grille a besoin de savoir de la machine et de la JVM résolue, en
/// plus de la RAM et de la version de Java.
#[derive(Debug, Clone, Copy)]
pub(super) struct GcEnv {
    /// Cœurs logiques de la machine.
    pub logical_cores: usize,
    /// La JVM résolue accepte `-XX:+UseShenandoahGC` (voir
    /// `java::supports_shenandoah`). Les runtimes Mojang 17, 21 et 25 l'ont ;
    /// Oracle JDK et les Java 8 ne l'ont pas, et le demander quand même
    /// empêche la JVM de démarrer.
    pub shenandoah: bool,
}

/// En dessous ou à ce nombre de cœurs logiques, « Auto » reste sur G1.
///
/// Shenandoah collecte pendant que le jeu tourne : il lui faut des cœurs que
/// le rendu, le serveur intégré et les threads de chunks n'occupent pas déjà.
/// La JVM ne lui en donne par défaut qu'un quart — un seul thread à 4 cœurs.
/// Le seuil est un raisonnement, pas une mesure : à revoir si un essai à
/// 4 cœurs montre Shenandoah devant.
const SHENANDOAH_MIN_CORES_EXCLUSIVE: usize = 4;

/// Première version de Java où la grille emploie Shenandoah et les réglages
/// de compilateur. Shenandoah existe depuis Java 12, mais rien n'a été
/// vérifié avant le runtime Mojang 17.
const MODERN_JAVA: u32 = 17;

/// Le mode générationnel de Shenandoah est officiel à partir de Java 25
/// (JEP 521). Sur Java 17 et 21, `ShenandoahGCMode=generational` fait refuser
/// le démarrage — vérifié sur les runtimes Mojang.
const GENERATIONAL_SHENANDOAH_JAVA: u32 = 25;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Gc {
    G1,
    Zgc,
    Shenandoah { generational: bool },
}

/// La grille, côté HotSpot (le palier « ≤ 2 Go → OpenJ9 » est décidé plus
/// tôt, par `resolve_auto_vendor`) :
///
/// | Réglage      | Condition                                   | Collecteur              |
/// |--------------|---------------------------------------------|-------------------------|
/// | auto         | Java 25+, JVM avec Shenandoah, > 4 cœurs    | Shenandoah générationnel|
/// | auto         | Java 17 à 24, mêmes conditions              | Shenandoah classique    |
/// | auto         | sinon (peu de cœurs, Java < 17, JVM sans)   | G1                      |
/// | shenandoah   | Java 17+ et JVM avec Shenandoah, sinon G1   | Shenandoah              |
/// | zgc          | Java 21+ et ≥ 6 Go, sinon G1                | ZGC générationnel       |
/// | g1           | toujours                                    | G1                      |
///
/// « Auto » ne choisit plus jamais ZGC (2026-10-04) : ses barrières de
/// lecture coûtent trop de débit sur un client. Il reste un choix manuel —
/// une instance réglée dessus l'a été exprès — avec son plancher : jamais en
/// dessous de 6 Go, son surcoût mémoire rapprochant un petit tas du plantage.
/// Le seuil de cœurs ne vaut que pour « Auto » : un choix explicite est suivi.
fn pick_gc(ram_mb: u32, java_major: u32, gc_policy: &str, env: GcEnv) -> Gc {
    let shenandoah = java_major >= MODERN_JAVA && env.shenandoah;
    let generational = java_major >= GENERATIONAL_SHENANDOAH_JAVA;
    match gc_policy {
        "g1" => Gc::G1,
        "zgc" if java_major >= 21 && ram_mb >= 6144 => Gc::Zgc,
        "zgc" => Gc::G1,
        "shenandoah" if shenandoah => Gc::Shenandoah { generational },
        "shenandoah" => Gc::G1,
        // "auto", ou toute valeur inconnue.
        _ if shenandoah && env.logical_cores > SHENANDOAH_MIN_CORES_EXCLUSIVE => Gc::Shenandoah { generational },
        _ => Gc::G1,
    }
}

/// `true` si la grille a besoin de savoir si cette JVM a Shenandoah — pour ne
/// lancer la sonde (un processus) que lorsque sa réponse change quelque chose.
pub(super) fn needs_shenandoah_probe(java_major: u32, vendor: JvmVendor, gc_policy: &str, logical_cores: usize) -> bool {
    if vendor == JvmVendor::OpenJ9 || java_major < MODERN_JAVA {
        return false;
    }
    match gc_policy {
        "g1" | "zgc" => false,
        "shenandoah" => true,
        _ => logical_cores > SHENANDOAH_MIN_CORES_EXCLUSIVE,
    }
}

fn build_hotspot_jvm_args(ram_mb: u32, natives_dir: &Path, java_major: u32, gc_policy: &str, env: GcEnv) -> Vec<String> {
    // P1-5 (audit launcher) : marge sous le plafond mémoire demandé — réserve
    // ~10% (plancher 200 Mo) pour le natif (rendu, GLFW/LWJGL, DLL, code
    // cache JIT, métaspace), jamais compté dans `ram_mb`. Sans cette marge,
    // -Xmx posé à la valeur brute demandée est la cause directe du risque OOM
    // sur les petites configs (ex: profil 2 Go → -Xmx2048m, zéro marge).
    let reserve_mb = (ram_mb / 10).max(200).min(ram_mb.saturating_sub(256));
    let heap_mb = ram_mb - reserve_mb;

    // Java 17 et plus : la base, le collecteur et le compilateur de la grille
    // du 2026-10-04. En dessous, rien n'a été vérifié — on garde l'ancienne.
    let modern = java_major >= MODERN_JAVA;

    let mut base = vec![
        format!("-Xmx{}m", heap_mb),
        format!("-Xms{}m", heap_mb),  // Xms = Xmx : pas de redimensionnement du heap
        format!("-Djava.library.path={}", natives_dir.display()),
        format!("-Dorg.lwjgl.librarypath={}", natives_dir.display()),
    ];
    if modern {
        // En TÊTE : plusieurs drapeaux qui suivent sont *experimental*
        // (`UseCriticalJavaThreadPriority`, les réglages de Shenandoah), et
        // la JVM refuse de démarrer si elle les lit avant le déverrouillage.
        base.push("-XX:+UnlockExperimentalVMOptions".into());
    }
    base.extend([
        "-XX:+DisableExplicitGC".into(),       // Ignore System.gc() appelés par les mods
        "-XX:+PerfDisableSharedMem".into(),    // Pas de fichiers perf OS (source de jitter)
        "-XX:+UseStringDeduplication".into(),  // Réduit les doublons String en mémoire
    ]);
    if modern {
        base.extend([
            "-XX:+AlwaysActAsServerClassMachine".into(), // heuristiques « serveur » même sur une petite machine
            "-XX:+UseCriticalJavaThreadPriority".into(), // le rendu passe devant les tâches de fond
            "-XX:MetaspaceSize=256m".into(),             // pas de collecte déclenchée par le chargement des classes des mods
        ]);
        // En-têtes d'objets de 8 octets au lieu de 12 (JEP 519) : le même
        // jeu tient dans un tas plus petit. Officiel à partir de Java 25 ;
        // avant, le drapeau est inconnu et la JVM refuse de démarrer.
        if java_major >= 25 {
            base.push("-XX:+UseCompactObjectHeaders".into());
        }
    }

    // AlwaysPreTouch force le touch physique de TOUT le tas dès le boot —
    // excellent pour le runtime (plus de page faults pendant le jeu), mais
    // ça concentre tout le coût d'allocation mémoire en un seul pic au
    // lancement. Sur une petite config (RAM dispo limitée, allocation
    // < 3 Go), ce pic peut largement dominer le temps de lancement perçu —
    // on le réserve donc aux configs où le heap est assez gros pour que le
    // gain runtime en vaille la peine.
    if ram_mb >= 3072 {
        base.push("-XX:+AlwaysPreTouch".into());
    }

    let mut args = match pick_gc(ram_mb, java_major, gc_policy, env) {
        Gc::Shenandoah { generational } => {
            // ── Shenandoah (Java 17+, plus de 4 cœurs) ────────────────────────
            // Évacuation concurrente : des pauses courtes sans la taxe de
            // débit des barrières de lecture de ZGC. Le mode générationnel ne
            // parcourt que les objets jeunes — presque tout ce que Minecraft
            // alloue meurt aussitôt.
            let mut args = base;
            args.push("-XX:+UseShenandoahGC".into());
            if generational {
                args.push("-XX:ShenandoahGCMode=generational".into());
            }
            args.extend([
                "-XX:+ParallelRefProcEnabled".into(),
                // Cycle « de garantie » toutes les ~17 minutes au lieu de 5 :
                // pas de collecte quand rien ne la demande.
                "-XX:ShenandoahGuaranteedGCInterval=1000000".into(),
                // Cohérent avec Xms = Xmx : la mémoire prise n'est jamais
                // rendue, donc jamais redemandée à l'OS en pleine partie.
                "-XX:-ShenandoahUncommit".into(),
            ]);
            args
        }
        Gc::Zgc => {
            // ── ZGC générationnel (choix manuel, Java 21+, ≥ 6 Go) ────────────
            let mut args = base;
            args.push("-XX:+UseZGC".into());
            // ZGenerational est le défaut depuis Java 24 — ne pas l'ajouter pour éviter le warning
            if java_major < 24 {
                args.push("-XX:+ZGenerational".into());
            }
            args.push("-XX:ZAllocationSpikeTolerance=5.0".into());
            args
        }
        Gc::G1 => build_g1_args(base, ram_mb, java_major),
    };

    // ── Compilateur (Java 17+, quel que soit le collecteur) ──────────────────
    if modern {
        args.extend([
            // HotSpot refuse par défaut de compiler les méthodes de plus de
            // 8000 bytecodes, dont plusieurs boucles chaudes de Minecraft.
            // Les deux limites de nœuds vont avec : sans elles, C2 abandonne
            // sur ces mêmes méthodes.
            "-XX:-DontCompileHugeMethods".into(),
            "-XX:MaxNodeLimit=240000".into(),
            "-XX:NodeLimitFudgeFactor=8000".into(),
            "-XX:NmethodSweepActivity=1".into(),
            // 240 Mo par défaut : un gros modpack le sature, et le JIT cesse
            // alors de compiler sans rien dire. La taille TOTALE seulement —
            // la JVM découpe d'elle-même (7 + 196 + 196 Mo). Écrire les trois
            // segments à la main fait refuser le démarrage dès que quelqu'un
            // change le total dans ses propres drapeaux.
            "-XX:ReservedCodeCacheSize=400M".into(),
        ]);
    }
    args
}

/// G1 : le repli de la grille (peu de cœurs, JVM sans Shenandoah, Java
/// antérieur à 17) et le choix manuel « G1GC ».
fn build_g1_args(base: Vec<String>, ram_mb: u32, java_major: u32) -> Vec<String> {
    if java_major <= 8 {
        // ── G1GC tuning Java 8 (versions legacy : 1.8.9 et antérieures) ───────
        // L'implémentation G1 de Java 8 (HotSpot ~2014) est nettement moins
        // mature que celle de Java 17+ : mêmes noms de flags, mais le moteur
        // sous-jacent diffère assez pour que les valeurs ci-dessus (pensées
        // pour Java 17-20) ne soient pas optimales ici. Set basé sur des
        // benchmarks communautaires pour Minecraft/Java 8 (cf. dépôt
        // brucethemoose/Minecraft-Performance-Flags-Benchmarks).
        //
        // Volontairement plus conservateur que la liste de cette source :
        // les flags JIT/C2 les plus obscurs (MaxNodeLimit, NmethodSweepActivity,
        // UseFPUForSpilling...) sont écartés — un flag -XX inconnu empêche le
        // JVM de démarrer du tout, et certains builds Java 8 (vendeur/version)
        // ne les reconnaissent pas tous. Mieux vaut un gain plus modeste mais
        // fiable qu'un launcher qui refuse de démarrer.
        let region_size = if ram_mb >= 6144 { "8M" }
            else if ram_mb >= 3072 { "4M" }
            else { "2M" };
        let mut args = base;
        args.extend([
            "-XX:+UseG1GC".into(),
            "-XX:+ParallelRefProcEnabled".into(),
            "-XX:MaxGCPauseMillis=50".into(),
            "-XX:+UnlockExperimentalVMOptions".into(),
            "-XX:+UnlockDiagnosticVMOptions".into(),
            format!("-XX:G1HeapRegionSize={}", region_size),
            "-XX:G1NewSizePercent=30".into(),
            "-XX:G1MaxNewSizePercent=40".into(),
            "-XX:G1ReservePercent=20".into(),
            "-XX:InitiatingHeapOccupancyPercent=15".into(),
            "-XX:+AlwaysActAsServerClassMachine".into(), // force les heuristiques JIT "serveur" (compilation plus agressive) même sur petite machine
            // -XX:+UseFastAccessorMethods retiré (P1-3, audit launcher) :
            // déprécié depuis 8u20, supprimé en JDK 9 — "Unrecognized VM
            // option" et refus de démarrer si jamais atteint avec un
            // java_major incohérent (n'apporte rien même sur du vrai Java 8).
            "-XX:MaxInlineLevel=15".into(),
            "-XX:+UseCompressedOops".into(),
            "-XX:ThreadPriorityPolicy=1".into(),
            "-XX:+UseDynamicNumberOfGCThreads".into(),
            "-XX:ReservedCodeCacheSize=350m".into(),
        ]);
        args
    } else {
        // ── G1GC (Java 9 et plus) ─────────────────────────────────────────────
        // `UnlockExperimentalVMOptions` est déjà en tête de la base à partir
        // de Java 17 ; le répéter ici est sans effet, et il reste nécessaire
        // de Java 9 à 16.
        let region_size = if ram_mb >= 12288 { "16M" }
            else if ram_mb >= 6144 { "8M" }
            else if ram_mb >= 3072 { "4M" }
            else { "2M" };
        let mut args = base;
        args.extend([
            "-XX:+UseG1GC".into(),
            "-XX:+ParallelRefProcEnabled".into(),
            "-XX:MaxGCPauseMillis=100".into(),
            "-XX:+UnlockExperimentalVMOptions".into(),
            format!("-XX:G1HeapRegionSize={}", region_size),
            "-XX:G1NewSizePercent=30".into(),
            "-XX:G1MaxNewSizePercent=40".into(),
            "-XX:G1ReservePercent=20".into(),
            "-XX:G1HeapWastePercent=5".into(),
            "-XX:G1MixedGCCountTarget=4".into(),
            "-XX:InitiatingHeapOccupancyPercent=20".into(),
            "-XX:G1MixedGCLiveThresholdPercent=90".into(),
            "-XX:G1RSetUpdatingPauseTimePercent=5".into(),
            "-XX:SurvivorRatio=32".into(),
            "-XX:MaxTenuringThreshold=1".into(),
        ]);
        args
    }
}

// Lien vers la WinAPI multimédia (timer haute résolution)
#[cfg(target_os = "windows")]
#[link(name = "winmm")]
extern "system" {
    pub(super) fn timeBeginPeriod(uPeriod: u32) -> u32;
    pub(super) fn timeEndPeriod(uPeriod: u32) -> u32;
}

/// Force la préférence GPU "Performances élevées" (GPU dédié) pour CE
/// java.exe précis, sur les configs GPU hybrides (portable avec iGPU +
/// NVIDIA/AMD dédié) — même registre que "Paramètres Windows > Affichage >
/// Graphismes" quand on ajoute une appli manuellement et choisit "Hautes
/// performances" (HKCU\...\UserGpuPreferences, valeur "GpuPreference=2;").
///
/// Sans ça, Windows assigne java.exe au GPU par défaut du système — sur un
/// portable hybride, souvent l'iGPU — observé en conditions réelles : ~100
/// FPS au lieu de plusieurs centaines sur une RTX 4060, alors que d'autres
/// launchers Java (le launcher officiel Mojang notamment, confirmé présent
/// dans ce même registre pour SES propres java.exe) fonctionnent bien parce
/// qu'ILS ont déjà cette préférence positionnée pour leur propre exécutable
/// — jamais faite pour le nôtre puisque chaque composant runtime Mojang
/// (jre-legacy, java-runtime-delta, etc.) vit à un chemin distinct.
///
/// Best-effort silencieux : ne bloque jamais le lancement si `reg.exe` est
/// absent ou la clé inaccessible (HKCU, donc normalement toujours
/// accessible sans élévation, mais on ne veut prendre aucun risque ici).
#[cfg(target_os = "windows")]
pub(super) async fn ensure_gpu_preference(java_exe: &str) {
    const KEY: &str = r"HKCU\SOFTWARE\Microsoft\DirectX\UserGpuPreferences";

    let already_set = crate::app::process::hidden_command("reg")
        .args(["query", KEY, "/v", java_exe])
        .output()
        .await
        .map(|out| out.status.success())
        .unwrap_or(false);
    if already_set {
        return;
    }

    let _ = crate::app::process::hidden_command("reg")
        .args(["add", KEY, "/v", java_exe, "/t", "REG_SZ", "/d", "GpuPreference=2;", "/f"])
        .output()
        .await;
}

#[cfg(not(target_os = "windows"))]
pub(super) async fn ensure_gpu_preference(_java_exe: &str) {}

pub(super) fn build_game_args(
    details: &VersionDetails,
    session: &MinecraftSession,
    game_dir: &Path,
    assets_dir: &Path,
    version_id: &str,
) -> Vec<String> {
    let assets_root = assets_dir.to_string_lossy().into_owned();
    let game_dir_str = game_dir.to_string_lossy().into_owned();
    let replacements: &[(&str, &str)] = &[
        ("${auth_player_name}", &session.username),
        ("${version_name}", version_id),
        ("${game_directory}", &game_dir_str),
        ("${assets_root}", &assets_root),
        ("${assets_index_name}", &details.asset_index.id),
        ("${auth_uuid}", &session.uuid),
        ("${auth_access_token}", &session.access_token),
        ("${user_type}", "msa"),
        ("${version_type}", "release"),
        // Vieilles versions (1.7.x–1.12.x)
        ("${user_properties}", "{}"),
        ("${game_assets}", &assets_root),
        ("${auth_session}", &session.access_token),
    ];

    let apply = |s: &str| -> String {
        let mut out = s.to_string();
        for (k, v) in replacements { out = out.replace(k, v); }
        out
    };

    let mut args = Vec::new();
    if let Some(ref mc_args) = details.minecraft_arguments {
        for part in mc_args.split_whitespace() { args.push(apply(part)); }
    } else if let Some(ref arguments) = details.arguments {
        for val in &arguments.game {
            if let serde_json::Value::String(s) = val { args.push(apply(s)); }
        }
    }
    args
}

/// Arguments JVM additionnels suggérés par Mojang pour cette version/OS
/// (`arguments.jvm`, absent des vieilles versions qui n'ont que
/// `minecraftArguments`) — surtout des correctifs spécifiques à l'OS, ex:
/// `-XstartOnFirstThread` obligatoire sur macOS pour que LWJGL/GLFW
/// fonctionnent, ou le fix `-Dos.name`/`-Dos.version` sur Windows 10+ pour
/// contourner un bug de détection d'Intel/AMD dans certains pilotes.
/// Ajoutés en plus de `build_jvm_args` (notre propre tuning GC), jamais à sa
/// place. Le classpath (`-cp`, `${classpath}`) est volontairement filtré :
/// on construit et pose le nôtre séparément, l'inclure ici le doublonnerait
/// sans bénéfice. Même chose pour `-Djava.library.path` (P2-3, audit
/// launcher) : `build_jvm_args` le pose déjà, ce bloc réinjecterait la même
/// valeur (`${natives_directory}` substitué) une seconde fois — sans effet
/// fonctionnel (la dernière valeur l'emporte), mais du bruit dans la ligne de
/// commande et les logs de diagnostic. Tout placeholder qu'on ne sait pas
/// substituer (autre que natives_directory/launcher_name/launcher_version)
/// fait sauter l'argument plutôt que de passer un token brisé du style
/// `${inconnu}` à Java.
pub(super) fn extract_mojang_jvm_args(details: &VersionDetails, natives_dir: &Path) -> Vec<String> {
    let Some(arguments) = details.arguments.as_ref() else { return Vec::new() };
    let natives = natives_dir.to_string_lossy().into_owned();

    extract_conditional_args(&arguments.jvm)
        .into_iter()
        .filter(|s| s != "-cp" && s != "-classpath" && !s.contains("${classpath}")
            && s != "-Djava.library.path" && !s.starts_with("-Djava.library.path="))
        .map(|s| s
            .replace("${natives_directory}", &natives)
            .replace("${launcher_name}", "YuyuFrame")
            .replace("${launcher_version}", env!("CARGO_PKG_VERSION")))
        .filter(|s| !s.contains("${"))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::{
        build_hotspot_jvm_args, build_jvm_args, jndi_dns_export_arg, merge_jvm_args, native_access_arg,
        needs_shenandoah_probe, parse_user_jvm_args, pick_gc, Gc, GcEnv, JvmVendor,
    };

    // ── La grille ────────────────────────────────────────────────────────────

    /// Une machine confortable dont la JVM a Shenandoah : le cas courant.
    const ROOMY: GcEnv = GcEnv { logical_cores: 16, shenandoah: true };

    fn hotspot(ram_mb: u32, java_major: u32, gc_policy: &str, env: GcEnv) -> Vec<String> {
        build_hotspot_jvm_args(ram_mb, std::path::Path::new("natives"), java_major, gc_policy, env)
    }

    fn has(args: &[String], flag: &str) -> bool {
        args.iter().any(|a| a == flag)
    }

    #[test]
    fn auto_choisit_shenandoah_generationnel_a_partir_de_java_25() {
        assert_eq!(pick_gc(4096, 25, "auto", ROOMY), Gc::Shenandoah { generational: true });
        // Le mode générationnel fait refuser le démarrage avant Java 25.
        assert_eq!(pick_gc(4096, 21, "auto", ROOMY), Gc::Shenandoah { generational: false });
        assert_eq!(pick_gc(4096, 17, "auto", ROOMY), Gc::Shenandoah { generational: false });
        let args = hotspot(4096, 21, "auto", ROOMY);
        assert!(has(&args, "-XX:+UseShenandoahGC"));
        assert!(!args.iter().any(|a| a.starts_with("-XX:ShenandoahGCMode")));
    }

    #[test]
    fn auto_reste_sur_g1_avec_peu_de_coeurs_ou_sans_shenandoah() {
        let four = GcEnv { logical_cores: 4, shenandoah: true };
        let six = GcEnv { logical_cores: 6, shenandoah: true };
        let without = GcEnv { logical_cores: 16, shenandoah: false };
        assert_eq!(pick_gc(8192, 25, "auto", four), Gc::G1);
        assert_eq!(pick_gc(8192, 25, "auto", six), Gc::Shenandoah { generational: true });
        assert_eq!(pick_gc(8192, 25, "auto", without), Gc::G1);
        // Java 8 et les versions intermédiaires : jamais Shenandoah.
        assert_eq!(pick_gc(8192, 8, "auto", ROOMY), Gc::G1);
        assert_eq!(pick_gc(8192, 16, "auto", ROOMY), Gc::G1);
    }

    /// « Auto » ne rend plus jamais ZGC, même là où il le choisissait avant.
    #[test]
    fn auto_ne_choisit_plus_zgc() {
        for env in [ROOMY, GcEnv { logical_cores: 2, shenandoah: false }] {
            assert_ne!(pick_gc(8192, 25, "auto", env), Gc::Zgc);
        }
    }

    #[test]
    fn un_choix_manuel_est_suivi_quand_la_jvm_le_permet() {
        let four = GcEnv { logical_cores: 4, shenandoah: true };
        assert_eq!(pick_gc(8192, 25, "g1", ROOMY), Gc::G1);
        // ZGC garde son plancher : Java 21 et 6 Go.
        assert_eq!(pick_gc(8192, 25, "zgc", ROOMY), Gc::Zgc);
        assert_eq!(pick_gc(4096, 25, "zgc", ROOMY), Gc::G1);
        assert_eq!(pick_gc(8192, 17, "zgc", ROOMY), Gc::G1);
        // Le seuil de cœurs ne vaut que pour « Auto ».
        assert_eq!(pick_gc(4096, 25, "shenandoah", four), Gc::Shenandoah { generational: true });
        // Une JVM sans Shenandoah ne démarrerait pas : G1.
        assert_eq!(pick_gc(4096, 25, "shenandoah", GcEnv { logical_cores: 16, shenandoah: false }), Gc::G1);
        assert_eq!(pick_gc(4096, 8, "shenandoah", ROOMY), Gc::G1);
    }

    /// Un seul collecteur dans les drapeaux générés, quelle que soit la
    /// branche : deux sélecteurs font refuser le démarrage.
    #[test]
    fn un_seul_selecteur_de_gc_par_configuration() {
        for java in [8, 16, 17, 21, 25] {
            for policy in ["auto", "g1", "zgc", "shenandoah"] {
                for env in [ROOMY, GcEnv { logical_cores: 4, shenandoah: false }] {
                    let args = hotspot(8192, java, policy, env);
                    let selectors = args.iter().filter(|a| a.starts_with("-XX:+Use") && a.ends_with("GC")).count();
                    assert_eq!(selectors, 1, "java {java}, {policy} : {args:?}");
                }
            }
        }
    }

    /// Les drapeaux *experimental* ne sont lus qu'après leur déverrouillage,
    /// sinon la JVM refuse de démarrer.
    #[test]
    fn le_deverrouillage_precede_les_drapeaux_experimentaux() {
        for java in [17, 21, 25] {
            let args = hotspot(6144, java, "auto", ROOMY);
            let unlock = args.iter().position(|a| a == "-XX:+UnlockExperimentalVMOptions").unwrap();
            for flag in ["-XX:+UseCriticalJavaThreadPriority", "-XX:ShenandoahGuaranteedGCInterval=1000000", "-XX:-ShenandoahUncommit"] {
                let at = args.iter().position(|a| a == flag).unwrap_or_else(|| panic!("{flag} absent en Java {java}"));
                assert!(unlock < at, "{flag} avant le déverrouillage en Java {java}");
            }
        }
    }

    /// Chaque drapeau propre à une version de Java reste dans sa version :
    /// inconnu ailleurs, il empêcherait le lancement.
    #[test]
    fn les_drapeaux_dates_restent_dans_leur_version() {
        assert!(has(&hotspot(4096, 25, "auto", ROOMY), "-XX:+UseCompactObjectHeaders"));
        assert!(!has(&hotspot(4096, 21, "auto", ROOMY), "-XX:+UseCompactObjectHeaders"));
        assert!(!has(&hotspot(4096, 17, "auto", ROOMY), "-XX:+UseCompactObjectHeaders"));
        // Java 8 garde sa branche d'origine, sans rien de la grille récente.
        let legacy = hotspot(4096, 8, "auto", ROOMY);
        assert!(has(&legacy, "-XX:ReservedCodeCacheSize=350m"));
        for flag in ["-XX:+UseCompactObjectHeaders", "-XX:MetaspaceSize=256m", "-XX:ReservedCodeCacheSize=400M", "-XX:-DontCompileHugeMethods"] {
            assert!(!has(&legacy, flag), "{flag} en Java 8");
        }
    }

    /// Le compilateur ne dépend pas du collecteur : mêmes réglages sous
    /// Shenandoah, G1 de repli et ZGC manuel. Jamais les trois segments du
    /// code cache, qui cassent le lancement dès que le total change.
    #[test]
    fn le_compilateur_est_regle_pour_tous_les_collecteurs_recents() {
        for policy in ["auto", "g1", "zgc"] {
            let args = hotspot(8192, 25, policy, ROOMY);
            for flag in ["-XX:-DontCompileHugeMethods", "-XX:MaxNodeLimit=240000", "-XX:NodeLimitFudgeFactor=8000", "-XX:ReservedCodeCacheSize=400M"] {
                assert!(has(&args, flag), "{flag} absent sous {policy}");
            }
            assert!(!args.iter().any(|a| a.contains("CodeHeapSize")), "segments du code cache sous {policy}");
        }
    }

    #[test]
    fn le_tas_garde_sa_reserve_et_le_pretouch_son_seuil() {
        // 6 Go alloués : la config de référence, 5530 Mo de tas.
        let args = hotspot(6144, 25, "auto", ROOMY);
        assert!(has(&args, "-Xmx5530m") && has(&args, "-Xms5530m"));
        assert!(has(&args, "-XX:+AlwaysPreTouch"));
        assert!(!has(&hotspot(2560, 25, "auto", ROOMY), "-XX:+AlwaysPreTouch"));
    }

    /// Poser son propre collecteur retire aussi les réglages de Shenandoah
    /// générés, qui n'ont de sens que sous lui.
    #[test]
    fn un_selecteur_utilisateur_retire_le_bloc_shenandoah() {
        let args = build_jvm_args(6144, std::path::Path::new("natives"), 25, JvmVendor::Temurin, "auto", "-XX:+UseG1GC", "append", ROOMY);
        assert!(has(&args, "-XX:+UseG1GC"));
        assert!(!args.iter().any(|a| a.contains("Shenandoah")), "{args:?}");
        // Le compilateur, lui, reste.
        assert!(has(&args, "-XX:ReservedCodeCacheSize=400M"));
    }

    /// OpenJ9 n'est jamais sondé (il accepte n'importe quel `-XX` en
    /// silence), ni une configuration dont la réponse ne changerait rien.
    #[test]
    fn la_sonde_ne_part_que_si_sa_reponse_compte() {
        assert!(needs_shenandoah_probe(25, JvmVendor::Temurin, "auto", 16));
        assert!(needs_shenandoah_probe(25, JvmVendor::Custom, "shenandoah", 4));
        assert!(!needs_shenandoah_probe(25, JvmVendor::Temurin, "auto", 4));
        assert!(!needs_shenandoah_probe(25, JvmVendor::Temurin, "g1", 16));
        assert!(!needs_shenandoah_probe(25, JvmVendor::Temurin, "zgc", 16));
        assert!(!needs_shenandoah_probe(8, JvmVendor::Temurin, "auto", 16));
        assert!(!needs_shenandoah_probe(25, JvmVendor::OpenJ9, "auto", 16));
    }

    /// Passe chaque configuration de la grille à de vraies JVM : un drapeau
    /// inconnu ou mal ordonné empêche le jeu de démarrer, et seule la JVM
    /// sait le dire. À relancer dès qu'un drapeau entre dans la grille :
    ///
    /// `YF_RUNTIMES=%AppData%\YuyuFrame\.minecraft\runtime cargo test --lib
    /// la_grille_demarre_sur_les_runtimes -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn la_grille_demarre_sur_les_runtimes() {
        let root = std::env::var("YF_RUNTIMES").expect("YF_RUNTIMES : dossier des runtimes");
        let mut checked = 0;
        for (dir, java_major) in [("jre-legacy", 8), ("jre-legacy-temurin", 8), ("java-runtime-gamma", 17), ("java-runtime-delta", 21), ("java-runtime-epsilon", 25)] {
            let java = std::path::Path::new(&root).join(dir).join("bin").join(if cfg!(windows) { "java.exe" } else { "java" });
            if !java.is_file() {
                println!("{dir} : absent, ignoré");
                continue;
            }
            let shenandoah = std::process::Command::new(&java)
                .args(["-XX:+UseShenandoahGC", "-version"])
                .output()
                .is_ok_and(|o| o.status.success());
            for policy in ["auto", "g1", "zgc", "shenandoah"] {
                for logical_cores in [4, 16] {
                    for ram_mb in [2560, 4096, 8192] {
                        let env = GcEnv { logical_cores, shenandoah };
                        // Les chemins des natives n'ont pas à exister pour `-version`.
                        let args = build_jvm_args(ram_mb, std::path::Path::new("natives"), java_major, JvmVendor::Temurin, policy, "", "append", env);
                        let out = std::process::Command::new(&java).args(&args).arg("-version").output().unwrap();
                        assert!(
                            out.status.success(),
                            "{dir} refuse {policy} / {logical_cores} cœurs / {ram_mb} Mo :\n{}\n{args:?}",
                            String::from_utf8_lossy(&out.stderr)
                        );
                        checked += 1;
                    }
                }
            }
            println!("{dir} (Java {java_major}, Shenandoah : {shenandoah}) : accepté");
        }
        assert!(checked > 0, "aucun runtime trouvé sous {root}");
    }

    // ── Fusion avec les drapeaux de l'utilisateur ────────────────────────────

    /// Le drapeau n'existe qu'à partir de Java 22 : une JVM plus ancienne
    /// refuserait de démarrer.
    #[test]
    fn acces_natif_seulement_a_partir_de_java_22() {
        assert_eq!(native_access_arg(8), None);
        assert_eq!(native_access_arg(21), None);
        assert_eq!(native_access_arg(22).as_deref(), Some("--enable-native-access=ALL-UNNAMED"));
        assert_eq!(native_access_arg(25).as_deref(), Some("--enable-native-access=ALL-UNNAMED"));
    }

    /// Pas du tuning : le mode "replace" ne doit pas le retirer.
    #[test]
    fn replace_garde_l_acces_natif() {
        let mut gen = generated();
        gen.push("--enable-native-access=ALL-UNNAMED".to_string());
        let out = merge_jvm_args(gen, "-XX:+UseParallelGC", "replace");
        assert!(out.contains(&"--enable-native-access=ALL-UNNAMED".to_string()));
    }

    /// `--add-exports` n'existe pas en Java 8 (versions legacy sans agent).
    #[test]
    fn export_jndi_dns_seulement_a_partir_de_java_9() {
        const FLAG: &str = "--add-exports=jdk.naming.dns/com.sun.jndi.dns=ALL-UNNAMED";
        assert_eq!(jndi_dns_export_arg(8), None);
        assert_eq!(jndi_dns_export_arg(17).as_deref(), Some(FLAG));
        assert_eq!(jndi_dns_export_arg(25).as_deref(), Some(FLAG));

        let mut gen = generated();
        gen.push(FLAG.to_string());
        let out = merge_jvm_args(gen, "-XX:+UseParallelGC", "replace");
        assert!(out.contains(&FLAG.to_string()));
    }

    fn generated() -> Vec<String> {
        ["-Xmx6452m", "-Xms6452m", "-Djava.library.path=C:\natives", "-XX:+AlwaysPreTouch",
         "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100", "-XX:G1NewSizePercent=30"]
            .iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn parse_ignore_les_commentaires_et_decoupe_sur_les_espaces() {
        let raw = "# un commentaire\n-XX:+UseNUMA -XX:MaxNodeLimit=240000\n\n  -Xss2m  ";
        assert_eq!(parse_user_jvm_args(raw), vec!["-XX:+UseNUMA", "-XX:MaxNodeLimit=240000", "-Xss2m"]);
    }

    /// Sans arguments manuels, rien ne doit bouger — c'est le cas de la très
    /// grande majorité des instances.
    #[test]
    fn append_sans_arguments_ne_change_rien() {
        assert_eq!(merge_jvm_args(generated(), "", "append"), generated());
    }

    /// Un drapeau utilisateur écrase son homologue généré au lieu de
    /// s'ajouter à côté : la valeur générée ne doit plus apparaître du tout.
    #[test]
    fn un_drapeau_utilisateur_ecrase_le_genere() {
        let out = merge_jvm_args(generated(), "-XX:MaxGCPauseMillis=37", "append");
        assert!(!out.contains(&"-XX:MaxGCPauseMillis=100".to_string()));
        assert!(out.contains(&"-XX:MaxGCPauseMillis=37".to_string()));
        assert!(out.contains(&"-XX:+UseG1GC".to_string()), "le reste du tuning généré est conservé");
    }

    /// La forme négative est le même réglage, pas un drapeau différent —
    /// sinon `-XX:+AlwaysPreTouch` et `-XX:-AlwaysPreTouch` cohabiteraient.
    #[test]
    fn la_forme_negative_ecrase_la_forme_positive() {
        let out = merge_jvm_args(generated(), "-XX:-AlwaysPreTouch", "append");
        assert!(!out.contains(&"-XX:+AlwaysPreTouch".to_string()));
        assert!(out.contains(&"-XX:-AlwaysPreTouch".to_string()));
    }

    /// Le cas qui rendrait l'instance impossible à lancer : deux sélecteurs
    /// de GC ("Multiple garbage collectors selected"). Poser le sien doit
    /// aussi emporter les réglages G1 générés, invalides ailleurs.
    #[test]
    fn un_selecteur_de_gc_utilisateur_retire_tout_le_bloc_gc_genere() {
        let out = merge_jvm_args(generated(), "-XX:+UseShenandoahGC", "append");
        assert!(!out.contains(&"-XX:+UseG1GC".to_string()));
        assert!(!out.contains(&"-XX:G1NewSizePercent=30".to_string()));
        assert!(out.contains(&"-XX:+UseShenandoahGC".to_string()));
        assert!(out.contains(&"-Xmx6452m".to_string()), "le heap n'est jamais touché");
    }

    /// En "replace", seule la base sans laquelle le jeu ne démarre pas reste.
    #[test]
    fn replace_ne_garde_que_la_base_obligatoire() {
        let out = merge_jvm_args(generated(), "-XX:+UseParallelGC", "replace");
        assert_eq!(out, vec![
            "-Xmx6452m".to_string(),
            "-Xms6452m".to_string(),
            "-Djava.library.path=C:\natives".to_string(),
            "-XX:+UseParallelGC".to_string(),
        ]);
    }

    /// Même en "replace", un -Xmx tapé à la main l'emporte sur celui déduit
    /// de la RAM choisie — il ne doit pas se retrouver en double.
    #[test]
    fn replace_laisse_l_utilisateur_redefinir_le_heap() {
        let out = merge_jvm_args(generated(), "-Xmx4g", "replace");
        assert_eq!(out.iter().filter(|a| a.starts_with("-Xmx")).count(), 1);
        assert!(out.contains(&"-Xmx4g".to_string()));
    }
}
