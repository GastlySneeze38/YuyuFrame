package com.yuyuframe.launcheragent.apimixin.v26_3.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lecture du terrain : le bloc à une position. Sur {@code Level}, la classe
 * qui déclare {@code getBlockState} (javap, jar 26.3, 2026-10-14) — pas sur
 * {@code ClientLevel}, qui en hérite.
 */
@Mixin(targets = "net.minecraft.world.level.Level")
public interface LevelAccessor263 {

    @Invoker("getBlockState")
    BlockState la$blockState(BlockPos pos);
}
