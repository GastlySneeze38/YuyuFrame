package com.yuyuframe.launcheragent.apimixin.v26_2.core;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code SoundEvent.createVariableRangeEvent(Identifier)} (statique, public,
 * javap sur le jar 26.2) — pendant de {@code SoundEventAccessor261}.
 */
@Mixin(targets = "net.minecraft.sounds.SoundEvent")
public interface SoundEventAccessor262 {

    @Invoker("createVariableRangeEvent")
    static SoundEvent la$create(Identifier id) {
        throw new AssertionError("SoundEventAccessor262 non tissé");
    }
}
