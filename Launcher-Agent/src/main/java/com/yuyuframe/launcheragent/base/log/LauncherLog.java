package com.yuyuframe.launcheragent.base.log;

/**
 * Gestion centralisée des logs LauncherAgent avec niveaux de priorité par catégorie.
 *
 * <h2>Pourquoi cette classe vit dans {@code base/} (2026-09-09)</h2>
 *
 * Elle était dans {@code runtime/log/}, et importée par 153 fichiers — dont 24
 * d'{@code apigraphic} et 13 d'{@code apimixin}. Or {@code runtime/} est la
 * couche du DESSUS : ces 37 imports étaient des dépendances montantes, et
 * empêchaient à eux seuls le moteur graphique et la couche d'injection de
 * respecter la règle de couches (module → apigraphic → apimixin → base).
 *
 * <p>{@code base/} est le socle : il ne dépend de RIEN (ce fichier n'a aucun
 * import), et tout le monde a le droit d'en dépendre. C'est la seule position
 * possible pour un service transverse comme le log.
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

    // Référence figée à System.out/err AU CHARGEMENT DE CETTE CLASSE (donc au
    // tout premier appel de LauncherAgent.premain(), avant tout autre code) —
    // PAS System.out/err lus dynamiquement à chaque appel. Si quoi que ce soit
    // plus tard dans le bootstrap (Mixin, LWJGL, SoundSystem...) appelle
    // System.setOut()/setErr() pour rediriger le flux vers un autre
    // PrintStream, nos logs continuent d'écrire vers le flux ORIGINAL
    // (toujours connecté au pipe que Rust lit), au lieu de silencieusement
    // suivre la redirection et disparaître de ce que le launcher capture.
    //
    // UTF-8 EXPLICITE (2026-09-15) : enveloppe du flux d'origine, qui écrit
    // les octets tels quels. Sans elle, la JVM encode en Cp1252 sous Windows
    // (codage de la plateforme sur un tube), et le launcher lit de l'UTF-8 :
    // chaque accent devenait un caractère illisible — et, avant le correctif
    // côté Rust, le premier accent coupait carrément la capture.
    private static final java.io.PrintStream ORIGINAL_OUT =
        new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8);
    private static final java.io.PrintStream ORIGINAL_ERR =
        new java.io.PrintStream(System.err, true, java.nio.charset.StandardCharsets.UTF_8);

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

    private static final String PROPS_FILENAME = "launcher-agent.properties";

    /**
     * Lit launcher-agent.properties (JAR embarqué, puis externe qui prend le
     * dessus) et applique aussitôt les seuils — appelé en tout premier dans
     * LauncherAgent.premain(), AVANT le moindre autre log.
     *
     * Sans cet appel précoce, les seuils restent à leur valeur par défaut (3 =
     * critique seul) jusqu'à ce que LauncherMixinConfigPlugin.onLoad() charge
     * la même config, ce qui n'arrive QUE tard dans le bootstrap (à
     * l'intérieur de Mixins.addConfiguration(), après loadYarnMappings/
     * writeRefmapFile/discoverMixinTargets) — la quasi-totalité des logs de
     * démarrage utiles (niveau 1/2) se retrouvait donc filtrée de la console
     * avant même que log.agent=1 (etc.) ne soit lu, alors que le fichier de
     * log (toFile(), toujours écrit) les contenait déjà tous. onLoad()
     * continue d'appeler loadConfig() une seconde fois — redondant mais sans
     * risque, et nécessaire pour capter un fichier externe déposé/modifié
     * entre l'appel précoce et le chargement de la config Mixin.
     */
    public static void loadConfigFromDefaultLocations(ClassLoader cl) {
        loadConfig(loadPropertiesFromDefaultLocations(cl));
    }

    /** Réutilisé par LauncherMixinConfigPlugin (a aussi besoin des Properties brutes pour ses propres clés). */
    public static java.util.Properties loadPropertiesFromDefaultLocations(ClassLoader cl) {
        java.util.Properties props = new java.util.Properties();

        try (java.io.InputStream is = cl.getResourceAsStream(PROPS_FILENAME)) {
            if (is != null) props.load(is);
        } catch (Exception ignored) {}

        String externPath = System.getenv("APPDATA") != null
            ? System.getenv("APPDATA") + "\\YuyuFrame\\agent\\" + PROPS_FILENAME
            : null;
        if (externPath != null) {
            java.io.File external = new java.io.File(externPath);
            if (external.exists()) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(external)) {
                    props.load(fis);
                } catch (Exception ignored) {}
            }
        }

        return props;
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

    /**
     * Roadmap Phase 6 ("LauncherLog construit la string avant de vérifier si
     * le niveau de log est actif") — variantes {@link java.util.function.Supplier}
     * pour un futur log posé dans un hot path (frame/tick) : {@code msg.get()}
     * n'est appelé qu'UNE FOIS, à l'intérieur de {@link #log}, jamais construit
     * par concaténation au point d'appel comme {@code ui(lvl, "x=" + calc())}
     * le force aujourd'hui (Java évalue l'argument AVANT l'appel de méthode,
     * qu'il soit lu ensuite ou non). Piège à connaître : {@link #toFile}
     * reste appelé INCONDITIONNELLEMENT quel que soit le seuil (voir javadoc
     * de classe — capture stdout/stderr du launcher Rust jugée peu fiable),
     * donc {@code msg.get()} est de toute façon toujours invoqué ici ; le
     * vrai gain de cette forme n'est PAS d'éviter l'appel, mais d'éviter que
     * le call-site lui-même paye la construction dans le cas — fréquent pour
     * un futur appel bien écrit — où {@code msg} est un lambda qui capture
     * des références déjà en main plutôt qu'une concaténation déjà faite.
     */
    public static void ui(int lvl, java.util.function.Supplier<String> msg)      { log("UI",      UI,      lvl, msg.get()); }
    public static void asm(int lvl, java.util.function.Supplier<String> msg)     { log("ASM",     ASM,     lvl, msg.get()); }
    public static void agent(int lvl, java.util.function.Supplier<String> msg)   { log("AGENT",   AGENT,   lvl, msg.get()); }
    public static void content(int lvl, java.util.function.Supplier<String> msg) { log("CONTENT", CONTENT, lvl, msg.get()); }

    public static void info(String msg) {
        ORIGINAL_OUT.println(msg);
        toFile(msg, 1);
    }

    public static Fatal fatal(String msg) {
        ORIGINAL_ERR.println("[FATAL] " + msg);
        toFile("[FATAL] " + msg, 3);
        throw new Fatal(msg);
    }

    public static final class Fatal extends RuntimeException {
        public Fatal(String msg) { super(msg); }
    }

    public static void err(String msg)  { ORIGINAL_ERR.println("[ERR] " + msg); toFile("[ERR] " + msg, 3); }
    public static void warn(String msg) { ORIGINAL_ERR.println("[WARN] " + msg); toFile("[WARN] " + msg, 3); }

    /**
     * Erreur AVEC sa pile d'appels complète (causes et exceptions supprimées
     * comprises), en console ET dans le fichier.
     *
     * <p>Remplace le couple {@code err("..." + e.getMessage())} +
     * {@code e.printStackTrace(System.err)} (2026-09-15). Ce couple perdait la
     * cause deux fois : le message seul ne dit presque rien
     * ({@code "net/minecraft/launchwrapper/LaunchClassLoader"} au premier
     * lancement de la 1.8.9 dégelée), et {@code System.err} lu à l'appel est
     * redirigé vers log4j par Minecraft 1.8.9 ({@code Bootstrap}) — la pile
     * n'atteignait ni la console du launcher ni ce fichier.
     *
     * <p>Toujours affichée, quel que soit le seuil : c'est une erreur.
     */
    public static void err(String msg, Throwable t) {
        err(msg + (t == null ? "" : " : " + t));
        if (t == null) return;
        java.io.StringWriter buffer = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(buffer));
        String[] lines = buffer.toString().split("\r?\n");
        // Ligne 0 = t.toString(), déjà dans le message.
        for (int i = 1; i < lines.length; i++) err("    " + lines[i].trim());
    }

    private static void log(String category, int threshold, int level, String msg) {
        String line = (level >= 3 ? "[!] " : "")
                    + (SHOW_CATEGORY ? "[" + category + "] " : "")
                    + msg;
        toFile(line, level);
        if (threshold == 0 || level < threshold) return;
        ORIGINAL_OUT.println(line);
    }

    // ── Fichier permanent, indépendant de la capture console (peu fiable) ────

    private static final String LOG_PATH =
        System.getenv("APPDATA") != null
            ? System.getenv("APPDATA") + "\\YuyuFrame\\agent\\logs\\launcher-agent.log"
            : null;

    private static java.io.Writer fileWriter;

    /** Posée par la première copie de cette classe qui ouvre le fichier dans cette JVM — voir {@link #toFile}. */
    private static final String LOG_OPENED_PROPERTY = "launcheragent.logFileOpened";
    /** Une fois le journal fichier en échec, ne plus jamais retenter : sinon on
     * repaie une exception d'E/S à chaque ligne, ce que cette méthode est
     * précisément censée éviter. */
    private static boolean fileDisabled;
    /** Writer ouvert pendant l'arrêt de la JVM, sans hook de fermeture : chaque ligne est vidée. */
    private static boolean flushEveryLine;

    /**
     * BUG TROUVÉ (2026-09-04, écart de FPS d'un facteur deux avec un launcher
     * concurrent) : cette méthode ouvrait un {@code FileWriter} — précédé d'un
     * {@code mkdirs()} — et le refermait <b>à chaque ligne</b>. Avec un module
     * HUD qui journalise à chaque frame (voir {@code SaturationModule}), ça
     * faisait plusieurs ouvertures/fermetures de fichier par image rendue :
     * 771 Mo de journal pour une seule session, et le thread de rendu bloqué
     * sur le disque à chaque appel.
     *
     * <p>Un seul writer, ouvert à la première ligne et tamponné, règle les
     * deux. Le tampon n'est vidé que sur un avertissement/erreur ({@code level
     * >= 3}) et à l'arrêt de la JVM : une ligne d'information perdue lors d'un
     * crash brutal ne coûte rien, alors qu'un {@code flush} par ligne
     * ramènerait l'essentiel du problème.
     */
    private static synchronized void toFile(String line, int level) {
        if (LOG_PATH == null || fileDisabled) return;
        try {
            if (fileWriter == null) {
                java.io.File f = new java.io.File(LOG_PATH);
                java.io.File dir = f.getParentFile();
                if (dir != null) dir.mkdirs();
                // Repart de ZÉRO à chaque lancement (2026-09-15) : jamais vidé
                // jusque-là, le fichier atteignait 833 Mo. Une seule copie de
                // cette classe vide le fichier : l'agent est chargé par plusieurs
                // classloaders dans la même JVM (bootstrap isolé, classloader du
                // jeu), et chaque copie ouvre son propre writer — une seconde
                // troncature effacerait les lignes de la première. La propriété
                // système est le seul état partagé entre ces copies.
                boolean append = System.getProperty(LOG_OPENED_PROPERTY) != null;
                System.setProperty(LOG_OPENED_PROPERTY, "true");
                fileWriter = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(f, append), java.nio.charset.StandardCharsets.UTF_8), 1 << 16);
                try {
                    Runtime.getRuntime().addShutdownHook(new Thread(LauncherLog::closeFile, "yuyu-log-close"));
                } catch (IllegalStateException shuttingDown) {
                    // Première écriture de CETTE copie pendant l'arrêt de la JVM
                    // (vu le 2026-09-15 : thread de retransform qui journalise
                    // pendant le crash). Plus de hook possible : écrire quand
                    // même, en vidant à chaque ligne, plutôt que de désactiver le
                    // journal au moment précis où il sert.
                    flushEveryLine = true;
                }
            }
            fileWriter.write("[" + System.currentTimeMillis() + "] " + line + "\n");
            if (level >= 3 || flushEveryLine) fileWriter.flush();
        } catch (Throwable t) {
            // On EST le journal : impossible de se logger soi-même ici sans
            // risquer la récursion. Une ligne sur la sortie d'origine, et on
            // se désactive.
            fileDisabled = true;
            ORIGINAL_ERR.println("[ERR] [LauncherLog] journal fichier désactivé : " + t);
        }
    }

    /** Tâches à exécuter à l'arrêt de la JVM AVANT la fermeture du fichier — voir {@link #addShutdownTask}. */
    private static final java.util.List<Runnable> SHUTDOWN_TASKS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Enregistre une tâche d'arrêt qui doit encore pouvoir JOURNALISER.
     *
     * <p>Un hook d'arrêt séparé tournerait en parallèle de celui qui ferme le
     * fichier (la JVM ne garantit aucun ordre) : ses lignes pourraient arriver
     * après la fermeture et être perdues. Exécutées ici, juste avant.
     */
    public static void addShutdownTask(Runnable task) {
        SHUTDOWN_TASKS.add(task);
    }

    private static void closeFile() {
        for (Runnable task : SHUTDOWN_TASKS) {
            try {
                task.run();
            } catch (Throwable t) {
                ORIGINAL_ERR.println("[ERR] [LauncherLog] tâche d'arrêt en échec : " + t);
            }
        }
        closeWriter();
    }

    private static synchronized void closeWriter() {
        if (fileWriter == null) return;
        try { fileWriter.flush(); fileWriter.close(); } catch (Throwable ignored) {}
        fileWriter = null;
    }
}
