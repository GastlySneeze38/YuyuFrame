package com.yuyuframe.launcheragent.runtime.log;

/**
 * Gestion centralisée des logs LauncherAgent avec niveaux de priorité par catégorie.
 *
 * Niveau d'un appel   : 1=verbose  2=info  3=critique
 * Seuil d'une catégorie : 0=désactivé  1=tout  2=info+critique  3=critique seul
 *
 * Un log s'affiche sur la CONSOLE si : seuil > 0 ET niveau_appel >= seuil.
 * En revanche, TOUT log (quel que soit le seuil) est aussi écrit dans
 * %APPDATA%\YuyuFrame\agent\logs\launcher-agent.log — la capture stdout/stderr
 * du launcher Rust s'est avérée peu fiable pendant le diagnostic du pipeline
 * 1.8.9 (lignes manquantes sans rapport avec l'exécution réelle du code) ;
 * ce fichier permet de vérifier après coup ce qui s'est vraiment passé, sans
 * dépendre de cette capture.
 */
public final class LauncherLog {

    private LauncherLog() {}

    /** Modification d'UI Minecraft — ScreenHelper, mixins clients. */
    public static volatile int UI    = 3;
    /** Patches ASM au démarrage. */
    public static volatile int ASM   = 3;
    /** Scan/retransform de l'agent Java au démarrage. */
    public static volatile int AGENT = 3;
    /** Recherche/téléchargement Modrinth. */
    public static volatile int CONTENT = 3;

    public static volatile boolean SHOW_CATEGORY = true;

    public static void loadConfig(java.util.Properties p) {
        UI      = intProp(p, "log.ui",      UI);
        ASM     = intProp(p, "log.asm",     ASM);
        AGENT   = intProp(p, "log.agent",   AGENT);
        CONTENT = intProp(p, "log.content", CONTENT);
        SHOW_CATEGORY = boolProp(p, "log.show_category", SHOW_CATEGORY);
    }

    private static int intProp(java.util.Properties p, String key, int fallback) {
        try { return Integer.parseInt(p.getProperty(key, String.valueOf(fallback)).trim()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static boolean boolProp(java.util.Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        return v != null ? "true".equalsIgnoreCase(v.trim()) : fallback;
    }

    public static void ui(String msg)      { log("UI",      UI,      2, msg); }
    public static void asm(String msg)     { log("ASM",     ASM,     2, msg); }
    public static void agent(String msg)   { log("AGENT",   AGENT,   2, msg); }
    public static void content(String msg) { log("CONTENT", CONTENT, 2, msg); }

    public static void ui(int lvl, String msg)      { log("UI",      UI,      lvl, msg); }
    public static void asm(int lvl, String msg)     { log("ASM",     ASM,     lvl, msg); }
    public static void agent(int lvl, String msg)   { log("AGENT",   AGENT,   lvl, msg); }
    public static void content(int lvl, String msg) { log("CONTENT", CONTENT, lvl, msg); }

    public static void info(String msg) {
        System.out.println(msg);
        toFile(msg);
    }

    public static Fatal fatal(String msg) {
        System.err.println("[FATAL] " + msg);
        toFile("[FATAL] " + msg);
        throw new Fatal(msg);
    }

    public static final class Fatal extends RuntimeException {
        public Fatal(String msg) { super(msg); }
    }

    public static void err(String msg)  { System.err.println("[ERR] " + msg); toFile("[ERR] " + msg); }
    public static void warn(String msg) { System.err.println("[WARN] " + msg); toFile("[WARN] " + msg); }

    private static void log(String category, int threshold, int level, String msg) {
        String line = (level >= 3 ? "[!] " : "")
                    + (SHOW_CATEGORY ? "[" + category + "] " : "")
                    + msg;
        toFile(line);
        if (threshold == 0 || level < threshold) return;
        System.out.println(line);
    }

    // ── Fichier permanent, indépendant de la capture console (peu fiable) ────

    private static final String LOG_PATH =
        System.getenv("APPDATA") != null
            ? System.getenv("APPDATA") + "\\YuyuFrame\\agent\\logs\\launcher-agent.log"
            : null;

    private static synchronized void toFile(String line) {
        if (LOG_PATH == null) return;
        try {
            java.io.File f = new java.io.File(LOG_PATH);
            java.io.File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            try (java.io.FileWriter fw = new java.io.FileWriter(f, true)) {
                fw.write("[" + System.currentTimeMillis() + "] " + line + "\n");
            }
        } catch (Throwable ignored) {}
    }
}
