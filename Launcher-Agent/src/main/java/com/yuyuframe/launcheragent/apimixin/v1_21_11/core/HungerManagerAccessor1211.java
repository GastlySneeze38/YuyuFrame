package com.yuyuframe.launcheragent.apimixin.v1_21_11.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Épuisement du joueur sur 1.21.11 — pour {@code AccessPoint.FOOD_EXHAUSTION}.
 *
 * <p>{@code getFoodLevel()} et {@code getSaturationLevel()} sont publics et
 * atteints en code typé ; l'épuisement, lui, n'a PAS de getter (champ privé
 * {@code exhaustion}, vérifié dans Yarn), d'où cet accessor. Type primitif :
 * rien à typer contre une classe obfusquée.
 */
@Mixin(targets = "net.minecraft.entity.player.HungerManager")
public interface HungerManagerAccessor1211 {

    @Accessor("exhaustion")
    float la$exhaustion();
}
