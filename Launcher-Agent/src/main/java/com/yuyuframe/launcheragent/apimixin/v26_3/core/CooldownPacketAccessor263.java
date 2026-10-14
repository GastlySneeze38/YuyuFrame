package com.yuyuframe.launcheragent.apimixin.v26_3.core;

import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Le paquet « mets ce groupe d'objets en recharge » ({@code ClientboundCooldownPacket},
 * un record). Noms vérifiés par javap sur le jar 26.3 (2026-10-14).
 */
@Mixin(targets = "net.minecraft.network.protocol.game.ClientboundCooldownPacket")
public interface CooldownPacketAccessor263 {

    @Invoker("cooldownGroup")
    Identifier la$cooldownGroup();

    @Invoker("duration")
    int la$duration();
}
