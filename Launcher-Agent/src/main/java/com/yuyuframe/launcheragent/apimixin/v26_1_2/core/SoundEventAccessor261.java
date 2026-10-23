package com.yuyuframe.launcheragent.apimixin.v26_1_2.core;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code SoundEvent.createVariableRangeEvent(Identifier)} (statique, public,
 * javap sur le jar 26.1.2) : un son désigné par son identifiant, comme le fait
 * le jeu pour chaque entrée de {@code SoundEvents}. Le gestionnaire de sons le
 * résout par son nom dans {@code sounds.json} — n'importe quel son vanilla
 * marche, et ceux qu'un pack de ressources ajoute aussi.
 */
@Mixin(targets = "net.minecraft.sounds.SoundEvent")
public interface SoundEventAccessor261 {

    @Invoker("createVariableRangeEvent")
    static SoundEvent la$create(Identifier id) {
        throw new AssertionError("SoundEventAccessor261 non tissé");
    }
}
