package com.yuyuframe.launcheragent.apimixin.v26_3.screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Les éléments d'un écran du jeu ({@code private final List<GuiEventListener>
 * children}, javap sur le jar 26.3) : les boutons y sont, ceux des autres mods
 * compris. Lu par {@code AccessPoint.SCREEN_WIDGETS} pour que les raccourcis
 * se posent à côté et jamais dessus.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.Screen")
public interface ScreenChildrenAccessor263 {

    @Accessor("children")
    java.util.List la$children();
}
