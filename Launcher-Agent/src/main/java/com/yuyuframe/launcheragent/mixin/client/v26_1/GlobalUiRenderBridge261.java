package com.yuyuframe.launcheragent.mixin.client.v26_1;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPoller;

/**
 * Équivalent de {@code GlobalUiRenderBridge} (bracket 1.21.11) pour le
 * bracket 26.1+ — Minecraft N'EST PLUS OBFUSQUÉ depuis cette version (voir
 * javadoc de tête de {@code VersionProfileRegistry}/{@code LauncherMixinService}) :
 * contrairement à l'original, PAS de résolution via
 * {@code MappingsRegistry.runtimeXxx()} (inutile : il n'y a plus
 * d'obfuscation à traduire, "official" et "runtime" sont désormais le MÊME
 * nom réel Mojang) — réflexion DIRECTE contre les vrais noms, chacun vérifié
 * via {@code javap} sur le jar client 26.1.2 réel (pas deviné) :
 * <pre>
 *   Minecraft.getInstance()          — remplace le nom obfusqué 1.21.11 "V"
 *   Minecraft.setScreen(Screen)      — nom déjà identique en 1.21.11 ET en réel
 *   Minecraft.window                 — champ, nom déjà identique
 *   Minecraft.screen                 — champ RENOMMÉ (Yarn l'appelait "currentScreen")
 *   Minecraft.getMainRenderTarget()  — remplace getFramebuffer() ; "Framebuffer"
 *                                       devient "RenderTarget" sur tout 26.1+
 *   Window.handle()                  — remplace getHandle()
 * </pre>
 * Ces classes ne sont PAS sur le classpath de COMPILATION (comme partout
 * ailleurs dans ce projet — seuls des stubs existent, voir build.bat) —
 * réflexion pure malgré l'absence d'obfuscation à l'exécution, exactement
 * comme l'original (le jar client n'est jamais ajouté au classpath javac,
 * seulement présent au runtime).
 *
 * Vérifié en jeu (26.1.2) — menu/HUD custom fonctionnels.
 */
public final class GlobalUiRenderBridge261 {
    private GlobalUiRenderBridge261() {}

    private static final String CLS_MC = "net.minecraft.client.Minecraft";

    public static volatile UiInputPoller inputPoller;

    private static volatile Object mcInstanceCache;
    private static volatile java.lang.reflect.Method getInstanceMethod;
    private static volatile java.lang.reflect.Method setScreenMethod;
    private static volatile java.lang.reflect.Method getMainRenderTargetMethod;

    public static Object getMcInstance() throws Exception {
        if (mcInstanceCache != null) return mcInstanceCache;
        if (getInstanceMethod == null) {
            ClassLoader cl = GlobalUiRenderBridge261.class.getClassLoader();
            Class<?> mcClass = Class.forName(CLS_MC, true, cl);
            getInstanceMethod = mcClass.getDeclaredMethod("getInstance");
            getInstanceMethod.setAccessible(true);
        }
        mcInstanceCache = getInstanceMethod.invoke(null);
        return mcInstanceCache;
    }

    public static void setScreen(Object mc, Object screen) throws Exception {
        if (setScreenMethod == null) {
            for (java.lang.reflect.Method m : mc.getClass().getMethods()) {
                if ("setScreen".equals(m.getName()) && m.getParameterCount() == 1
                        && !m.getParameterTypes()[0].isPrimitive()) {
                    setScreenMethod = m;
                    break;
                }
            }
        }
        if (setScreenMethod == null) {
            LauncherLog.warn("[LauncherAgent] GlobalUiRenderBridge261: setScreen introuvable");
            return;
        }
        setScreenMethod.invoke(mc, screen);
    }

    /** Ferme l'écran (setScreen(null)) — méthode séparée, voir javadoc de l'original (closingScreenType inutile ici, setScreen(Screen) est la SEULE surcharge à ce nom, pas d'ambiguïté à lever). */
    public static void closeScreen(Object mc, Class<?> closingScreenType) throws Exception {
        setScreen(mc, null);
    }

    public static long getWindowHandle(Object mc) throws Exception {
        Object window = getField(mc, "window");
        if (window == null) return 0L;
        java.lang.reflect.Method m = window.getClass().getMethod("handle");
        return (long) m.invoke(window);
    }

    private static Object getField(Object obj, String fieldName) {
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
        return getField(mc, "screen");
    }

    /**
     * BUG SIGNALÉ PAR L'UTILISATEUR : des mods d'édition façon Blender (ex:
     * Axiom) affichent leur propre éditeur en OVERLAY par-dessus le jeu SANS
     * passer par {@code Minecraft.setScreen()} (donc {@code currentScreen}
     * reste {@code null} pendant que cet éditeur est ouvert) — notre menu
     * s'ouvrait quand même par-dessus à l'appui de sa touche, aucun moyen de
     * l'empêcher avec la seule vérification {@code currentScreen == null}.
     * Demande explicite : une solution GÉNÉRIQUE, pas spécifique à Axiom —
     * n'importe quel mod utilisant ce genre d'éditeur en overlay doit
     * libérer la souris (curseur visible, cliquable) pour que son interface
     * fonctionne, exactement comme un vrai Screen vanilla le ferait. {@code
     * Minecraft.mouseHandler.isMouseGrabbed()} (vérifié par javap sur le jar
     * client 26.1.2 réel — {@code public boolean isMouseGrabbed()}) est donc
     * un signal fiable et agnostique du mod : en gameplay normal (aucune UI
     * ouverte, ni la nôtre ni celle d'un autre mod), la souris est TOUJOURS
     * grabbed (verrouillée, invisible, pilote la caméra) ; dès qu'UNE
     * interface la libère pour permettre des clics, ce signal en informe —
     * qu'il s'agisse d'un vrai Screen ou d'un overlay custom comme Axiom.
     */
    public static boolean isMouseGrabbed(Object mc) {
        try {
            Object mouseHandler = getField(mc, "mouseHandler");
            if (mouseHandler == null) return true; // repli permissif : comportement inchangé si introuvable
            java.lang.reflect.Method m = mouseHandler.getClass().getMethod("isMouseGrabbed");
            return (boolean) m.invoke(mouseHandler);
        } catch (Throwable t) {
            return true; // repli permissif — voir ci-dessus
        }
    }

    public static Object getMainFramebuffer(Object mc) throws Exception {
        if (getMainRenderTargetMethod == null) {
            getMainRenderTargetMethod = mc.getClass().getMethod("getMainRenderTarget");
        }
        return getMainRenderTargetMethod.invoke(mc);
    }
}
