package com.yuyuframe.launcheragent.apimixin.v26_1_0.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor Sponge pour les trois valeurs de {@code FoodData} — voir
 * {@code SaturationModule}.
 *
 * <p>{@code foodLevel} et {@code saturationLevel} ont pourtant des getters
 * publics ({@code getFoodLevel()}/{@code getSaturationLevel()}) : ils passent
 * quand même par un accessor, sur consigne explicite. Mélanger appels directs
 * et accessors ferait perdre la propriété qui rend le portage mécanique — un
 * renommage ou un changement de visibilité sur un autre bracket ne doit
 * toucher QUE l'accessor de ce bracket, jamais un appelant.
 *
 * <p>{@code exhaustionLevel}, lui, n'a de getter sur AUCUN bracket (seulement
 * {@code addExhaustion(float)}) : l'accessor y est la seule voie.
 */
@Mixin(targets = "net.minecraft.world.food.FoodData")
public interface FoodDataAccessor2610 {
    @Accessor("foodLevel")
    int la$foodLevel();

    @Accessor("saturationLevel")
    float la$saturationLevel();

    @Accessor("exhaustionLevel")
    float la$exhaustionLevel();
}
