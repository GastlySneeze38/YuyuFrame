package com.yuyuframe.launcheragent.apimixin.v1_21_11.screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Les éléments d'un écran du jeu ({@code children} en Yarn, {@code private
 * final List d} dans {@code gsb}, javap sur le jar 1.21.11) — voir
 * {@code ScreenChildrenAccessor263}. Entrée dans
 * {@code LauncherMixinService.REFMAP_ENTRIES}. Aucun homonyme de même type à
 * la casse près ({@code e} et {@code t} sont d'autres listes, d'autres noms).
 */
@Mixin(targets = "net.minecraft.client.gui.screen.Screen")
public interface ScreenChildrenAccessor1211 {

    @Accessor("children")
    java.util.List la$children();
}
