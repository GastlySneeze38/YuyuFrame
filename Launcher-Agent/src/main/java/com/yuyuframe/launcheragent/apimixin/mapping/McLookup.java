package com.yuyuframe.launcheragent.apimixin.mapping;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Recherches réflexives par nom YARN, quand {@link McReflect} ne suffit pas.
 *
 * <p>Vit dans la couche {@code mapping} et non dans le moteur graphique parce
 * que c'est son métier : traduire un nom Yarn vers le nom d'exécution du loader
 * actif avant de comparer. Extrait de {@code UiVanillaItemRenderer} lors de son
 * découpage.
 *
 * <p><b>Ne sert plus que les brackets GELÉS</b> ({@code Gl3VanillaItemRenderer},
 * 1.20.4/1.21.4) depuis le 2026-09-13 : l'ère Blaze3D (26.1.2, 1.21.11) passe
 * par des appels typés dans ses {@code VanillaGuiSink}. {@code fieldInHierarchy}
 * et {@code methodInHierarchy}, qui n'avaient plus d'appelant, ont été retirés ;
 * la leçon de la seconde (résolution par nom seul face à quatre surcharges de
 * {@code drawItem}) est consignée dans l'audit, v1105.
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
    private static Method staticStringMethod(Class<?> owner, String yarnClass, String... candidateNames) {
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
    private static Class<?> classByLoader(ClassLoader cl, String... candidateNames) {
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
