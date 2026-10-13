package com.yuyuframe.launcheragent.apimixin.v26_3.core;

import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Les champs du paquet de dégâts — pendant de
 * {@code DamageEventPacketAccessor261}. Relevé par {@code javap} sur le jar
 * 26.3 le 2026-10-13 : {@code entityId:I}, {@code sourceType:Holder},
 * {@code sourceCauseId:I}, {@code sourceDirectId:I}. Les deux identifiants de
 * source valent −1 quand il n'y en a pas.
 */
@Mixin(targets = "net.minecraft.network.protocol.game.ClientboundDamageEventPacket")
public interface DamageEventPacketAccessor263 {

    @Accessor("entityId")
    int la$entityId();

    @SuppressWarnings("rawtypes")
    @Accessor("sourceType")
    Holder la$sourceType();

    @Accessor("sourceCauseId")
    int la$sourceCauseId();

    @Accessor("sourceDirectId")
    int la$sourceDirectId();
}
