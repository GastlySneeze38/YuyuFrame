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

        byte[] result;
        try {
            result = transformer.transformClassBytes(obfDot, yarnNamed.replace('/', '.'), classfileBuffer);
        } catch (Throwable t) {
            // ⚠️ NE JAMAIS avaler ça en silence (2026-08-25, §14) : une exception
            // qui sort d'un ClassFileTransformer est SILENCIEUSEMENT ignorée par
            // la JVM, qui charge alors la classe NON transformée — aucune trace
            // nulle part, ni dans latest.log ni ici. C'est exactement ce qui
            // masquait l'échec de tissage de net.minecraft.client.gui.Gui :
            // Mixin journalisait bien « Mixing HudExtractCrosshairMixin261 …
            // into …gui.Gui » (l'application COMMENÇAIT), puis levait ; la classe
            // partait sans notre @Redirect (crosshair vanilla jamais supprimé),
            // et le rattrapage par retransform déclenchait ensuite le conflit
            // fatal avec fabric-content-registries-v0. On relance après avoir
            // journalisé : comportement JVM inchangé, mais plus jamais muet.
            // IllegalClassLoadError = « cette classe est dans le package mixin
            // déclaré mais n'est pas un mixin » — c'est le cas ATTENDU de nos
            // classes d'API (HookPoint, VanillaHookRegistry…) qui vivent sous
            // com.yuyuframe.launcheragent.apimixin, le package déclaré par le
            // JSON. Sans intérêt à hurler dessus à chaque chargement : warn.
            boolean expected = t.getClass().getName().endsWith("IllegalClassLoadError");
            if (expected) {
                com.yuyuframe.launcheragent.runtime.log.LauncherLog.warn(
                    "[LauncherAgent] " + obfDot + " est dans le package mixin déclaré sans être un mixin"
                    + " — non transformée (attendu pour les classes d'API d'apimixin/)");
            } else {
                com.yuyuframe.launcheragent.runtime.log.LauncherLog.err("[LauncherAgent] Tissage Mixin ÉCHOUÉ pour " + obfDot
                    + " — classe chargée NON transformée (la JVM ignore silencieusement"
                    + " toute exception d'un ClassFileTransformer) : " + t);
                t.printStackTrace(System.err);
            }
            throw t;
        }

        // Trace autoritaire du tissage au CHARGEMENT INITIAL (2026-08-25, §14) —
        // voir IsolatedBootstrap.scheduleDelayedRetransform, qui la consulte
        // avant de décider un retransform. Enregistrée ICI parce que c'est le
        // seul point qui SAIT si Mixin a réellement modifié la classe : le
        // test qui servait avant (countHooks(), réflexion getDeclaredMethods()
        // sur la classe une fois chargée) est intrinsèquement RACE-Y — le
        // thread de retransform sonde getAllLoadedClasses() toutes les 200 ms
        // et peut tomber sur la classe à un instant où ses méthodes tissées ne
        // sont pas encore visibles, concluant à tort « 0 hook, il faut
        // retransformer ». Symptôme réel et reproductible : net.minecraft.client.gui.Gui
        // (tissée avec succès au chargement) partait quand même en retransform,
        // ce qui réveille l'agent hot-swap interne de Sponge Mixin — lequel
        // retente d'appliquer les mixins de TOUS les mods sur Gui, dont
        // fabric-content-registries-v0 déjà appliqué → InvalidMixinException
        // « cannot overwrite method » puis ClassFormatError, à chaque lancement.
        // classBeingRedefined == null distingue le chargement initial d'un
        // retransform (où le résultat ne prouve plus rien sur l'état de départ).
        if (classBeingRedefined == null && result != null && result != classfileBuffer) {
            MIXED_AT_LOAD.add(obfDot);
        }
        // DIAG TEMPORAIRE (§14) — Gui est la SEULE cible sur 23 à ne pas
        // apparaître dans MIXED_AT_LOAD alors que Mixin journalise pourtant
        // « Mixing HudExtractCrosshairMixin261 … into net.minecraft.client.gui.Gui ».
        // On veut savoir laquelle des trois conditions ci-dessus échoue.
        return result;
    }

    /**
     * Classes que Mixin a RÉELLEMENT modifiées lors de leur chargement initial
     * (nom pointé). Alimenté par {@link #transform}, lu par {@code
     * IsolatedBootstrap.scheduleDelayedRetransform} — voir le commentaire de
     * l'enregistrement pour le bug de course que ça corrige.
     *
     * Sans course par construction : une classe n'apparaît dans {@code
     * Instrumentation.getAllLoadedClasses()} qu'une fois {@code defineClass}
     * terminé, donc APRÈS que ce transformer ait rendu la main et rempli ce set.
     */
    private static final java.util.Set<String> MIXED_AT_LOAD =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /** @return {@code true} si {@code dotClassName} a été tissée par Mixin à son chargement initial — un retransform serait alors inutile ET dangereux. */
    public static boolean wasMixedAtLoad(String dotClassName) {
        return MIXED_AT_LOAD.contains(dotClassName);
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
