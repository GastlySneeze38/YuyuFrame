package com.mojang.brigadier.builder;

import com.mojang.brigadier.tree.LiteralCommandNode;

/**
 * Stub compile-only. Vérifié sur brigadier-1.3.10 (javap) :
 * {@code static <S> LiteralArgumentBuilder<S> literal(String)},
 * {@code LiteralCommandNode<S> build()}.
 */
public class LiteralArgumentBuilder<S> extends ArgumentBuilder<S, LiteralArgumentBuilder<S>> {
    private LiteralArgumentBuilder() {}
    public static <S> LiteralArgumentBuilder<S> literal(String name) { return null; }
    @Override public LiteralCommandNode<S> build() { return null; }
}
