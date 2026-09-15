package com.yuyuframe.launcheragent.apimixin.v1_8_9.core;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.platform.lwjgl3.UiInputPollerModern;
import com.yuyuframe.launcheragent.apigraphic.widget.UiDrawable;
import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.AgentBridge;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hub du moteur UI sur 1.8.9 — logique ET dessin, portage apimixin de
 * {@code mixin.client.v1_8.GlobalUiRenderMixin189} (conservé comme référence).
 *
 * <p>Même point d'accroche que l'ancien : {@code GameRenderer.render(FJ)V},
 * TAIL. En ère {@code gl3} (depuis le 2026-09-14, {@code gl2} avant) il n'y a
 * ni présentation Blaze3D ni passe GUI vanilla où s'intercaler : tout se
 * dessine ici, après la frame vanilla, encadré par {@link GlFrameState189}.
 * D'où {@link AgentBridge#renderHud}, absent des hubs Blaze3D.
 *
 * <p>Entrées : {@code UiInputPollerModern} sur le handle GLFW de la fenêtre
 * depuis le passage à LWJGL 3 (2026-09-14) — plus {@code UiInputPollerLegacy}.
 *
 * <p>Deux différences avec l'ancien :
 * <ul>
 *   <li>plus aucun appel à {@code runtime/} — tout passe par {@link AgentBridge} ;</li>
 *   <li>plus de réflexion : Minecraft et l'écran courant passent par les points
 *       d'accès {@link AccessPoint#CLIENT_SCREEN} et
 *       {@link AccessPoint#CLIENT_SET_SCREEN}. Tant que leurs liaisons 1.8.9
 *       n'existent pas, rien n'est dessiné (voir {@link #screensAvailable()}) ;
 *       entrées et tick des modules tournent déjà.</li>
 * </ul>
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class GlobalUiRenderMixin189 {

    private static UiInputPoller inputPoller;

    @Inject(method = "render(FJ)V", at = @At("TAIL"))
    private void la$onRenderTail(CallbackInfo ci) {
        try {
            ClassLoader gameLoader = this.getClass().getClassLoader();
            AgentBridge agent = AgentBridge.get(gameLoader);

            if (inputPoller == null) {
                // LWJGL 3 (2026-09-14) : la fenêtre est une fenêtre GLFW créée
                // par la couche de compatibilité (org.lwjgl.opengl.Display de
                // lwjgl2-compat.jar) — même lecteur d'entrées que les versions
                // GLFW, comme en 1.16.5. Appel typé, pas de réflexion. Ses
                // callbacks GLFW enchaînent ceux de la couche (Keyboard/Mouse
                // de LWJGL 2), qui continuent d'alimenter le jeu.
                long window = org.lwjgl.opengl.Display.getHandle();
                if (window == -1L || window == 0L) return;
                inputPoller = new UiInputPollerModern(window, gameLoader);
                try {
                    agent.bootstrap();
                } catch (Throwable t) {
                    LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin189: bootstrap() a levé: " + t);
                }
            }
            inputPoller.poll();
            agent.tick();

            if (!screensAvailable()) return;

            // Ère gl3 sur le pipeline fixe de la 1.8.9 : tout le dessin de
            // l'agent est encadré par une capture/restauration de l'état GL,
            // sans quoi les caches de GlStateManager divergent de l'état réel
            // (voir GlFrameState189).
            GL_STATE.capture();
            try {
                drawFrame(agent, gameLoader);
            } finally {
                GL_STATE.restore();
            }

            // Icônes d'item vanilla mises en file par le dessin ci-dessus,
            // dessinées APRÈS lui (2026-09-16) : vidées plus tôt, dans
            // InGameHud.render (ancien HudItemFlushMixin189), elles passaient
            // SOUS les panneaux de l'agent dessinés ici — le fond du module
            // Armure/Durabilité les recouvrait. Hors du cadre GL_STATE :
            // Gl3VanillaItemSink189 dessine avec GlStateManager, dont les caches
            // sont justes une fois l'état restauré ; la projection GUI posée par
            // vanilla pour son HUD/écran est toujours en place.
            UiRenderer.flushPendingLegacyHudItems(this);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin189: " + t);
        }
    }

    private static final GlFrameState189 GL_STATE = new GlFrameState189();

    private static void drawFrame(AgentBridge agent, ClassLoader gameLoader) {
        UiRenderer renderer = UiRenderer.get(gameLoader);
        Object currentScreen = AccessorRegistry.get(AccessPoint.CLIENT_SCREEN, null);
        if (currentScreen == null) {
            drawInGame(agent, renderer);
            if (inputPoller.menuKeyPressed) {
                Object menu = agent.mainMenuScreen();
                if (menu != null) AccessorRegistry.invoke(AccessPoint.CLIENT_SET_SCREEN, null, menu);
            }
            return;
        }

        if (!(currentScreen instanceof UiDrawable)) {
            // Écran vanilla ouvert : seuls les éléments HUD persistants restent.
            agent.renderHud(renderer, currentScreen, inputPoller.fbWidth, inputPoller.fbHeight);
            return;
        }

        UiDrawable ui = (UiDrawable) currentScreen;
        ui.uiPollInput(inputPoller);
        ui.uiDraw(inputPoller.mouseX, inputPoller.mouseY);

        if (agent.hasPendingNavigation(currentScreen)) {
            // null = fermer l'écran : même point d'accès, argument null.
            Object target = agent.consumePendingNavigation(currentScreen);
            AccessorRegistry.invoke(AccessPoint.CLIENT_SET_SCREEN, null, target);
        }
    }

    /** HUD en jeu : masqué avec le HUD vanilla (F1), comme l'ancien hub. */
    private static void drawInGame(AgentBridge agent, UiRenderer renderer) {
        if (agent.hudHidden()) return;
        agent.renderHud(renderer, null, inputPoller.fbWidth, inputPoller.fbHeight);
        agent.renderOverlay(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
    }

    /**
     * Sans liaison de l'écran courant, « aucun écran » et « liaison absente »
     * se confondent : le hub ouvrirait le menu et dessinerait le HUD par-dessus
     * n'importe quel écran vanilla. Mieux vaut ne rien dessiner.
     */
    private static boolean screensAvailable() {
        return AccessorRegistry.isBound(AccessPoint.CLIENT_SCREEN)
            && AccessorRegistry.isBound(AccessPoint.CLIENT_SET_SCREEN);
    }
}
