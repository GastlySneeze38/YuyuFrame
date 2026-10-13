package com.yuyuframe.launcheragent.apimixin.v26_3.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Les joueurs du monde courant — pendant de {@code ClientLevelAccessor261}.
 * {@code ClientLevel.players()} rend une {@code List<AbstractClientPlayer>}
 * (javap, jar 26.3, 2026-10-13), rendue ici sans type d'élément.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientLevel")
public interface ClientLevelAccessor263 {

    @SuppressWarnings("rawtypes")
    @Invoker("players")
    List la$players();
}
