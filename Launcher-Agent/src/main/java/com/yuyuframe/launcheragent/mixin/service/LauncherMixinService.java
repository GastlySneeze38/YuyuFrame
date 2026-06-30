package com.yuyuframe.launcheragent.mixin.service;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.runtime.mapping.YarnMappings;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.*;
import org.spongepowered.asm.util.ReEntranceLock;

import java.lang.instrument.Instrumentation;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;

/**
 * Service Mixin standalone pour LauncherAgent — sans LaunchWrapper/ModLauncher.
 * Enregistré via META-INF/services, sélectionné car isValid() = true.
 *
 * Copie indépendante de com.p2pminecraft.mixin.service.P2PMixinService :
 * même plomberie, mais aucune classe partagée avec le p2p-agent.
 */
public class LauncherMixinService implements IMixinService, IClassProvider, IClassBytecodeProvider {

    private static volatile Instrumentation savedInst;
    private static volatile IMixinTransformer storedTransformer;

    public static void setInstrumentation(Instrumentation inst) {
        savedInst = inst;
    }

    public static Instrumentation getInstrumentation() { return savedInst; }

    private final ReEntranceLock lock = new ReEntranceLock(1);
    private final IContainerHandle container =
        new LauncherContainerHandle("launcher-agent", "YuyuFrame LauncherAgent");

    @Override public String getName()  { return "LauncherJavaAgent"; }
    @Override public boolean isValid() { return true; }
    @Override public void prepare()    {}
    @Override public void init()       {}
    @Override public void beginPhase() {}

    @Override
    public void offer(IMixinInternal internal) {
        if (!(internal instanceof IMixinTransformerFactory)) return;
        try {
            storedTransformer = ((IMixinTransformerFactory) internal).createTransformer();
            LauncherLog.asm(1, "[LauncherAgent] offer() : transformer stocké, wrapper installé plus tard");
        } catch (Exception e) {
            LauncherLog.err("[LauncherAgent] Erreur offer(): " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    /** Appelé explicitement par LauncherAgent.premain() APRÈS mappings + addConfiguration(). */
    public static void installWrapper() {
        if (savedInst == null) { LauncherLog.err("[LauncherAgent] installWrapper: savedInst null"); return; }
        if (storedTransformer == null) { LauncherLog.err("[LauncherAgent] installWrapper: storedTransformer null"); return; }
        try {
            savedInst.addTransformer(new LauncherMixinTransformerWrapper(storedTransformer), true);
            LauncherLog.asm(3, "[LauncherAgent] Wrapper installé (mappings+config prêts, canRetransform=true)");
        } catch (Exception e) {
            LauncherLog.err("[LauncherAgent] installWrapper() erreur: " + e);
        }
    }

    @Override public void checkEnv(Object bootSource) {}

    @Override
    public MixinEnvironment.Phase getInitialPhase() {
        return MixinEnvironment.Phase.DEFAULT;
    }

    @Override public ReEntranceLock getReEntranceLock()     { return lock; }
    @Override public IClassProvider getClassProvider()      { return this; }
    @Override public IClassBytecodeProvider getBytecodeProvider() { return this; }
    @Override public ITransformerProvider getTransformerProvider() { return null; }
    @Override public IClassTracker getClassTracker()        { return null; }
    @Override public IMixinAuditTrail getAuditTrail()       { return null; }

    @Override
    public Collection<String> getPlatformAgents() { return Collections.emptyList(); }

    @Override
    public IContainerHandle getPrimaryContainer() { return container; }

    @Override
    public Collection<IContainerHandle> getMixinContainers() {
        return Collections.emptyList();
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        ClassLoader cl = getContextClassLoader();
        InputStream is = cl.getResourceAsStream(name);
        if (is == null) {
            cl = LauncherMixinService.class.getClassLoader();
            is = cl.getResourceAsStream(name);
        }
        return is;
    }

    /**
     * Une entrée = un @Inject(method="...") à traduire dans le refmap.
     *
     * Tous les noms sont en Yarn NAMED (humain, stables entre versions) :
     *   namedMethod / namedDesc → lookup Yarn → official → intermediary (Fabric).
     * Plus aucun nom obfusqué hardcodé ici — si l'official change entre 1.21.x
     * et 1.22, rien à toucher tant que le nom Yarn named reste "init" / "initWidgets".
     *
     * {@code fallbackNamedOwner} : classe Yarn named vers laquelle replier la
     * recherche quand la méthode est héritée sans entrée propre à la sous-classe
     * (ex: "init" est déclaré sur Screen, pas sur TitleScreen dans le Yarn).
     * {@code null} si la méthode est directement sur la classe cible.
     *
     * Pour les constructeurs ("<init>"), namedDesc utilise les noms Yarn named
     * des types (ex: "Lnet/minecraft/client/gui/screen/Screen;") — traduits en
     * official puis intermediary par runtimeDesc() au moment de la génération.
     */
    private record RefmapEntry(String mixinInternalName, String yarnTargetClass,
                                String namedMethod, String namedDesc, String fallbackNamedOwner) {}

    private static final RefmapEntry[] REFMAP_ENTRIES = {
        // init() héritée de Screen — repli sur Screen pour la lookup Yarn.
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/TitleScreenMixin",
            "net/minecraft/client/gui/screen/TitleScreen",
            "init", "()V", "net/minecraft/client/gui/screen/Screen"),
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/PackScreenMixin",
            "net/minecraft/client/gui/screen/pack/PackScreen",
            "init", "()V", "net/minecraft/client/gui/screen/Screen"),
        // initWidgets() déclaré directement sur GameMenuScreen — pas de repli.
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/GameMenuScreenMixin",
            "net/minecraft/client/gui/screen/GameMenuScreen",
            "initWidgets", "()V", null),
        // "<init>" n'a jamais de nom à traduire, mais le descripteur contient des
        // types Yarn named (Screen, GameOptions) → traduits en official+intermediary
        // par runtimeDesc() via la lookup Yarn.
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/KeybindsScreenMixin",
            "net/minecraft/client/gui/screen/option/KeybindsScreen",
            "<init>",
            "(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/option/GameOptions;)V",
            null),
    };

    /**
     * Construit le JSON du refmap Mixin (official→intermediary) pour nos
     * @Inject(method="...", ...). Écrit dans un VRAI fichier par l'appelant
     * (IsolatedBootstrap).
     *
     * En vanilla (scheme OFFICIAL), l'appelant n'écrit aucun fichier du tout —
     * Mixin retombe sur la chaîne littérale Yarn named inchangée (warning
     * "No refMap loaded", non fatal) — comportement validé, aucune régression.
     */
    public static String buildRefmapJson() {
        java.util.Map<String, java.util.Map<String, String>> byMixin = new java.util.LinkedHashMap<>();
        for (RefmapEntry e : REFMAP_ENTRIES) {
            // La CLÉ JSON = ce qui est écrit dans @Inject(method = "...") = nom named + desc named.
            // Mixin cherche cette clé dans le refmap pour obtenir le nom intermediary (Fabric).
            // Sur vanilla, le remapper (MappingsRegistry) traduit le nom named → official directement,
            // sans passer par le refmap — les deux chemins sont indépendants.
            String refmapKey = e.namedMethod() + e.namedDesc();

            // La VALEUR = nom intermediary + desc intermediary, pour que Fabric trouve la méthode.
            String officialMethod = resolveOfficialMethodName(e.yarnTargetClass(), e.namedMethod(),
                                                              e.namedDesc(), e.fallbackNamedOwner());
            String officialDesc   = resolveOfficialDesc(e.namedDesc());
            String replacement    = refmapMethodReplacement(e.yarnTargetClass(), officialMethod,
                                                            officialDesc, e.fallbackNamedOwner());
            byMixin.computeIfAbsent(e.mixinInternalName(), k -> new java.util.LinkedHashMap<>())
                   .put(refmapKey, replacement);
        }

        StringBuilder sb = new StringBuilder("{\"mappings\":{");
        boolean firstMixin = true;
        for (var mixinEntry : byMixin.entrySet()) {
            if (!firstMixin) sb.append(',');
            firstMixin = false;
            sb.append('"').append(mixinEntry.getKey()).append("\":{");
            boolean firstMethod = true;
            for (var methodEntry : mixinEntry.getValue().entrySet()) {
                if (!firstMethod) sb.append(',');
                firstMethod = false;
                sb.append('"').append(methodEntry.getKey()).append("\":\"")
                  .append(methodEntry.getValue()).append('"');
            }
            sb.append('}');
        }
        sb.append("}}");
        return sb.toString();
    }

    /**
     * Yarn named → official pour un nom de méthode.
     * Cherche d'abord dans la classe cible, puis dans fallbackNamedOwner.
     * Retourne namedMethod tel quel si introuvable (constructeur ou méthode non obfusquée).
     */
    private static String resolveOfficialMethodName(String yarnClass, String namedMethod,
                                                     String namedDesc, String fallbackNamedOwner) {
        if ("<init>".equals(namedMethod)) return "<init>";
        YarnMappings.MethodEntry me = YarnMappings.getOfficialMethod(yarnClass, namedMethod);
        if (me == null && fallbackNamedOwner != null)
            me = YarnMappings.getOfficialMethod(fallbackNamedOwner, namedMethod);
        if (me != null) {
            LauncherLog.asm(1, "[LauncherAgent] refmap named→official: " + namedMethod + " → " + me.officialName);
            return me.officialName;
        }
        LauncherLog.warn("[LauncherAgent] refmap: aucune entrée Yarn pour " + yarnClass + "#" + namedMethod
            + " — utilisation du nom named tel quel");
        return namedMethod;
    }

    /**
     * Traduit un descripteur Yarn named → official (pour les types dans la signature).
     * Ex: "Lnet/minecraft/client/gui/screen/Screen;" → "Lgsb;"
     * Les primitives et "()", "V" passent tels quels.
     */
    private static String resolveOfficialDesc(String namedDesc) {
        StringBuilder sb = new StringBuilder(namedDesc.length());
        int i = 0;
        while (i < namedDesc.length()) {
            char c = namedDesc.charAt(i++);
            if (c == 'L') {
                int semi = namedDesc.indexOf(';', i);
                if (semi < 0) { sb.append('L').append(namedDesc.substring(i)); break; }
                String namedCls = namedDesc.substring(i, semi);
                String officialCls = YarnMappings.getOfficialClass(namedCls);
                sb.append('L').append(officialCls != null ? officialCls : namedCls).append(';');
                i = semi + 1;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * official → intermediary pour la valeur du refmap JSON.
     * Cherche d'abord dans la classe cible, puis dans fallbackNamedOwner (converti en official).
     */
    private static String refmapMethodReplacement(String yarnClass, String officialMethod,
                                                   String officialDesc, String fallbackNamedOwner) {
        String officialClass = YarnMappings.getOfficialClass(yarnClass);
        String inter = officialClass != null
            ? YarnMappings.getIntermediaryMethod(officialClass, officialMethod, officialDesc)
            : null;
        if (inter == null && fallbackNamedOwner != null) {
            String fallbackOfficial = YarnMappings.getOfficialClass(fallbackNamedOwner);
            if (fallbackOfficial != null)
                inter = YarnMappings.getIntermediaryMethod(fallbackOfficial, officialMethod, officialDesc);
        }
        LauncherLog.asm(1, "[LauncherAgent] refmap official→inter: " + officialMethod + officialDesc
            + " → " + inter);
        return (inter != null ? inter : officialMethod) + MappingsRegistry.runtimeDesc(officialDesc);
    }

    @Override public String getSideName() { return "CLIENT"; }

    @Override
    public MixinEnvironment.CompatibilityLevel getMinCompatibilityLevel() { return null; }

    @Override
    public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() { return null; }

    @Override
    public ILogger getLogger(String name) { return new LauncherLogger(name); }

    // ── IClassProvider ────────────────────────────────────────────────────────

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, getContextClassLoader());
    }

    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, getContextClassLoader());
    }

    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, LauncherMixinService.class.getClassLoader());
    }

    @Override
    public URL[] getClassPath() { return new URL[0]; }

    // ── IClassBytecodeProvider ────────────────────────────────────────────────

    @Override
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, false, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers)
            throws ClassNotFoundException, IOException {
        return getClassNode(name, runTransformers, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags)
            throws ClassNotFoundException, IOException {
        String nameSlash = name.replace('.', '/');
        String resource = nameSlash + ".class";

        ClassLoader cl = getContextClassLoader();
        InputStream is = cl.getResourceAsStream(resource);
        if (is == null) {
            cl = LauncherMixinService.class.getClassLoader();
            is = cl.getResourceAsStream(resource);
        }

        String obfSlash = nameSlash;
        if (is == null && MappingsRegistry.isLoaded()) {
            // Toujours "official" ici, jamais le schéma actif (intermediary
            // sous Fabric) — cette lecture cherche un VRAI fichier .class sur
            // le classpath, et seul le nom official correspond à une entrée
            // réelle dans le jar Minecraft (toujours présent même sous Fabric,
            // qui en a besoin comme source de remapping) — voir
            // MappingsRegistry.getOfficialClassAlways().
            obfSlash = MappingsRegistry.getOfficialClassAlways(nameSlash);
            if (!obfSlash.equals(nameSlash)) {
                String obfResource = obfSlash + ".class";
                cl = getContextClassLoader();
                is = cl.getResourceAsStream(obfResource);
                if (is == null) {
                    cl = LauncherMixinService.class.getClassLoader();
                    is = cl.getResourceAsStream(obfResource);
                }
            }
        }

        if (is == null) throw new ClassNotFoundException(name);

        try {
            ClassReader cr = new ClassReader(is);
            ClassNode cn = new ClassNode();
            cr.accept(cn, readerFlags == 0 ? ClassReader.EXPAND_FRAMES : readerFlags);
            if (!obfSlash.equals(nameSlash)) {
                cn.name = nameSlash;
            }
            return cn;
        } finally {
            is.close();
        }
    }

    private static ClassLoader getContextClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : ClassLoader.getSystemClassLoader();
    }
}
