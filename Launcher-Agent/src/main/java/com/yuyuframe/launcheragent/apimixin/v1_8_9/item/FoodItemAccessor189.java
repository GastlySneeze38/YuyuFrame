package com.yuyuframe.launcheragent.apimixin.v1_8_9.item;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * « Mangeable même rassasié » — champ PRIVÉ {@code alwaysEdible}
 * ({@code zs.k}, vérifié javap). C'est l'argument que vanilla passe lui-même à
 * {@code PlayerEntity.canConsume(boolean)} ; il remplace le
 * {@code FoodComponent.canAlwaysEat()} des versions modernes.
 */
@Mixin(targets = "net.minecraft.item.FoodItem")
public interface FoodItemAccessor189 {

    @Accessor("alwaysEdible")
    boolean la$alwaysEdible();
}
