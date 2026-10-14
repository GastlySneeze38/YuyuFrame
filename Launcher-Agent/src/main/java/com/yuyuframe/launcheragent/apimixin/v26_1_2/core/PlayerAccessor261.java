package com.yuyuframe.launcheragent.apimixin.v26_1_2.core;

import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** L'inventaire d'un joueur ({@code Player.getInventory}, javap, jar 26.1.2, 2026-10-14). */
@Mixin(targets = "net.minecraft.world.entity.player.Player")
public interface PlayerAccessor261 {

    @Invoker("getInventory")
    Inventory la$inventory();
}
