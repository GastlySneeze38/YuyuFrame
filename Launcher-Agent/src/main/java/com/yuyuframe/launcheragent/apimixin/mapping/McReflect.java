package com.yuyuframe.launcheragent.apimixin.mapping;

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

        // ⚠️ NE JAMAIS forcer <clinit> de Minecraft hors du Render thread
        // (2026-08-25, §12). Plus bas, getInstance.invoke(null) est un appel de
        // méthode STATIQUE : il déclenche l'initialisation de la classe. Appelé
        // trop tôt — typiquement depuis notre thread "YuyuFrame-ConfigSave",
        // 2,5 s après le lancement — Minecraft.<clinit> s'exécute avant que le
        // jeu ne soit prêt, échoue en ExceptionInInitializerError, et la classe
        // devient DÉFINITIVEMENT inutilisable pour toute la JVM : plus aucun
        // démarrage possible, sans message côté Fabric.
        //
        // Preuve par les logs : dans les runs sains l'erreur apparaissait à
        // +94 s / +222 s (à la fermeture, donc sans conséquence) ; dans le run
        // cassé, à +2,5 s, et le jeu ne démarrait plus.
        //
        // Le Render thread est le seul où Minecraft est garanti déjà
        // initialisé quand notre code s'exécute. Ailleurs on renvoie null — ce
        // que les appelants savent déjà gérer (c'était déjà le résultat en
        // pratique, l'échec étant simplement journalisé) — et le cache
        // mcInstance, une fois peuplé par le Render thread, reste lisible par
        // tous les threads.
        if (!"Render thread".equals(Thread.currentThread().getName())) return null;

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
                com.yuyuframe.launcheragent.base.log.LauncherLog.err(
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
     * Comme {@link #yarnClass(String)} mais avec repli sur un nom RÉEL Mojang
     * explicite si la résolution Yarn échoue — nécessaire sur 26.1+ (Yarn
     * jamais chargé, {@link MappingsRegistry#loadClass} essaie le nom Yarn TEL
     * QUEL comme s'il s'agissait déjà du nom réel, ce qui échoue silencieusement
     * dès qu'une classe a été RENOMMÉE, ex: {@code GameOptions}→{@code Options},
     * {@code DynamicRegistryManager}→{@code RegistryAccess} — voir historique
     * de session, même piège déjà rencontré et corrigé pour {@code
     * McReflect.minecraftClient()}).
     */
    public static Class<?> yarnClass(String yarnClass, String realNameFallback) {
        Class<?> c = yarnClass(yarnClass);
        if (c != null) return c;
        try {
            return Class.forName(realNameFallback, false, Thread.currentThread().getContextClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Classe NON obfusquée (ex: {@code org.lwjgl.input.Keyboard}) — jamais
     * traduite par Yarn, mais toujours chargée via le classloader du jeu
     * (notre agent compile contre des stubs, pas le vrai jar LWJGL/Minecraft).
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
        return FIELD_CACHE.computeIfAbsent(key, k -> findFieldInHierarchy(owner, MappingsRegistry.getObfFieldName(yarnClass, yarnField)));
    }

    /**
     * Comme {@link #field(Class, String, String)} mais avec repli sur un nom
     * de champ RÉEL Mojang si la recherche par nom Yarn (inchangé sur 26.1+,
     * Yarn jamais chargé) ne trouve rien dans la hiérarchie — ex: {@code
     * KeyBinding.pressed}→{@code KeyMapping.isDown}, {@code
     * KeyBinding.boundKey}→{@code KeyMapping.key}, vérifiés par javap sur le
     * jar client 26.1.2 réel. Sur les brackets antérieurs (Yarn chargé), le
     * premier essai trouve toujours le bon champ — le repli n'est jamais
     * atteint, sans conséquence.
     */
    public static Field field(Class<?> owner, String yarnClass, String yarnField, String realFieldFallback) {
        String key = owner.getName() + "#" + yarnField + "|" + realFieldFallback;
        return FIELD_CACHE.computeIfAbsent(key, k -> {
            Field f = findFieldInHierarchy(owner, MappingsRegistry.getObfFieldName(yarnClass, yarnField));
            return f != null ? f : findFieldInHierarchy(owner, realFieldFallback);
        });
    }

    private static Field findFieldInHierarchy(Class<?> owner, String fieldName) {
        Class<?> c = owner;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
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
            // BUG TROUVÉ (26.1+) : hasFieldMapping() renvoie isLoaded() && ... —
            // TOUJOURS false quand Yarn n'est pas chargé (bracket 26.1+, voir
            // historique de session), donc cette méthode retournait TOUJOURS
            // null sur ce bracket, indépendamment de tout le reste (même si le
            // nom Yarn était par ailleurs le bon nom réel). Le garde ne doit
            // s'appliquer QUE quand Yarn est effectivement chargé — sur un
            // bracket non mappé, on tente directement la résolution.
            if (MappingsRegistry.isLoaded() && !MappingsRegistry.hasFieldMapping(yarnDeclaringClass, yarnField)) return null;
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
     * Comme {@link #fieldOnClass(String, String)} mais avec repli sur un nom
     * de classe déclarante ET un nom de champ RÉELS Mojang si la résolution
     * Yarn échoue (26.1+) — ex: {@code Entity.world}→{@code Entity.level},
     * vérifié par javap sur le jar client 26.1.2 réel.
     */
    public static Field fieldOnClass(String yarnDeclaringClass, String realDeclaringClassFallback, String yarnField, String realFieldFallback) {
        String key = "decl:" + yarnDeclaringClass + "#" + yarnField + "|" + realDeclaringClassFallback + "#" + realFieldFallback;
        return FIELD_CACHE.computeIfAbsent(key, k -> {
            try {
                Class<?> owner = yarnClass(yarnDeclaringClass, realDeclaringClassFallback);
                if (owner == null) return null;
                Field f = findFieldInHierarchy(owner, MappingsRegistry.getObfFieldName(yarnDeclaringClass, yarnField));
                return f != null ? f : findFieldInHierarchy(owner, realFieldFallback);
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

    /**
     * Comme {@link #noArgMethod(Class, String, String)} mais avec repli sur un
     * nom de méthode RÉEL Mojang si la recherche par nom Yarn ne trouve rien
     * (26.1+, mêmes garanties que {@link #field(Class, String, String, String)}) —
     * ex: {@code getStatusEffectInstances}→{@code getActiveEffects}, {@code
     * getUuid}→{@code getUUID}, vérifiés par javap sur le jar client 26.1.2 réel.
     */
    public static Method noArgMethod(Class<?> owner, String yarnClass, String yarnMethod, String realMethodFallback) {
        String key = owner.getName() + "#" + yarnMethod + "()|" + realMethodFallback;
        return METHOD_CACHE.computeIfAbsent(key, k -> {
            Method m = findNoArgInHierarchy(owner, MappingsRegistry.getObfMethodName(yarnClass, yarnMethod));
            return m != null ? m : findNoArgInHierarchy(owner, realMethodFallback);
        });
    }

    private static Method resolveNoArg(Class<?> owner, String yarnClass, String yarnMethod) {
        String key = owner.getName() + "#" + yarnMethod + "()";
        return METHOD_CACHE.computeIfAbsent(key, k -> findNoArgInHierarchy(owner, MappingsRegistry.getObfMethodName(yarnClass, yarnMethod)));
    }

    private static Method findNoArgInHierarchy(Class<?> owner, String methodName) {
        Class<?> c = owner;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
            Method fromIface = findNoArgInInterfaces(c, methodName);
            if (fromIface != null) return fromIface;
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * Parcourt les interfaces implémentées (récursivement) — indispensable pour
     * les méthodes {@code default} déclarées sur l'interface mais jamais
     * redéclarées par la classe concrète (ex: {@code Component.getString()} sur
     * {@code MutableComponent} en 26.1+, confirmé par javap : {@code
     * getDeclaredMethods()} ne remonte QUE les superclasses, jamais les
     * interfaces, donc une résolution qui s'arrêtait à {@code getSuperclass()}
     * échouait silencieusement — pas d'exception, juste un {@code null} qui
     * faisait avorter l'appelant sans aucun log).
     */
    private static Method findNoArgInInterfaces(Class<?> c, String methodName) {
        for (Class<?> iface : c.getInterfaces()) {
            for (Method m : iface.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
            Method nested = findNoArgInInterfaces(iface, methodName);
            if (nested != null) return nested;
        }
        return null;
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

    /** Comme {@link #oneArgMethod} mais avec repli sur un nom de méthode RÉEL Mojang — voir {@link #method(Class, String, String, String, Class[])}. */
    public static Method oneArgMethod(Class<?> owner, String yarnClass, String yarnMethod, String realMethodFallback, Class<?> paramType) {
        return method(owner, yarnClass, yarnMethod, realMethodFallback, paramType);
    }

    /**
     * Méthode à N paramètres (N ≥ 0), désambiguïsée par type exact —
     * généralisation de {@link #oneArgMethod}/{@link #noArgMethod} pour les
     * méthodes à plusieurs arguments (ex: ItemRenderer.renderInGuiWithOverrides,
     * qui prend un ItemStack + 2 int).
     */
    public static Method method(Class<?> owner, String yarnClass, String yarnMethod, Class<?>... paramTypes) {
        String key = owner.getName() + "#" + yarnMethod + "(" + java.util.Arrays.toString(paramTypes) + ")";
        return METHOD_CACHE.computeIfAbsent(key, k -> findMethodInHierarchy(owner, MappingsRegistry.getObfMethodName(yarnClass, yarnMethod), paramTypes));
    }

    /**
     * Comme {@link #method(Class, String, String, Class[])} mais avec repli sur
     * un nom de méthode RÉEL Mojang si la recherche par nom Yarn ne trouve rien
     * (26.1+) — ex: {@code getStackInHand}→{@code getItemInHand}, {@code
     * getEquippedStack}→{@code getItemBySlot}, vérifiés par javap sur le jar
     * client 26.1.2 réel.
     */
    public static Method method(Class<?> owner, String yarnClass, String yarnMethod, String realMethodFallback, Class<?>... paramTypes) {
        String key = owner.getName() + "#" + yarnMethod + "(" + java.util.Arrays.toString(paramTypes) + ")|" + realMethodFallback;
        return METHOD_CACHE.computeIfAbsent(key, k -> {
            Method m = findMethodInHierarchy(owner, MappingsRegistry.getObfMethodName(yarnClass, yarnMethod), paramTypes);
            return m != null ? m : findMethodInHierarchy(owner, realMethodFallback, paramTypes);
        });
    }

    private static Method findMethodInHierarchy(Class<?> owner, String methodName, Class<?>[] paramTypes) {
        Class<?> c = owner;
        while (c != null) {
            Method direct = findMethodInDeclared(c.getDeclaredMethods(), methodName, paramTypes);
            if (direct != null) return direct;
            Method fromIface = findMethodInInterfaces(c, methodName, paramTypes);
            if (fromIface != null) return fromIface;
            c = c.getSuperclass();
        }
        return null;
    }

    /** Voir {@link #findNoArgInInterfaces} — même piège pour les méthodes à paramètres. */
    private static Method findMethodInInterfaces(Class<?> c, String methodName, Class<?>[] paramTypes) {
        for (Class<?> iface : c.getInterfaces()) {
            Method direct = findMethodInDeclared(iface.getDeclaredMethods(), methodName, paramTypes);
            if (direct != null) return direct;
            Method nested = findMethodInInterfaces(iface, methodName, paramTypes);
            if (nested != null) return nested;
        }
        return null;
    }

    private static Method findMethodInDeclared(Method[] candidates, String methodName, Class<?>[] paramTypes) {
        for (Method m : candidates) {
            if (!m.getName().equals(methodName) || m.getParameterCount() != paramTypes.length) continue;
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
        return null;
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
                "net/minecraft/client/option/SimpleOption", "getValue", "get");
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
                "net/minecraft/client/option/SimpleOption", "getValue", "get");
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
