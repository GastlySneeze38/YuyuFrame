package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.runtime.ui.UiDrawable;
import com.yuyuframe.launcheragent.runtime.ui.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.UiInputPollerLegacy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Équivalent 1.8.9 de GlobalUiRenderMixin (package client, 1.21+) — même
 * principe : un seul point d'accroche global sur GameRenderer.render(),
 * CallbackInfo seul (aucun paramètre capturé), donc aucun stub requis — la
 * signature réelle est juste (float, long) ici de toute façon (vérifié dans
 * mappings/mappings-1.8.9.tiny : GameRenderer.render(F J)V, nommé "render"
 * côté Yarn, comme en 1.21).
 *
 * Pas de refmap ici : IsolatedBootstrap n'écrit jamais de refmap sur la
 * branche 1.8.9 (voir IsolatedBootstrap.start(), "fabric && !legacy189") —
 * MappingsRegistry.INSTANCE (IRemapper) gère seul la traduction Yarn→official.
 *
 * MinecraftClient 1.8.9 n'a PAS de notion de "window handle" (LWJGL2, Display
 * global implicite) — UiInputPollerLegacy n'en a donc pas besoin, contrairement
 * à UiInputPollerModern (GLFW) côté 1.21+.
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class GlobalUiRenderMixin189 {

    private static final String CLS_MC = "ave"; // net.minecraft.client.MinecraftClient

    private static UiInputPoller inputPoller;

    @Inject(method = "render(FJ)V", at = @At("TAIL"))
    private void la$onRenderTail(CallbackInfo ci) {
        try {
            Object mc = getMcInstance();
            if (mc == null) return;
            Object currentScreen = getCurrentScreen(mc);
            if (!(currentScreen instanceof UiDrawable ui)) return;

            if (inputPoller == null) {
                inputPoller = new UiInputPollerLegacy(GlobalUiRenderMixin189.class.getClassLoader());
            }
            inputPoller.poll();
            ui.uiPollInput(inputPoller);
            ui.uiDraw(inputPoller.mouseX, inputPoller.mouseY);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin189: " + t);
        }
    }

    private static Object mcInstanceCache;

    private static Object getMcInstance() throws Exception {
        if (mcInstanceCache != null) return mcInstanceCache;
        ClassLoader cl = GlobalUiRenderMixin189.class.getClassLoader();
        String mcClassName = MappingsRegistry.runtimeClass(CLS_MC);
        Class<?> mcClass = Class.forName(mcClassName, true, cl);
        java.util.Set<String> names = MappingsRegistry.runtimeMethodNames(CLS_MC, "A"); // getInstance()
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
        String fieldName = MappingsRegistry.runtimeField(CLS_MC, "m"); // currentScreen
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
