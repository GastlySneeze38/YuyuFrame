package com.yuyuframe.launcheragent.apimixin.v26_1_2.core;

import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * L'entrée de registre d'une pile, d'où l'on tire son identifiant — voir
 * {@code AccessPoint.ITEM_ID}. {@code ItemStack.typeHolder()} rend un
 * {@code Holder<Item>} (javap, 26.1.2, 26.2 et 26.3, 2026-10-13).
 */
@Mixin(targets = "net.minecraft.world.item.ItemStack")
public interface ItemStackAccessor261 {

    @SuppressWarnings("rawtypes")
    @Invoker("typeHolder")
    Holder la$typeHolder();
}
