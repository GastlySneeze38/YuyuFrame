package com.yuyuframe.launcheragent.apimixin.v1_8_9.core;

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
 */
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public interface MinecraftClientAccessor189 {

    @Mutable
    @Accessor("session")
    void la$setSession(Session session);
}
