package com.yuyuframe.launcheragent.apimixin.v1_21_11.core;

import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apimixin.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

/**
 * État partagé entre {@link GlobalUiRenderMixin1211} (logique) et
 * {@link GlobalUiPresentMixin1211} (dessin) — portage apimixin de
 * {@code mixin.client.GlobalUiRenderBridge}, voir ce fichier pour l'historique
 * complet (bug du blit Blaze3D, {@code IllegalAccessError} des membres non
 * publics appelés depuis du code fusionné).
 *
 * <h2>⚠️ Encore de la réflexion — transitoire, et assumé</h2>
 *
 * Contrairement à {@code GlobalUiRenderBridge261}, ce pont résout Minecraft,
 * la fenêtre, l'écran courant et le framebuffer par RÉFLEXION sur des noms
 * officiels ({@code "gfj"}, {@code "fyk"}), traduits vers le schéma actif par
 * {@link MappingsRegistry}. C'est contraire à la règle du projet (accès au jeu
 * par accessors, jamais par réflexion) — mais c'est le code qui fonctionnait
 * en jeu avant le gel de la tranche, déplacé sans être réécrit.
 *
 * <p>Le déplacer d'abord, le remplacer ensuite : c'est l'étape « accessors
 * 1.21.11 » du portage, où ces quatre accès deviendront des {@code AccessPoint}
 * liés par {@code AccessorBindings1211}. Les réécrire maintenant aurait mêlé
 * deux inconnues dans un même test en jeu — le dégel de la tranche, et la
 * première série d'{@code @Accessor} sur des classes obfusquées.
 */
public final class GlobalUiRenderBridge1211 {
    private GlobalUiRenderBridge1211() {}

    private static final String CLS_MC = "gfj"; // net.minecraft.client.MinecraftClient
    private static final String CLS_WINDOW = "fyk"; // net.minecraft.client.util.Window

    public static volatile UiInputPoller inputPoller;

    private static volatile Object mcInstanceCache;

    public static Object getMcInstance() throws Exception {
        if (mcInstanceCache != null) return mcInstanceCache;
        ClassLoader cl = GlobalUiRenderBridge1211.class.getClassLoader();
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

    public static void setScreen(Object mc, Object screen) throws Exception {
        java.util.Set<String> names = MappingsRegistry.runtimeMethodNames(CLS_MC, "a"); // setScreen(Screen)
        for (java.lang.reflect.Method m : mc.getClass().getMethods()) {
            if (names.contains(m.getName()) && m.getParameterCount() == 1
                    && !m.getParameterTypes()[0].isPrimitive()
                    && m.getParameterTypes()[0].isInstance(screen)) {
                m.invoke(mc, screen);
                return;
            }
        }
        LauncherLog.warn("[LauncherAgent] GlobalUiRenderBridge1211: setScreen introuvable");
    }

    /**
     * Ferme l'écran (setScreen(null)) — méthode séparée de setScreen() car
     * Method.getParameterTypes()[0].isInstance(null) vaut TOUJOURS false (donc
     * inutilisable pour retrouver le bon overload quand screen==null) : on
     * matche ici par assignabilité depuis la classe de l'écran qu'on ferme.
     */
    public static void closeScreen(Object mc, Class<?> closingScreenType) throws Exception {
        java.util.Set<String> names = MappingsRegistry.runtimeMethodNames(CLS_MC, "a");
        for (java.lang.reflect.Method m : mc.getClass().getMethods()) {
            if (names.contains(m.getName()) && m.getParameterCount() == 1
                    && !m.getParameterTypes()[0].isPrimitive()
                    && m.getParameterTypes()[0].isAssignableFrom(closingScreenType)) {
                m.invoke(mc, (Object) null);
                return;
            }
        }
        LauncherLog.warn("[LauncherAgent] GlobalUiRenderBridge1211: closeScreen: setScreen introuvable");
    }

    public static long getWindowHandle(Object mc) throws Exception {
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
                LauncherLog.err("[LauncherAgent] GlobalUiRenderBridge1211: accès refusé à "
                    + officialOwner + "." + officialField + " : " + e);
                return null;
            }
        }
        return null;
    }

    public static Object getCurrentScreen(Object mc) throws Exception {
        return getField(mc, CLS_MC, "x"); // MinecraftClient.currentScreen
    }

    /**
     * MinecraftClient.getFramebuffer() (obf "l", unique sur cette classe dans
     * les mappings 1.21.11) — le framebuffer PRINCIPAL, pour la garde de
     * {@link GlobalUiPresentMixin1211} : {@code blitToScreen()} pourrait en
     * théorie être appelée sur une autre instance (rendu hors écran d'un mod).
     */
    private static volatile java.lang.reflect.Method getFramebufferMethod;

    public static Object getMainFramebuffer(Object mc) throws Exception {
        if (getFramebufferMethod == null) {
            java.util.Set<String> names = MappingsRegistry.runtimeMethodNames(CLS_MC, "l");
            for (java.lang.reflect.Method m : mc.getClass().getMethods()) {
                if (names.contains(m.getName()) && m.getParameterCount() == 0) {
                    getFramebufferMethod = m;
                    break;
                }
            }
            if (getFramebufferMethod == null) return null;
        }
        return getFramebufferMethod.invoke(mc);
    }
}
