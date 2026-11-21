package com.yuyuframe.launcheragent.apimixin.v1_21_11.screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Position, taille et état d'un bouton du jeu ({@code ClickableWidget} en
 * Yarn, {@code gjc} dans le jar 1.21.11) — voir {@code WidgetAccessor263}.
 *
 * <p>Champs relevés par javap et yarn-1.21.11-mergedv2 : {@code x → a},
 * {@code y → b}, {@code width → g}, {@code height → h}, {@code active → k},
 * {@code visible → l}. Chacun a son entrée dans
 * {@code LauncherMixinService.REFMAP_ENTRIES} ; aucun homonyme en majuscule
 * dans la classe.
 */
@Mixin(targets = "net.minecraft.client.gui.widget.ClickableWidget")
public interface WidgetAccessor1211 {

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
