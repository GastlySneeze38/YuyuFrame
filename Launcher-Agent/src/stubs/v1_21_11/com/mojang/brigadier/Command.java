package com.mojang.brigadier;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

/** Stub compile-only. Vérifié sur brigadier-1.3.10 (javap) : interface, {@code int run(CommandContext) throws CommandSyntaxException}. */
public interface Command<S> {
    int run(CommandContext<S> context) throws CommandSyntaxException;
}
