package com.yuyuframe.launcheragent.apimixin.v1_8_9.screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Les boutons d'un écran du jeu ({@code buttons} en Yarn legacy,
 * {@code protected List n} dans {@code axu}, javap sur le jar 1.8.9) — la
 * 1.8.9 n'a pas de liste d'éléments générique, ses boutons sont tous là.
 * Entrée dans {@code LauncherMixinService.REFMAP_ENTRIES}.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.Screen")
public interface ScreenButtonsAccessor189 {

    @Accessor("buttons")
    java.util.List la$buttons();
}
