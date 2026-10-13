package com.yuyuframe.launcheragent.apimixin.v26_3.core;

import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * L'entrée de registre d'une pile — pendant de {@code ItemStackAccessor261}.
 * {@code ItemStack.typeHolder()} rend un {@code Holder<Item>} (javap, jar
 * 26.3, 2026-10-13).
 */
@Mixin(targets = "net.minecraft.world.item.ItemStack")
public interface ItemStackAccessor263 {

    @SuppressWarnings("rawtypes")
    @Invoker("typeHolder")
    Holder la$typeHolder();
}
