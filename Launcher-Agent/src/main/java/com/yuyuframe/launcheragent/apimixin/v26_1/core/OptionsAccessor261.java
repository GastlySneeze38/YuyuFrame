package com.yuyuframe.launcheragent.apimixin.v26_1.core;

import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor Sponge pour {@code Options.fov}/{@code Options.sensitivity} (privés) — voir {@code ZoomModule}. */
@Mixin(targets = "net.minecraft.client.Options")
public interface OptionsAccessor261 {
    @Accessor("fov")
    OptionInstance la$fov();

    @Accessor("sensitivity")
    OptionInstance la$sensitivity();
}
