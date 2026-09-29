package com.yuyuframe.launcheragent.apimixin.v26_2.core;

import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor Sponge pour {@code KeyMapping.isDown}/{@code KeyMapping.key} (privés) — voir {@code KeystrokesModule}. */
@Mixin(targets = "net.minecraft.client.KeyMapping")
public interface KeyMappingAccessor262 {
    @Accessor("isDown")
    boolean la$isDown();

    @Accessor("key")
    InputConstants.Key la$key();
}
