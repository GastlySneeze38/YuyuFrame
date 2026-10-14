package com.yuyuframe.launcheragent.apimixin.v26_1_2.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** La case sélectionnée de la barre d'inventaire ({@code Inventory.getSelectedSlot}, javap, jar 26.1.2, 2026-10-14). */
@Mixin(targets = "net.minecraft.world.entity.player.Inventory")
public interface InventoryAccessor261 {

    @Invoker("getSelectedSlot")
    int la$selectedSlot();
}
