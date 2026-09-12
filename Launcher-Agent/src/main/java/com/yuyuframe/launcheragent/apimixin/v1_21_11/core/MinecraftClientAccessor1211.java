package com.yuyuframe.launcheragent.apimixin.v1_21_11.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Compteur d'images de {@code MinecraftClient} sur 1.21.11 — pour
 * {@code FpsModule}, via {@code AccessPoint.CLIENT_FPS}.
 *
 * <p>C'est le SEUL champ de cette classe qui demande un accessor ici : sur
 * cette version, {@code player}, {@code world}, {@code options}, {@code
 * currentScreen}, {@code inGameHud} et {@code mouse} sont PUBLICS (vérifié
 * javap), et {@code window}/{@code session}/{@code resourceManager} ont des
 * getters publics — {@code AccessorBindings1211} les atteint donc en code
 * TYPÉ, sans mixin. La 26.1.2, elle, a besoin d'un accessor pour tous.
 *
 * <p>{@code static} REQUIS : le champ l'est ({@code private static int
 * currentFps}), et Sponge exige la même staticité pour l'accesseur. Type
 * primitif, donc rien à typer contre une classe obfusquée — même contrainte
 * que {@code CameraAccessor1211}.
 */
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public interface MinecraftClientAccessor1211 {

    @Accessor("currentFps")
    static int la$fps() { throw new AssertionError("MinecraftClientAccessor1211 non tissé"); }
}
