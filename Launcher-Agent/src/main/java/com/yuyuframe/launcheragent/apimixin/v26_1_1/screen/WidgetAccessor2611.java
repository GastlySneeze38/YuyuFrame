package com.yuyuframe.launcheragent.apimixin.v26_1_1.screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Position, taille et état d'un bouton du jeu (javap 26.1.1, mêmes champs qu'en 26.3) — voir {@code WidgetAccessor263}. */
@Mixin(targets = "net.minecraft.client.gui.components.AbstractWidget")
public interface WidgetAccessor2611 {

    @Accessor("x")
    int la$x();

    @Accessor("x")
    void la$setX(int x);

    @Accessor("y")
    int la$y();

    @Accessor("width")
    int la$width();

    @Accessor("height")
    int la$height();

    @Accessor("visible")
    boolean la$visible();

    @Accessor("active")
    boolean la$active();

    @Accessor("active")
    void la$setActive(boolean active);
}
