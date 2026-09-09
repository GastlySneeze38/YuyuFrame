package com.yuyuframe.launcheragent.mixin.client.v1_21_4;

import com.yuyuframe.launcheragent.apimixin.loader.FabricKnotExposer;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudOverlayRenderer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apimixin.mapping.MappingsRegistry;
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
 * Équivalent ~1.21-1.21.5 de {@code GlobalUiRenderMixin1204} — bracket "D"
 * (voir VersionProfileRegistry, docs/LauncherAgent) : MÊME profil OpenGL Core
 * que le bracket "C" (1.20.4) — {@code UiRenderer.modern} vaut {@code true}
 * ici aussi — mais {@code GameRenderer.render} change de signature :
 * {@code render(RenderTickCounter, boolean)} au lieu de {@code render(float,
 * long, boolean)} (RenderTickCounter introduit entre la 1.20.4 et la 1.21,
 * vérifié dans mappings/yarn-1.21.4-mergedv2.jar : {@code (Lfla;Z)V a
 * method_3192 render}, {@code fla} = {@code
 * net.minecraft.client.render.RenderTickCounter} — MÊME signature que le
 * bracket 1.21.11 déjà existant, {@code mixin/client/GlobalUiRenderMixin.java},
 * mais celui-ci utilise des noms obfusqués figés en dur (technique plus
 * fragile, propre à un seul jar de mappings) — ce fichier-ci réutilise la
 * résolution 100% dynamique du bracket "C" à la place.
 *
 * Aucun stub de compilation nécessaire pour {@code RenderTickCounter} : la
 * signature cible n'est qu'une chaîne dans l'annotation Mixin, jamais un vrai
 * paramètre Java capturé par le handler (voir {@code GlobalUiRenderMixin}
 * 1.21.11 pour la même remarque).
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class GlobalUiRenderMixin1214 {

    private static final String YARN_MC = "net/minecraft/client/MinecraftClient";
    private static final String YARN_WINDOW = "net/minecraft/client/util/Window";

    private static UiInputPoller inputPoller;
    private static final java.util.Set<Class<?>> DIAG_LOGGED_CLASSES =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("TAIL"))
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
            // BUG TROUVÉ : voir GlobalUiRenderMixin (1.21+) pour le détail
            // complet — sans son propre try/catch, un module en échec d'init
            // statique bloquait PERMANENTEMENT (NoClassDefFoundError à chaque
            // frame) tout le reste de la méthode, y compris le test
            // d'ouverture du menu plus bas. Isolé ici.
            try {
                ModuleRegistry.tickAll();
            } catch (Throwable t) {
                LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin1214: ModuleRegistry.tickAll() a levé: " + t);
            }

            Object currentScreen = ScreenBridge1214.getCurrentScreen(mc);
            if (currentScreen == null) {
                UiRenderer renderer = UiRenderer.get(this.getClass().getClassLoader());
                // F1 (voir HudOverlayRenderer#vanillaHudHidden) : nos
                // éléments HUD/modules doivent disparaître avec le HUD vanilla.
                if (!HudOverlayRenderer.vanillaHudHidden()) {
                    HudOverlayRenderer.render(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                    ModuleRegistry.renderOverlayAll(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                }
                if (inputPoller.menuKeyPressed) {
                    LauncherLog.info("[LauncherAgent] DIAG-1214: menuKeyPressed détecté, ouverture demandée (appliquée au tick)");
                    ScreenBridge1214.requestMenuOpen();
                }
                return;
            }

            if (DIAG_LOGGED_CLASSES.add(currentScreen.getClass())) {
                LauncherLog.info("[LauncherAgent] DIAG-1214: currentScreen=" + currentScreen
                    + " class=" + currentScreen.getClass() + " isUiDrawable=" + (currentScreen instanceof UiDrawable));
            }

            if (!(currentScreen instanceof UiDrawable)) {
                UiRenderer renderer = UiRenderer.get(this.getClass().getClassLoader());
                HudOverlayRenderer.renderPersistent(renderer, currentScreen, inputPoller.fbWidth, inputPoller.fbHeight);
                return;
            }
            UiDrawable ui = (UiDrawable) currentScreen;
            ui.uiPollInput(inputPoller);
            ui.uiDraw(inputPoller.mouseX, inputPoller.mouseY);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin1214: " + t);
        }
    }

    private static long getWindowHandle(Object mc) throws Exception {
        Object window = ScreenBridge1214.getNamedField(mc, YARN_MC, "window");
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
