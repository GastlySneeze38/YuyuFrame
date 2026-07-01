package com.yuyuframe.launcheragent.mixin.client;

import com.yuyuframe.launcheragent.runtime.hud.HudOverlayRenderer;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.runtime.modules.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiMainMenuScreen;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiDrawable;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPollerModern;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Point d'accroche GLOBAL unique pour tout notre moteur UI custom — PAS de
 * surcharge de Screen.render/mouseClicked/keyPressed (types record "DrawContext"
 * /"Click"/"KeyInput" sans stub compilable en 1.21+, voir docs/LauncherAgent/
 * index.md et ScreenStubPatcher). À la place : un seul @Inject sur
 * GameRenderer.render(RenderTickCounter, boolean), en TAIL, SANS capturer le
 * paramètre RenderTickCounter — donc aucun stub requis pour ce type non plus
 * (la signature cible n'est qu'une chaîne dans l'annotation Mixin, pas un vrai
 * paramètre Java compilé ; seul notre handler (CallbackInfo ci) doit compiler).
 *
 * Si l'écran actuellement ouvert (MinecraftClient.currentScreen) implémente
 * UiDrawable, on pollue nous-mêmes l'input GLFW et on dessine nous-mêmes via
 * UiRenderer — l'écran vanilla sous-jacent ne sert plus qu'à déclencher les
 * effets de bord normaux d'un écran ouvert (pause, curseur libéré, input jeu
 * bloqué), jamais à son propre rendu/clic.
 *
 * Point d'entrée du menu : Right Shift, pollé même quand AUCUN écran n'est
 * ouvert (gameplay) — voir UiInputPoller.menuKeyPressed. N'ouvre le menu que
 * si currentScreen == null, pour ne jamais voler le focus d'un autre écran
 * déjà ouvert (inventaire, chat, etc.).
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class GlobalUiRenderMixin {

    private static final String CLS_MC = "gfj"; // net.minecraft.client.MinecraftClient
    private static final String CLS_WINDOW = "fyk"; // net.minecraft.client.util.Window

    private static UiInputPoller inputPoller;

    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("TAIL"))
    private void la$onRenderTail(CallbackInfo ci) {
        try {
            Object mc = getMcInstance();
            if (mc == null) return;

            if (inputPoller == null) {
                long handle = getWindowHandle(mc);
                if (handle == 0L) return;
                inputPoller = new UiInputPollerModern(handle, GlobalUiRenderMixin.class.getClassLoader());
                // Force le chargement des modules intégrés (voir ModuleRegistry) dès
                // la première frame — sinon leurs éléments HUD (voir runtime.modules.builtin)
                // ne s'enregistreraient qu'à la première ouverture du menu "YuyuFrame".
                ModuleRegistry.all();
            }
            inputPoller.poll();

            // Logique de module continue, qu'un écran custom soit ouvert ou
            // non (ex: FOV forcé, voir FovModule) — équivalent de TickEvent
            // côté Forge, mais ici juste "cette même méthode s'exécute à
            // chaque frame" (suffisant, pas besoin d'un hook de tick séparé).
            ModuleRegistry.tickAll();

            Object currentScreen = getCurrentScreen(mc);
            if (currentScreen == null) {
                UiRenderer renderer = UiRenderer.get(GlobalUiRenderMixin.class.getClassLoader());
                // Overlay HUD permanent — même règle que le HUD vanilla
                // (hotbar/vie), qui ne s'affiche pas non plus quand un écran
                // est ouvert. Pendant l'édition (UiHudEditorScreen), ce sont
                // les UiHudBox de cet écran qui dessinent, pas cet appel.
                HudOverlayRenderer.render(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                ModuleRegistry.renderOverlayAll(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                if (inputPoller.menuKeyPressed) {
                    setScreen(mc, new UiMainMenuScreen(null));
                }
                return;
            }

            if (!(currentScreen instanceof UiDrawable)) {
                // Écran NON custom ouvert (chat, inventaire, tout autre GUI
                // vanilla/mod) — seuls les éléments HUD marqués
                // showWhenScreenOpen restent visibles (voir HudElement, réglage
                // générique façon OneConfig).
                HudOverlayRenderer.renderPersistent(UiRenderer.get(GlobalUiRenderMixin.class.getClassLoader()),
                    inputPoller.fbWidth, inputPoller.fbHeight);
                return;
            }
            UiDrawable ui = (UiDrawable) currentScreen;
            ui.uiPollInput(inputPoller);
            ui.uiDraw(inputPoller.mouseX, inputPoller.mouseY);

            // Navigation demandée par l'écran lui-même (UiScreenBase.closeTo,
            // ex: clic sur une carte de mod ou bouton retour) — appliquée ICI,
            // seul endroit qui connaît déjà mc + setScreen correctement résolus
            // pour cette version (voir UiScreenBase.closeTo pour le pourquoi).
            if (currentScreen instanceof UiScreenBase) {
                UiScreenBase uiScreen = (UiScreenBase) currentScreen;
                if (uiScreen.hasPendingNavigation()) {
                    Object target = uiScreen.consumePendingNavigation();
                    if (target != null) setScreen(mc, target);
                    else closeScreen(mc, currentScreen.getClass());
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin: " + t);
        }
    }

    private static void setScreen(Object mc, Object screen) throws Exception {
        java.util.Set<String> names = MappingsRegistry.runtimeMethodNames(CLS_MC, "a"); // setScreen(Screen)
        for (java.lang.reflect.Method m : mc.getClass().getMethods()) {
            if (names.contains(m.getName()) && m.getParameterCount() == 1
                    && !m.getParameterTypes()[0].isPrimitive()
                    && m.getParameterTypes()[0].isInstance(screen)) {
                m.invoke(mc, screen);
                return;
            }
        }
        LauncherLog.warn("[LauncherAgent] GlobalUiRenderMixin: setScreen introuvable");
    }

    /**
     * Ferme l'écran (setScreen(null)) — méthode séparée de setScreen() car
     * Method.getParameterTypes()[0].isInstance(null) vaut TOUJOURS false (donc
     * inutilisable pour retrouver le bon overload quand screen==null) : on
     * matche ici par assignabilité depuis la classe de l'écran qu'on ferme.
     */
    private static void closeScreen(Object mc, Class<?> closingScreenType) throws Exception {
        java.util.Set<String> names = MappingsRegistry.runtimeMethodNames(CLS_MC, "a");
        for (java.lang.reflect.Method m : mc.getClass().getMethods()) {
            if (names.contains(m.getName()) && m.getParameterCount() == 1
                    && !m.getParameterTypes()[0].isPrimitive()
                    && m.getParameterTypes()[0].isAssignableFrom(closingScreenType)) {
                m.invoke(mc, (Object) null);
                return;
            }
        }
        LauncherLog.warn("[LauncherAgent] GlobalUiRenderMixin: closeScreen: setScreen introuvable");
    }

    private static long getWindowHandle(Object mc) throws Exception {
        Object window = getField(mc, CLS_MC, "O"); // MinecraftClient.window
        if (window == null) return 0L;
        java.util.Set<String> names = MappingsRegistry.runtimeMethodNames(CLS_WINDOW, "h"); // getHandle()
        for (java.lang.reflect.Method m : window.getClass().getMethods()) {
            if (names.contains(m.getName()) && m.getParameterCount() == 0 && m.getReturnType() == long.class) {
                return (long) m.invoke(window);
            }
        }
        return 0L;
    }

    private static Object getField(Object obj, String officialOwner, String officialField) {
        String fieldName = MappingsRegistry.runtimeField(officialOwner, officialField);
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    private static Object mcInstanceCache;

    private static Object getMcInstance() throws Exception {
        if (mcInstanceCache != null) return mcInstanceCache;
        ClassLoader cl = GlobalUiRenderMixin.class.getClassLoader();
        String mcClassName = MappingsRegistry.runtimeClass(CLS_MC);
        Class<?> mcClass = Class.forName(mcClassName, true, cl);
        java.util.Set<String> names = MappingsRegistry.runtimeMethodNames(CLS_MC, "V");
        for (java.lang.reflect.Method m : mcClass.getDeclaredMethods()) {
            if (names.contains(m.getName()) && m.getParameterCount() == 0
                    && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                m.setAccessible(true);
                mcInstanceCache = m.invoke(null);
                return mcInstanceCache;
            }
        }
        return null;
    }

    private static Object getCurrentScreen(Object mc) throws Exception {
        String fieldName = MappingsRegistry.runtimeField(CLS_MC, "x");
        Class<?> c = mc.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(mc);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
