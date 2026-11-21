package com.yuyuframe.launcheragent.apimixin.v26_3.screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Position, taille et état d'un bouton du jeu ({@code AbstractWidget}, javap
 * sur le jar 26.3 : {@code private int x, y}, {@code protected int width,
 * height}, {@code public boolean active, visible}).
 *
 * <p>Les raccourcis en lisent le rectangle, décalent la rangée d'icônes du
 * centre pour y glisser les leurs ({@code x}) et rendent les boutons inactifs
 * le temps de l'édition ({@code active}), pour qu'un clic sur l'éditeur ne
 * déclenche pas le bouton en dessous.
 */
@Mixin(targets = "net.minecraft.client.gui.components.AbstractWidget")
public interface WidgetAccessor263 {

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
