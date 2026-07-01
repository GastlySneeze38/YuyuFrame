package com.yuyuframe.launcheragent.mixin.client;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.screen.UiMainMenuScreen;
import com.yuyuframe.launcheragent.runtime.ui.UiDrawable;
import com.yuyuframe.launcheragent.runtime.ui.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.UiInputPollerModern;
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
            }
            inputPoller.poll();

            Object currentScreen = getCurrentScreen(mc);
            if (currentScreen == null) {
                if (inputPoller.menuKeyPressed) {
                    setScreen(mc, new UiMainMenuScreen(null));
                }
                return;
            }

            if (!(currentScreen instanceof UiDrawable ui)) return;
            ui.uiPollInput(inputPoller);
            ui.uiDraw(inputPoller.mouseX, inputPoller.mouseY);
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
