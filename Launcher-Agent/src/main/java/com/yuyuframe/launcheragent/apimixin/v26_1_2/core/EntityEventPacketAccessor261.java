package com.yuyuframe.launcheragent.apimixin.v26_1_2.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Les champs du paquet d'événement d'entité — voir
 * {@code HookPoint.ENTITY_EVENT}.
 *
 * <p>{@code entityId} n'a AUCUN accesseur public : le paquet n'offre que
 * {@code getEntity(Level)}, qui rend l'entité déjà résolue. L'accessor est
 * ici la seule voie vers l'identifiant. Relevé par {@code javap} sur les cinq
 * jars 26.x le 2026-10-13 : {@code entityId:I}, {@code eventId:B}.
 */
@Mixin(targets = "net.minecraft.network.protocol.game.ClientboundEntityEventPacket")
public interface EntityEventPacketAccessor261 {

    @Accessor("entityId")
    int la$entityId();

    @Accessor("eventId")
    byte la$eventId();
}
