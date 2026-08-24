package com.yuyuframe.launcheragent.agent;

import java.util.UUID;

/**
 * Configuration passée via : -javaagent:launcher-agent.jar=yarn=...,version=...
 *
 * Paramètres supportés :
 *   yarn=<chemin>     Yarn mappings tiny v2 (JAR mergedv2 ou .tiny direct).
 *                     Pour 1.8.9 : fournir un JAR Legacy Fabric Yarn 1.8.9.
 *   version=<ver>     Force la version MC (ex: "1.8.9"). Par défaut auto-détectée
 *                     via -Dminecraft.version ou sonde de classes.
 *   loader=<nom>      "vanilla", "fabric", "quilt", "forge" ou "neoforge" —
 *                     connu avec certitude côté Rust (voir launcher/agents.rs).
 */
public class AgentConfig {

    /** ID de session unique par JVM — affichage debug uniquement. */
    public final String instanceId = UUID.randomUUID().toString();
    /**
     * Chemin vers les mappings Yarn tiny v2.
     * Accepte un .tiny direct OU un JAR Yarn mergedv2 (ex: yarn-1.21.1+build.X-mergedv2.jar).
     * Pour 1.8.9 : fournir legacy-yarn-1.8.9+build.X-mergedv2.jar (Legacy Fabric).
     * Null si non fourni — dans ce cas l'agent tente une auto-détection dans les caches locaux.
     */
    public String yarnPath;

    /**
     * Version MC forcée par l'arg "version=..." — null = auto-détection au runtime
     * via MinecraftVersionDetector (recommandé, plus fiable).
     */
    public String forcedVersion;

    /**
     * Nom du Named Event Win32 (arg "readyEvent=...") à signaler une fois le
     * menu principal atteint — voir ReadyEventSignal. Null si non fourni
     * (non-Windows, ou création échouée côté Rust) : le fallback stdout+
     * fichier (marqueur [YUYUFRAME_READY]) reste alors le seul canal.
     */
    public String readyEvent;

    /**
     * Loader choisi par l'utilisateur (arg "loader=...") — "vanilla",
     * "fabric", "quilt", "forge" ou "neoforge". Le Rust le connaît avec
     * certitude (voir launcher/agents.rs, setup_launcher_agent) — consommé
     * par {@code LauncherAgent.resolveLoaderName()}/{@code needsIsolation()}/
     * {@code usesIntermediaryMappings()} pour la décision d'isolation Mixin
     * (P0-2/P0-3/P0-5, voir docs/launcher/audit/README-bugs-a-fix.md — fait,
     * ce n'est plus le "devinage" que ce commentaire décrivait).
     */
    public String loader;

    private static AgentConfig current;

    public static AgentConfig parse(String args) {
        AgentConfig cfg = new AgentConfig();

        if (args != null && !args.isEmpty()) {
            for (String pair : args.split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length != 2) continue;
                switch (kv[0].trim()) {
                    case "yarn":       cfg.yarnPath      = kv[1].trim(); break;
                    case "version":    cfg.forcedVersion = kv[1].trim(); break;
                    case "readyEvent": cfg.readyEvent    = kv[1].trim(); break;
                    case "loader":     cfg.loader        = kv[1].trim(); break;
                }
            }
        }

        current = cfg;
        return cfg;
    }

    public static AgentConfig getCurrent() {
        return current != null ? current : parse(null);
    }
}
