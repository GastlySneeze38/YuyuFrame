package com.yuyuframe.launcheragent.apimixin.v26_1_2.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Un bloc est-il solide ? Sur {@code BlockBehaviour$BlockStateBase}, la classe
 * qui déclare {@code isSolid} (javap, jar 26.1.2, 2026-10-14).
 *
 * <p>{@code isSolid} plutôt que {@code blocksMotion}, présent ici mais disparu
 * en 26.3 : la même règle dans les deux versions.
 */
@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase")
public interface BlockStateAccessor261 {

    @Invoker("isSolid")
    boolean la$isSolid();
}
