package com.yuyuframe.launcheragent.mixin.client.v1_20_4;

import com.yuyuframe.launcheragent.runtime.fabric.FabricKnotExposer;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudOverlayRenderer;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiDrawable;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPollerModern;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Équivalent 1.17-1.20.4 de {@code GlobalUiRenderMixin116} (bracket "B") —
 * bracket "C" (voir VersionBracketRegistry, docs/LauncherAgent) : Core
 * Profile OpenGL 3.2 obligatoire (pipeline fixe supprimé), donc {@code
 * UiRenderer.modern} vaut {@code true} ici (voir
 * {@link com.yuyuframe.launcheragent.runtime.version.MinecraftVersionDetector#supportsFixedFunctionDrawing}),
 * contrairement à 1.16.5 où il vaut {@code false} — c'est la SEULE différence
 * de comportement réelle entre ces deux brackets pour ce fichier : le point
 * d'accroche, l'input (LWJGL3/GLFW) et toute la logique de dessin/sondage
 * sont identiques, seul le CHOIX du style de dessin dans {@code UiRenderer}
 * change en fonction de la version détectée.
 *
 * `GameRenderer.render(float tickDelta, long startTime, boolean tick)` —
 * MÊME signature (FJZ)V que 1.16.5, vérifiée directement dans
 * mappings/yarn-1.20.4-mergedv2.jar (method_3192 render, sous {@code fta}) —
 * DIFFÉRENTE de la 1.8.9 ({@code render(FJ)V}, pas de booléen "tick") et de la
 * 1.21.11 ({@code render(RenderTickCounter,Z)V}, introduit plus tard).
 *
 * Voir {@link GlobalUiRenderMixin1204}'s ainé {@code GlobalUiRenderMixin116}
 * pour le détail de CHAQUE bug déjà corrigé qui s'applique identiquement ici
 * (setScreen ambigu, mouseClicked/keyPressed jamais déclenchés, réentrance
 * GLFW) — ce fichier ne fait QUE réutiliser ces mêmes leçons sur un bracket
 * de version différent, rien de nouveau à découvrir en théorie.
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class GlobalUiRenderMixin1204 {

    private static final String YARN_MC = "net/minecraft/client/MinecraftClient";
    private static final String YARN_WINDOW = "net/minecraft/client/util/Window";

    private static UiInputPoller inputPoller;
    private static final java.util.Set<Class<?>> DIAG_LOGGED_CLASSES =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @Inject(method = "render(FJZ)V", at = @At("TAIL"))
    private void la$onRenderTail(CallbackInfo ci) {
        try {
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
            ModuleRegistry.tickAll();

            Object currentScreen = ScreenBridge1204.getCurrentScreen(mc);
            if (currentScreen == null) {
                UiRenderer renderer = UiRenderer.get(this.getClass().getClassLoader());
                HudOverlayRenderer.render(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                ModuleRegistry.renderOverlayAll(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                if (inputPoller.menuKeyPressed) {
                    LauncherLog.info("[LauncherAgent] DIAG-1204: menuKeyPressed détecté, ouverture demandée (appliquée au tick)");
                    ScreenBridge1204.requestMenuOpen();
                }
                return;
            }

            if (DIAG_LOGGED_CLASSES.add(currentScreen.getClass())) {
                LauncherLog.info("[LauncherAgent] DIAG-1204: currentScreen=" + currentScreen
                    + " class=" + currentScreen.getClass() + " isUiDrawable=" + (currentScreen instanceof UiDrawable));
            }

            if (!(currentScreen instanceof UiDrawable)) {
                HudOverlayRenderer.renderPersistent(
                    UiRenderer.get(this.getClass().getClassLoader()), inputPoller.fbWidth, inputPoller.fbHeight);
                return;
            }
            UiDrawable ui = (UiDrawable) currentScreen;
            ui.uiPollInput(inputPoller);
            ui.uiDraw(inputPoller.mouseX, inputPoller.mouseY);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin1204: " + t);
        }
    }

    private static long getWindowHandle(Object mc) throws Exception {
        Object window = ScreenBridge1204.getNamedField(mc, YARN_MC, "window");
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
