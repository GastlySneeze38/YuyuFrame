package com.yuyuframe.launcheragent.apimixin.v26_1_2.core;

import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** La case sélectionnée de la barre d'inventaire ({@code Inventory.getSelectedSlot}, javap, jar 26.1.2, 2026-10-14). */
@Mixin(targets = "net.minecraft.world.entity.player.Inventory")
public interface InventoryAccessor261 {

    @Invoker("getSelectedSlot")
    int la$selectedSlot();

    /**
     * Toutes les cases, armure et main gauche comprises ({@code
     * AccessPoint.PLAYER_INVENTORY_ITEMS}, 2026-11-07) — javap, jars 26.1.2 et
     * 26.3 : {@code getContainerSize()} et {@code getItem(int)} publics,
     * déclarés sur {@code Inventory}.
     */
    @Invoker("getContainerSize")
    int la$containerSize();

    @Invoker("getItem")
    ItemStack la$item(int slot);
}
