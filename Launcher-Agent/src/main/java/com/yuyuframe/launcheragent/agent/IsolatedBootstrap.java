package com.yuyuframe.launcheragent.agent;

import com.yuyuframe.launcheragent.mixin.service.LauncherMixinService;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.runtime.mapping.YarnMappings;
import com.yuyuframe.launcheragent.runtime.version.VersionBracket;
import com.yuyuframe.launcheragent.runtime.version.VersionBracketRegistry;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigSource;

import org.objectweb.asm.*;

import java.lang.instrument.Instrumentation;
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

        // Sélection du fichier de config Mixin selon la version MC.
        String mixinConfig = bracket.mixinConfigResource;

        Set<String> mixinTargets = discoverMixinTargets(mixinConfig);
        bootstrapMixin(inst, mixinTargets, mixinConfig);
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

    /** @return true si le bootstrap a réussi. */
    private static boolean bootstrapMixin(Instrumentation inst, Set<String> mixinTargets, String mixinConfig) {
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

            Mixins.addConfiguration(mixinConfig, (IMixinConfigSource) null);
            LauncherLog.agent(1, "[LauncherAgent] Config Mixin enregistrée : " + mixinConfig);

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
                + mixinConfig + ", " + mixinTargets.size() + " cible(s) : " + mixinTargets + ")");
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
            try {
                inst.retransformClasses(cls);
                count++;
            } catch (Throwable ex) {
                LauncherLog.err("[LauncherAgent] Retransform " + cls.getName() + " erreur: " + ex);
            }
        }
        LauncherLog.agent(3, "[LauncherAgent] Retransformations immédiates: " + count + "/" + targets.size());
    }

    private static void scheduleDelayedRetransform(Instrumentation inst, Set<String> targets) {
        if (targets.isEmpty()) return;
        Thread t = new Thread(() -> {
            Set<String> remaining = new LinkedHashSet<>(targets);
            long deadline = System.currentTimeMillis() + 30_000;
            while (!remaining.isEmpty() && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
                for (Class<?> cls : inst.getAllLoadedClasses()) {
                    if (!remaining.remove(cls.getName())) continue;
                    int alreadyHooks = countHooks(cls);
                    if (alreadyHooks > 0) {
                        LauncherLog.agent(1, "[LauncherAgent] Cible déjà mixée (initial load): "
                                + cls.getName() + " — skip retransform");
                        continue;
                    }
                    boolean mod = inst.isModifiableClass(cls);
                    LauncherLog.agent(1, "[LauncherAgent] Retransform différé: " + cls.getName()
                            + " | modifiable=" + mod);
                    if (!mod) continue;
                    try {
                        inst.retransformClasses(cls);
                    } catch (Throwable ex) {
                        LauncherLog.err("[LauncherAgent] Retransform " + cls.getName() + " erreur: " + ex);
                    }
                }
            }
            if (!remaining.isEmpty()) {
                LauncherLog.warn("[LauncherAgent] cibles jamais chargées: " + remaining);
            }
            LauncherLog.agent(1, "[LauncherAgent] Thread retransform terminé");
        }, "LauncherAgent-Retransform");
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
    private static int countHooks(Class<?> cls) {
        int n = 0;
        for (java.lang.reflect.Method m : cls.getDeclaredMethods())
            if (m.getName().contains("la$")) n++;
        return n;
    }
}
