package com.yuyuframe.launcheragent.apimixin.v1_8_9.screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Position, taille et état d'un bouton du jeu ({@code ButtonWidget} en Yarn
 * legacy, {@code avs} dans le jar 1.8.9) — voir {@code WidgetAccessor263}.
 *
 * <p>Champs relevés par javap et yarn-1.8.9-mergedv2 : {@code width → f},
 * {@code height → g}, {@code x → h}, {@code y → i}, {@code active → l},
 * {@code visible → m}. Chacun a son entrée dans
 * {@code LauncherMixinService.REFMAP_ENTRIES}.
 */
@Mixin(targets = "net.minecraft.client.gui.widget.ButtonWidget")
public interface WidgetAccessor189 {

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
