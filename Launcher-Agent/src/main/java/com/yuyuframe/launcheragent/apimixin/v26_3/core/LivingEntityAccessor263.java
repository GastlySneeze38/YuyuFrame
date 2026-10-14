package com.yuyuframe.launcheragent.apimixin.v26_3.core;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * L'équipement d'une entité vivante — pendant de
 * {@code LivingEntityAccessor261}. Les deux méthodes sont déclarées par
 * {@code LivingEntity} (javap, jar 26.3, 2026-10-13).
 */
@Mixin(targets = "net.minecraft.world.entity.LivingEntity")
public interface LivingEntityAccessor263 {

    @Invoker("getItemBySlot")
    ItemStack la$itemBySlot(EquipmentSlot slot);

    @Invoker("getItemInHand")
    ItemStack la$itemInHand(InteractionHand hand);

    /** Objet bloquant levé ET armé (le délai du bouclier est passé) — javap, jar 26.3, 2026-10-14. */
    @Invoker("isBlocking")
    boolean la$isBlocking();

    @Invoker("isFallFlying")
    boolean la$isFallFlying();

    @Invoker("isUsingItem")
    boolean la$isUsingItem();
}
