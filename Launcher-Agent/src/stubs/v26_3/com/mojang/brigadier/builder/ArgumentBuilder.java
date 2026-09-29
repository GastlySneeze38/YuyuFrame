package com.mojang.brigadier.builder;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.CommandNode;

/**
 * Stub compile-only — constructeur de nœud brigadier. Signatures vérifiées sur
 * brigadier-1.3.10 (javap) : {@code then(ArgumentBuilder)}, {@code executes(Command)},
 * {@code abstract build()}.
 */
public abstract class ArgumentBuilder<S, T extends ArgumentBuilder<S, T>> {
    public T then(ArgumentBuilder<S, ?> argument) { return null; }
    public T executes(Command<S> command) { return null; }
    public abstract CommandNode<S> build();
}
