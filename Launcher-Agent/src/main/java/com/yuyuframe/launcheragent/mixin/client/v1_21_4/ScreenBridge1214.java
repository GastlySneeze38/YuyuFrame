package com.yuyuframe.launcheragent.mixin.client.v1_21_4;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Copie de {@link com.yuyuframe.launcheragent.mixin.client.v1_20_4.ScreenBridge1204}
 * pour le bracket "D" (~1.21-1.21.5, voir VersionBracketRegistry) — même
 * mécanique de résolution dynamique (aucun nom obfusqué figé), donc aucune
 * logique à réécrire ici, seulement le package/nom de classe qui change.
 *
 * Vérifié via mappings/yarn-1.21.4-mergedv2.jar (mappings.tiny) :
 * {@code MinecraftClient.setScreen(Screen)} est nommé Yarn **"setScreen"**
 * ici aussi (comme en 1.20.4, pas "openScreen" comme en 1.16.5) — le repli
 * déjà présent dans {@link #resolveSetScreenMethod} couvre donc ce bracket
 * aussi sans aucun changement de code.
 */
public final class ScreenBridge1214 {

    private ScreenBridge1214() {}

    private static volatile boolean pendingMenuOpen;

    public static void requestMenuOpen() {
        pendingMenuOpen = true;
    }

    public static boolean consumePendingMenuOpen() {
        boolean v = pendingMenuOpen;
        pendingMenuOpen = false;
        return v;
    }

    private static final String YARN_MC = "net/minecraft/client/MinecraftClient";

    public static Object getCurrentScreen(Object mc) {
        return getNamedField(mc, YARN_MC, "currentScreen");
    }

    public static Object getNamedField(Object obj, String yarnOwner, String yarnField) {
        String fieldName = MappingsRegistry.getObfFieldName(yarnOwner, yarnField);
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(fieldName);
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

    private static Class<?> nativeScreenClass(Class<?> screenClass) {
        Class<?> c = screenClass;
        while (c != null && c.getName().startsWith("com.yuyuframe.launcheragent")) {
            c = c.getSuperclass();
        }
        return c;
    }

    private static Method resolveSetScreenMethod(Class<?> mcClass, Class<?> nativeScreen) {
        String officialDesc = "(L" + nativeScreen.getName().replace('.', '/') + ";)V";
        String obfName = MappingsRegistry.getObfMethodName(YARN_MC, "openScreen", officialDesc);
        if ("openScreen".equals(obfName)) {
            obfName = MappingsRegistry.getObfMethodName(YARN_MC, "setScreen", officialDesc);
        }
        try {
            return mcClass.getMethod(obfName, nativeScreen);
        } catch (NoSuchMethodException e) {
            LauncherLog.err("[LauncherAgent] ScreenBridge1214: setScreen introuvable (nom résolu=" + obfName + "): " + e);
            return null;
        }
    }

    private static Method cachedSetScreenMethod;

    public static void setScreen(Object mc, Object screen) throws Exception {
        if (cachedSetScreenMethod == null) {
            Class<?> nativeScreen = nativeScreenClass(screen.getClass());
            cachedSetScreenMethod = resolveSetScreenMethod(mc.getClass(), nativeScreen);
            LauncherLog.info("[LauncherAgent] DIAG-1214: méthode setScreen résolue = " + cachedSetScreenMethod);
        }
        invokeSetScreen(mc, screen);
    }

    public static void closeScreen(Object mc, Class<?> closingScreenType) throws Exception {
        if (cachedSetScreenMethod == null) {
            Class<?> nativeScreen = nativeScreenClass(closingScreenType);
            cachedSetScreenMethod = resolveSetScreenMethod(mc.getClass(), nativeScreen);
        }
        invokeSetScreen(mc, null);
    }

    private static void invokeSetScreen(Object mc, Object screen) throws Exception {
        if (cachedSetScreenMethod == null) {
            LauncherLog.warn("[LauncherAgent] ScreenBridge1214: setScreen introuvable");
            return;
        }
        cachedSetScreenMethod.invoke(mc, screen);
    }
}
