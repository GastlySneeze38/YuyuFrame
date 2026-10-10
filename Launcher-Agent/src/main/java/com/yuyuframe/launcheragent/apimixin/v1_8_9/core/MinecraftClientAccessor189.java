package com.yuyuframe.launcheragent.apimixin.v1_8_9.core;

import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import net.minecraft.client.util.Session;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Le compte du jeu, pour le changer en cours de route (liste déroulante du
 * client intégré, {@code AccessPoint.CLIENT_SET_ACCOUNT}).
 *
 * <p>{@code session} est privé et « final » (javap : {@code private final avm
 * ae}, seul champ de ce type) : {@link Mutable} lève le « final » au tissage.
 * Sur cette version il n'y a rien d'autre à refaire — ni service de compte,
 * ni clés de chat.
 *
 * <p>{@link YarnNamed} : la signature porte un type du jeu ({@code Session}),
 * que Mixin compare à la lettre au type du champ ({@code avm}). Sans la
 * traduction, « No candidates were found » et {@code MinecraftClient} entier
 * n'est plus tissé.
 */
@YarnNamed
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public interface MinecraftClientAccessor189 {

    @Mutable
    @Accessor("session")
    void la$setSession(Session session);
}
