package com.yuyuframe.launcheragent.agent;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.version.MinecraftVersionDetector;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Java agent LauncherAgent — installation de resource packs Modrinth in-game.
 *
 * Totalement séparé du p2p-agent : propre JAR, propre package
 * (com.yuyuframe.launcheragent), propre copie des mappings Yarn et de la
 * plomberie Mixin standalone. Chargé comme javaagent additionnel, à côté de
 * p2p-agent.jar, dans la même JVM — aucune dépendance de code entre les deux.
 *
 * Toute la logique Mixin/ASM vit dans IsolatedBootstrap (voir ce fichier pour
 * le détail) — ce point d'entrée ne fait que décider COMMENT la charger :
 * directement (vanilla/Forge) ou via un classloader isolé dédié (Fabric, pour
 * ne jamais partager l'état statique de Mixin avec celui de Fabric Loader —
 * voir docs/LauncherAgent/index.md, historique des bugs Fabric).
 *
 * Voir docs/LauncherAgent/index.md pour le cahier des charges complet.
 */
public class LauncherAgent {

    private static final String BUILD_VERSION = "2026-07-03-v215";

    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            premain0(agentArgs, inst);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] premain() exception non capturée : " + t);
            t.printStackTrace(System.err);
            throw t;
        }
    }

    private static void premain0(String agentArgs, Instrumentation inst) {
        // Tout premier appel : lit launcher-agent.properties (log.agent=1 etc.)
        // et applique les seuils AVANT le moindre autre log. Sans ça, les
        // seuils restent à leur valeur par défaut (3 = critique seul) jusqu'à
        // ce que LauncherMixinConfigPlugin.onLoad() charge la même config —
        // qui n'arrive QUE tard dans le bootstrap Mixin, bien après la
        // plupart des logs de démarrage utiles (voir LauncherLog.loadConfigFromDefaultLocations).
        LauncherLog.loadConfigFromDefaultLocations(LauncherAgent.class.getClassLoader());

        // Filet de sécurité pour HudConfigStore (runtime.ui) : les points
        // d'accroche normaux (ConfigScreenBuilder, UiHudBox, toggle
        // d'activation) sauvegardent déjà à chaque changement, mais un futur
        // point de mutation oublié ne perdrait ainsi jamais les changements
        // en cours à la fermeture du jeu — save() est déjà défensif
        // (try/catch complet), sûr même si le classloader Fabric isolé est
        // déjà en cours de démontage.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { com.yuyuframe.launcheragent.runtime.ui.HudConfigStore.save(); } catch (Throwable ignored) {}
        }, "YuyuFrame-ConfigSave"));

        // Réchauffe UiFont/AWT Toolkit ICI, MAINTENANT, PENDANT premain() — pas
        // un simple souci de perf. UiFont mesure le texte via un
        // BufferedImage.createGraphics().getFontMetrics(), mais sous Java 8/
        // Windows ça déclenche quand même en interne Toolkit.getDefaultToolkit()
        // (FontDesignMetrics.getDefaultFrc() -> Win32GraphicsEnvironment ->
        // D3DGraphicsDevice.<clinit>), qui lui-même essaie d'enregistrer SON
        // PROPRE shutdown hook (AWTAutoShutdown). Si cette toute première
        // init AWT du process arrive DEPUIS un shutdown hook déjà en cours
        // (ex: le hook YuyuFrame-ConfigSave juste au-dessus, ou tout autre
        // hook, déclenché par un crash précoce de la JVM avant même que
        // Minecraft démarre) -> "Shutdown in progress" pendant l'init AWT ->
        // bloqué indéfiniment (observé : JVM figée des minutes, RAM occupée,
        // 0% CPU, jamais de logs/latest.log créé — le process ne quitte
        // jamais alors qu'il a déjà planté). En la forçant ici, sur le thread
        // principal, bien avant qu'un quelconque shutdown ne puisse démarrer,
        // toute réutilisation ultérieure (rendu HUD normal OU shutdown hook)
        // retombe sur un Toolkit déjà chaud, donc instantanée et sans risque.
        try {
            com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont.REGULAR.textWidth("YuyuFrame", 1f);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] Réchauffage UiFont/AWT échoué (non bloquant) : " + t);
        }

        // Doit être posé avant que Knot ne construise sa whitelist de codeSources
        // (validParentCodeSources) — sinon KnotClassDelegate.loadClass() refuse de
        // résoudre toute classe dont le jar (launcher-agent.jar, ajouté via
        // -javaagent, donc absent de cette whitelist) n'est pas "exposé au jeu" :
        // "ClassNotFoundException: ... as it hasn't been exposed to the game".
        // Flag de debug officiel de Fabric Loader prévu pour ce cas exact
        // (classpath non standard / outils externes), désactive tout le contrôle
        // d'isolation de classpath de Knot.
        System.setProperty("fabric.debug.disableClassPathIsolation", "true");

        LauncherLog.agent(3, "[LauncherAgent] ===== VERSION " + BUILD_VERSION + " =====");
        LauncherLog.agent(1, "[LauncherAgent] Démarrage (mode Mixin)...");

        AgentConfig config = AgentConfig.parse(agentArgs);
        LauncherLog.agent(1, "[LauncherAgent] instanceId=" + config.instanceId);

        // Version détectée ici (avant tout chargement Mixin) — system props déjà
        // posées par le launcher vanilla, donc détection fiable à ce stade.
        String mcVersion = config.forcedVersion != null
            ? config.forcedVersion
            : MinecraftVersionDetector.detect();
        LauncherLog.agent(1, "[LauncherAgent] version MC détectée : " + mcVersion);
        System.setProperty("launcheragent.mcVersion", mcVersion);

        boolean fabric = isFabricPresent();

        // Sous Fabric, le code tissé par Mixin dans les classes du jeu (la$onInit
        // de TitleScreenMixin/PackScreenMixin) est résolu par KnotClassLoader,
        // PAS par notre classloader isolé — donc MappingsRegistry/YarnMappings y
        // existent comme une COPIE STATIQUE SÉPARÉE, jamais initialisée par
        // IsolatedBootstrap (qui tourne sur le classloader isolé). Une System
        // property est le seul canal de configuration qui traverse vraiment
        // toutes les copies/classloaders — MappingsRegistry.ensureInitialized()
        // (appelé paresseusement à la première utilisation, quelle que soit la
        // copie de la classe) la relit pour se réinitialiser elle-même.
        if (config.yarnPath != null) System.setProperty("launcheragent.yarnPath", config.yarnPath);
        System.setProperty("launcheragent.fabric", String.valueOf(fabric));

        // Chemin du jar — lu par FabricKnotExposer pour enregistrer
        // launcher-agent.jar comme "code source" PROPRE à KnotClassLoader (pas
        // juste exposé à son parent). Sans ça, Knot délègue toute classe de ce
        // jar à AppClassLoader, qui ne peut jamais résoudre les superclasses
        // obfusquées (ex: net/minecraft/class_437) que ScreenStubPatcher
        // injecte dans nos écrans custom — voir FabricKnotExposer.
        java.io.File agentJarFile = agentDir();
        if (agentJarFile != null) {
            System.setProperty("launcheragent.jarPath",
                new java.io.File(agentJarFile, "launcher-agent.jar").getAbsolutePath());
        }

        if (fabric) {
            LauncherLog.agent(1, "[LauncherAgent] Fabric détecté — bootstrap Mixin via classloader isolé");
            startIsolated(inst, config.yarnPath, mcVersion);
        } else {
            IsolatedBootstrap.start(inst, config.yarnPath, false, mcVersion);
        }

        LauncherLog.agent(3, "[LauncherAgent] Prêt — en attente du chargement Minecraft");
    }

    /** Détecte si Fabric Loader est sur le classpath (présent ≠ encore initialisé). */
    private static boolean isFabricPresent() {
        try {
            Class.forName("net.fabricmc.loader.impl.launch.knot.Knot", false,
                LauncherAgent.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Charge IsolatedBootstrap (+ org.spongepowered.asm.*, MappingsRegistry,
     * etc.) via un URLClassLoader dédié dont le parent est le classloader de
     * plateforme JDK (PAS le classloader système où vivent les classes de
     * Fabric Loader) — donc une copie totalement indépendante des singletons
     * statiques de Mixin, qui ne peut plus entrer en conflit avec celle de
     * Fabric. Le ClassFileTransformer enregistré (via Instrumentation,
     * classloader-agnostique) continue de tisser nos mixins sur les classes
     * chargées par Fabric (KnotClassLoader) — voir IsolatedBootstrap pour le
     * détail.
     */
    private static void startIsolated(Instrumentation inst, String yarnPath, String mcVersion) {
        try {
            java.io.File agentDir = agentDir();
            if (agentDir == null) {
                LauncherLog.err("[LauncherAgent] isolation: dossier de l'agent introuvable — abandon");
                return;
            }

            // launcher-agent.jar est dans agentDir, les dépendances dans agentDir/libs/
            java.io.File libsDir = new java.io.File(agentDir, "libs");
            String[] libJarNames = {
                "mixin.jar", "asm-9.5.jar", "asm-tree-9.5.jar",
                "asm-util-9.5.jar", "asm-analysis-9.5.jar", "asm-commons-9.5.jar",
            };
            List<URL> urls = new ArrayList<>();
            urls.add(new java.io.File(agentDir, "launcher-agent.jar").toURI().toURL());
            for (String name : libJarNames) {
                java.io.File f = new java.io.File(libsDir, name);
                if (f.exists()) {
                    urls.add(f.toURI().toURL());
                } else {
                    LauncherLog.warn("[LauncherAgent] isolation: " + name + " manquant dans " + libsDir);
                }
            }

            // mixin.jar dépend de Guava/Gson sans les embarquer — en vanilla,
            // c'est invisible parce que mixin.jar tourne sur le classloader
            // système, qui voit déjà les libs Minecraft (.minecraft/libraries/).
            // Notre classloader isolé, lui, n'a QUE les jars listés ci-dessus
            // (parent = classloader de plateforme JDK, volontairement, pour ne
            // pas voir les classes de Fabric) — donc NoClassDefFoundError sur
            // com.google.common.* tant qu'on n'ajoute pas ces jars nous-mêmes.
            // On les retrouve dans .minecraft/libraries/ (sibling de agentDir),
            // pas besoin de connaître la version exacte : recherche par préfixe.
            java.io.File librariesDir = new java.io.File(agentDir.getParentFile(), ".minecraft/libraries");
            for (String prefix : new String[]{"guava-", "gson-", "failureaccess-"}) {
                java.io.File found = findLibraryJar(librariesDir, prefix);
                if (found != null) {
                    urls.add(found.toURI().toURL());
                    LauncherLog.agent(1, "[LauncherAgent] isolation: " + prefix + "* trouvé → " + found);
                } else {
                    LauncherLog.warn("[LauncherAgent] isolation: " + prefix + "*.jar introuvable sous " + librariesDir);
                }
            }

            if (urls.isEmpty()) {
                LauncherLog.err("[LauncherAgent] isolation: aucun JAR trouvé dans " + agentDir + " / " + libsDir + " — abandon");
                return;
            }

            // Dossier sur le classpath isolé où IsolatedBootstrap écrira le
            // refmap Mixin généré dynamiquement (voir IsolatedBootstrap.start()
            // et LauncherMixinService.buildRefmapJson()) — un VRAI fichier, pas
            // une ressource interceptée : Mixin 0.8.7 charge son refmap par un
            // chemin qui ne passe pas par IMixinService.getResourceAsStream(),
            // donc il faut que ce soit trouvable via la résolution normale du
            // classloader. Le dossier doit déjà être sur le classpath AVANT que
            // le fichier n'existe (URLClassLoader scanne les dossiers à chaque
            // appel, pas seulement à la construction — écrire le fichier après
            // coup suffit).
            java.io.File generatedDir = new java.io.File(agentDir, "generated");
            generatedDir.mkdirs();
            urls.add(0, generatedDir.toURI().toURL());

            ClassLoader isolatedCl = new URLClassLoader(
                urls.toArray(new URL[0]), platformClassLoaderOrNull());

            Class<?> bootstrapClass = Class.forName(
                "com.yuyuframe.launcheragent.agent.IsolatedBootstrap", true, isolatedCl);
            java.lang.reflect.Method startMethod =
                bootstrapClass.getMethod("start", Instrumentation.class, String.class, boolean.class, String.class);

            // Mixin résout son IMixinService via ServiceLoader, qui se base par
            // défaut sur le classloader de CONTEXTE du thread courant — pas
            // seulement sur celui qui a chargé la classe appelante. Sans ce
            // changement temporaire, ce scan retomberait sur le classloader
            // système (où vit MixinServiceKnot, l'implémentation de Fabric)
            // même en appelant une classe chargée par isolatedCl, et on
            // retrouverait le même conflit qu'avant l'isolation.
            Thread current = Thread.currentThread();
            ClassLoader previousContext = current.getContextClassLoader();
            current.setContextClassLoader(isolatedCl);
            try {
                startMethod.invoke(null, inst, yarnPath, true, mcVersion);
            } finally {
                current.setContextClassLoader(previousContext);
            }

            LauncherLog.agent(3, "[LauncherAgent] Bootstrap isolé lancé (classloader=" + isolatedCl + ")");
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] Bootstrap isolé échoué : " + t);
            t.printStackTrace(System.err);
        }
    }

    /**
     * ClassLoader.getPlatformClassLoader() n'existe qu'à partir de Java 9 —
     * l'agent compile en bytecode Java 8 (Forge 1.8.9/LaunchWrapper l'exige,
     * incompatible Java 9+), mais Fabric (seul appelant de ce chemin) ne
     * tourne que sur JVM moderne : appelé par réflexion pour profiter du vrai
     * classloader plateforme quand il existe, {@code null} (bootstrap) sinon.
     */
    private static ClassLoader platformClassLoaderOrNull() {
        try {
            java.lang.reflect.Method m = ClassLoader.class.getMethod("getPlatformClassLoader");
            return (ClassLoader) m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Recherche récursive (profondeur max 8) d'un jar dont le nom commence par
     * {@code prefix} et finit par ".jar" (exclut "-sources.jar"/"-javadoc.jar")
     * sous {@code dir} — typiquement .minecraft/libraries/com/google/guava/guava/<version>/.
     */
    private static java.io.File findLibraryJar(java.io.File dir, String prefix) {
        return findLibraryJar(dir, prefix, 0);
    }

    private static java.io.File findLibraryJar(java.io.File dir, String prefix, int depth) {
        if (depth > 8 || dir == null || !dir.isDirectory()) return null;
        java.io.File[] children = dir.listFiles();
        if (children == null) return null;
        for (java.io.File f : children) {
            if (f.isFile() && f.getName().startsWith(prefix) && f.getName().endsWith(".jar")
                    && !f.getName().contains("-sources") && !f.getName().contains("-javadoc")) {
                return f;
            }
        }
        for (java.io.File f : children) {
            if (f.isDirectory()) {
                java.io.File r = findLibraryJar(f, prefix, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** Dossier contenant launcher-agent.jar — %APPDATA%\YuyuFrame\agent\. Les libs (mixin/asm) sont dans agent\libs\. */
    private static java.io.File agentDir() {
        try {
            java.net.URI uri = LauncherAgent.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI();
            return new java.io.File(uri).getParentFile();
        } catch (Exception e) {
            LauncherLog.err("[LauncherAgent] agentDir(): " + e);
            return null;
        }
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }
}
