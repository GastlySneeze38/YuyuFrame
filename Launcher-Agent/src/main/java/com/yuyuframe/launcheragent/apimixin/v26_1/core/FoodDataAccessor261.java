package com.yuyuframe.launcheragent.apimixin.v26_1.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor Sponge pour {@code FoodData.exhaustionLevel} (privé) — voir {@code SaturationModule}. */
@Mixin(targets = "net.minecraft.world.food.FoodData")
public interface FoodDataAccessor261 {
    @Accessor("exhaustionLevel")
    float la$exhaustionLevel();
}
