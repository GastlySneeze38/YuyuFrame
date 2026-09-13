package com.mojang.brigadier;

import com.mojang.brigadier.tree.RootCommandNode;

/**
 * Stub compile-only — brigadier n'est PAS au classpath de compilation de
 * l'agent (voir build.bat), d'où ce stub comme pour les classes du jeu.
 * Seul {@code getRoot()} nous intéresse.
 */
public class CommandDispatcher<S> {
    private CommandDispatcher() {}
    public RootCommandNode<S> getRoot() { return null; }
}
