package com.yuyuframe.launcheragent.runtime.mapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pont réflexion partagé par les modules (voir runtime.module :
 * FpsModule/PingModule/CoordsModule/ArmorDurabilityModule/KeystrokesModule/
 * PotionEffectsModule/FovModule/LowHealthTintModule) — s'appuie sur l'API
 * haut niveau de {@link MappingsRegistry} (loadClass/getObfFieldName/
 * getObfMethodName, qui prend directement des noms Yarn lisibles) plutôt que
 * le pattern bas niveau runtimeClass/runtimeMethodNames utilisé par les
 * Mixin globaux : pas besoin ici de résoudre un refmap, juste d'appeler
 * depuis du code non-tissé à chaque frame — d'où le cache Method/Field
 * statique.
 *
 * Vit dans {@code runtime.mapping} (pas {@code runtime.ui.hud}) : ce n'est PAS
 * un composant du HUD, juste un utilitaire de réflexion générique vers le
 * jeu — le placer dans le package HUD aurait mélangé "API du moteur HUD" et
 * "outil de réflexion générique", contribuant au désordre qui a motivé cette
 * réorganisation.
 */
public final class McReflect {
    private McReflect() {}

    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Class<?>> RAW_CLASS_CACHE = new ConcurrentHashMap<>();
    private static volatile Object mcInstance;

    public static Object minecraftClient() {
        if (mcInstance != null) return mcInstance;
        try {
            Class<?> mcClass = MappingsRegistry.loadClass("net/minecraft/client/MinecraftClient");
            Method getInstance = resolveNoArg(mcClass, "net/minecraft/client/MinecraftClient", "getInstance");
            if (getInstance != null) mcInstance = getInstance.invoke(null);
        } catch (Throwable ignored) {}
        return mcInstance;
    }

    /** Charge une classe du jeu par son nom Yarn (ex: pour accéder à un champ STATIC sans passer par une instance) — {@code null} si non résolue. */
    public static Class<?> yarnClass(String yarnClass) {
        try {
            return MappingsRegistry.loadClass(yarnClass);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Classe NON obfusquée (ex: {@code org.lwjgl.input.Keyboard}) — jamais
     * traduite par Yarn, mais toujours chargée via le classloader du jeu
     * (notre agent compile contre des stubs, pas le vrai jar LWJGL/Minecraft,
     * voir UiInputPollerLegacy pour le même besoin côté input).
     */
    public static Class<?> rawClass(String binaryName) {
        return RAW_CLASS_CACHE.computeIfAbsent(binaryName, k -> {
            try {
                ClassLoader ctx = Thread.currentThread().getContextClassLoader();
                if (ctx != null) {
                    try { return Class.forName(binaryName, false, ctx); } catch (ClassNotFoundException ignored) {}
                }
                return Class.forName(binaryName);
            } catch (Throwable t) {
                return null;
            }
        });
    }

    /** Méthode PUBLIQUE d'une classe non obfusquée (ex: LWJGL) — nom réel, aucune traduction Yarn. */
    public static Method rawMethod(Class<?> owner, String methodName, Class<?>... paramTypes) {
        if (owner == null) return null;
        String key = owner.getName() + "#" + methodName + java.util.Arrays.toString(paramTypes);
        return METHOD_CACHE.computeIfAbsent(key, k -> {
            try {
                Method m = owner.getMethod(methodName, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                return null;
            }
        });
    }

    /** Champ (cherché dans toute la hiérarchie), résolu + mis en cache par (classe réelle, nom Yarn). */
    public static Field field(Class<?> owner, String yarnClass, String yarnField) {
        String key = owner.getName() + "#" + yarnField;
        return FIELD_CACHE.computeIfAbsent(key, k -> {
            String obfName = MappingsRegistry.getObfFieldName(yarnClass, yarnField);
            Class<?> c = owner;
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(obfName);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            return null;
        });
    }

    /** Méthode sans paramètre — cas le plus fréquent des getters portés depuis PvP-Mod. */
    public static Method noArgMethod(Class<?> owner, String yarnClass, String yarnMethod) {
        return resolveNoArg(owner, yarnClass, yarnMethod);
    }

    private static Method resolveNoArg(Class<?> owner, String yarnClass, String yarnMethod) {
        String key = owner.getName() + "#" + yarnMethod + "()";
        return METHOD_CACHE.computeIfAbsent(key, k -> {
            String obfName = MappingsRegistry.getObfMethodName(yarnClass, yarnMethod);
            Class<?> c = owner;
            while (c != null) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(obfName) && m.getParameterCount() == 0) {
                        m.setAccessible(true);
                        return m;
                    }
                }
                c = c.getSuperclass();
            }
            return null;
        });
    }

    /**
     * Méthode à UN paramètre, désambiguïsée par type exact — nécessaire pour
     * ClientPlayNetworkHandler.getPlayerListEntry, qui a DEUX surcharges
     * Yarn (UUID et String) partageant la même entrée dans
     * YarnMappings.methodsByNamed (une seule gardée par nom Yarn — non-
     * problème ici car les deux surcharges partagent de toute façon la même
     * lettre officielle "a", vérifié dans mappings-1.8.9.tiny) ; le filtre
     * par type reste nécessaire pour choisir le bon java.lang.reflect.Method
     * au moment de l'appel.
     */
    public static Method oneArgMethod(Class<?> owner, String yarnClass, String yarnMethod, Class<?> paramType) {
        return method(owner, yarnClass, yarnMethod, paramType);
    }

    /**
     * Méthode à N paramètres (N ≥ 0), désambiguïsée par type exact —
     * généralisation de {@link #oneArgMethod}/{@link #noArgMethod} pour les
     * méthodes à plusieurs arguments (ex: ItemRenderer.renderInGuiWithOverrides,
     * qui prend un ItemStack + 2 int).
     */
    public static Method method(Class<?> owner, String yarnClass, String yarnMethod, Class<?>... paramTypes) {
        String key = owner.getName() + "#" + yarnMethod + "(" + java.util.Arrays.toString(paramTypes) + ")";
        return METHOD_CACHE.computeIfAbsent(key, k -> {
            String obfName = MappingsRegistry.getObfMethodName(yarnClass, yarnMethod);
            Class<?> c = owner;
            while (c != null) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(obfName) || m.getParameterCount() != paramTypes.length) continue;
                    Class<?>[] actual = m.getParameterTypes();
                    boolean match = true;
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (!actual[i].isAssignableFrom(paramTypes[i])) { match = false; break; }
                    }
                    if (match) {
                        m.setAccessible(true);
                        return m;
                    }
                }
                c = c.getSuperclass();
            }
            return null;
        });
    }
}
