package com.yuyuframe.launcheragent.agent;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apimixin.version.MinecraftVersionDetector;

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
 * directement (vanilla, seul loader sans Mixin embarqué) ou via un
 * classloader isolé dédié (Fabric/Quilt/Forge 1.13+/NeoForge — tous
 * embarquent Mixin, voir needsIsolation() — pour ne jamais partager l'état
 * statique de Mixin avec le leur, voir docs/LauncherAgent/index.md et
 * docs/launcher/audit/README-bugs-a-fix.md P0-2/P0-3/P0-5 pour l'historique).
 *
 * Voir docs/LauncherAgent/index.md pour le cahier des charges complet.
 */
public class LauncherAgent {

    private static final String BUILD_VERSION = "2026-09-13-v1137";

    /** Accesseur public — voir {@code YfCommands} ("/yf version"/"/yf report"), Phase 4.5. */
    public static String buildVersion() { return BUILD_VERSION; }

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
            // flush() et NON save() : depuis que l'écriture est différée
            // (2026-08-31), save() ne fait plus que marquer la config comme
            // sale — et le tick qui l'écrirait ne tournera plus, le jeu est en
            // train de se fermer. flush() écrit sur place.
            try { com.yuyuframe.launcheragent.runtime.ui.HudConfigStore.flush(); } catch (Throwable t) {
                // Journalisation impossible à ce stade (classloader isolé
                // potentiellement déjà démonté) — c'est le seul catch muet
                // légitime de ce fichier, et il est volontaire.
            }
        }, "YuyuFrame-ConfigSave"));

        // Phase 4.5 (ROADMAP-agent.md) — système de commandes client. RETIRÉ
        // D'ICI (2026-08-24, voir [[project_mc_261_port]] §10/bug classloader) :
        // appeler ClientCommandRegistry.bootstrap() ICI touchait VanillaHookRegistry
        // EN PREMIER via le classloader SYSTÈME (celui qui charge l'agent
        // lui-même, premain() tourne dessus) — alors que ModuleRegistry/les
        // modules (NoFogModule, etc.) ne sont touchés QUE depuis le code tissé
        // dans le jeu, via KnotClassLoader (Fabric), après que FabricKnotExposer
        // ait fait adopter tout notre jar par Knot. Deux classloaders différents
        // définissant chacun leur propre copie de VanillaHookRegistry$HookHandler
        // → LinkageError (loader constraint violation) dès qu'un module tissé
        // dans le jeu appelle VanillaHookRegistry.register(...). Bootstrap
        // déplacé dans GlobalUiRenderMixin261 (même bloc d'init one-shot que
        // ModuleRegistry.all()) pour que TOUT ce qui touche apimixin/ soit
        // systématiquement chargé depuis le MÊME classloader que le jeu.

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
        // HYPOTHÈSE "warm-up AWT jetable" (era E) INFIRMÉE : testée en v360,
        // le texte du corps (UiFont.REGULAR) reste corrompu à CHAQUE
        // lancement (pas juste ~1 sur 2 comme observé avant ce fix) — la
        // cause n'est donc PAS une instabilité ponctuelle du tout premier
        // contact AWT. Retiré. Voir UiFont.java pour le diagnostic PNG ajouté
        // à la place (dump direct de l'atlas, preuve plutôt qu'hypothèse).
        try {
            com.yuyuframe.launcheragent.apigraphic.value.UiFont.REGULAR.textWidth("YuyuFrame", 1f);
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

        // Phase 3.3 (ROADMAP-agent.md) / P0-2+P0-3+P0-5 (docs/launcher/audit/
        // README-bugs-a-fix.md) : le loader vient du Rust (arg "loader=...",
        // connu avec certitude, voir Backend/src/minecraft/launcher/agents.rs)
        // plutôt que redeviné ici par Class.forName — fiabilise Quilt/Forge/
        // NeoForge, qu'un test Fabric-only classait auparavant à tort comme
        // "vanilla, pas d'isolation nécessaire". Repli par introspection de
        // classe UNIQUEMENT si "loader=" est absent (lancement manuel hors
        // Rust, ou ancien build du launcher qui ne le fournit pas encore).
        String loaderName = resolveLoaderName(config.loader);
        boolean needsIsolation = needsIsolation(loaderName);
        String schemeName = resolveSchemeName(loaderName);

        // mixin.jar (-javaagent AVANT nous) pose mixin.hotSwap=true dans
        // MixinAgent.premain — vérifié javap. Propriété SYSTÈME, donc lue aussi
        // par la Mixin DU LOADER (Fabric/Quilt/Forge), qui charge alors son
        // agent hot-swap (« Attempting to load Hot-Swap agent » dans latest.log).
        // Cet agent réapplique les mixins de TOUS les mods pendant chacun de nos
        // retransforms : « cannot overwrite method … @Overwrite is required »
        // puis ClassFormatError, retransform perdu (constaté 2026-09-11, Fabric
        // 1.21.11, 74 mods : MinecraftClient, World, Mouse, GameRenderer…).
        // Notre rattrapage passe par notre propre transformer, pas par cet agent.
        if (needsIsolation) {
            String hotSwap = System.getProperty("mixin.hotSwap");
            if (hotSwap != null) {
                System.clearProperty("mixin.hotSwap");
                LauncherLog.agent(3, "[LauncherAgent] mixin.hotSwap=" + hotSwap
                    + " (posé par mixin.jar) retiré — sinon la Mixin de " + loaderName
                    + " réapplique ses mixins pendant nos retransforms");
            }
        }
        // Conservé pour la propriété héritée "launcheragent.intermediary" et
        // pour le paramètre de IsolatedBootstrap.start, dont la signature est
        // liée par un getMethod() réflexif (voir startIsolated) — la changer
        // demanderait de modifier les deux côtés en même temps, sans gain.
        boolean intermediary = "intermediary".equals(schemeName);

        // Sous Fabric/Quilt/Forge/NeoForge, le code tissé par Mixin dans les
        // classes du jeu (la$onInit de TitleScreenMixin) est résolu par LEUR
        // classloader, PAS par notre classloader isolé — donc MappingsRegistry/
        // YarnMappings y existent comme une COPIE STATIQUE SÉPARÉE, jamais
        // initialisée par IsolatedBootstrap (qui tourne sur le classloader
        // isolé). Une System property est le seul canal de configuration qui
        // traverse vraiment toutes les copies/classloaders —
        // MappingsRegistry.ensureInitialized() (appelé paresseusement à la
        // première utilisation, quelle que soit la copie de la classe) la
        // relit pour se réinitialiser elle-même.
        if (config.yarnPath != null) System.setProperty("launcheragent.yarnPath", config.yarnPath);
        if (config.readyEvent != null) System.setProperty("launcheragent.readyEvent", config.readyEvent);
        // Remplace l'ancienne clé "launcheragent.fabric" — DÉCOUPLÉE en deux
        // notions distinctes (P0-3) : "faut-il isoler le classloader Mixin ?"
        // (needsIsolation, ci-dessous) et "le jeu tourne-t-il en mappings
        // intermediary ?" (intermediary, seule celle-ci lue par MappingsRegistry).
        //
        // ⚠️ Ce booléen ne suffit plus depuis l'ajout du schéma SRG
        // (2026-09-10) : il ne sait dire que "intermediary ou pas", donc il
        // range Forge/NeoForge avec vanilla. Il reste écrit parce qu'un
        // launcher Rust plus ancien peut encore le lire, mais la source de
        // vérité est "launcheragent.mappingScheme" juste en dessous —
        // MappingsRegistry.schemeFromProperties() lit celle-ci EN PREMIER et
        // ne retombe sur le booléen que si elle est absente.
        System.setProperty("launcheragent.intermediary", String.valueOf(intermediary));
        if (System.getProperty("launcheragent.mappingScheme") == null) {
            System.setProperty("launcheragent.mappingScheme", schemeName);
        } else {
            LauncherLog.agent(1, "[LauncherAgent] mappingScheme forcé en ligne de commande : \""
                + System.getProperty("launcheragent.mappingScheme") + "\" (déduction \"" + schemeName + "\" ignorée)");
        }
        if (config.srgPath != null) System.setProperty("launcheragent.srgPath", config.srgPath);

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

        if (needsIsolation) {
            LauncherLog.agent(1, "[LauncherAgent] loader=" + loaderName + " — bootstrap Mixin via classloader isolé");
            startIsolated(inst, config.yarnPath, mcVersion, intermediary);
        } else {
            // Try/catch dédié (audit robustesse pipeline de lancement) : le
            // chemin isolé (startIsolated) capture déjà toute exception dans
            // son propre try/catch(Throwable) — CE chemin (vanilla, le PLUS
            // emprunté) n'avait aucune protection locale. Une exception non
            // capturée ici remonte jusqu'à premain(), qui la relance
            // (throw t) — et java.lang.instrument fait avorter TOUTE LA JVM
            // sur une exception non capturée dans premain. Un seul mixin mal
            // résolu contre les mappings (ex: régression de bracket de
            // version) plantait donc le lancement vanilla en entier, alors
            // que ce n'est qu'une fonctionnalité annexe (resource packs
            // Modrinth in-game) qui devrait se dégrader proprement.
            try {
                IsolatedBootstrap.start(inst, config.yarnPath, intermediary, false, mcVersion);
            } catch (Throwable t) {
                LauncherLog.err("[LauncherAgent] Bootstrap non isolé échoué (vanilla) : " + t);
                t.printStackTrace(System.err);
            }
        }

        LauncherLog.agent(3, "[LauncherAgent] Prêt — en attente du chargement Minecraft");
    }

    /**
     * Loader tel que fourni par le Rust (arg "loader=..." — voir AgentConfig,
     * Backend/src/minecraft/launcher/agents.rs), ou repli par introspection de
     * classe si absent (lancement manuel, ou build du launcher antérieur à ce
     * plumbing). Retourne "vanilla"/"fabric"/"quilt"/"forge"/"neoforge".
     *
     * <h2>Le launcher fait autorité, l'introspection n'est qu'un filet</h2>
     *
     * {@code loader=} est TOUJOURS envoyé par le Rust (voir agents.rs :
     * {@code loader.unwrap_or("vanilla")}), qui a lui-même installé le loader
     * et connaît donc le paramètre avec certitude — là où l'introspection ne
     * peut qu'inférer d'une présence de classe. La valeur de configuration
     * gagne donc AVANT tout test, et le repli ne sert qu'à un lancement
     * manuel {@code -javaagent:} hors launcher (ou à un build du launcher
     * antérieur à ce plumbing).
     *
     * <p>La source retenue est journalisée : lire ce fichier ne doit pas être
     * nécessaire pour savoir laquelle a décidé.
     *
     * <p>Forge et NeoForge sont distingués dans ce repli (2026-09-10) : ils
     * partagent {@code cpw.mods.modlauncher.Launcher}, mais leur classe FML
     * porte un paquet différent — {@code net.neoforged} contre
     * {@code net.minecraftforge}. La distinction ne changeait rien tant que
     * les deux retombaient sur OFFICIAL ; elle compte maintenant, parce que
     * {@link #resolveSchemeName} peut avoir à les traiter différemment (voir
     * sa javadoc sur le passage de NeoForge aux noms Mojang).
     */
    private static String resolveLoaderName(String configLoader) {
        if (configLoader != null && !configLoader.isEmpty()) {
            LauncherLog.agent(1, "[LauncherAgent] loader=\"" + configLoader + "\" (fourni par le launcher)");
            return configLoader;
        }
        String detected = detectLoaderFromClasspath();
        LauncherLog.warn("[LauncherAgent] loader non fourni par le launcher — déduit du classpath : \""
            + detected + "\". Lancement manuel ? Passez loader=<vanilla|fabric|quilt|forge|neoforge>"
            + " en argument de l'agent pour ne pas dépendre de cette déduction.");
        return detected;
    }

    private static String detectLoaderFromClasspath() {
        ClassLoader cl = LauncherAgent.class.getClassLoader();
        if (classPresent("net.fabricmc.loader.impl.launch.knot.Knot", cl)) return "fabric";
        if (classPresent("org.quiltmc.loader.impl.launch.knot.Knot", cl)) return "quilt";
        // Testé AVANT ModLauncher : NeoForge l'embarque aussi, donc l'ordre
        // inverse classerait tout NeoForge en "forge".
        if (classPresent("net.neoforged.fml.loading.FMLLoader", cl)) return "neoforge";
        if (classPresent("net.minecraftforge.fml.loading.FMLLoader", cl)) return "forge";
        if (classPresent("cpw.mods.modlauncher.Launcher", cl)) return "forge";
        return "vanilla";
    }

    private static boolean classPresent(String className, ClassLoader cl) {
        try {
            Class.forName(className, false, cl);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Isolation nécessaire dès qu'un AUTRE hôte Mixin vit déjà dans le
     * classloader système — Fabric et Quilt (Knot), Forge 1.13+/NeoForge
     * (ModLauncher, mixin embarqué depuis 1.13+) — pas seulement Fabric, voir
     * P0-2 (README-bugs-a-fix.md) : le test Fabric-only précédent laissait
     * Quilt/Forge moderne/NeoForge prendre le chemin non isolé à tort
     * (collision des singletons statiques Mixin → crash ou "could not find
     * any targets matching").
     */
    private static boolean needsIsolation(String loader) {
        return "fabric".equals(loader) || "quilt".equals(loader)
            || "forge".equals(loader) || "neoforge".equals(loader);
    }

    /**
     * Sous quel nommage le jeu tourne-t-il avec ce loader ? DÉCOUPLÉ de
     * {@link #needsIsolation} (P0-3) : le besoin d'isolation classloader et le
     * schéma de mappings sont deux questions indépendantes — Forge/NeoForge
     * répondent « oui » à la première et « ni official ni intermediary » à la
     * seconde.
     *
     * <p>Remplace {@code usesIntermediaryMappings()} (booléen), qui ne savait
     * répondre que « intermediary ou pas » et rangeait donc Forge/NeoForge
     * avec vanilla, en OFFICIAL — c'est-à-dire sur des noms absents du jar
     * réellement chargé. Le commentaire d'origine l'annonçait déjà comme
     * « pas encore de vrai support » ; c'est ce trou que {@code Scheme.SRG}
     * comble.
     *
     * <h2>⚠️ NeoForge : choix assumé, non vérifié</h2>
     *
     * NeoForge a basculé son exécution sur les noms Mojang lisibles
     * (« Mojmap ») aux alentours de la 1.20.2 — ses membres s'appelleraient
     * alors {@code getInstance}, pas {@code m_91087_}, ce qui ne serait AUCUN
     * des trois schémas actuels. Impossible à vérifier depuis cet
     * environnement, faute d'instance NeoForge.
     *
     * <p>Il est donc classé en SRG comme Forge, ce qui est le comportement
     * juste sur les NeoForge antérieurs et, sur les récents, échoue de la même
     * façon qu'aujourd'hui — pas de régression. Le diagnostic en jeu tranchera
     * ; {@code -Dlauncheragent.mappingScheme=official} permet de forcer l'autre
     * hypothèse sans rebuild, et un quatrième schéma {@code MOJMAP} s'ajoutera
     * comme celui-ci s'il s'avère nécessaire.
     *
     * @return {@code "official"}, {@code "intermediary"} ou {@code "srg"} —
     *         volontairement des chaînes et pas l'énumération
     *         {@code MappingsRegistry.Scheme} : cette classe tourne sur le
     *         classloader système, où toucher à {@code apimixin/} déclenche le
     *         {@code LinkageError} documenté dans {@code LauncherMixinService}.
     */
    private static String resolveSchemeName(String loader) {
        if ("fabric".equals(loader) || "quilt".equals(loader)) return "intermediary";
        if ("forge".equals(loader) || "neoforge".equals(loader)) return "srg";
        return "official";
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
    private static void startIsolated(Instrumentation inst, String yarnPath, String mcVersion, boolean intermediary) {
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
                "mixinextras.jar",
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
                bootstrapClass.getMethod("start", Instrumentation.class, String.class, boolean.class, boolean.class, String.class);

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
                // "intermediary" propagé depuis premain0() (P0-3) — n'est
                // PLUS toujours "true" ici : Forge/NeoForge passent aussi par
                // ce chemin isolé (voir needsIsolation) mais avec intermediary
                // = false (mappings SRG, pas intermediary Fabric/Quilt).
                // "isolated" = true : CE chemin (startIsolated) est TOUJOURS
                // le cas isolé par définition — pilote writeRefmapFile.
                startMethod.invoke(null, inst, yarnPath, intermediary, true, mcVersion);
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
