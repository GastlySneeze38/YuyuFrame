package com.yuyuframe.launcheragent.apimixin.v26_1_0.core;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code SoundEvent.createVariableRangeEvent(Identifier)} (statique, public,
 * javap sur le jar 26.1) — pendant de {@code SoundEventAccessor261} : un son
 * désigné par son identifiant, n'importe lequel de {@code sounds.json}.
 */
@Mixin(targets = "net.minecraft.sounds.SoundEvent")
public interface SoundEventAccessor2610 {

    @Invoker("createVariableRangeEvent")
    static SoundEvent la$create(Identifier id) {
        throw new AssertionError("SoundEventAccessor2610 non tissé");
    }
}
