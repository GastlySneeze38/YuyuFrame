package com.yuyuframe.launcheragent.mixin.client.v1_16;

import com.yuyuframe.launcheragent.runtime.fabric.FabricKnotExposer;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudOverlayRenderer;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.apigraphic.core.UiDrawable;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPollerModern;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Équivalent 1.13-1.16.x de {@code GlobalUiRenderMixin} (package client,
 * 1.21+) — bracket "B" (voir docs/LauncherAgent, historique de session) :
 * LWJGL3/GLFW comme le pipeline moderne (donc {@link UiInputPollerModern},
 * PAS {@link com.yuyuframe.launcheragent.apigraphic.input.UiInputPollerLegacy}),
 * mais contexte OpenGL encore en dessous de 3.2 Core Profile — le dessin
 * immédiat (glBegin/glMatrixMode, voir {@code UiRenderer.drawXxxLegacy})
 * fonctionne donc ENCORE ici, contrairement à 1.17+ (voir
 * {@link com.yuyuframe.launcheragent.runtime.version.MinecraftVersionDetector#supportsFixedFunctionDrawing}
 * — c'est CETTE méthode, pas la présence de ce Mixin, qui décide du style de
 * dessin réel dans {@code UiRenderer}).
 *
 * `GameRenderer.render(float tickDelta, long startTime, boolean tick)` — SIGNATURE
 * DIFFÉRENTE à la fois de la 1.8.9 (`render(FJ)V`, sans le booléen `tick`) et
 * de la 1.21.11 (`render(RenderTickCounter,Z)V`, introduit plus tard) —
 * vérifié via la doc Yarn officielle pour 1.16.5/1.20.4 (les deux partagent
 * cette même signature à 3 paramètres primitifs).
 *
 * **Ce Mixin ne fait plus QUE du dessin/sondage d'input** (voir historique de
 * session, comparaison avec les vrais mods Fabric via leur documentation
 * officielle) — l'ouverture/fermeture réelle de Screen ({@code setScreen()})
 * a été déplacée vers {@link GlobalTickMixin116} (`MinecraftClient.tick()`),
 * exactement le point où un vrai mod Fabric le ferait (jamais depuis un hook
 * de rendu — voir sa javadoc de classe). Ce fichier se contente de : sonder
 * l'input, dessiner l'écran actif, et poser un simple drapeau "ouverture
 * demandée" (voir {@code ScreenBridge116.requestMenuOpen()}/{@code
 * consumePendingMenuOpen()} — PAS un champ ici : Sponge Mixin rejette toute
 * méthode non-privée déclarée dans une classe Mixin, voir historique de
 * session) — plus aucun appel à setScreen() ici, donc plus besoin de la
 * ré-entrance qui causait plusieurs bugs (déluge de NullPointerException au
 * clic, fermeture cassée, crash).
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class GlobalUiRenderMixin116 {

    private static final String YARN_MC = "net/minecraft/client/MinecraftClient";
    private static final String YARN_WINDOW = "net/minecraft/client/util/Window";

    private static UiInputPoller inputPoller;
    private static final java.util.Set<Class<?>> DIAG_LOGGED_CLASSES =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @Inject(method = "render(FJZ)V", at = @At("TAIL"))
    private void la$onRenderTail(CallbackInfo ci) {
        try {
            // DOIT être en tout premier — voir GlobalUiRenderMixin (1.21+) pour
            // le détail du bug de classloader que ça corrige (ModuleRegistry/
            // ModuleGroup chargées deux fois, une par 'app' une par 'knot', si
            // Knot ne sait pas encore que notre jar est sa propre source de
            // code au moment où ce hook force leur premier chargement).
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());

            Object mc = getMcInstance();
            if (mc == null) return;

            if (inputPoller == null) {
                long handle = getWindowHandle(mc);
                if (handle == 0L) return;
                inputPoller = new UiInputPollerModern(handle, this.getClass().getClassLoader());
                ModuleRegistry.all();
                GlobalUiSettings.INSTANCE.onConfigChanged();
            }
            inputPoller.poll();
            // BUG TROUVÉ : voir GlobalUiRenderMixin (1.21+) pour le détail
            // complet — sans son propre try/catch, un module en échec d'init
            // statique bloquait PERMANENTEMENT (NoClassDefFoundError à chaque
            // frame) tout le reste de la méthode, y compris le test
            // d'ouverture du menu plus bas. Isolé ici.
            try {
                ModuleRegistry.tickAll();
            } catch (Throwable t) {
                LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin116: ModuleRegistry.tickAll() a levé: " + t);
            }

            Object currentScreen = ScreenBridge116.getCurrentScreen(mc);
            if (currentScreen == null) {
                UiRenderer renderer = UiRenderer.get(this.getClass().getClassLoader());
                // F1 (voir HudOverlayRenderer#vanillaHudHidden) : nos
                // éléments HUD/modules doivent disparaître avec le HUD vanilla.
                if (!HudOverlayRenderer.vanillaHudHidden()) {
                    HudOverlayRenderer.render(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                    ModuleRegistry.renderOverlayAll(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                }
                if (inputPoller.menuKeyPressed) {
                    LauncherLog.info("[LauncherAgent] DIAG-116: menuKeyPressed détecté, ouverture demandée (appliquée au tick)");
                    ScreenBridge116.requestMenuOpen();
                }
                return;
            }

            if (DIAG_LOGGED_CLASSES.add(currentScreen.getClass())) {
                LauncherLog.info("[LauncherAgent] DIAG-116: currentScreen=" + currentScreen
                    + " class=" + currentScreen.getClass() + " isUiDrawable=" + (currentScreen instanceof UiDrawable));
            }

            if (!(currentScreen instanceof UiDrawable)) {
                HudOverlayRenderer.renderPersistent(
                    UiRenderer.get(this.getClass().getClassLoader()), currentScreen, inputPoller.fbWidth, inputPoller.fbHeight);
                return;
            }
            UiDrawable ui = (UiDrawable) currentScreen;
            ui.uiPollInput(inputPoller);
            ui.uiDraw(inputPoller.mouseX, inputPoller.mouseY);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin116: " + t);
        }
    }

    // ── Résolution dynamique (named Yarn → official/intermediary selon le
    // schéma actif) — voir javadoc de classe : aucun nom obfusqué figé ici. ──

    private static long getWindowHandle(Object mc) throws Exception {
        Object window = ScreenBridge116.getNamedField(mc, YARN_MC, "window");
        if (window == null) return 0L;
        String name = MappingsRegistry.getObfMethodName(YARN_WINDOW, "getHandle");
        for (Method m : window.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0 && m.getReturnType() == long.class) {
                return (long) m.invoke(window);
            }
        }
        return 0L;
    }

    private static Object mcInstanceCache;

    private static Object getMcInstance() throws Exception {
        if (mcInstanceCache != null) return mcInstanceCache;
        Class<?> mcClass = MappingsRegistry.loadClass(YARN_MC);
        String name = MappingsRegistry.getObfMethodName(YARN_MC, "getInstance");
        for (Method m : mcClass.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0 && Modifier.isStatic(m.getModifiers())) {
                m.setAccessible(true);
                mcInstanceCache = m.invoke(null);
                return mcInstanceCache;
            }
        }
        return null;
    }
}
