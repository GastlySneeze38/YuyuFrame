package com.yuyuframe.launcheragent.apimixin.v26_1.core;

import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor Sponge pour {@code Options.fov}/{@code Options.sensitivity}/{@code Options.gamma} (privés) — voir {@code ZoomModule}/{@code FullbrightModule}. */
@Mixin(targets = "net.minecraft.client.Options")
public interface OptionsAccessor261 {
    @Accessor("fov")
    OptionInstance la$fov();

    @Accessor("sensitivity")
    OptionInstance la$sensitivity();

    /** Ajouté pour {@code FullbrightModule} (2026-08-26, §22) — {@code OptionInstance<Double>} (vérifié javap), même famille que fov/sensitivity. */
    @Accessor("gamma")
    OptionInstance la$gamma();
}
