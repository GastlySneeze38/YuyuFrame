package com.yuyuframe.launcheragent.apimixin.v26_1_2.core;

import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Les champs du paquet de dégâts — voir {@code HookPoint.DAMAGE_EVENT}.
 *
 * <p>Le paquet est un {@code record} : ses quatre champs sont privés et
 * finaux, avec un accesseur public du même nom. On lit les CHAMPS, pour que
 * le mixin n'ait pas à nommer le type du paquet (aucun stub à écrire).
 * Relevé par {@code javap} sur les cinq jars 26.x le 2026-10-13 :
 * {@code entityId:I}, {@code sourceType:Holder}, {@code sourceCauseId:I},
 * {@code sourceDirectId:I}.
 *
 * <p>Les deux identifiants de source valent −1 quand il n'y en a pas : le
 * paquet les écrit décalés de un sur le réseau et les relit dans cet état.
 */
@Mixin(targets = "net.minecraft.network.protocol.game.ClientboundDamageEventPacket")
public interface DamageEventPacketAccessor261 {

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
