package com.yuyuframe.launcheragent.apimixin.v26_1_1.screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Les éléments d'un écran du jeu (javap 26.1.1 : {@code private final List children}) — voir {@code ScreenChildrenAccessor263}. */
@Mixin(targets = "net.minecraft.client.gui.screens.Screen")
public interface ScreenChildrenAccessor2611 {

    @Accessor("children")
    java.util.List la$children();
}
