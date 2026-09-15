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
/// budget serré) ; au-delà → Temurin (le choix de GC exact — G1 ou ZGC — est
/// déjà décidé par `build_hotspot_jvm_args` sur `ram_mb`/`java_major`).
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
pub(super) fn build_jvm_args(ram_mb: u32, natives_dir: &Path, java_major: u32, vendor: JvmVendor, gc_policy: &str, extra_args: &str, args_mode: &str) -> Vec<String> {
    let mut generated = match vendor {
        JvmVendor::OpenJ9 => build_openj9_jvm_args(ram_mb, natives_dir, gc_policy),
        JvmVendor::Temurin | JvmVendor::Graal | JvmVendor::Custom => build_hotspot_jvm_args(ram_mb, natives_dir, java_major, gc_policy),
    };
    if let Some(flag) = native_access_arg(java_major) {
        generated.push(flag);
    }
    merge_jvm_args(generated, extra_args, args_mode)
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
pub(super) fn parse_user_jvm_args(raw: &str) -> Vec<String> {
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
/// `native_access_arg`) : il reste aussi.
fn is_mandatory_base(arg: &str) -> bool {
    arg.starts_with("-Xmx")
        || arg.starts_with("-Xms")
        || arg.starts_with("-Djava.library.path=")
        || arg.starts_with("-Dorg.lwjgl.librarypath=")
        || arg.starts_with("--enable-native-access=")
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

fn build_hotspot_jvm_args(ram_mb: u32, natives_dir: &Path, java_major: u32, gc_policy: &str) -> Vec<String> {
    // P1-5 (audit launcher) : marge sous le plafond mémoire demandé — réserve
    // ~10% (plancher 200 Mo) pour le natif (rendu, GLFW/LWJGL, DLL, code
    // cache JIT, métaspace), jamais compté dans `ram_mb`. Sans cette marge,
    // -Xmx posé à la valeur brute demandée est la cause directe du risque OOM
    // sur les petites configs (ex: profil 2 Go → -Xmx2048m, zéro marge).
    let reserve_mb = (ram_mb / 10).max(200).min(ram_mb.saturating_sub(256));
    let heap_mb = ram_mb - reserve_mb;

    let mut base = vec![
        format!("-Xmx{}m", heap_mb),
        format!("-Xms{}m", heap_mb),  // Xms = Xmx : pas de redimensionnement du heap
        format!("-Djava.library.path={}", natives_dir.display()),
        format!("-Dorg.lwjgl.librarypath={}", natives_dir.display()),
        "-XX:+DisableExplicitGC".into(),       // Ignore System.gc() appelés par les mods
        "-XX:+PerfDisableSharedMem".into(),    // Pas de fichiers perf OS (source de jitter)
        "-XX:+UseStringDeduplication".into(),  // Réduit les doublons String en mémoire
    ];

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

    // P1-4 (audit launcher) : ZGC croisé sur `ram_mb` ET `java_major`, pas
    // seulement sur la version Java — son overhead structurel (colored
    // pointers, ~1.5x le jeu de données vivant contre ~1.15x pour G1) mange
    // une part disproportionnée d'un petit tas et rapproche du crash OOM.
    // Repli sur G1 en dessous de 6 Go quel que soit `java_major` : c'est le
    // palier "6 Go → max" de la grille jvm-config, à ne jamais franchir vers
    // ZGC (le palier "~2 Go" cible OpenJ9/gencon, voir `build_openj9_jvm_args`).
    //
    // P1-6 (Phase 6) : `gc_policy` permet un choix explicite ("g1"/"zgc")
    // depuis les paramètres avancés, mais le plancher de sécurité "jamais
    // ZGC en dessous de 6 Go" (doc jvm-config, "à ne jamais faire" — le
    // mismatch le plus dommageable de toute la grille) reste appliqué même
    // en override manuel, pas seulement en "auto".
    let use_zgc = match gc_policy {
        "g1" => false,
        _ => java_major >= 21 && ram_mb >= 6144, // "auto", "zgc" (sous réserve du plancher), ou toute valeur inconnue
    };
    if use_zgc {
        // ── ZGC Generational (Java 21+, ≥6 Go) ────────────────────────────────
        // GC concurrent : collecte en parallèle du jeu → pauses < 1ms
        // Élimine les freezes récurrents de 200ms causés par G1 mixed collections
        let mut args = base;
        // P2-2 (audit launcher) : UnlockExperimentalVMOptions en TÊTE de la
        // branche, comme les deux autres branches ci-dessous — jusqu'ici posé
        // en dernier, inoffensif tant que ZAllocationSpikeTolerance (un flag
        // *product*, pas *experimental*) reste seul après lui, mais un futur
        // flag réellement experimental ajouté à cette liste ferait sinon
        // refuser le démarrage de la JVM avec un message peu parlant.
        args.push("-XX:+UnlockExperimentalVMOptions".into());
        args.push("-XX:+UseZGC".into());
        // ZGenerational est le défaut depuis Java 24 — ne pas l'ajouter pour éviter le warning
        if java_major < 24 {
            args.push("-XX:+ZGenerational".into());
        }
        args.push("-XX:ZAllocationSpikeTolerance=5.0".into());
        args
    } else if java_major <= 8 {
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
        // ── G1GC (Java 9-20, ou Java 21+ en dessous de 6 Go) ──────────────────
        // ZGC non disponible (Java < 21) ou écarté par la grille RAM (P1-4,
        // heap trop petit pour amortir son overhead structurel) — fallback
        // G1GC avec tuning client dans les deux cas, les flags sont identiques.
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

    let already_set = crate::process::hidden_command("reg")
        .args(["query", KEY, "/v", java_exe])
        .output()
        .await
        .map(|out| out.status.success())
        .unwrap_or(false);
    if already_set {
        return;
    }

    let _ = crate::process::hidden_command("reg")
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
    use super::{merge_jvm_args, native_access_arg, parse_user_jvm_args};

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
