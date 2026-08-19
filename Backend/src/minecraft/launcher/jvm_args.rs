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
    /// Chemin JVM fourni intégralement par l'utilisateur (`custom_path`) —
    /// aucune résolution/téléchargement, aucune hypothèse sur le vendeur
    /// réel : traité comme HotSpot pour le choix des flags GC (le cas très
    /// majoritaire des JVM "custom" en pratique).
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
pub(super) fn resolve_auto_vendor(ram_mb: u32) -> &'static str {
    if ram_mb < 3072 { "openj9" } else { "temurin" }
    }
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
pub(super) fn build_jvm_args(ram_mb: u32, natives_dir: &Path, java_major: u32, vendor: JvmVendor, gc_policy: &str) -> Vec<String> {
    match vendor {
        JvmVendor::OpenJ9 => build_openj9_jvm_args(ram_mb, natives_dir, gc_policy),
        JvmVendor::Temurin | JvmVendor::Graal | JvmVendor::Custom => build_hotspot_jvm_args(ram_mb, natives_dir, java_major, gc_policy),
    }
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

    let already_set = tokio::process::Command::new("reg")
        .args(["query", KEY, "/v", java_exe])
        .output()
        .await
        .map(|out| out.status.success())
        .unwrap_or(false);
    if already_set {
        return;
    }

    let _ = tokio::process::Command::new("reg")
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
