package com.yuyuframe.launcheragent.apimixin.service;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apimixin.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnMappings;
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
    // BUG DE COMPILATION CORRIGE (mise à jour mixin.jar vers le fork Fabric,
    // compat Java 25 — voir build.bat) : IMixinService a gagné ces deux
    // méthodes dans une version plus récente de Sponge Mixin — mêmes
    // fonctionnalités avancées optionnelles que getClassTracker/getAuditTrail
    // ci-dessus, jamais utilisées par ce service standalone minimal.
    @Override public IFeatureValidator getFeatureValidator() { return null; }
    @Override public IAdviceProvider getAdviceProvider()     { return null; }

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
    private static final class RefmapEntry {
        /**
         * Nature de la référence (2026-09-11). Seule METHOD existait : le
         * générateur ne savait écrire que des sélecteurs {@code method=}.
         * Or Mixin fait passer AUSSI les noms d'{@code @Accessor} et
         * d'{@code @Invoker} par le refmap (vérifié dans mixin.jar :
         * {@code AnnotatedMethodInfo.remap}), et ces références n'ont PAS de
         * propriétaire — {@code RemappingReferenceMapper} ne peut donc pas les
         * traduire par nos remappers. Sans entrée ici, un accessor sur une
         * version obfusquée visait un nom Yarn qui n'existe pas.
         *
         * <ul>
         *   <li>METHOD — clé {@code nom+descripteur}, sélecteur {@code method=} ;</li>
         *   <li>INVOKER — clé {@code nom} seul (c'est ce qu'on écrit dans
         *       {@code @Invoker("x")}), valeur avec descripteur ;</li>
         *   <li>FIELD — clé {@code nom} seul ({@code @Accessor("x")}), valeur
         *       {@code nomRuntime:descripteurRuntime} — le descripteur désigne
         *       le vrai type du champ, quand l'accessor renvoie {@code Object}.</li>
         * </ul>
         */
        enum Kind { METHOD, INVOKER, FIELD }

        final Kind kind;
        final String mixinInternalName, yarnTargetClass, namedMethod, namedDesc, fallbackNamedOwner;
        RefmapEntry(String mixinInternalName, String yarnTargetClass,
                    String namedMethod, String namedDesc, String fallbackNamedOwner) {
            this(Kind.METHOD, mixinInternalName, yarnTargetClass, namedMethod, namedDesc, fallbackNamedOwner);
        }
        private RefmapEntry(Kind kind, String mixinInternalName, String yarnTargetClass,
                            String namedMethod, String namedDesc, String fallbackNamedOwner) {
            this.kind = kind;
            this.mixinInternalName = mixinInternalName;
            this.yarnTargetClass = yarnTargetClass;
            this.namedMethod = namedMethod;
            this.namedDesc = namedDesc;
            this.fallbackNamedOwner = fallbackNamedOwner;
        }

        /** {@code @Accessor("namedField")} sur {@code yarnTargetClass}. */
        static RefmapEntry field(String mixinInternalName, String yarnTargetClass, String namedField) {
            return new RefmapEntry(Kind.FIELD, mixinInternalName, yarnTargetClass, namedField, null, null);
        }

        /** {@code @Invoker("namedMethod")} — le descripteur désambiguïse les surcharges. */
        static RefmapEntry invoker(String mixinInternalName, String yarnTargetClass,
                                   String namedMethod, String namedDesc) {
            return new RefmapEntry(Kind.INVOKER, mixinInternalName, yarnTargetClass, namedMethod, namedDesc, null);
        }
    }

    /** {@code FogModifier.applyStartEndModifier} 1.21.11 (Mojang {@code FogEnvironment.setupFog}), six entrées. */
    private static final String FOG_SETUP_DESC_1211 =
        "(Lnet/minecraft/client/render/fog/FogData;Lnet/minecraft/client/render/Camera;"
        + "Lnet/minecraft/client/world/ClientWorld;FLnet/minecraft/client/render/RenderTickCounter;)V";

    /** Préfixe interne des mixins apimixin 1.8.9. */
    private static final String M189 = "com/yuyuframe/launcheragent/apimixin/v1_8_9/";

    /** {@code Window} 1.8.9 (Yarn legacy), paramètre de la plupart des méthodes du HUD. */
    private static final String WINDOW_189 = "Lnet/minecraft/client/util/Window;";

    private static final RefmapEntry[] REFMAP_ENTRIES = {
        // init() héritée de Screen — repli sur Screen pour la lookup Yarn.
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/TitleScreenMixin",
            "net/minecraft/client/gui/screen/TitleScreen",
            "init", "()V", "net/minecraft/client/gui/screen/Screen"),
        // render() déclaré directement sur GameRenderer — pas de repli. Point
        // d'accroche de la LOGIQUE du moteur UI custom (input/tick/ouverture
        // du menu), voir GlobalUiRenderMixin. Le DESSIN, lui, est sur
        // Framebuffer.blitToScreen() — voir GlobalUiPresentMixin juste après
        // (era E, bug de composition Blaze3D, voir javadoc des deux classes).
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/GlobalUiRenderMixin",
            "net/minecraft/client/render/GameRenderer",
            "render", "(Lnet/minecraft/client/render/RenderTickCounter;Z)V", null),
        // blitToScreen() déclaré directement sur Framebuffer — pas de repli.
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/GlobalUiPresentMixin",
            "net/minecraft/client/gl/Framebuffer",
            "blitToScreen", "()V", null),
        // InGameHud.renderCrosshair(DrawContext, RenderTickCounter) — voir
        // CrosshairMixin, même signature que le bracket 1.21.4
        // (MixinCrosshair1214), vérifiée indépendamment dans
        // mappings/yarn-1.21.11-mergedv2.jar (cache local).
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/CrosshairMixin",
            "net/minecraft/client/gui/hud/InGameHud",
            "renderCrosshair", "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),
        // Même méthode que GlobalUiRenderMixin ci-dessus (render() déclaré
        // directement sur GameRenderer) — entrée de refmap indépendante car
        // scopée par mixin (byMixin, voir buildRefmapJson()), pas de collision
        // avec l'entrée de GlobalUiRenderMixin même si la clé JSON est identique.
        // Voir GuiFlushMixin : soumet les icônes d'objet vanilla en attente
        // (ArmorDurabilityModule/ShulkerPreviewModule) dans le GuiRenderState
        // partagé. RETARGETÉ une seconde fois cette session (voir javadoc de
        // GuiFlushMixin) : TAIL de clear()V arrivait trop TÔT dans la frame
        // (avant InGameHud.render/Screen.render), plaçant nos icônes/fond
        // DERRIÈRE le HUD et tout écran ouvert (mauvais z-order, confirmé par
        // test utilisateur : panneau shulker visible mais sous l'inventaire).
        // Cible maintenant HEAD de GuiRenderer.render(GpuBufferSlice)V — la
        // soumission GPU réelle, appelée APRÈS tout le contenu de la frame
        // (HUD, écran, toasts) — vérifié par désassemblage complet de
        // GameRenderer.render()V (javap, jar 1.21.11 réel).
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/GuiFlushMixin",
            "net/minecraft/client/gui/render/GuiRenderer",
            "render", "(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", null),

        // ── Branche 1.8.9 (mixin.client.v1_8.*) — mêmes noms Yarn named que
        // ci-dessus, vérifiés indépendamment dans mappings/mappings-1.8.9.tiny.
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_8/TitleScreenMixin189",
            "net/minecraft/client/gui/screen/TitleScreen",
            "init", "()V", "net/minecraft/client/gui/screen/Screen"),
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_8/GlobalUiRenderMixin189",
            "net/minecraft/client/render/GameRenderer",
            "render", "(FJ)V", null),

        // ── Branche 1.13-1.16.x (mixin.client.v1_16.*) — bracket "B" : LWJGL3/
        // GLFW comme le pipeline moderne, mais dessin encore possible en
        // immédiat (GL en dessous du Core Profile 3.2 imposé depuis la 1.17).
        // render(FJZ)V — 3 paramètres primitifs, signature partagée avec
        // 1.17-1.20.4 (voir doc Yarn), DIFFÉRENTE de la 1.8.9 (FJ)V (pas de
        // booléen "tick") et de la 1.21.11 (RenderTickCounter au lieu de F,J).
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_16/GlobalUiRenderMixin116",
            "net/minecraft/client/render/GameRenderer",
            "render", "(FJZ)V", null),
        // tick() déclaré directement sur MinecraftClient — pas de repli. Voir
        // GlobalTickMixin116 pour le pourquoi (ouverture/fermeture de Screen
        // déplacée ici depuis le render-tail, comme un vrai mod Fabric).
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_16/GlobalTickMixin116",
            "net/minecraft/client/MinecraftClient",
            "tick", "()V", null),
        // InGameHud.renderCrosshair(MatrixStack) — déclaré directement, pas de repli.
        // Voir MixinCrosshair116 (audit modules — crosshair vanilla jamais masqué sur ce bracket).
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_16/MixinCrosshair116",
            "net/minecraft/client/gui/hud/InGameHud",
            "renderCrosshair", "(Lnet/minecraft/client/util/math/MatrixStack;)V", null),
        // MixinWorldTime116 RETIRÉ (voir historique de session) : ciblait
        // net.minecraft.world.LunarWorldView, une INTERFACE — Sponge Mixin
        // 0.8.7 rejette @Inject sur une interface (InvalidMixinException:
        // "@Mixin target type mismatch ... is an interface"), a fait planter
        // TOUTE la config Mixin en cascade (jeu arrêté). Nécessite une autre
        // approche (cibler une classe concrète qui APPELLE getSkyAngle, pas
        // l'interface elle-même) — non résolu pour l'instant.

        // ── Branche 1.17-1.20.4 (mixin.client.v1_20_4.*) — bracket "C" : Core
        // Profile OpenGL 3.2 obligatoire (pipeline fixe supprimé), mais
        // render(FJZ)V/tick()V gardent la même signature que le bracket "B"
        // (1.16.5) — voir VersionProfileRegistry.
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_20_4/GlobalUiRenderMixin1204",
            "net/minecraft/client/render/GameRenderer",
            "render", "(FJZ)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_20_4/GlobalTickMixin1204",
            "net/minecraft/client/MinecraftClient",
            "tick", "()V", null),
        // InGameHud.renderCrosshair(DrawContext) — DIFFÉRENT de 1.16.5
        // (MatrixStack) : voir MixinCrosshair1204 pour le détail vérifié via
        // mappings/yarn-1.20.4-mergedv2.jar.
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_20_4/MixinCrosshair1204",
            "net/minecraft/client/gui/hud/InGameHud",
            "renderCrosshair", "(Lnet/minecraft/client/gui/DrawContext;)V", null),

        // ── Branche ~1.21-1.21.5 (mixin.client.v1_21_4.*) — bracket "D" :
        // même profil GL Core que "C", mais render(RenderTickCounter,Z)V
        // (RenderTickCounter introduit entre la 1.20.4 et la 1.21) — voir
        // VersionProfileRegistry.
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_21_4/GlobalUiRenderMixin1214",
            "net/minecraft/client/render/GameRenderer",
            "render", "(Lnet/minecraft/client/render/RenderTickCounter;Z)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_21_4/GlobalTickMixin1214",
            "net/minecraft/client/MinecraftClient",
            "tick", "()V", null),
        // InGameHud.renderCrosshair(DrawContext, RenderTickCounter) — un
        // paramètre de plus que 1.20.4, voir MixinCrosshair1214.
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_21_4/MixinCrosshair1214",
            "net/minecraft/client/gui/hud/InGameHud",
            "renderCrosshair", "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),
        // Portage 1.21.4 de ClearOverlaysMixin/Freelook (voir leurs javadoc) —
        // architecture Yarn IDENTIQUE à 1.21.11 pour ces 3 Mixins, vérifiée
        // indépendamment dans mappings/yarn-1.21.4-mergedv2.jar (mêmes IDs
        // intermediary method_31977/method_1606/method_19321/method_19324).
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_21_4/ClearOverlaysMixin1214",
            "net/minecraft/client/gui/hud/InGameHud",
            "renderOverlay", "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/util/Identifier;F)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_21_4/MouseHandlerFreelookMixin1214",
            "net/minecraft/client/Mouse", "updateMouse", "(D)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_21_4/CameraFreelookMixin1214",
            "net/minecraft/client/render/Camera", "update",
            "(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;ZZF)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_21_4/CameraFreelookMixin1214",
            "net/minecraft/client/render/Camera", "moveBy", "(FFF)V", null),
        // clipToSpace(F)F : même bug/correctif "traverse les murs" que
        // CameraFreelookMixin (1.21.11) — IDs intermediary confirmés
        // identiques par grep de mappings/yarn-1.21.4-mergedv2.jar.
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_21_4/CameraFreelookMixin1214",
            "net/minecraft/client/render/Camera", "clipToSpace", "(F)F", null),
        // ── CORRECTIF RÉTROACTIF (audit multiversion, bracket 1.21.11 +
        // 1.16.5/1.20.4/1.21.4) : ces @Inject(method="...") utilisaient un nom
        // Yarn named DIRECTEMENT, en supposant (à tort) qu'aucun refmap n'était
        // nécessaire (voir l'ancienne conclusion "getRefMapperConfig() renvoie
        // null → pas de refmap" — FAUSSE : chaque mixins.launcheragent*.json
        // déclare "refmap": "mixins.launcheragent.refmap.json", généré par
        // buildRefmapJson() UNIQUEMENT à partir de REFMAP_ENTRIES ci-dessus.
        // Sans entrée ici, Mixin cherche le nom Yarn TEL QUEL dans le
        // ClassNode obfusqué (getClassNode() ne renomme QUE cn.name, jamais
        // les méthodes) → aucune correspondance → no-op silencieux (require=0)
        // sur tous les brackets obfusqués. Confirmé par grep du log réel :
        // ZÉRO ligne "Mixin initialisé avec succès" pour WorldTimeMixin*/
        // ClearOverlaysMixin, alors que les entrées v26_1 (noms RÉELS, jamais
        // besoin de refmap) apparaissent bien. World.getTimeOfDay()J déclarée
        // DIRECTEMENT sur World (pas une interface), vérifié dans
        // mappings/mappings.tiny (Yarn 1.21.11) — pas de fallbackNamedOwner
        // nécessaire ; même nom Yarn stable sur 1.16.5/1.20.4/1.21.4 (déjà
        // vérifié par désassemblage lors de leur écriture).
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/WorldTimeMixin",
            "net/minecraft/world/World", "getTimeOfDay", "()J", null),
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_16/WorldTimeMixin116",
            "net/minecraft/world/World", "getTimeOfDay", "()J", null),
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_20_4/WorldTimeMixin1204",
            "net/minecraft/world/World", "getTimeOfDay", "()J", null),
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_21_4/WorldTimeMixin1214",
            "net/minecraft/world/World", "getTimeOfDay", "()J", null),
        // WorldTimePropertiesMixin1214 : la VRAIE source lue par le rendu du
        // ciel/lune sur ce bracket (LunarWorldView.getSkyAngle → getLunarTime
        // → ClientWorld$Properties.getTimeOfDay, voir sa javadoc complète) —
        // World.getTimeOfDay() ci-dessus n'a aucun effet visuel à lui seul.
        // "getTimeOfDay" n'a pas d'entrée Yarn propre sur ClientWorld$Properties
        // (seul "setTimeOfDay" y figure) — repli sur WorldProperties, où le nom
        // Yarn du getter est réellement déclaré (method_217).
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_21_4/WorldTimePropertiesMixin1214",
            "net/minecraft/client/world/ClientWorld$Properties", "getTimeOfDay", "()J",
            "net/minecraft/world/WorldProperties"),
        // HudItemFlushMixin1214 : vide la file d'icônes d'objet vanilla en
        // attente (ArmorDurabilityModule) directement dans le VRAI DrawContext
        // vivant de InGameHud.render() — voir UiRenderer#drawVanillaItemIconModernImmediate
        // pour le pourquoi (remplace la construction d'un DrawContext isolé,
        // hors du contexte GL vanilla, qui ne fonctionnait jamais).
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/v1_21_4/HudItemFlushMixin1214",
            "net/minecraft/client/gui/hud/InGameHud", "render",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),
        // HandledScreenBlitFlushMixin1214 : entrée SUPPRIMÉE le 2026-08-31
        // avec le mixin lui-même. Il ne servait qu'au fond de fenêtre de
        // l'aperçu shulker ; celui-ci retiré, il s'injectait dans
        // HandledScreen.render() à CHAQUE frame, sur tout écran de conteneur,
        // pour vider une file désormais toujours vide.
        // InGameHud.renderOverlay(DrawContext,Identifier,F) — voir
        // ClearOverlaysMixin/NoPumpkinOverlayModule, même correctif que ci-dessus.
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/ClearOverlaysMixin",
            "net/minecraft/client/gui/hud/InGameHud", "renderOverlay",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/util/Identifier;F)V", null),

        // ── Freelook 1.21.11 (mixin.client.*, portage — voir FreelookModule) —
        // mêmes noms Yarn que ceux vérifiés par désassemblage du vrai jar
        // 1.21.11 (mappings/mappings.tiny + javap sur gfk.class/ger.class).
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/MouseHandlerFreelookMixin",
            "net/minecraft/client/Mouse", "updateMouse", "(D)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/CameraFreelookMixin",
            "net/minecraft/client/render/Camera", "update",
            "(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;ZZF)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/CameraFreelookMixin",
            "net/minecraft/client/render/Camera", "moveBy", "(FFF)V", null),
        // clipToSpace(F)F : raycast de collision réel (Level.clip via
        // getMaxZoom sur 26.1.2) — voir bug "traverse les murs" dans la
        // javadoc de classe de CameraFreelookMixin.
        new RefmapEntry("com/yuyuframe/launcheragent/mixin/client/CameraFreelookMixin",
            "net/minecraft/client/render/Camera", "clipToSpace", "(F)F", null),

        // ── apimixin 1.21.11 (v1_21_11.*) — dégel de la tranche, étape 1 ──
        // Mêmes cibles et mêmes descripteurs que les mixins mixin/client/*
        // qu'ils remplacent (entrées conservées ci-dessus, avec leur
        // historique) — seul le nom interne du mixin change, et c'est lui qui
        // indexe le refmap (byMixin). Premiers mixins apimixin/ à AVOIR une
        // entrée ici : ceux de 26.1.2 visent des noms réels et n'en ont jamais
        // eu besoin.
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/core/TitleScreenMixin1211",
            "net/minecraft/client/gui/screen/TitleScreen",
            "init", "()V", "net/minecraft/client/gui/screen/Screen"),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/core/GlobalUiRenderMixin1211",
            "net/minecraft/client/render/GameRenderer",
            "render", "(Lnet/minecraft/client/render/RenderTickCounter;Z)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/core/GlobalUiPresentMixin1211",
            "net/minecraft/client/gl/Framebuffer",
            "blitToScreen", "()V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/core/GuiFlushMixin1211",
            "net/minecraft/client/gui/render/GuiRenderer",
            "render", "(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractCrosshairMixin1211",
            "net/minecraft/client/gui/hud/InGameHud",
            "renderCrosshair", "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractTextureOverlayMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderOverlay",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/util/Identifier;F)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/clock/ClockTotalTicksMixin1211",
            "net/minecraft/world/World", "getTimeOfDay", "()J", null),
        // ── apimixin 1.21.11, lot 5 : accès aux données du jeu ────────────
        // Quatre champs PRIVÉS seulement : tout le reste de ce que la 26.1.2
        // atteint par accessor est public en 1.21.11 et passe par du code
        // typé (AccessorBindings1211).
        RefmapEntry.field("com/yuyuframe/launcheragent/apimixin/v1_21_11/core/MinecraftClientAccessor1211",
            "net/minecraft/client/MinecraftClient", "currentFps"),
        RefmapEntry.field("com/yuyuframe/launcheragent/apimixin/v1_21_11/core/HungerManagerAccessor1211",
            "net/minecraft/entity/player/HungerManager", "exhaustion"),
        // Troisième champ privé : l'historique du chat. addMessage() et
        // refresh() sont PUBLIQUES sur cette version (javap sur le jar réel),
        // donc pas d'Invoker à déclarer — contrairement à la 26.1.2.
        RefmapEntry.field("com/yuyuframe/launcheragent/apimixin/v1_21_11/chat/ChatHudAccessor1211",
            "net/minecraft/client/gui/hud/ChatHud", "messages"),
        // Quatrième : la valeur d'une option. setValue() est pourtant PUBLIQUE
        // — mais elle CLAMPE, et ce point d'accès existe pour ne pas clamper
        // (voir SimpleOptionAccessor1211).
        RefmapEntry.field("com/yuyuframe/launcheragent/apimixin/v1_21_11/option/SimpleOptionAccessor1211",
            "net/minecraft/client/option/SimpleOption", "value"),
        // ── apimixin 1.21.11, lot 3 : HUD dans la passe GUI de vanilla ────
        // renderChat = le point d'insertion du HUD de l'agent (tout le HUD
        // vanilla déjà extrait, chat pas encore) ; le constructeur de
        // DrawContext livre le GuiRenderState, inaccessible autrement.
        // Signatures vérifiées dans yarn-1.21.11-mergedv2.jar.
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractChatMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderChat",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),
        // Constructeur PRIVÉ à 5 paramètres — le public lui délègue (bytecode
        // vérifié). Le nom "<init>" n'est pas traduit, seul le descripteur l'est.
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/render/DrawContextStateMixin1211",
            "net/minecraft/client/gui/DrawContext", "<init>",
            "(Lnet/minecraft/client/MinecraftClient;Lorg/joml/Matrix3x2fStack;Lnet/minecraft/client/gui/render/state/GuiRenderState;II)V", null),
        // ── apimixin 1.21.11, lot 2 : HookPoints réclamés par des modules
        // enregistrés sur cette version (effets, chat, lance, tick client).
        // Pendants des mixins 26.1.2 du même nom, cibles résolues dans les
        // mappings officiels 1.21.11 puis dans Yarn.
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractEffectsMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderStatusEffectOverlay",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/chat/ChatReceiveMixin1211",
            "net/minecraft/client/network/message/MessageHandler", "onGameMessage",
            "(Lnet/minecraft/text/Text;Z)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/chat/ChatReceiveMixin1211",
            "net/minecraft/client/network/message/MessageHandler", "onChatMessage",
            "(Lnet/minecraft/network/message/SignedMessage;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/network/message/MessageType$Parameters;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/chat/ChatSendMixin1211",
            "net/minecraft/client/network/ClientPlayNetworkHandler", "sendChatMessage",
            "(Ljava/lang/String;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/chat/CommandSendMixin1211",
            "net/minecraft/client/network/ClientPlayNetworkHandler", "sendChatCommand",
            "(Ljava/lang/String;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/chat/CommandTreeMixin1211",
            "net/minecraft/client/network/ClientPlayNetworkHandler", "onCommandTree",
            "(Lnet/minecraft/network/packet/s2c/play/CommandTreeS2CPacket;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/combat/PiercingAttackMixin1211",
            "net/minecraft/client/network/ClientPlayerInteractionManager", "attackWithPiercingWeapon",
            "(Lnet/minecraft/component/type/PiercingWeaponComponent;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/lifecycle/ClientTickMixin1211",
            "net/minecraft/client/MinecraftClient", "tick", "()V", null),

        // ── apimixin 1.21.11, freelook — premières entrées FIELD/INVOKER.
        // Seuls les sélecteurs method= et les noms d'accessor sont ici : les
        // cibles @At(INVOKE) nomment leur propriétaire et sont traduites par
        // REFMAP_REMAP (voir IsolatedBootstrap.bootstrapMixin).
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/freelook/CameraFreelookMixin1211",
            "net/minecraft/client/render/Camera", "update",
            "(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;ZZF)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/freelook/MouseHandlerFreelookMixin1211",
            "net/minecraft/client/Mouse", "updateMouse", "(D)V", null),
        RefmapEntry.field("com/yuyuframe/launcheragent/apimixin/v1_21_11/freelook/CameraAccessor1211",
            "net/minecraft/client/render/Camera", "yaw"),
        RefmapEntry.field("com/yuyuframe/launcheragent/apimixin/v1_21_11/freelook/CameraAccessor1211",
            "net/minecraft/client/render/Camera", "pitch"),
        RefmapEntry.invoker("com/yuyuframe/launcheragent/apimixin/v1_21_11/freelook/CameraAccessor1211",
            "net/minecraft/client/render/Camera", "setRotation", "(FF)V"),
        RefmapEntry.invoker("com/yuyuframe/launcheragent/apimixin/v1_21_11/freelook/EntityInvoker1211",
            "net/minecraft/entity/Entity", "changeLookDirection", "(DD)V"),

        // ── apimixin 1.21.11, lot 4 : reste du catalogue 26.1.2 ────────────
        // HUD (InGameHud) — sélecteurs seulement ; les cibles @At(INVOKE) des
        // quatre @WrapOperation de renderMainHud passent par REFMAP_REMAP.
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractCameraOverlayMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderMiscOverlays",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractItemHotbarMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderHotbar",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractArmorMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderArmor",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/entity/player/PlayerEntity;IIII)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractHeartsMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderHealthBar",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/entity/player/PlayerEntity;IIIIFIIIZ)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractFoodMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderFood",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/entity/player/PlayerEntity;II)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractAirBubblesMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderAirBubbles",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/entity/player/PlayerEntity;III)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractVehicleHealthMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderMountHealth",
            "(Lnet/minecraft/client/gui/DrawContext;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractSelectedItemNameMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderHeldItemTooltip",
            "(Lnet/minecraft/client/gui/DrawContext;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractBossOverlayMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderBossBarHud",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractSleepOverlayMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderSleepOverlay",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractDemoOverlayMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderDemoTimer",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractScoreboardSidebarMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderScoreboardSidebar",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractOverlayMessageMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderOverlayMessage",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractTitleMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderTitleAndSubtitle",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractTabListMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderPlayerList",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractContextualBarBackgroundMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderMainHud",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractExperienceLevelMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderMainHud",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractSpectatorHotbarMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderMainHud",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/hud/HudExtractSpectatorActionMixin1211",
            "net/minecraft/client/gui/hud/InGameHud", "renderMainHud",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", null),

        // Brouillard — la méthode n'est déclarée dans Yarn que sur FogModifier
        // (les six sous-classes l'héritent sans entrée propre) : parent en repli.
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/fog/FogSetupAtmosphericMixin1211",
            "net/minecraft/client/render/fog/AtmosphericFogModifier", "applyStartEndModifier", FOG_SETUP_DESC_1211,
            "net/minecraft/client/render/fog/FogModifier"),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/fog/FogSetupWaterMixin1211",
            "net/minecraft/client/render/fog/WaterFogModifier", "applyStartEndModifier", FOG_SETUP_DESC_1211,
            "net/minecraft/client/render/fog/FogModifier"),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/fog/FogSetupLavaMixin1211",
            "net/minecraft/client/render/fog/LavaFogModifier", "applyStartEndModifier", FOG_SETUP_DESC_1211,
            "net/minecraft/client/render/fog/FogModifier"),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/fog/FogSetupPowderedSnowMixin1211",
            "net/minecraft/client/render/fog/PowderSnowFogModifier", "applyStartEndModifier", FOG_SETUP_DESC_1211,
            "net/minecraft/client/render/fog/FogModifier"),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/fog/FogSetupBlindnessMixin1211",
            "net/minecraft/client/render/fog/BlindnessEffectFogModifier", "applyStartEndModifier", FOG_SETUP_DESC_1211,
            "net/minecraft/client/render/fog/FogModifier"),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/fog/FogSetupDarknessMixin1211",
            "net/minecraft/client/render/fog/DarknessEffectFogModifier", "applyStartEndModifier", FOG_SETUP_DESC_1211,
            "net/minecraft/client/render/fog/FogModifier"),
        RefmapEntry.field("com/yuyuframe/launcheragent/apimixin/v1_21_11/fog/FogDataAccessor1211",
            "net/minecraft/client/render/fog/FogData", "environmentalStart"),
        RefmapEntry.field("com/yuyuframe/launcheragent/apimixin/v1_21_11/fog/FogDataAccessor1211",
            "net/minecraft/client/render/fog/FogData", "environmentalEnd"),
        RefmapEntry.field("com/yuyuframe/launcheragent/apimixin/v1_21_11/fog/FogDataAccessor1211",
            "net/minecraft/client/render/fog/FogData", "renderDistanceStart"),
        RefmapEntry.field("com/yuyuframe/launcheragent/apimixin/v1_21_11/fog/FogDataAccessor1211",
            "net/minecraft/client/render/fog/FogData", "renderDistanceEnd"),

        // Rendu
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/render/ItemDecorationsExtractMixin1211",
            "net/minecraft/client/gui/DrawContext", "drawStackOverlay",
            "(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/render/AdvancementToastExtractMixin1211",
            "net/minecraft/client/toast/AdvancementToast", "draw",
            "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;J)V",
            "net/minecraft/client/toast/Toast"),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/render/SubtitleOverlayExtractMixin1211",
            "net/minecraft/client/gui/hud/SubtitlesHud", "render",
            "(Lnet/minecraft/client/gui/DrawContext;)V", null),

        // Monde / frame
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/level/LevelBlockOutlineExtractMixin1211",
            "net/minecraft/client/render/WorldRenderer", "fillEntityOutlineRenderStates",
            "(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/state/WorldRenderState;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/level/LevelExtractMixin1211",
            "net/minecraft/client/render/WorldRenderer", "render",
            "(Lnet/minecraft/client/util/memory/ObjectAllocator;Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/level/GuiRenderStateResetMixin1211",
            "net/minecraft/client/gui/render/state/GuiRenderState", "clear", "()V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/level/GameRenderExtractMixin1211",
            "net/minecraft/client/render/GameRenderer", "render",
            "(Lnet/minecraft/client/render/RenderTickCounter;Z)V", null),

        // Écrans / entrées
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/screen/ScreenInitMixin1211",
            "net/minecraft/client/gui/screen/Screen", "init", "(II)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/screen/ScreenSetMixin1211",
            "net/minecraft/client/MinecraftClient", "setScreen",
            "(Lnet/minecraft/client/gui/screen/Screen;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/screen/ScreenAfterBackgroundExtractMixin1211",
            "net/minecraft/client/gui/screen/Screen", "renderWithTooltip",
            "(Lnet/minecraft/client/gui/DrawContext;IIF)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/screen/KeyboardKeyMixin1211",
            "net/minecraft/client/Keyboard", "onKey",
            "(JILnet/minecraft/client/input/KeyInput;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/screen/MouseScrollMixin1211",
            "net/minecraft/client/Mouse", "onMouseScroll", "(JDD)V", null),

        // Touches
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/keybind/KeybindRegisterMixin1211",
            "net/minecraft/client/option/GameOptions", "load", "()V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/keybind/KeybindCategoryRegisterMixin1211",
            "net/minecraft/client/option/KeyBinding$Category", "create",
            "(Lnet/minecraft/util/Identifier;)Lnet/minecraft/client/option/KeyBinding$Category;", null),

        // Objets / cycle de vie
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/item/ItemTooltipMixin1211",
            "net/minecraft/item/ItemStack", "getTooltip",
            "(Lnet/minecraft/item/Item$TooltipContext;Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/item/tooltip/TooltipType;)Ljava/util/List;", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/lifecycle/EntityLoadMixin1211",
            "net/minecraft/client/world/ClientWorld$ClientEntityHandler", "startTracking",
            "(Lnet/minecraft/entity/Entity;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/lifecycle/EntityUnloadMixin1211",
            "net/minecraft/client/world/ClientWorld$ClientEntityHandler", "stopTracking",
            "(Lnet/minecraft/entity/Entity;)V", null),
        new RefmapEntry("com/yuyuframe/launcheragent/apimixin/v1_21_11/lifecycle/ClientLevelLoadMixin1211",
            "net/minecraft/client/MinecraftClient", "setWorld",
            "(Lnet/minecraft/client/world/ClientWorld;Z)V", null),

        // ── apimixin 1.8.9 (v1_8_9.*) — noms Yarn LEGACY, chaque sélecteur
        // vérifié dans yarn-1.8.9-mergedv2 et sur le bytecode du jar 1.8.9.
        // Les cibles @At (drawTexture, Profiler, TextRenderer…) n'ont pas
        // d'entrée : REFMAP_REMAP les traduit.
        new RefmapEntry(M189 + "core/TitleScreenMixin189",
            "net/minecraft/client/gui/screen/TitleScreen", "init", "()V", "net/minecraft/client/gui/screen/Screen"),
        new RefmapEntry(M189 + "core/GlobalUiRenderMixin189",
            "net/minecraft/client/render/GameRenderer", "render", "(FJ)V", null),

        new RefmapEntry(M189 + "hud/HudExtractCrosshairMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "showCrosshair", "()Z", null),
        new RefmapEntry(M189 + "hud/HudExtractTextureOverlayMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "renderPumpkinBlur", "(" + WINDOW_189 + ")V", null),
        new RefmapEntry(M189 + "hud/HudExtractCameraOverlayMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "renderVignetteOverlay", "(F" + WINDOW_189 + ")V", null),
        new RefmapEntry(M189 + "hud/HudExtractCameraOverlayMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "renderNausea", "(F" + WINDOW_189 + ")V", null),
        new RefmapEntry(M189 + "hud/HudExtractItemHotbarMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "renderHotbar", "(" + WINDOW_189 + "F)V", null),
        new RefmapEntry(M189 + "hud/HudExtractSpectatorHotbarMixin189",
            "net/minecraft/client/gui/hud/SpectatorHud", "render", "(" + WINDOW_189 + "F)V", null),
        new RefmapEntry(M189 + "hud/HudExtractSpectatorActionMixin189",
            "net/minecraft/client/gui/hud/SpectatorHud", "render", "(" + WINDOW_189 + ")V", null),
        new RefmapEntry(M189 + "hud/HudExtractArmorMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "renderStatusBars", "(" + WINDOW_189 + ")V", null),
        new RefmapEntry(M189 + "hud/HudExtractHeartsMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "renderStatusBars", "(" + WINDOW_189 + ")V", null),
        new RefmapEntry(M189 + "hud/HudExtractFoodMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "renderStatusBars", "(" + WINDOW_189 + ")V", null),
        new RefmapEntry(M189 + "hud/HudExtractAirBubblesMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "renderStatusBars", "(" + WINDOW_189 + ")V", null),
        new RefmapEntry(M189 + "hud/HudExtractVehicleHealthMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "renderStatusBars", "(" + WINDOW_189 + ")V", null),
        new RefmapEntry(M189 + "hud/HudExtractContextualBarBackgroundMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "renderExperienceBar", "(" + WINDOW_189 + "I)V", null),
        new RefmapEntry(M189 + "hud/HudExtractContextualBarBackgroundMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "renderHorseHealth", "(" + WINDOW_189 + "I)V", null),
        new RefmapEntry(M189 + "hud/HudExtractExperienceLevelMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "renderExperienceBar", "(" + WINDOW_189 + "I)V", null),
        new RefmapEntry(M189 + "hud/HudExtractSelectedItemNameMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "renderHeldItemName", "(" + WINDOW_189 + ")V", null),
        new RefmapEntry(M189 + "hud/HudExtractBossOverlayMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "renderBossBar", "()V", null),
        new RefmapEntry(M189 + "hud/HudExtractSleepOverlayMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "render", "(F)V", null),
        new RefmapEntry(M189 + "hud/HudExtractDemoOverlayMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "renderDemoTime", "(" + WINDOW_189 + ")V", null),
        new RefmapEntry(M189 + "hud/HudExtractScoreboardSidebarMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "renderScoreboardObjective",
            "(Lnet/minecraft/scoreboard/ScoreboardObjective;" + WINDOW_189 + ")V", null),
        new RefmapEntry(M189 + "hud/HudExtractOverlayMessageMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "render", "(F)V", null),
        new RefmapEntry(M189 + "hud/HudExtractTitleMixin189",
            "net/minecraft/client/gui/hud/InGameHud", "render", "(F)V", null),
        new RefmapEntry(M189 + "hud/HudExtractTabListMixin189",
            "net/minecraft/client/gui/hud/PlayerListHud", "render",
            "(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreboardObjective;)V", null),

        new RefmapEntry(M189 + "render/ItemDecorationsExtractMixin189",
            "net/minecraft/client/render/item/ItemRenderer", "renderGuiItemOverlay",
            "(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V", null),
        new RefmapEntry(M189 + "level/LevelBlockOutlineExtractMixin189",
            "net/minecraft/client/render/WorldRenderer", "drawBlockOutline",
            "(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/util/hit/BlockHitResult;IF)V", null),
        new RefmapEntry(M189 + "level/LevelExtractMixin189",
            "net/minecraft/client/render/GameRenderer", "renderWorld", "(FJ)V", null),
        new RefmapEntry(M189 + "level/GameRenderExtractMixin189",
            "net/minecraft/client/render/GameRenderer", "render", "(FJ)V", null),

        new RefmapEntry(M189 + "screen/ScreenInitMixin189",
            "net/minecraft/client/gui/screen/Screen", "init", "(Lnet/minecraft/client/MinecraftClient;II)V", null),
        new RefmapEntry(M189 + "screen/ScreenSetMixin189",
            "net/minecraft/client/MinecraftClient", "setScreen", "(Lnet/minecraft/client/gui/screen/Screen;)V", null),
        new RefmapEntry(M189 + "screen/KeyboardKeyMixin189",
            "net/minecraft/client/gui/screen/Screen", "handleKeyboard", "()V", null),
        new RefmapEntry(M189 + "screen/ScreenAfterBackgroundExtractMixin189",
            "net/minecraft/client/gui/screen/Screen", "renderBackground", "(I)V", null),
        new RefmapEntry(M189 + "keybind/KeybindRegisterMixin189",
            "net/minecraft/client/option/GameOptions", "load", "()V", null),

        new RefmapEntry(M189 + "chat/ChatReceiveMixin189",
            "net/minecraft/client/network/ClientPlayNetworkHandler", "onChatMessage",
            "(Lnet/minecraft/network/packet/s2c/play/ChatMessageS2CPacket;)V",
            "net/minecraft/network/listener/ClientPlayPacketListener"),
        new RefmapEntry(M189 + "chat/ChatSendMixin189",
            "net/minecraft/entity/player/ClientPlayerEntity", "sendChatMessage", "(Ljava/lang/String;)V", null),
        new RefmapEntry(M189 + "chat/CommandSendMixin189",
            "net/minecraft/entity/player/ClientPlayerEntity", "sendChatMessage", "(Ljava/lang/String;)V", null),
        new RefmapEntry(M189 + "item/ItemTooltipMixin189",
            "net/minecraft/item/ItemStack", "getTooltip",
            "(Lnet/minecraft/entity/player/PlayerEntity;Z)Ljava/util/List;", null),

        new RefmapEntry(M189 + "lifecycle/ClientTickMixin189",
            "net/minecraft/client/MinecraftClient", "tick", "()V", null),
        new RefmapEntry(M189 + "lifecycle/EntityLoadMixin189",
            "net/minecraft/client/world/ClientWorld", "onEntitySpawned", "(Lnet/minecraft/entity/Entity;)V",
            "net/minecraft/world/World"),
        new RefmapEntry(M189 + "lifecycle/EntityUnloadMixin189",
            "net/minecraft/client/world/ClientWorld", "onEntityRemoved", "(Lnet/minecraft/entity/Entity;)V",
            "net/minecraft/world/World"),
        new RefmapEntry(M189 + "lifecycle/ClientLevelLoadMixin189",
            "net/minecraft/client/MinecraftClient", "connect",
            "(Lnet/minecraft/client/world/ClientWorld;Ljava/lang/String;)V", null),
        new RefmapEntry(M189 + "clock/ClockTotalTicksMixin189",
            "net/minecraft/world/World", "getSkyAngle", "(F)F", null),
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
            if (e.kind == RefmapEntry.Kind.FIELD) {
                // @Accessor : clé = nom du champ tel qu'écrit dans l'annotation.
                String fieldRef = MappingsRegistry.runtimeFieldReference(e.yarnTargetClass, e.namedMethod);
                if (fieldRef == null) {
                    LauncherLog.warn("[LauncherAgent] refmap: champ Yarn inconnu " + e.yarnTargetClass + "#"
                        + e.namedMethod + " — accessor de " + e.mixinInternalName + " non traduit");
                    continue;
                }
                byMixin.computeIfAbsent(e.mixinInternalName, k -> new java.util.LinkedHashMap<>())
                       .put(e.namedMethod, fieldRef);
                continue;
            }

            // La CLÉ JSON = ce qui est écrit dans @Inject(method = "...") = nom named + desc named.
            // Mixin cherche cette clé dans le refmap pour obtenir le nom intermediary (Fabric).
            // Sur vanilla, le remapper (MappingsRegistry) traduit le nom named → official directement,
            // sans passer par le refmap — les deux chemins sont indépendants.
            // @Invoker : clé = nom seul, c'est ce qu'on écrit dans l'annotation.
            String refmapKey = e.kind == RefmapEntry.Kind.INVOKER ? e.namedMethod : e.namedMethod + e.namedDesc;

            // La VALEUR = nom intermediary + desc intermediary, pour que Fabric trouve la méthode.
            String officialMethod = resolveOfficialMethodName(e.yarnTargetClass, e.namedMethod,
                                                              e.namedDesc, e.fallbackNamedOwner);
            String officialDesc   = resolveOfficialDesc(e.namedDesc);
            String replacement    = refmapMethodReplacement(e.yarnTargetClass, officialMethod,
                                                            officialDesc, e.fallbackNamedOwner);
            byMixin.computeIfAbsent(e.mixinInternalName, k -> new java.util.LinkedHashMap<>())
                   .put(refmapKey, replacement);
        }

        StringBuilder sb = new StringBuilder("{\"mappings\":{");
        boolean firstMixin = true;
        for (java.util.Map.Entry<String, java.util.Map<String, String>> mixinEntry : byMixin.entrySet()) {
            if (!firstMixin) sb.append(',');
            firstMixin = false;
            sb.append('"').append(mixinEntry.getKey()).append("\":{");
            boolean firstMethod = true;
            for (java.util.Map.Entry<String, String> methodEntry : mixinEntry.getValue().entrySet()) {
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
     * Cherche d'abord dans la classe cible (avec puis sans descripteur), puis
     * dans fallbackNamedOwner (idem). Retourne namedMethod tel quel si
     * introuvable (constructeur ou méthode non obfusquée).
     *
     * CORRECTIF (bug réel trouvé en test 1.21.11) : plusieurs méthodes Yarn
     * peuvent partager le même NOM avec des descripteurs différents (ex:
     * Screen a deux méthodes "init" : init()V → official bg_, et
     * init(II)V → official b, complètement différentes). Chercher par NOM
     * SEUL (sans descripteur) est ambigu et peut retourner la mauvaise
     * surcharge selon l'ordre d'insertion dans le tiny — observé : nos
     * @Inject visant Screen.init()V se retrouvaient reciblés vers "b"
     * (init(II)V) au lieu de "bg_", donc "Mixin apply failed ... could not
     * find any targets matching b()V". D'où la priorité à la lookup EXACTE
     * (avec descripteur officiel, traduit via resolveOfficialDesc), avec
     * repli sur la lookup par nom seul uniquement si la lookup exacte échoue
     * (robustesse pour d'éventuels cas où la traduction de descripteur ne
     * matcherait pas exactement, ex: type non présent dans les mappings).
     */
    private static String resolveOfficialMethodName(String yarnClass, String namedMethod,
                                                     String namedDesc, String fallbackNamedOwner) {
        if ("<init>".equals(namedMethod)) return "<init>";
        String officialDesc = resolveOfficialDesc(namedDesc);
        YarnMappings.MethodEntry me = YarnMappings.getOfficialMethod(yarnClass, namedMethod, officialDesc);
        if (me == null) me = YarnMappings.getOfficialMethod(yarnClass, namedMethod);
        if (me == null && fallbackNamedOwner != null) {
            me = YarnMappings.getOfficialMethod(fallbackNamedOwner, namedMethod, officialDesc);
            if (me == null) me = YarnMappings.getOfficialMethod(fallbackNamedOwner, namedMethod);
        }
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
     * official → nom du schéma ACTIF, pour la valeur du refmap JSON.
     * Cherche d'abord dans la classe cible, puis dans fallbackNamedOwner (converti en official).
     *
     * <p>Passe par {@code MappingsRegistry.runtimeMethod} plutôt que
     * d'interroger un index en direct (2026-09-10) : la version précédente
     * appelait {@code YarnMappings.getIntermediaryMethod} en dur, donc tout
     * schéma autre qu'{@code OFFICIAL} était traité comme
     * {@code INTERMEDIARY}. Sous SRG, la recherche échouait et le refmap
     * pointait sur le nom officiel — un refmap syntaxiquement valide qui vise
     * des méthodes absentes du jar : exactement l'échec
     * « could not find any targets matching » que le refmap existe pour
     * éviter.
     */
    private static String refmapMethodReplacement(String yarnClass, String officialMethod,
                                                   String officialDesc, String fallbackNamedOwner) {
        // Vanilla (OFFICIAL) : le refmap doit pointer vers le nom OFFICIEL brut
        // (celui réellement présent dans le jar chargé) — PAS l'intermediary,
        // qui n'existe qu'en mémoire sous Fabric. Sans refmap écrit pour ce cas,
        // Mixin valide les cibles @Inject contre la chaîne Yarn named littérale
        // (ex: "init") — qui ne correspond à rien dans le jar obfusqué → échec
        // "could not find any targets matching" (observé en test 1.8.9 vanilla).
        if (MappingsRegistry.getScheme() == MappingsRegistry.Scheme.OFFICIAL) {
            return officialMethod + MappingsRegistry.runtimeDesc(officialDesc);
        }

        String runtime = resolveRuntimeMethod(YarnMappings.getOfficialClass(yarnClass),
            officialMethod, officialDesc);
        if (runtime == null && fallbackNamedOwner != null) {
            runtime = resolveRuntimeMethod(YarnMappings.getOfficialClass(fallbackNamedOwner),
                officialMethod, officialDesc);
        }
        LauncherLog.asm(1, "[LauncherAgent] refmap official→" + MappingsRegistry.getScheme() + ": "
            + officialMethod + officialDesc + " → " + runtime);
        return (runtime != null ? runtime : officialMethod) + MappingsRegistry.runtimeDesc(officialDesc);
    }

    /**
     * @return le nom runtime, ou {@code null} si l'index n'a pas su traduire.
     *
     * <p>L'échec se lit sur l'ÉGALITÉ avec le nom officiel, pas sur un
     * {@code null} : {@code runtimeMethod} retombe par contrat sur son entrée
     * quand il ne trouve rien. Distinguer les deux compte ici, parce que c'est
     * ce qui décide s'il faut essayer {@code fallbackNamedOwner} (le cas d'une
     * méthode héritée, déclarée sur la classe parente et absente de la table
     * de la sous-classe).
     */
    private static String resolveRuntimeMethod(String officialClass, String officialMethod,
                                                String officialDesc) {
        if (officialClass == null) return null;
        String runtime = MappingsRegistry.runtimeMethod(officialClass, officialMethod, officialDesc);
        return runtime.equals(officialMethod) ? null : runtime;
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
        return findClass(name, false);
    }

    @Override
    /**
     * Résolution via le classloader de CONTEXTE — surtout ne pas préférer le
     * classloader isolé ici.
     *
     * Diagnostic établi le 2026-08-25 (§12), conservé parce qu'il ne faut pas
     * refaire cette tentative sans un plan complet : comme
     * {@code launcher-agent.jar} est passé en {@code -javaagent}, il est AUSSI
     * sur le classloader 'app'. La sélection des configs Mixin n'ayant pas lieu
     * pendant {@code LauncherAgent.startIsolated()} (qui ne pose isolatedCl
     * comme contexte que temporairement) mais bien plus tard, cette méthode
     * résout {@code LauncherMixinConfigPlugin} depuis 'app' — où il implémente
     * la copie APP de {@code IMixinConfigPlugin}, incompatible avec celle de
     * notre Mixin isolé :
     * <pre>ClassCastException: ...LauncherMixinConfigPlugin cannot be cast to
     * ...IMixinConfigPlugin (loader 'app' vs java.net.URLClassLoader@…)</pre>
     * Mixin l'attrape, laisse {@code plugin} à null, et n'appelle donc jamais
     * {@code onLoad()} ni {@code shouldApplyMixin()}. Le plugin de config est
     * ainsi INERTE sur ce bracket depuis toujours (l'erreur était en plus
     * invisible : bug de niveaux de {@code LauncherLogger}, corrigé depuis).
     *
     * ⚠️ Deux tentatives de correction ont été faites et TOUTES DEUX ANNULÉES,
     * parce que rendre le plugin fonctionnel casse le démarrage du jeu :
     * <ul>
     *   <li>isolatedCl pour TOUTES les classes (v737) → active
     *       {@code org.spongepowered.tools.agent.MixinAgent}, dont le
     *       transformer réapplique les mixins de Fabric pendant notre
     *       retransform : cascade de {@code cannot overwrite method …
     *       @Overwrite is required} puis {@code ClassFormatError}.</li>
     *   <li>isolatedCl limité à {@code com.yuyuframe.*} (v738-v741) → même
     *       crash, puis d'autres en chaîne une fois celui-là écarté
     *       (double init MixinExtras, {@code Minecraft.<clinit>} forcé trop
     *       tôt), et finalement un échec silencieux non diagnostiqué.</li>
     * </ul>
     * Le seul bénéfice attendu était d'activer la gate {@code HookPoint} — or
     * elle repose sur {@code VanillaHookRegistry}, dont l'état n'est connu
     * qu'à l'EXÉCUTION (modules enregistrés à la première frame), alors que
     * {@code shouldApplyMixin()} décide au TISSAGE : à cet instant le
     * registre est TOUJOURS vide (51 mixins écartés, 0 tissé, constaté en
     * v739). La gate a depuis été DÉPLACÉE vers {@code
     * IsolatedBootstrap.filterConfigByHookPoints()} (2026-08-25, §12), qui
     * filtre le JSON par lecture de bytecode AVANT tout enregistrement Mixin
     * — donc avant même que ce classloading foireux n'entre en jeu. Ce
     * plugin reste sans intérêt à réveiller pour CETTE raison-là ; ses
     * autres responsabilités ({@code disable_mixin.*}, {@code mixin.debug.*},
     * journal de succès par mixin) restent fonctionnelles sur les brackets
     * non isolés (vanilla), seule voie où il se charge correctement.
     */
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

        InputStream is = openResource(resource);

        String obfSlash = nameSlash;
        if (is == null && MappingsRegistry.isLoaded()) {
            // 1) Nom "official" (obfusqué brut Mojang) — c'est ce que contient
            //    le jar client en VANILLA sans Fabric.
            String officialSlash = MappingsRegistry.getOfficialClassAlways(nameSlash);
            if (!officialSlash.equals(nameSlash)) {
                is = openResource(officialSlash + ".class");
                if (is != null) obfSlash = officialSlash;
            }

            // 2) Nom "intermediary" — utile UNIQUEMENT sur les brackets
            //    réellement obfusqués (1.8.9, 1.21.x...), où Fabric place sur
            //    le classpath .fabric/remappedJars/<mc>-<loader>/
            //    client-intermediary.jar, dont les entrées sont nommées
            //    net/minecraft/class_1297.class. SANS OBJET en 26.1.2 : cette
            //    version est livrée DÉOBFUSQUÉE (0 entrée "class_" dans
            //    26.1.2.jar, aucun remappedJars généré) — les noms named sont
            //    déjà les noms réels, l'essai (1) comme (2) sont des no-op.
            if (is == null) {
                String runtimeSlash = MappingsRegistry.getObfClassDot(nameSlash).replace('.', '/');
                if (!runtimeSlash.equals(nameSlash)) {
                    is = openResource(runtimeSlash + ".class");
                    if (is != null) obfSlash = runtimeSlash;
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

    /**
     * Classloader capable de voir le JAR DU JEU — capturé par {@link
     * LauncherMixinTransformerWrapper#transform} à la première classe
     * {@code net/minecraft/**} transformée (Knot sous Fabric/Quilt, le
     * classloader système en vanilla, TransformingClassLoader sous Forge).
     *
     * ⚠️ Indispensable, voir [[project_mc_261_port]] §12 : NI le classloader
     * de contexte NI celui de ce service ne voient le jar du jeu. Les deux
     * valent {@code isolatedCl} (URLClassLoader agent + libs Mixin, parent =
     * platform CL, monté dans {@code LauncherAgent.startIsolated()}) —
     * et le thread {@code LauncherAgent-Retransform}, créé par un
     * {@code new Thread()} sans {@code setContextClassLoader} pendant que le
     * contexte valait {@code isolatedCl}, en hérite À VIE.
     *
     * Sans ce troisième essai, {@link #getClassNode} échoue sur TOUTE classe
     * du jeu, y compris quand elle existe dans le jar sous exactement le nom
     * demandé. Cet échec est SILENCIEUX côté Mixin (simple
     * "[Mixin/WARN] Error loading class") : {@code ClassInfo.forName()}
     * renvoie null, donc {@code getCommonSuperClass()} retombe sur
     * {@code java/lang/Object} pendant le calcul ASM COMPUTE_FRAMES → stack
     * map frame déclarant Object là où le vérificateur attend un type précis
     * → {@code VerifyError "Bad type on operand stack"} sur une méthode
     * ARBITRAIRE de la classe tissée (la réécriture Mixin est class-wide, pas
     * limitée à la méthode hookée). C'est l'origine réelle du crash attribué
     * à tort à une "incompatibilité avec fabric-screen-api-v1" sur
     * {@code Minecraft.setScreen} (voir §10) : les grosses classes comme
     * {@code Minecraft} contiennent forcément des fusions de frames entre
     * deux types du jeu, les petites ({@code Camera}, {@code FoodData}) non —
     * d'où "n'importe lequel des 4 mixins seul = crash", que l'hypothèse
     * "conflit entre mods" n'expliquait pas.
     */
    private static volatile ClassLoader gameClassLoader;

    static void setGameClassLoader(ClassLoader cl) {
        if (gameClassLoader == null && cl != null) gameClassLoader = cl;
    }

    /** Voir {@code LauncherMixinTransformerWrapper#transform} : Mixin n'est pas sollicité avant. */
    static boolean hasGameClassLoader() {
        return gameClassLoader != null;
    }

    /** Ouvre une ressource .class : contexte, puis ce service, puis le loader du jeu, puis le système. */
    private static InputStream openResource(String resource) {
        InputStream is = getContextClassLoader().getResourceAsStream(resource);
        if (is == null) is = LauncherMixinService.class.getClassLoader().getResourceAsStream(resource);
        if (is == null) {
            ClassLoader game = gameClassLoader;
            if (game != null) is = game.getResourceAsStream(resource);
        }
        if (is == null) is = ClassLoader.getSystemClassLoader().getResourceAsStream(resource);
        return is;
    }

    private static ClassLoader getContextClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : ClassLoader.getSystemClassLoader();
    }
}
