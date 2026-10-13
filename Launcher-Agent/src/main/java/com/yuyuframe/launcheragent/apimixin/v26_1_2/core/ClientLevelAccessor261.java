package com.yuyuframe.launcheragent.apimixin.v26_1_2.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Les joueurs du monde courant — voir {@code AccessPoint.LEVEL_PLAYERS}.
 * {@code ClientLevel.players()} rend une {@code List<AbstractClientPlayer>}
 * (javap, cinq jars 26.x, 2026-10-13) ; la liste est rendue sans type
 * d'élément pour ne pas avoir à stuber celui-ci.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientLevel")
public interface ClientLevelAccessor261 {

    @SuppressWarnings("rawtypes")
    @Invoker("players")
    List la$players();
}
