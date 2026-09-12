package com.mojang.brigadier;

import com.mojang.brigadier.tree.RootCommandNode;

/**
 * Stub compile-only — brigadier n'est PAS au classpath de compilation de
 * l'agent (voir build.bat), d'où ce stub comme pour les classes du jeu.
 * Seul {@code getRoot()} nous intéresse.
 *
 * <p>COPIE VOLONTAIRE de {@code src/stubs} : l'unité 1.21.11 est compilée
 * contre ses PROPRES stubs uniquement (les {@code com.mojang.blaze3d.*} des
 * deux versions portent les mêmes noms pour des API différentes, voir
 * build.bat). Aucune divergence à craindre ici : brigadier est une
 * bibliothèque externe NON obfusquée, identique sur les deux versions — le
 * remappeur la laisse d'ailleurs telle quelle.
 */
public class CommandDispatcher<S> {
    private CommandDispatcher() {}
    public RootCommandNode<S> getRoot() { return null; }
}
