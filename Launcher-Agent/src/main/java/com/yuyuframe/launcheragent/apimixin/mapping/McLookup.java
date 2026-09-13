package com.yuyuframe.launcheragent.apimixin.mapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Recherches réflexives par nom YARN, quand {@link McReflect} ne suffit pas —
 * c'est-à-dire quand le membre doit être trouvé en REMONTANT une hiérarchie de
 * classes, ou choisi parmi plusieurs surcharges par la FORME de ses paramètres.
 *
 * <p>Vit dans la couche {@code mapping} et non dans le moteur graphique parce
 * que c'est son métier : traduire un nom Yarn vers le nom d'exécution du loader
 * actif avant de comparer. Extrait de {@code UiVanillaItemRenderer} lors de son
 * découpage, où ces quatre recherches étaient partagées par deux ères.
 *
 * <h2>Piège que ces méthodes existent pour éviter</h2>
 *
 * Comparer {@code m.getName()} au nom Yarn LITTÉRAL fonctionne par coïncidence
 * sur un bracket non obfusqué (26.1.2) et jamais sur un bracket obfusqué. C'est
 * le bug qui a fait disparaître les icônes d'armure en 1.21.11 : le nom
 * d'exécution de {@code DrawContext.drawItem} y est une simple lettre.
 */
public final class McLookup {

    private McLookup() {}

    /**
     * Méthode statique à UN argument {@code String}, par nom Yarn.
     *
     * <p>Cas d'usage : {@code Identifier.ofVanilla(path)} (Yarn 1.21.11) ==
     * {@code Identifier.withDefaultNamespace(path)} (vrai nom Mojang 26.1.2).
     */
    public static Method staticStringMethod(Class<?> owner, String yarnClass, String... candidateNames) {
        for (String name : candidateNames) {
            if (name == null) continue;
            String runtimeName = MappingsRegistry.getObfMethodName(yarnClass, name);
            for (Method m : owner.getDeclaredMethods()) {
                if (m.getName().equals(runtimeName) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * Champ déclaré, en remontant la hiérarchie de {@code owner}.
     *
     * <p>Les candidats sont essayés DANS L'ORDRE et passés tels quels : c'est
     * à l'appelant de mettre le nom déjà traduit en premier et le repli en
     * second (motif {@code getObfFieldName(...), "state", "renderState"}).
     * Un candidat {@code null} est ignoré, ce qui permet de passer directement
     * le résultat d'une traduction qui peut échouer.
     */
    public static Field fieldInHierarchy(Class<?> owner, String... candidateNames) {
        for (String name : candidateNames) {
            if (name == null) continue;
            Class<?> c = owner;
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        }
        return null;
    }

    /**
     * Méthode {@code (X, int, int)} où {@code X} accepte {@code argType}, par
     * nom Yarn, en remontant la hiérarchie de {@code owner}.
     *
     * @param namedDesc descripteur Yarn « named » de la surcharge visée, ex.
     *     {@code "(Lnet/minecraft/item/ItemStack;II)V"} — ou {@code null} pour
     *     une résolution par nom seul, acceptable UNIQUEMENT quand le nom est
     *     unique dans la classe.
     *
     *     <p>Il n'est pas optionnel en pratique : {@code DrawContext.drawItem}
     *     a QUATRE surcharges en 1.21.11, chacune avec un nom d'exécution
     *     DIFFÉRENT ({@code method_51423/51425/51427/51428}). Résolue par nom
     *     seul, la table ne peut pas trancher et rend le nom Yarn INCHANGÉ —
     *     donc introuvable, donc plus aucune icône d'item de toute la session,
     *     le drapeau d'échec étant définitif. {@code drawItemBar}, lui, n'a
     *     qu'une surcharge et fonctionnait : d'où « seules les icônes
     *     manquent ». Même famille de piège que {@code Text.getString}.
     */
    public static Method methodInHierarchy(Class<?> owner, Class<?> argType, String yarnClass,
                                           String namedDesc, String... candidateNames) {
        for (String name : candidateNames) {
            if (name == null) continue;
            String runtimeName = namedDesc != null
                ? MappingsRegistry.namedToRuntimeMethod(yarnClass, name, namedDesc, false)
                : MappingsRegistry.getObfMethodName(yarnClass, name);
            Class<?> c = owner;
            while (c != null) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(runtimeName) || m.getParameterCount() != 3) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p[0].isAssignableFrom(argType) && p[1] == int.class && p[2] == int.class) {
                        m.setAccessible(true);
                        return m;
                    }
                }
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /**
     * {@code Class.forName} avec le classloader EXPLICITE donné, jamais celui
     * du thread courant.
     *
     * <p>BUG TROUVÉ (test utilisateur, 26.1.2) qui justifie cette méthode :
     * résoudre une classe du jeu via le classloader de contexte du thread
     * échouait SILENCIEUSEMENT sur le thread de rendu, alors que la classe
     * voisine, résolue par un autre chemin, passait. Quand on tient déjà une
     * instance vivante, son propre classloader est la seule réponse sans
     * ambiguïté.
     */
    public static Class<?> classByLoader(ClassLoader cl, String... candidateNames) {
        for (String name : candidateNames) {
            if (name == null) continue;
            try {
                return Class.forName(name, false, cl);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static final Map<String, Object> VANILLA_IDENTIFIERS = new HashMap<>();

    /**
     * {@code Identifier.ofVanilla(path)} — namespace {@code minecraft}
     * implicite —, mis en cache par chemin.
     *
     * <p>{@code ofVanilla} (Yarn 1.21.11) == {@code withDefaultNamespace}
     * (vrai nom Mojang 26.1.2, confirmé par javap).
     */
    public static Object vanillaIdentifier(ClassLoader cl, String path) {
        Object cached = VANILLA_IDENTIFIERS.get(path);
        if (cached != null) return cached;
        try {
            Class<?> identifierClass = classByLoader(cl,
                MappingsRegistry.getObfClassDot("net/minecraft/util/Identifier"),
                "net.minecraft.resources.Identifier");
            if (identifierClass == null) return null;
            Method ofVanilla = staticStringMethod(identifierClass, "net/minecraft/util/Identifier",
                "ofVanilla", "withDefaultNamespace");
            if (ofVanilla == null) return null;
            Object id = ofVanilla.invoke(null, path);
            VANILLA_IDENTIFIERS.put(path, id);
            return id;
        } catch (Throwable t) {
            return null;
        }
    }
}
