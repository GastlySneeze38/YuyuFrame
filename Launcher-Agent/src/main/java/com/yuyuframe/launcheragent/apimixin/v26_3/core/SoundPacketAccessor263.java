package com.yuyuframe.launcheragent.apimixin.v26_3.core;

import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Le paquet « joue un son à une position » ({@code ClientboundSoundPacket}).
 * Noms vérifiés par javap sur le jar 26.3 (2026-10-14) : la position est
 * stockée en entiers ×8, les accesseurs publics la rendent en blocs.
 */
@Mixin(targets = "net.minecraft.network.protocol.game.ClientboundSoundPacket")
public interface SoundPacketAccessor263 {

    @SuppressWarnings("rawtypes")
    @Invoker("getSound")
    Holder la$sound();

    @Invoker("getX")
    double la$x();

    @Invoker("getY")
    double la$y();

    @Invoker("getZ")
    double la$z();
}
