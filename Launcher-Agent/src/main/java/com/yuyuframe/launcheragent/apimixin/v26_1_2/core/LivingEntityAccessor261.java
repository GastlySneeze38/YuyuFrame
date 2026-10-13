package com.yuyuframe.launcheragent.apimixin.v26_1_2.core;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * L'équipement d'une entité vivante — voir {@code AccessPoint.ENTITY_EQUIPMENT}
 * et {@code HookPoint.ITEM_USE}. Les deux méthodes sont déclarées par
 * {@code LivingEntity} (javap, cinq jars 26.x, 2026-10-13).
 */
@Mixin(targets = "net.minecraft.world.entity.LivingEntity")
public interface LivingEntityAccessor261 {

    @Invoker("getItemBySlot")
    ItemStack la$itemBySlot(EquipmentSlot slot);

    @Invoker("getItemInHand")
    ItemStack la$itemInHand(InteractionHand hand);
}
