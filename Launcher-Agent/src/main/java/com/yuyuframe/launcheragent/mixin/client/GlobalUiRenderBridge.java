package com.yuyuframe.launcheragent.mixin.client;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPoller;

/**
 * Bridge partagé entre {@link GlobalUiRenderMixin} (hook GameRenderer.render,
 * logique : input/tick/ouverture du menu) et {@link GlobalUiPresentMixin}
 * (hook Framebuffer.blitToScreen, dessin réel) — même patron que
 * ScreenBridge1204/1214 (brackets C/D) pour partager un état entre deux
 * Mixins ciblant des classes différentes.
 *
 * BUG TROUVÉ (era E, 1.21.11 — "rien ne s'affiche jamais") : dessiner en GL
 * brut à la TAIL de GameRenderer.render() ne survit pas — MinecraftClient.
 * render(boolean) appelle ensuite Framebuffer.blitToScreen(), qui recopie la
 * cible de rendu Blaze3D interne PAR-DESSUS le framebuffer par défaut,
 * écrasant tout dessin fait avant (confirmé par désassemblage bytecode, voir
 * javadoc de GlobalUiRenderMixin). Le dessin doit donc se faire APRÈS ce blit
 * — mais cibler directement MinecraftClient avec un second @Inject a été
 * tenté puis abandonné : MinecraftClient (net.minecraft.class_310) est
 * chargée trop tôt dans les gros modpacks (avant que notre config Mixin ne
 * soit prête), forçant un retransform à chaud d'une classe déjà chargée —
 * et Mixin doit y ajouter une méthode synthétique (le handler @Inject), ce
 * que le JVM refuse en retransform standard (ClassFormatError observé en
 * jeu). Framebuffer, elle, est instanciée bien plus tard (pendant l'init du
 * renderer) — même timing que GameRenderer, qui a toujours fonctionné.
 *
 * BUG TROUVÉ (v352 test) : {@code IllegalAccessError} au premier appel depuis
 * {@code GlobalUiPresentMixin} — {@code @Inject} FUSIONNE le bytecode du
 * handler DANS la classe cible (ici {@code net.minecraft.client.gl.Framebuffer},
 * un package totalement différent du nôtre) ; le contrôle d'accès Java est
 * alors vérifié depuis CE package cible, pas depuis notre package d'origine.
 * Une classe non-publique (package-private) référencée depuis ce code fusionné
 * devient donc inaccessible à l'exécution, même si tout compile normalement
 * (le compilateur, lui, voit encore le vrai package d'origine). D'où : cette
 * classe et tous ses membres appelés depuis les deux Mixins sont {@code public}
 * — pas juste package-private comme le ferait une classe utilitaire "normale".
 */
public final class GlobalUiRenderBridge {
    private GlobalUiRenderBridge() {}

    private static final String CLS_MC = "gfj"; // net.minecraft.client.MinecraftClient
    private static final String CLS_WINDOW = "fyk"; // net.minecraft.client.util.Window

    public static volatile UiInputPoller inputPoller;
    public static final java.util.Set<Class<?>> DIAG_LOGGED_CLASSES =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static volatile Object mcInstanceCache;

    public static Object getMcInstance() throws Exception {
        if (mcInstanceCache != null) return mcInstanceCache;
        ClassLoader cl = GlobalUiRenderBridge.class.getClassLoader();
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
        LauncherLog.warn("[LauncherAgent] GlobalUiRenderBridge: setScreen introuvable");
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
        LauncherLog.warn("[LauncherAgent] GlobalUiRenderBridge: closeScreen: setScreen introuvable");
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
                return null;
            }
        }
        return null;
    }

    public static Object getCurrentScreen(Object mc) throws Exception {
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

    /**
     * MinecraftClient.getFramebuffer() (obf "l", confirmé UNIQUE sur cette
     * classe dans les mappings 1.21.11 — pas d'ambiguïté de surcharge) — le
     * Framebuffer principal (fenêtre), pour la garde de
     * {@link GlobalUiPresentMixin} : Framebuffer.blitToScreen() pourrait en
     * théorie être appelée sur d'autres instances que le framebuffer
     * principal (aucun cas connu à ce jour, mais son nom Yarn générique et
     * l'absence de garantie contraire justifient la vérification).
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
