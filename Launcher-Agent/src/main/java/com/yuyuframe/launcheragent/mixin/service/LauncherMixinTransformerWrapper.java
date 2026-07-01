package com.yuyuframe.launcheragent.mixin.service;

import com.yuyuframe.launcheragent.mixin.service.transformer.ScreenStubPatcher;
import com.yuyuframe.launcheragent.runtime.log.DiagFile;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Adapte IMixinTransformer → ClassFileTransformer.
 *
 * Patch ASM direct (sans ajout de méthodes) :
 *   Nos écrans custom (cf. STUB_PATCHED_SCREENS) — remap stubs Screen/Text
 *   vers les classes obfusquées réelles (ScreenStubPatcher) — voir ce
 *   patcher pour le pourquoi.
 */
public class LauncherMixinTransformerWrapper implements ClassFileTransformer {

    /** Classes compilées contre les stubs Screen/Text — à patcher au chargement. */
    private static final java.util.Set<String> STUB_PATCHED_SCREENS = java.util.Collections.unmodifiableSet(
        new java.util.HashSet<String>(java.util.Arrays.asList(
            "com/yuyuframe/launcheragent/screen/ResourcePackSearchScreen",
            "com/yuyuframe/launcheragent/screen/ResourcePackDetailScreen",
            "com/yuyuframe/launcheragent/screen/ShaderPackSearchScreen",
            "com/yuyuframe/launcheragent/screen/ShaderPackDetailScreen",
            "com/yuyuframe/launcheragent/screen/CustomKeybindsScreen",
            "com/yuyuframe/launcheragent/screen/UiScreenBase"
        )));

    private final IMixinTransformer transformer;

    public LauncherMixinTransformerWrapper(IMixinTransformer transformer) {
        this.transformer = transformer;
    }

    // TEMPORAIRE (diagnostic pipeline 1.8.9) — cibles obfusquées connues de ce
    // test (TitleScreen=aya, EntityRenderDispatcher=biu, GameRenderer=bfk) —
    // retirer une fois validé en jeu.
    private static final java.util.Set<String> DIAG_TARGETS = new java.util.HashSet<String>(
        java.util.Arrays.asList("aya", "biu", "bfk"));

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain domain,
                            byte[] classfileBuffer) {
        if (classfileBuffer == null || className == null) return null;
        if (isBootstrapPackage(className)) return null;

        boolean diag = DIAG_TARGETS.contains(className);
        if (diag) DiagFile.log("transform() APPELÉ pour " + className + " (taille=" + classfileBuffer.length + ")");

        // ── Nos propres écrans : remap stubs Screen / Text ───────────────────
        if (STUB_PATCHED_SCREENS.contains(className)) {
            byte[] patched = ScreenStubPatcher.patch(classfileBuffer);
            if (patched != null) return patched;
        }

        String obfDot = className.replace('/', '.');
        String yarnNamed = com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry.INSTANCE.unmap(className);

        if (diag) DiagFile.log("transform() " + className + " → obfDot=" + obfDot + " yarnNamed=" + yarnNamed);

        try {
            byte[] result = transformer.transformClassBytes(obfDot, yarnNamed.replace('/', '.'), classfileBuffer);
            if (diag) DiagFile.log("transform() " + className + " résultat: "
                + (result == null ? "null (inchangé)" : "modifié, taille=" + result.length));
            return result;
        } catch (Throwable t) {
            if (diag) {
                DiagFile.log("transform() " + className + " EXCEPTION: " + t);
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                DiagFile.log(sw.toString());
            }
            throw t;
        }
    }

    /**
     * Exclut les classes JDK/bootstrap (java/, javax/, jdk/, sun/, com/sun/)
     * avant de les transmettre à Mixin.
     *
     * Can-Retransform-Classes: true fait que ce transformer est appelé pour
     * TOUTE classe chargée dans la JVM, pas seulement nos cibles Mixin —
     * y compris les classes JDK internes. Si l'une d'elles (ex:
     * java.io.InterruptedIOException, chargée paresseusement à la première
     * E/S interrompue) se charge alors que notre transformer est déjà actif,
     * la transmettre à Mixin peut redéclencher du logging/chargement de
     * classes qui boucle sur cette même classe en cours de définition →
     * ClassCircularityError. Aucune classe JDK n'est jamais une cible Mixin
     * légitime, donc ce filtre est sans risque fonctionnel.
     */
    private static boolean isBootstrapPackage(String className) {
        return className.startsWith("java/")
            || className.startsWith("javax/")
            || className.startsWith("jdk/")
            || className.startsWith("sun/")
            || className.startsWith("com/sun/");
    }
}
