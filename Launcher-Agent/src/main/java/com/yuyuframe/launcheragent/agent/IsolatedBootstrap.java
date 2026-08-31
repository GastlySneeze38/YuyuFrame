package com.yuyuframe.launcheragent.agent;

import com.yuyuframe.launcheragent.apimixin.service.LauncherMixinService;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.runtime.mapping.YarnMappings;
import com.yuyuframe.launcheragent.apimixin.version.VersionBracket;
import com.yuyuframe.launcheragent.apimixin.version.VersionBracketRegistry;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigSource;

import org.objectweb.asm.*;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Toute la logique qui touche Mixin/ASM — extraite de LauncherAgent pour
 * pouvoir être chargée soit normalement (vanilla/Forge, même classloader que
 * l'agent), soit via un classloader isolé dédié (Fabric — voir
 * LauncherAgent.premain()).
 *
 * Pourquoi l'isolation sous Fabric : MixinBootstrap/Mixins/MixinEnvironment
 * sont des singletons statiques par classe Java. Si CETTE classe (et donc
 * org.spongepowered.asm.* et MappingsRegistry/YarnMappings) est chargée par un
 * classloader séparé du classloader système (où vivent les classes de Fabric
 * Loader), elle obtient sa PROPRE copie indépendante de cet état statique —
 * GlobalProperties ne découvre alors plus le service Mixin de Fabric
 * (FabricGlobalPropertyService), qui causait le NPE/crash documentés dans
 * docs/LauncherAgent/index.md. Le ClassFileTransformer enregistré via
 * Instrumentation reste lui classloader-agnostique (le JVM l'appelle pour
 * toute classe définie, peu importe qui l'a chargée) — donc le tissage Mixin
 * sur les classes chargées par Fabric (KnotClassLoader) continue de
 * fonctionner sans jamais passer par le pipeline Mixin propre à Fabric.
 */
public final class IsolatedBootstrap {

    private IsolatedBootstrap() {}

    /**
     * @param intermediary vrai si le jeu tourne en mappings intermediary
     *                     (Fabric/Quilt) — déterminé par LauncherAgent à partir
     *                     du loader (arg "loader=...", voir P0-3/P0-5 dans
     *                     docs/launcher/audit/README-bugs-a-fix.md). DÉCOUPLÉ
     *                     de {@code isolated} (Forge/NeoForge passent aussi par
     *                     le chemin isolé mais avec {@code intermediary=false}
     *                     — mappings SRG, pas encore supportés par {@code
     *                     MappingsRegistry.Scheme}).
     * @param isolated     vrai si CE bootstrap tourne sur le classloader isolé
     *                     dédié (LauncherAgent.startIsolated(), TOUS les
     *                     loaders qui embarquent Mixin — voir needsIsolation())
     *                     plutôt que sur le classloader système (vanilla) —
     *                     pilote UNIQUEMENT la stratégie d'écriture du refmap
     *                     (writeRefmapFile), sans rapport avec le schéma de
     *                     mappings.
     * @param mcVersion    version Minecraft détectée par MinecraftVersionDetector.
     */
    public static void start(Instrumentation inst, String yarnPath, boolean intermediary, boolean isolated, String mcVersion) {
        LauncherLog.agent(1, "[LauncherAgent] IsolatedBootstrap.start (classloader=" + IsolatedBootstrap.class.getClassLoader()
            + ", intermediary=" + intermediary + ", isolated=" + isolated + ", version=" + mcVersion + ")");

        VersionBracket bracket = VersionBracketRegistry.resolve(mcVersion);
        if (bracket == null) {
            LauncherLog.err("[LauncherAgent] Version MC \"" + mcVersion + "\" non supportée — aucun bracket "
                + "ne correspond dans VersionBracketRegistry, bootstrap Mixin ABANDONNÉ (pas de Mixin appliqué, "
                + "mais l'agent continue de tourner). Voir VersionBracketRegistry pour la liste des versions "
                + "supportées et la convention pour en ajouter une.");
            return;
        }
        LauncherLog.agent(1, "[LauncherAgent] Bracket de version résolu : " + bracket.key);

        MappingsRegistry.setScheme(intermediary
            ? MappingsRegistry.Scheme.INTERMEDIARY
            : MappingsRegistry.Scheme.OFFICIAL);

        LauncherMixinService.setInstrumentation(inst);

        loadYarnMappings(yarnPath, bracket);

        // Log de sanité : vérifie que la classe principale de la version est bien mappée.
        // Même nom Yarn named "TitleScreen" sur les deux branches — Legacy Fabric
        // (1.8.9) reprend la nomenclature Yarn moderne, PAS les noms MCP
        // historiques type "GuiMainMenu" (vérifié dans mappings-1.8.9.tiny).
        if (MappingsRegistry.isLoaded()) {
            String probe = "net/minecraft/client/gui/screen/TitleScreen";
            String obfClass = MappingsRegistry.INSTANCE.map(probe);
            LauncherLog.agent(1, "[LauncherAgent] Yarn probe → \"" + obfClass + "\""
                + (obfClass.equals(probe) ? "  ← NON MAPPÉ" : "  ← OK"));
        }

        // Refmap requis dans TOUS les cas (isolé ET non isolé) : nos @Inject
        // utilisent des noms Yarn NAMED ("init", pas "bg_"/"b" en dur) — sans
        // refmap écrit, Mixin valide les cibles contre la chaîne named
        // littérale, qui ne correspond à rien dans le jar obfusqué chargé →
        // "could not find any targets matching". refmapMethodReplacement() est
        // scheme-aware (voir LauncherMixinService) : nom officiel brut en
        // vanilla, intermediary sous Fabric/Quilt — un seul mécanisme couvre
        // les deux cas. writeRefmapFile ne dépend QUE de "isolated" (stratégie
        // fichier brut vs jar), jamais de "intermediary" — voir sa javadoc.
        writeRefmapFile(inst, isolated);

        // Sélection du/des fichier(s) de config Mixin selon la version MC —
        // mixinConfig (legacy) est NULLABLE : un bracket entièrement basculé
        // vers apimixin/ (voir VersionBracketRegistry, 26.1.2) n'en a plus.
        String mixinConfig = bracket.mixinConfigResource;
        String apiMixinConfig = bracket.apiMixinConfigResource;

        // Filtrage déclaratif HookPoint — remplace l'ancienne gate du plugin de
        // config Mixin (voir filterConfigByHookPoints). Fait ICI, avant tout
        // enregistrement, pour que Mixin n'ouvre même pas les mixins écartés.
        if (apiMixinConfig != null) {
            apiMixinConfig = filterConfigByHookPoints(apiMixinConfig);
        }

        Set<String> mixinTargets = new LinkedHashSet<>();
        if (mixinConfig != null) {
            mixinTargets.addAll(discoverMixinTargets(mixinConfig));
        }
        if (apiMixinConfig != null) {
            mixinTargets.addAll(discoverMixinTargets(apiMixinConfig));
        }
        bootstrapMixin(inst, mixinTargets, mixinConfig, apiMixinConfig);
        scheduleDelayedRetransform(inst, mixinTargets);
    }

    /**
     * Écrit mixins.launcheragent.refmap.json — voir LauncherMixinService.buildRefmapJson()
     * pour le contenu.
     *
     * Deux mécanismes de résolution selon le mode de chargement :
     *   - Isolé (Fabric/Quilt/Forge/NeoForge, voir needsIsolation()) :
     *     <agentDir>/generated/ est déjà sur le classpath du classloader isolé
     *     dédié (ajouté par LauncherAgent.startIsolated() AVANT sa
     *     construction) — écrire le fichier brut dans ce dossier suffit, il
     *     devient résolvable immédiatement.
     *   - Non isolé (vanilla) : IsolatedBootstrap tourne sur le classloader
     *     SYSTÈME, que launcher.rs n'a jamais configuré pour inclure
     *     <agentDir>/generated/ dans son -cp — écrire le fichier là ne suffit
     *     pas, il resterait introuvable. java.lang.instrument n'offre PAS
     *     d'équivalent "ajoute ce dossier au classpath système" à chaud
     *     (seulement Instrumentation.appendToSystemClassLoaderSearch(), qui
     *     n'accepte qu'un JarFile) — le refmap est donc empaqueté dans un
     *     petit jar dédié, ajouté au classloader système via cette API.
     */
    private static void writeRefmapFile(Instrumentation inst, boolean isolated) {
        try {
            java.io.File agentDir = agentDir();
            if (agentDir == null) {
                LauncherLog.err("[LauncherAgent] writeRefmapFile: dossier agent introuvable");
                return;
            }
            java.io.File dir = new java.io.File(agentDir, "generated");
            dir.mkdirs();
            String json = LauncherMixinService.buildRefmapJson();

            if (isolated) {
                java.io.File file = new java.io.File(dir, "mixins.launcheragent.refmap.json");
                java.nio.file.Files.write(file.toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                LauncherLog.agent(1, "[LauncherAgent] refmap écrit (fichier brut, classloader isolé) : " + file);
            } else {
                java.io.File jarFile = new java.io.File(dir, "refmap.jar");
                try (java.util.jar.JarOutputStream jos =
                        new java.util.jar.JarOutputStream(new java.io.FileOutputStream(jarFile))) {
                    jos.putNextEntry(new java.util.zip.ZipEntry("mixins.launcheragent.refmap.json"));
                    jos.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    jos.closeEntry();
                }
                inst.appendToSystemClassLoaderSearch(new java.util.jar.JarFile(jarFile));
                LauncherLog.agent(1, "[LauncherAgent] refmap écrit (jar ajouté au classloader système) : " + jarFile);
            }
            LauncherLog.agent(1, "[LauncherAgent] refmap contenu : " + json);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] writeRefmapFile: " + t);
        }
    }

    /** Dossier contenant ce JAR (et ses jars frères) — identique à LauncherAgent.agentDir(). */
    private static java.io.File agentDir() {
        try {
            java.net.URI uri = IsolatedBootstrap.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI();
            return new java.io.File(uri).getParentFile();
        } catch (Exception e) {
            LauncherLog.err("[LauncherAgent] agentDir(): " + e);
            return null;
        }
    }

    /**
     * Installe l'extension MixinExtras dans NOTRE environnement Mixin — sans
     * quoi TOUTE annotation MixinExtras d'{@code apimixin/} ({@code
     * @WrapOperation}, {@code @WrapWithCondition}, {@code @ModifyExpressionValue},
     * {@code @WrapMethod} — une trentaine d'injecteurs) est SILENCIEUSEMENT
     * INERTE : le cœur de Sponge Mixin ne connaît pas ces annotations, il se
     * contente de recopier la méthode handler telle quelle dans la classe
     * cible, sans jamais l'appeler ni signaler quoi que ce soit.
     *
     * Symptôme historique exact (2026-08-25, §14) : {@code la$dispatchCrosshair}
     * était bien présente dans {@code Gui}, mais NUE — sans le préfixe {@code
     * wrapOperation$…} qu'aurait produit un vrai traitement MixinExtras
     * (comparer avec {@code modifyExpressionValue$zfn000$fabric-content-registries-v0$…},
     * bien traité, lui, parce que Fabric a initialisé MixinExtras pour SES
     * configs). Même cause pour {@code @WrapWithCondition} du freelook, jamais
     * déclenché.
     *
     * ⚠️ Pourquoi l'appel vit ICI et pas dans {@code LauncherMixinConfigPlugin}
     * (où il se trouvait) : sur tout bracket isolé — Fabric compris — le plugin
     * de config N'EST JAMAIS INSTANCIÉ (ClassCastException app/isolatedCl sur
     * {@code IMixinConfigPlugin}, avalée par le {@code PluginHandle} de Mixin,
     * voir {@code LauncherMixinService.findClass}). Son {@code onLoad()} — donc
     * son {@code initMixinExtrasIfHostDidNot()} — ne s'exécutait tout
     * simplement jamais là où on en avait besoin. {@code bootstrapMixin} tourne,
     * lui, dans {@code isolatedCl}, qui contient déjà {@code mixinextras.jar}
     * (voir la liste de jars dans {@code LauncherAgent}) : la classe résolue
     * ici est NOTRE copie, donc l'extension s'enregistre dans NOTRE transformer,
     * pas dans celui de Fabric.
     *
     * Les classloaders sont journalisés volontairement : si {@code
     * MixinExtrasBootstrap} venait à être résolu depuis Knot au lieu
     * d'{@code isolatedCl}, on réinitialiserait le MixinExtras de Fabric — ce
     * qui reproduirait la cascade « cannot overwrite method … @Overwrite is
     * required » sur les mods Fabric déjà tissés. Repli immédiat sans rebuild :
     * {@code -Dlauncheragent.mixinextras=false}.
     */
    private static void initMixinExtras() {
        if ("false".equalsIgnoreCase(System.getProperty("launcheragent.mixinextras"))) {
            LauncherLog.agent(3, "[LauncherAgent] MixinExtras désactivé (-Dlauncheragent.mixinextras=false)");
            return;
        }
        try {
            Class<?> boot = Class.forName("com.llamalad7.mixinextras.MixinExtrasBootstrap",
                true, IsolatedBootstrap.class.getClassLoader());
            LauncherLog.agent(3, "[LauncherAgent] MixinExtras: classe résolue depuis "
                + describeLoader(boot.getClassLoader())
                + " | Mixin depuis " + describeLoader(MixinEnvironment.class.getClassLoader()));
            boot.getMethod("init").invoke(null);
            LauncherLog.agent(3, "[LauncherAgent] MixinExtras initialisé dans NOTRE environnement Mixin"
                + " — injecteurs @WrapOperation/@WrapWithCondition/@ModifyExpressionValue actifs");
        } catch (Throwable t) {
            // Jamais silencieux : sans ça, une trentaine d'injecteurs redeviennent
            // inertes sans le moindre signe (voir javadoc).
            LauncherLog.err("[LauncherAgent] MixinExtras NON initialisé — tous les injecteurs"
                + " MixinExtras d'apimixin/ resteront inertes : " + t);
        }
    }

    private static String describeLoader(ClassLoader cl) {
        return cl == null ? "bootstrap" : cl.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(cl));
    }

    /** @return true si le bootstrap a réussi. */
    private static boolean bootstrapMixin(Instrumentation inst, Set<String> mixinTargets, String mixinConfig, String apiMixinConfig) {
        try {
            MixinBootstrap.init();

            if (MappingsRegistry.isLoaded()) {
                MixinEnvironment.getDefaultEnvironment().getRemappers().add(MappingsRegistry.INSTANCE);
                LauncherLog.agent(1, "[LauncherAgent] Remappeur Mojang → obfusqué enregistré dans Mixin");
            }

            try {
                java.lang.reflect.Method gotoPhase = MixinEnvironment.class
                    .getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
                gotoPhase.setAccessible(true);
                gotoPhase.invoke(null, MixinEnvironment.Phase.DEFAULT);
                LauncherLog.agent(1, "[LauncherAgent] gotoPhase(DEFAULT) OK");
            } catch (Exception ex) {
                LauncherLog.warn("[LauncherAgent] gotoPhase(DEFAULT) erreur: " + ex);
            }

            initMixinExtras();

            if (mixinConfig != null) {
                Mixins.addConfiguration(mixinConfig, (IMixinConfigSource) null);
                LauncherLog.agent(1, "[LauncherAgent] Config Mixin enregistrée : " + mixinConfig);
            }

            if (apiMixinConfig != null) {
                Mixins.addConfiguration(apiMixinConfig, (IMixinConfigSource) null);
                LauncherLog.agent(1, "[LauncherAgent] Config Mixin enregistrée : " + apiMixinConfig);
            }

            LauncherMixinService.installWrapper();

            try {
                java.lang.reflect.Method injectMethod =
                    MixinBootstrap.class.getDeclaredMethod("inject");
                injectMethod.setAccessible(true);
                injectMethod.invoke(null);
                LauncherLog.agent(1, "[LauncherAgent] MixinBootstrap.inject() OK");
            } catch (Exception ex) {
                LauncherLog.warn("[LauncherAgent] inject() non accessible: " + ex.getMessage());
            }

            retransformLoadedTargets(inst, mixinTargets);
            LauncherLog.agent(3, "[LauncherAgent] Composant Mixin initialisé avec succès ("
                + (mixinConfig != null ? mixinConfig : "(aucune config legacy)")
                + (apiMixinConfig != null ? " + " + apiMixinConfig : "")
                + ", " + mixinTargets.size() + " cible(s) : " + mixinTargets + ")");
            return true;
        } catch (Throwable e) {
            // Throwable, pas Exception : certains échecs Mixin (ex: MixinInitialisationError)
            // sont des Error, pas des Exception.
            LauncherLog.err("[LauncherAgent] ERREUR Mixin bootstrap : " + e.getMessage());
            e.printStackTrace(System.err);
            return false;
        }
    }

    private static void loadYarnMappings(String explicitPath, VersionBracket bracket) {
        if (explicitPath != null && !explicitPath.isEmpty()) {
            try {
                if (explicitPath.endsWith(".jar") || explicitPath.endsWith(".zip")) {
                    YarnMappings.loadFromJar(explicitPath);
                } else {
                    YarnMappings.load(new java.io.FileInputStream(explicitPath));
                }
                LauncherLog.agent(3, "[LauncherAgent] Yarn chargé depuis : " + explicitPath);
                return;
            } catch (Exception e) {
                LauncherLog.warn("[LauncherAgent] Yarn explicite non chargé (" + explicitPath + "): " + e.getMessage());
            }
        }

        // Cherche d'abord un JAR Yarn dont le nom contient l'indice du bracket
        // résolu (ex: "1.8.9" pour la tranche legacy189, "1.21.11" pour la
        // tranche moderne actuelle) — voir VersionBracket.yarnJarNameHint.
        String[] searchRoots = {
            System.getProperty("user.home") + "\\.gradle\\caches\\fabric-loom",
            System.getProperty("user.home") + "\\.gradle\\caches",
            System.getenv("APPDATA") != null ? System.getenv("APPDATA") + "\\.minecraft\\libraries" : null,
        };
        for (String root : searchRoots) {
            if (root == null) continue;
            java.io.File found = findYarnJar(new java.io.File(root), bracket.yarnJarNameHint, 0);
            if (found != null) {
                try {
                    YarnMappings.loadFromJar(found.getAbsolutePath());
                    LauncherLog.agent(3, "[LauncherAgent] Yarn auto-détecté : " + found.getAbsolutePath());
                    return;
                } catch (Exception e) {
                    LauncherLog.agent(1, "[LauncherAgent] Yarn auto-detect échec (" + found + "): " + e.getMessage());
                }
            }
        }

        try (java.io.InputStream is = IsolatedBootstrap.class.getResourceAsStream("/yarn-mappings.tiny")) {
            if (is != null) {
                YarnMappings.load(is);
                LauncherLog.agent(3, "[LauncherAgent] Yarn chargé depuis la resource JAR embarquée");
                return;
            }
        } catch (Exception e) {
            LauncherLog.agent(1, "[LauncherAgent] Yarn resource JAR non chargée : " + e.getMessage());
        }

        LauncherLog.warn("[LauncherAgent] Yarn non disponible pour le bracket \"" + bracket.key + "\" — "
            + "passez yarn=<chemin vers un jar Yarn mergedv2 contenant \"" + bracket.yarnJarNameHint
            + "\"> en argument de l'agent (legacy189 : maven.legacyfabric.net).");
    }

    /**
     * Cherche un JAR Yarn dans {@code dir}. Priorité aux JARs dont le nom
     * contient {@code yarnJarNameHint} (voir VersionBracket.yarnJarNameHint).
     */
    private static java.io.File findYarnJar(java.io.File dir, String yarnJarNameHint, int depth) {
        if (depth > 6 || !dir.isDirectory()) return null;
        java.io.File[] children = dir.listFiles();
        if (children == null) return null;
        for (java.io.File f : children) {
            if (!f.isFile() || !f.getName().contains("yarn") || !f.getName().endsWith("-mergedv2.jar")) continue;
            if (yarnJarNameHint != null && f.getName().contains(yarnJarNameHint)) return f;
        }
        // Deuxième passe : accepter n'importe quel Yarn si rien de version-exact trouvé
        for (java.io.File f : children) {
            if (f.isFile() && f.getName().contains("yarn") && f.getName().endsWith("-mergedv2.jar")) return f;
        }
        for (java.io.File f : children) {
            if (f.isDirectory()) {
                java.io.File r = findYarnJar(f, yarnJarNameHint, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** InputStream.readAllBytes() n'existe qu'à partir de Java 9 — équivalent Java 8. */
    private static byte[] readAllBytes(java.io.InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }

    // ── Filtrage déclaratif HookPoint (2026-08-25, §12) ────────────────────
    //
    // Remplace la gate qui vivait dans LauncherMixinConfigPlugin.
    // shouldApplyMixin() — voir LauncherMixinService.findClass pour l'histoire
    // complète. Deux raisons de l'avoir déplacée ici :
    //
    //  1. CORRECTION. Le plugin de config est INERTE sur ce bracket
    //     (ClassCastException app/isolatedCl, launcher-agent.jar étant en
    //     -javaagent donc présent sur les deux classloaders). Quatre tentatives
    //     pour le réveiller ont cassé le démarrage du jeu. IsolatedBootstrap,
    //     lui, tourne déjà dans le bon classloader : le problème disparaît par
    //     construction.
    //
    //  2. PERFORMANCE. Le plugin arrive trop tard : quand Mixin appelle
    //     shouldApplyMixin(), il a DÉJÀ chargé le bytecode de chaque mixin,
    //     parsé ses annotations, construit son MixinInfo et résolu son refmap.
    //     Il n'évite que le merge final. En retirant les entrées du JSON avant
    //     addConfiguration(), Mixin ne sait même pas que ces mixins existent.
    //     Mesuré sur ce poste : ~5,5 ms de préparation par mixin (66 en 365 ms),
    //     pour ~40 mixins écartés sur 51 gatés → ~220 ms de démarrage, sur un
    //     bootstrap agent de ~1 545 ms.
    //
    // ⚠️ TOUT passe par de la lecture de bytecode (ASM), JAMAIS par un
    // Class.forName : charger HookPoint / MixinHookPointRegistry / ModuleRegistry
    // depuis premain reviendrait à toucher apimixin depuis le classloader
    // système — exactement le LinkageError du §10 (bug A).
    //
    // En cas d'échec quelconque, on renvoie la config d'origine : le filtrage
    // est une optimisation, jamais un point de rupture.

    private static final String HOOKPOINT_OWNER = "com/yuyuframe/launcheragent/apimixin/HookPoint";
    private static final boolean FILTER_ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("launcheragent.hookpointFilter", "true"));

    /**
     * Réécrit {@code configName} en ne gardant que les mixins dont le HookPoint
     * est réellement réclamé, et renvoie le nom de ressource à enregistrer.
     *
     * @return le nom du fichier filtré, ou {@code configName} inchangé si le
     *         filtrage est désactivé, inapplicable ou en échec
     */
    private static String filterConfigByHookPoints(String configName) {
        if (!FILTER_ENABLED) {
            LauncherLog.agent(3, "[HookPointFilter] désactivé (-Dlauncheragent.hookpointFilter=false)");
            return configName;
        }
        try {
            ClassLoader agentCL = IsolatedBootstrap.class.getClassLoader();
            String json;
            try (java.io.InputStream is = agentCL.getResourceAsStream(configName)) {
                if (is == null) return configName;
                json = new String(readAllBytes(is), java.nio.charset.StandardCharsets.UTF_8);
            }

            Map<String, String> mixinToPoint = scanMixinHookPointMap();
            Set<String> claimed = scanClaimedHookPoints();
            if (mixinToPoint.isEmpty() || claimed.isEmpty()) {
                LauncherLog.warn("[HookPointFilter] catalogue vide (map=" + mixinToPoint.size()
                    + ", réclamés=" + claimed.size() + ") — config inchangée");
                return configName;
            }

            List<String> dropped = new ArrayList<>();
            String filtered = json;
            for (Map.Entry<String, String> e : mixinToPoint.entrySet()) {
                if (claimed.contains(e.getValue())) continue;
                // L'entrée JSON se termine par le nom simple du mixin ; on la
                // retire avec sa virgule pour garder un tableau valide.
                Matcher m = Pattern.compile("\\s*\"[A-Za-z0-9$._]*" + Pattern.quote(e.getKey()) + "\",?")
                        .matcher(filtered);
                if (m.find()) {
                    filtered = m.replaceFirst("");
                    dropped.add(e.getKey() + " (" + e.getValue() + ")");
                }
            }
            if (dropped.isEmpty()) {
                LauncherLog.agent(3, "[HookPointFilter] aucun mixin à écarter — config inchangée");
                return configName;
            }
            // Une entrée retirée en fin de tableau peut laisser une virgule
            // orpheline avant le ']'.
            filtered = filtered.replaceAll(",(\\s*])", "$1");

            java.io.File agentDir = agentDir();
            if (agentDir == null) return configName;
            java.io.File dir = new java.io.File(agentDir, "generated");
            dir.mkdirs();
            String outName = configName.replace(".json", "-filtered.json");
            java.nio.file.Files.write(new java.io.File(dir, outName).toPath(),
                filtered.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            LauncherLog.agent(3, "[HookPointFilter] " + dropped.size() + " mixin(s) écarté(s) sur "
                + mixinToPoint.size() + " gaté(s) — " + claimed.size() + " HookPoint réclamé(s) → " + outName);
            LauncherLog.agent(1, "[HookPointFilter] écartés : " + dropped);
            return outName;
        } catch (Throwable t) {
            LauncherLog.err("[HookPointFilter] échec, config d'origine conservée : " + t);
            return configName;
        }
    }

    /**
     * Lit le {@code <clinit>} de {@code MixinHookPointRegistry} et en extrait
     * les paires {@code put("NomDuMixin", HookPoint.X)} — sans charger la
     * classe (voir l'avertissement du bloc ci-dessus).
     */
    private static Map<String, String> scanMixinHookPointMap() {
        Map<String, String> map = new LinkedHashMap<>();
        try (java.io.InputStream is = IsolatedBootstrap.class.getClassLoader()
                .getResourceAsStream("com/yuyuframe/launcheragent/apimixin/MixinHookPointRegistry.class")) {
            if (is == null) return map;
            new ClassReader(readAllBytes(is)).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int a, String name, String d, String s, String[] ex) {
                    return new org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {
                        private String lastString;
                        @Override public void visitLdcInsn(Object value) {
                            if (value instanceof String) lastString = (String) value;
                        }
                        @Override public void visitFieldInsn(int op, String owner, String fname, String fdesc) {
                            if (op == Opcodes.GETSTATIC && HOOKPOINT_OWNER.equals(owner) && lastString != null) {
                                map.put(lastString, fname);
                                lastString = null;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        } catch (Throwable t) {
            LauncherLog.warn("[HookPointFilter] scanMixinHookPointMap: " + t);
        }
        return map;
    }

    /**
     * Ensemble des HookPoint référencés par le code de {@code runtime/} —
     * modules ({@code super(..., HookPoint.X)} comme
     * {@code VanillaHookRegistry.register(HookPoint.X, ...)}) et
     * infrastructure ({@code ClientCommandRegistry}, {@code ModuleRegistry.
     * INFRA_HOOK_POINTS}).
     *
     * Le catalogue se dérive ainsi du code lui-même, sans table à maintenir en
     * double. On ne scanne QUE {@code runtime/} : les mixins d'{@code apimixin/}
     * référencent évidemment tous les HookPoint (ce sont eux qui les
     * dispatchent), les inclure réclamerait tout et annulerait le filtre.
     *
     * Sur-approximation assumée : une référence à un HookPoint suffit à le
     * réclamer, même si l'enregistrement est conditionnel. Un mixin gardé pour
     * rien ne coûte que du temps de tissage ; un mixin écarté à tort casserait
     * une fonctionnalité.
     */
    private static Set<String> scanClaimedHookPoints() {
        Set<String> claimed = new LinkedHashSet<>();
        String jarPath = System.getProperty("launcheragent.jarPath");
        if (jarPath == null || jarPath.isEmpty()) return claimed;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarPath)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String n = entry.getName();
                if (!n.startsWith("com/yuyuframe/launcheragent/runtime/") || !n.endsWith(".class")) continue;
                try (java.io.InputStream is = zip.getInputStream(entry)) {
                    new ClassReader(readAllBytes(is)).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public org.objectweb.asm.MethodVisitor visitMethod(int a, String mn, String d, String s, String[] ex) {
                            return new org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {
                                @Override public void visitFieldInsn(int op, String owner, String fn, String fd) {
                                    if (op == Opcodes.GETSTATIC && HOOKPOINT_OWNER.equals(owner)) claimed.add(fn);
                                }
                            };
                        }
                    }, ClassReader.SKIP_FRAMES);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            LauncherLog.warn("[HookPointFilter] scanClaimedHookPoints: " + t);
        }
        return claimed;
    }

    private static Set<String> discoverMixinTargets(String configName) {
        Set<String> targets = new LinkedHashSet<>();
        Map<String, String> unmapped = new LinkedHashMap<>();
        try {
            ClassLoader agentCL = IsolatedBootstrap.class.getClassLoader();
            try (java.io.InputStream cfgIs = agentCL.getResourceAsStream(configName)) {
                if (cfgIs == null) {
                    LauncherLog.err("[LauncherAgent] " + configName + " introuvable dans le JAR");
                    return targets;
                }
                String json = new String(readAllBytes(cfgIs), java.nio.charset.StandardCharsets.UTF_8);
                String pkg = jsonString(json, "package");
                if (pkg == null) return targets;

                for (String arrayKey : new String[]{"mixins", "client", "server"}) {
                    int keyIdx = json.indexOf("\"" + arrayKey + "\"");
                    if (keyIdx < 0) continue;
                    int arrStart = json.indexOf('[', keyIdx);
                    int arrEnd = json.indexOf(']', arrStart);
                    if (arrStart < 0 || arrEnd < 0) continue;

                    // "_" inclus : requis par le package v1_8 (Mixins 1.8.9) — sans
                    // lui, "client.v1_8.XXX" ne matche jamais (aucune erreur ni
                    // warning déclenché non plus : targets reste juste vide en
                    // silence, bug découvert via diagnostic fichier, voir diag.log).
                    Matcher m = Pattern.compile("\"([A-Za-z][A-Za-z0-9$._]+)\"")
                            .matcher(json.substring(arrStart + 1, arrEnd));
                    while (m.find()) {
                        String entry = m.group(1);
                        String classRes = pkg.replace('.', '/') + "/" + entry.replace('.', '/') + ".class";
                        LauncherLog.agent(1, "[LauncherAgent] Scan bytecode [" + arrayKey + "]: " + entry);
                        try (java.io.InputStream cls = agentCL.getResourceAsStream(classRes)) {
                            if (cls == null) {
                                LauncherLog.warn("[LauncherAgent]   → .class introuvable: " + classRes);
                                unmapped.put(entry, ".class introuvable dans le JAR (" + classRes + ")");
                                continue;
                            }
                            targets.addAll(extractMixinTargets(readAllBytes(cls), entry, unmapped));
                        } catch (Throwable e) {
                            LauncherLog.err("[LauncherAgent]   → ERREUR " + entry + ": " + e);
                            unmapped.put(entry, "exception au scan : " + e);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] discoverMixinTargets erreur: " + t);
        }
        LauncherLog.agent(3, "[LauncherAgent] " + targets.size() + " cible(s) Mixin: " + targets);

        if (!unmapped.isEmpty()) {
            StringBuilder sb = new StringBuilder("Résolution Yarn→obfusqué échouée pour ");
            sb.append(unmapped.size()).append(" Mixin(s) :\n");
            for (Map.Entry<String, String> e : unmapped.entrySet()) {
                sb.append("  - ").append(e.getKey()).append(" : ").append(e.getValue()).append('\n');
            }
            sb.append("Vérifiez que la version de Yarn chargée correspond à la version de Minecraft lancée.");
            throw LauncherLog.fatal("[LauncherAgent] " + sb);
        }

        return targets;
    }

    private static Set<String> extractMixinTargets(byte[] classBytes, String simpleName,
                                                     Map<String, String> unmapped) {
        Set<String> result = new LinkedHashSet<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (!desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        if (name.equals("value")) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override public void visit(String n, Object val) {
                                    if (!(val instanceof Type)) return;
                                    String slash = ((Type) val).getInternalName();
                                    String obf = MappingsRegistry.INSTANCE.map(slash);
                                    result.add(obf.replace('/', '.'));
                                    LauncherLog.agent(1, "[LauncherAgent]   → " + simpleName
                                            + " value: " + slash + " → " + obf.replace('/', '.'));
                                    checkMapped(simpleName, slash, obf, unmapped);
                                }
                            };
                        }
                        if (name.equals("targets")) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override public void visit(String n, Object val) {
                                    if (!(val instanceof String)) return;
                                    String slash = ((String) val).replace('.', '/');
                                    String obf = MappingsRegistry.INSTANCE.map(slash);
                                    result.add(obf.replace('/', '.'));
                                    LauncherLog.agent(1, "[LauncherAgent]   → " + simpleName
                                            + " targets: " + val + " → " + obf.replace('/', '.'));
                                    checkMapped(simpleName, slash, obf, unmapped);
                                }
                            };
                        }
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (result.isEmpty()) {
            LauncherLog.warn("[LauncherAgent]   → WARN: aucune cible trouvée dans " + simpleName);
            unmapped.put(simpleName, "aucune cible @Mixin(value/targets) trouvée dans le bytecode");
        }
        return result;
    }

    private static void checkMapped(String simpleName, String yarnSlash, String obfSlash,
                                      Map<String, String> unmapped) {
        if (MappingsRegistry.isLoaded() && yarnSlash.equals(obfSlash)) {
            unmapped.put(simpleName + " → " + yarnSlash.replace('/', '.'),
                "aucune entrée Yarn pour cette classe (mapping inchangé)");
        }
    }

    private static String jsonString(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        i = json.indexOf('"', json.indexOf(':', i) + 1);
        if (i < 0) return null;
        int end = json.indexOf('"', i + 1);
        return end > i ? json.substring(i + 1, end) : null;
    }

    private static void retransformLoadedTargets(Instrumentation inst, Set<String> targets) {
        if (targets.isEmpty()) return;
        int count = 0;
        for (Class<?> cls : inst.getAllLoadedClasses()) {
            if (!targets.contains(cls.getName())) continue;
            // Tout le corps est protégé, countHooks() COMPRIS : il appelle
            // getDeclaredMethods(), qui force la résolution de TOUTES les
            // signatures de la classe et peut donc lever une LinkageError sur
            // une classe massive (Minecraft). Hors try, cette erreur remontait
            // et tuait la boucle — voir la même correction dans
            // scheduleDelayedRetransform, où elle tuait carrément le thread.
            try {
                // Défensif : si la classe est déjà mixée (voir countHooks), pas besoin
                // de retransform, même ici — même raisonnement que scheduleDelayedRetransform.
                if (countHooks(cls) > 0) {
                    LauncherLog.agent(1, "[LauncherAgent] Cible déjà mixée (retransform immédiat): "
                            + cls.getName() + " — skip");
                    continue;
                }
                boolean modifiable = inst.isModifiableClass(cls);
                LauncherLog.agent(1, "[LauncherAgent] Retransform immédiat: " + cls.getName()
                        + " | modifiable=" + modifiable);
                if (!modifiable) continue;
                inst.retransformClasses(cls);
                count++;
            } catch (Throwable ex) {
                LauncherLog.err("[LauncherAgent] Retransform immédiat " + cls.getName() + " erreur: " + ex);
                ex.printStackTrace(System.err);
            }
        }
        LauncherLog.agent(3, "[LauncherAgent] Retransformations immédiates: " + count + "/" + targets.size());
    }

    private static void scheduleDelayedRetransform(Instrumentation inst, Set<String> targets) {
        if (targets.isEmpty()) return;
        // DIAG §12 : niveau 3 (visible) — sert à répondre à « Minecraft est-il
        // seulement dans la liste des cibles ? ». Les décisions par classe
        // ci-dessous étaient en niveau 1, donc filtrées : on ne voyait ni les
        // skips ni les retransforms, seulement leurs effets.
        LauncherLog.agent(3, "[LauncherAgent] Cibles retransform (" + targets.size() + ") — Minecraft présent="
                + targets.contains("net.minecraft.client.Minecraft"));
        Thread t = new Thread(() -> {
            Set<String> remaining = new LinkedHashSet<>(targets);
            long deadline = System.currentTimeMillis() + 30_000;
            while (!remaining.isEmpty() && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
                for (Class<?> cls : inst.getAllLoadedClasses()) {
                    if (!remaining.remove(cls.getName())) continue;
                    // ⚠️ countHooks() DOIT être dans le try (2026-08-25, §12) : il
                    // appelle getDeclaredMethods(), qui résout toutes les signatures
                    // de la classe et peut lever une LinkageError/Error sur une
                    // classe massive comme Minecraft. Placé hors du try comme
                    // auparavant, cette erreur remontait hors du for ET du while et
                    // TUAIT CE THREAD — donc tous les rattrapages restants, en
                    // silence. Symptôme observé : dès qu'un mixin ciblant Minecraft
                    // entrait dans la liste des cibles, une douzaine de mixins sans
                    // rapport (Options, Camera, ClientClockManager, LevelRenderer,
                    // TitleScreen…) cessaient d'être tissés, sans le moindre message.
                    try {
                        // Source AUTORITAIRE d'abord (2026-08-25, §14) : le
                        // transformer note lui-même les classes qu'il a
                        // réellement tissées au chargement — countHooks() en
                        // dessous reste comme filet, mais il est race-y (voir
                        // LauncherMixinTransformerWrapper.MIXED_AT_LOAD).
                        if (com.yuyuframe.launcheragent.apimixin.service.LauncherMixinTransformerWrapper
                                .wasMixedAtLoad(cls.getName())) {
                            LauncherLog.agent(3, "[LauncherAgent] Cible déjà tissée au chargement (transformer): "
                                    + cls.getName() + " — skip retransform");
                            continue;
                        }
                        int alreadyHooks = countHooks(cls);
                        if (alreadyHooks > 0) {
                            // Les noms sont journalisés : un skip injustifié (faux
                            // positif comme celui de Minecraft en §12) se repère
                            // alors d'un coup d'œil dans launcher-agent.log.
                            LauncherLog.agent(3, "[LauncherAgent] Cible déjà mixée (initial load): "
                                    + cls.getName() + " — skip retransform (" + alreadyHooks
                                    + " hooks: " + hookNames(cls) + ")");
                            continue;
                        }
                        boolean mod = inst.isModifiableClass(cls);
                        LauncherLog.agent(3, "[LauncherAgent] Retransform différé: " + cls.getName()
                                + " | modifiable=" + mod);
                        if (!mod) continue;
                        inst.retransformClasses(cls);
                    } catch (Throwable ex) {
                        LauncherLog.err("[LauncherAgent] Retransform différé " + cls.getName() + " erreur: " + ex);
                        ex.printStackTrace(System.err);
                    }
                }
            }
            if (!remaining.isEmpty()) {
                LauncherLog.agent(3, "[LauncherAgent] cibles JAMAIS CHARGÉES (" + remaining.size() + "): " + remaining);
            }
            LauncherLog.agent(3, "[LauncherAgent] Thread retransform terminé");
        }, "LauncherAgent-Retransform") {
            @Override public void run() {
                // Filet global : une Error non rattrapée ici faisait disparaître
                // tout le rattrapage sans laisser la moindre trace dans le log.
                try {
                    super.run();
                } catch (Throwable fatal) {
                    LauncherLog.err("[LauncherAgent] Thread retransform MORT : " + fatal);
                    fatal.printStackTrace(System.err);
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    /**
     * CORRECTIF (bug de timing réel, trouvé via log DIAG dans
     * LauncherMixinTransformerWrapper — voir historique du projet) : Mixin
     * RENOMME les méthodes handler effectivement appliquées en
     * "handler$<id>$<nomOriginal>" (ex: "handler$zza000$la$onInit", vu dans
     * les logs) — un nom qui NE COMMENCE JAMAIS par "la$" même quand
     * l'injection a parfaitement réussi. L'ancien test
     * ("startsWith(\"la$\")") ne trouvait donc JAMAIS rien, y compris sur des
     * classes déjà mixées avec succès au chargement initial — ce qui faisait
     * forcer un retransform à chaud INUTILE (et dans certains cas dangereux,
     * en conflit avec le mixin d'un autre mod sur la même classe, ex:
     * fabric-rendering-v1 sur GameRenderer) sur des cibles qui n'en avaient
     * en réalité aucun besoin. Chercher "la$" n'importe où dans le nom
     * (au lieu d'exiger qu'il soit en tête) détecte correctement ce cas.
     */
    /**
     * Compte les méthodes que NOUS avons tissées dans {@code cls}.
     *
     * Resserré 2026-08-25 (§12) : le test était
     * {@code m.getName().contains("la$")} — un {@code contains} sur 3
     * caractères, qui matche la séquence « la$ » n'importe où, y compris au
     * milieu d'un nom de méthode injecté par un AUTRE mod. Aucun faux positif
     * constaté à ce jour (le skip observé sur {@code Minecraft} portait bien
     * sur notre propre {@code handler$zba000$la$dispatchScreenSet}, donc à
     * juste titre) — c'est un durcissement préventif, pas la correction d'un
     * bug avéré. Ne pas relâcher ce test sans raison.
     *
     * Formes réellement produites pour nos handlers, toutes couvertes ici :
     * <ul>
     *   <li>{@code la$applyFreelookOffset} — tissage direct, préfixe intact</li>
     *   <li>{@code handler$zza000$la$onInit} — renommage @Inject de Mixin</li>
     *   <li>{@code la$guiTextured_$md$f4a86e$0} — renommage @Accessor</li>
     * </ul>
     * Un nom qui contient « la$ » sans être à l'une de ces deux positions
     * n'est pas à nous et ne doit plus compter.
     */
    private static int countHooks(Class<?> cls) {
        int n = 0;
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            String name = m.getName();
            if (name.startsWith("la$") || name.contains("$la$")) n++;
        }
        return n;
    }

    /** Noms des méthodes comptées par {@link #countHooks} — pour rendre un faux positif lisible dans le log. */
    private static String hookNames(Class<?> cls) {
        StringBuilder sb = new StringBuilder();
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            String name = m.getName();
            if (name.startsWith("la$") || name.contains("$la$")) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(name);
            }
        }
        return sb.toString();
    }
}
