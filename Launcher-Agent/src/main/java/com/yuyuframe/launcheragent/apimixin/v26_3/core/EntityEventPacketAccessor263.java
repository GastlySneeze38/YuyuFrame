package com.yuyuframe.launcheragent.apimixin.v26_3.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Les champs du paquet d'événement d'entité — pendant de
 * {@code EntityEventPacketAccessor261}. {@code entityId} n'a aucun accesseur
 * public : l'accessor est la seule voie. Relevé par {@code javap} sur le jar
 * 26.3 le 2026-10-13 : {@code entityId:I}, {@code eventId:B}.
 */
@Mixin(targets = "net.minecraft.network.protocol.game.ClientboundEntityEventPacket")
public interface EntityEventPacketAccessor263 {

    @Accessor("entityId")
    int la$entityId();

    @Accessor("eventId")
    byte la$eventId();
}
