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

    private static boolean minecraftClientErrorLogged;

    public static Object minecraftClient() {
        if (mcInstance != null) return mcInstance;
        try {
            // BUG TROUVÉ (26.1+) : MappingsRegistry.loadClass() ne retombe
            // JAMAIS sur le vrai nom Mojang quand Yarn n'est pas chargé — il
            // essaie juste le nom Yarn "net/minecraft/client/MinecraftClient"
            // TEL QUEL comme s'il s'agissait déjà du nom réel (fonctionne pour
            // les brackets obfusqués où le nom obf est bien ce qu'on
            // reconstruit, mais PAS pour 26.1+ où la classe a été RENOMMÉE en
            // "net.minecraft.client.Minecraft", vérifié par javap). Ce catch
            // avalait l'échec SANS LOGGER — mcInstance restait null pour
            // TOUJOURS, faisant échouer silencieusement drawText/drawRect/
            // drawIcon (UiTextBlaze3D) ET tous les modules HUD listés dans la
            // javadoc de cette classe (d'où leurs placeholders "--" jamais
            // remplacés par de vraies valeurs).
            Class<?> mcClass;
            try {
                mcClass = MappingsRegistry.loadClass("net/minecraft/client/MinecraftClient");
            } catch (ClassNotFoundException notFound) {
                mcClass = Class.forName("net.minecraft.client.Minecraft", false,
                    Thread.currentThread().getContextClassLoader());
            }
            Method getInstance = resolveNoArg(mcClass, "net/minecraft/client/MinecraftClient", "getInstance");
            if (getInstance != null) mcInstance = getInstance.invoke(null);
        } catch (Throwable t) {
            if (!minecraftClientErrorLogged) {
                minecraftClientErrorLogged = true;
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                com.yuyuframe.launcheragent.runtime.log.LauncherLog.err(
                    "[LauncherAgent] McReflect.minecraftClient(): échec : " + t + " | cause réelle : " + cause);
            }
        }
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

    /**
     * BUG TROUVÉ (1.20.4, audit modules — yaw PUIS world, même classe
     * intermédiaire fautive à chaque fois) : {@link #field} remonte la
     * hiérarchie de classes RÉELLE de l'instance passée et s'arrête au
     * PREMIER champ portant le nom obfusqué recherché — si une classe
     * INTERMÉDIAIRE de cette hiérarchie (ex: {@code bml}, entre
     * ClientPlayerEntity et Entity) définit SA PROPRE variable totalement
     * différente sous le MÊME nom obfusqué court (coïncidence, ex: "aG"/"t"),
     * elle est trouvée EN PREMIER au lieu du vrai champ déclaré plus haut
     * (ex: sur Entity) — silencieux tant qu'on ne regarde pas le type réel
     * du champ trouvé. {@code hasFieldMapping} ne protège PAS contre ce cas
     * (le mapping recherché EST authentique, ce n'est pas un repli hasardeux
     * vers un nom inchangé).
     *
     * Cette méthode résout le {@code Field} DIRECTEMENT sur la classe qui le
     * DÉCLARE VRAIMENT (donnée en paramètre, ex: "net/minecraft/entity/Entity"),
     * jamais en remontant depuis la classe runtime d'une instance quelconque —
     * élimine structurellement le risque de collision. {@code Field.get(Object)}
     * reste valide même appelé sur une SOUS-classe de la classe déclarante.
     */
    public static Field fieldOnClass(String yarnDeclaringClass, String yarnField) {
        String key = "decl:" + yarnDeclaringClass + "#" + yarnField;
        return FIELD_CACHE.computeIfAbsent(key, k -> {
            if (!MappingsRegistry.hasFieldMapping(yarnDeclaringClass, yarnField)) return null;
            try {
                Class<?> owner = yarnClass(yarnDeclaringClass);
                if (owner == null) return null;
                String obfName = MappingsRegistry.getObfFieldName(yarnDeclaringClass, yarnField);
                Field f = owner.getDeclaredField(obfName);
                f.setAccessible(true);
                return f;
            } catch (Throwable t) {
                return null;
            }
        });
    }

    /**
     * BUG TROUVÉ (1.20.4, MumbleLinkModule — même risque que {@link #fieldOnClass}
     * mais pour une MÉTHODE) : {@code MumbleLinkModule} appelait {@code
     * noArgMethod(player.getClass(), "net/minecraft/entity/Entity",
     * "getEyeHeight")} en supposant un no-arg — en réalité, {@code
     * Entity.getEyeHeight} n'a QUE des surcharges à paramètres en 1.20.4
     * ({@code (EntityPose,EntityDimensions):float} et
     * {@code (EntityPose):float}, vérifié dans les mappings), aucune version
     * sans argument. Le filtre "0 paramètre" de {@code noArgMethod} en
     * remontant la hiérarchie du joueur tombait donc sur une méthode
     * SANS RAPPORT (nom obfusqué coïncident sur une classe intermédiaire)
     * renvoyant un {@code PlayerListEntry} — {@code ClassCastException:
     * class fob cannot be cast to class java.lang.Float} en résultait.
     * Résout ici directement sur la classe qui déclare vraiment la méthode
     * (jamais en remontant depuis une instance), symétrique de {@link
     * #fieldOnClass} pour les champs.
     */
    public static Method methodOnClass(String yarnDeclaringClass, String yarnMethod, Class<?>... paramTypes) {
        String key = "decl:" + yarnDeclaringClass + "#" + yarnMethod + "(" + java.util.Arrays.toString(paramTypes) + ")";
        return METHOD_CACHE.computeIfAbsent(key, k -> {
            try {
                Class<?> owner = yarnClass(yarnDeclaringClass);
                if (owner == null) return null;
                String obfName = MappingsRegistry.getObfMethodName(yarnDeclaringClass, yarnMethod);
                for (Method m : owner.getDeclaredMethods()) {
                    if (!m.getName().equals(obfName) || m.getParameterCount() != paramTypes.length) continue;
                    Class<?>[] actual = m.getParameterTypes();
                    boolean match = true;
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (!actual[i].getName().equals(paramTypes[i].getName())) { match = false; break; }
                    }
                    if (match) {
                        m.setAccessible(true);
                        return m;
                    }
                }
                return null;
            } catch (Throwable t) {
                return null;
            }
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

    private static Method cachedSimpleOptionGetValue;
    private static Field cachedSimpleOptionValueField;

    /**
     * BUG TROUVÉ (1.20.4, audit modules) : plusieurs champs {@code GameOptions}
     * lus comme primitifs directs par les modules (fov, gamma...) sont devenus
     * des objets {@code SimpleOption} (final, jamais un float/double brut)
     * depuis la refonte "SimpleOption" (~1.19-1.20, tous les réglages du menu
     * Options unifiés sous un objet générique avec getValue()/setValue(Object)
     * — voir {@code net.minecraft.client.option.SimpleOption}). Lire/écrire en
     * supposant un champ primitif lève {@code IllegalArgumentException}
     * ("illegal data type conversion" / "Can not set final field"), avalée
     * silencieusement selon les appelants. Un module doit détecter le VRAI
     * type du champ ({@code Field.getType()}) et, si ce n'est ni {@code float}
     * ni {@code double}, passer par ces deux méthodes au lieu d'accéder au
     * champ directement.
     */
    public static double simpleOptionGetValue(Object simpleOption) throws Exception {
        if (cachedSimpleOptionGetValue == null) {
            cachedSimpleOptionGetValue = noArgMethod(simpleOption.getClass(),
                "net/minecraft/client/option/SimpleOption", "getValue");
        }
        return ((Number) cachedSimpleOptionGetValue.invoke(simpleOption)).doubleValue();
    }

    /**
     * BUG TROUVÉ #2 (test utilisateur, suite au fix #1 ci-dessus) : passer par
     * {@code setValue(Object)} (méthode publique) déclenche la VALIDATION de
     * l'option (voir javadoc de SimpleOption : "Option values are
     * automatically validated... some validators will coerce the invalid
     * value... in this case the new value is used", d'autres RESTAURENT la
     * valeur par défaut) — observé concrètement : {@code gamma=1000} rejeté
     * ("Illegal option value 1000.0 for translation... options.gamma",
     * fullbright inopérant, valeur silencieusement remise au défaut) et
     * {@code fov} en zoom visiblement CLAMPÉ à la borne minimale vanilla
     * (zoom "pas assez gros" — la valeur demandée, en dessous du minimum
     * vanilla, est coercée au lieu d'être appliquée telle quelle). Exactement
     * le comportement qu'on veut ÉVITER (l'astuce "valeur hors bornes"
     * dépend justement de l'ABSENCE de validation, comme l'ancien champ
     * primitif brut n'en avait aucune). Fix : écrire DIRECTEMENT dans le
     * champ interne non-final {@code value} de SimpleOption (vérifié via
     * javap sur le vrai jar 1.20.4 : {@code T k;}, package-private mais PAS
     * final, contrairement au champ fov/gamma sur GameOptions lui-même) —
     * contourne complètement le validateur de {@code setValue()}.
     */
    public static void simpleOptionSetValue(Object simpleOption, double value) throws Exception {
        if (cachedSimpleOptionGetValue == null) {
            cachedSimpleOptionGetValue = noArgMethod(simpleOption.getClass(),
                "net/minecraft/client/option/SimpleOption", "getValue");
        }
        if (cachedSimpleOptionValueField == null) {
            cachedSimpleOptionValueField = field(simpleOption.getClass(),
                "net/minecraft/client/option/SimpleOption", "value");
        }
        Object current = cachedSimpleOptionGetValue.invoke(simpleOption);
        Object boxed = current instanceof Integer ? (Object) Integer.valueOf((int) Math.round(value))
            : current instanceof Float ? (Object) Float.valueOf((float) value)
            : (Object) Double.valueOf(value);
        cachedSimpleOptionValueField.set(simpleOption, boxed);
    }
}
