package com.yuyuframe.launcheragent.apimixin.v26_3.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Un bloc est-il solide ? Sur {@code BlockBehaviour$BlockStateBase}, la classe
 * qui déclare {@code isSolid} (javap, jar 26.3, 2026-10-14).
 *
 * <p>Pas {@code blocksMotion} : présent en 26.1.2, il n'existe plus en 26.3.
 * {@code isSolid} existe dans les deux.
 */
@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase")
public interface BlockStateAccessor263 {

    @Invoker("isSolid")
    boolean la$isSolid();
}
