package com.yuyuframe.launcheragent.apimixin.v1_21_11.core;

import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code SoundEvent.of(Identifier)} (Yarn 1.21.11, {@code method_47908},
 * statique, public) — pendant de {@code SoundEventAccessor261} : un son
 * désigné par son identifiant, n'importe lequel de {@code sounds.json}.
 * {@code of} a trois surcharges : le refmap ({@code LauncherMixinService})
 * porte le descripteur de celle-ci.
 */
@Mixin(targets = "net.minecraft.sound.SoundEvent")
public interface SoundEventAccessor1211 {

    @Invoker("of")
    static SoundEvent la$create(Identifier id) {
        throw new AssertionError("SoundEventAccessor1211 non tissé");
    }
}
