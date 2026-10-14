package com.yuyuframe.launcheragent.apimixin.v26_3.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** La case sélectionnée de la barre d'inventaire ({@code Inventory.getSelectedSlot}, javap, jar 26.3, 2026-10-14). */
@Mixin(targets = "net.minecraft.world.entity.player.Inventory")
public interface InventoryAccessor263 {

    @Invoker("getSelectedSlot")
    int la$selectedSlot();
}
