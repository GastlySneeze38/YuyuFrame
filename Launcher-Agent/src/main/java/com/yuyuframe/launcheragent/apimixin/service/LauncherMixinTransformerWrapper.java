package com.yuyuframe.launcheragent.apimixin.service;

import com.yuyuframe.launcheragent.apimixin.service.transformer.ScreenStubPatcher;
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
            "com/yuyuframe/launcheragent/runtime/ui/ingameui/UiScreenBase"
        )));

    private final IMixinTransformer transformer;

    public LauncherMixinTransformerWrapper(IMixinTransformer transformer) {
        this.transformer = transformer;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain domain,
                            byte[] classfileBuffer) {
        if (classfileBuffer == null || className == null) return null;
        if (isBootstrapPackage(className)) return null;

        triggerEarlyKnotExpose(loader);

        // Le classloader qui définit une classe du jeu est, par construction,
        // capable de lire le jar du jeu — contrairement à isolatedCl, seul
        // loader connu de LauncherMixinService. Capture volontairement
        // agnostique du loader (Knot sous Fabric, système en vanilla,
        // TransformingClassLoader sous Forge) — voir la javadoc de
        // LauncherMixinService.gameClassLoader pour le pourquoi.
        if (loader != null && className.startsWith("net/minecraft/")) {
            LauncherMixinService.setGameClassLoader(loader);
        }

        // ── Nos propres écrans : remap stubs Screen / Text ───────────────────
        if (STUB_PATCHED_SCREENS.contains(className)) {
            byte[] patched = ScreenStubPatcher.patch(classfileBuffer, loader);
            if (patched != null) return patched;
        }

        String obfDot = className.replace('/', '.');
        String yarnNamed = com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry.INSTANCE.unmap(className);

        return transformer.transformClassBytes(obfDot, yarnNamed.replace('/', '.'), classfileBuffer);
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

    private static volatile boolean knotExposeTriggered = false;

    /**
     * Déclenche {@code FabricKnotExposer.ensureExposed()} dès la PREMIÈRE
     * classe transformée dont le classloader est Knot/Quilt (2026-08-24, voir
     * [[project_mc_261_port]] §11, bug C — LinkageError app/knot sur
     * VanillaHookRegistry).
     *
     * Root cause identifiée par lecture de code (pas par log — les diagnostics
     * précédents dans FabricKnotExposer/VanillaHookRegistry n'ont jamais
     * réussi à capturer le moment exact) : {@code GameRenderExtractMixin261}/
     * {@code ClockTotalTicksMixin261} appellent {@code VanillaHookRegistry}
     * DIRECTEMENT depuis {@code GameRenderer.extract}/{@code
     * ClientClockManager.getTotalTicks} — deux points d'entrée qui s'exécutent
     * à CHAQUE frame, dès la toute première, SANS jamais appeler
     * {@code ensureExposed()} avant (contrairement aux 2 mixins "hub" qui,
     * eux, l'appellent en tête de {@code GameRenderer.render}). Comme
     * {@code extract} précède {@code render} dans le pipeline de rendu
     * moderne, ce chemin gagne systématiquement la course à la PREMIÈRE
     * résolution de VanillaHookRegistry par Knot — qui, code source pas
     * encore possédé, délègue silencieusement à APP (grâce à
     * {@code fabric.debug.disableClassPathIsolation}, posé dans
     * LauncherAgent.premain0() — sans lui Knot lèverait une exception
     * bruyante au lieu de déléguer en silence). Cette résolution se fige
     * pour ce site d'appel précis, quoi qu'il arrive ensuite.
     *
     * Fix : n'importe QUEL point d'entrée qui touche VanillaHookRegistry en
     * premier casse le même piège — plutôt que garder chaque mixin
     * individuellement responsable d'appeler ensureExposed() (fragile, un
     * seul oubli suffit), on le garantit ICI, structurellement, dès que Knot
     * transforme sa toute première classe — largement avant que le moindre
     * mixin apimixin ne s'exécute.
     *
     * ⚠️ Volontairement PAS de réflexion inline ici : {@code transform()} est
     * appelé par la JVM PENDANT la définition d'une classe (JVMTI class-file-
     * load hook), pour TOUTE classe du process (voir {@link
     * #isBootstrapPackage}) — un contexte non-réentrant. La réflexion
     * profonde de {@code FabricKnotExposer} ({@code getDeclaredMethods()}/
     * {@code getDeclaredFields()} récursifs) peut elle-même déclencher du
     * chargement de classes ; si l'une d'elles boucle sur la classe en cours
     * de définition sur CE thread → {@code ClassCircularityError}. On se
     * contente donc de CAPTURER la référence du classloader ici (gratuit,
     * aucune réflexion) et de déléguer le vrai travail à un thread dédié,
     * hors de la pile d'appel réentrante de {@code transform()}.
     */
    private static void triggerEarlyKnotExpose(ClassLoader loader) {
        if (knotExposeTriggered || loader == null) return;
        // "net.fabricmc.loader.impl.launch.knot." (Fabric) ou
        // "org.quiltmc.loader.impl.launch.knot." (Quilt) — jamais Forge/
        // NeoForge (ModLauncher, pas de notion de "Knot" à exposer ainsi).
        if (!loader.getClass().getName().contains(".launch.knot.")) return;
        knotExposeTriggered = true;
        new Thread(() -> com.yuyuframe.launcheragent.runtime.fabric.FabricKnotExposer.ensureExposed(loader),
            "LauncherAgent-EarlyKnotExpose").start();
    }
}
