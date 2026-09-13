package com.yuyuframe.launcheragent.apigraphic.draw.item;

/**
 * Une icône d'objet vanilla EN ATTENTE — position déjà convertie en
 * coordonnées GUI vanilla (origine haut-gauche, échelle « GUI Scale »).
 *
 * <p>Structure de données pure : elle ne connaît ni ère, ni version, ni la
 * façon dont l'icône finira dessinée. Le seul champ opaque est
 * {@link #itemStack}, la pile du jeu, que l'ère repasse telle quelle au
 * renderer vanilla sans jamais l'inspecter.
 */
public final class VanillaItemIcon {

    /** Poignée OPAQUE vers l'{@code ItemStack} du jeu. */
    public final Object itemStack;

    /** Coin HAUT-gauche, en pixels GUI vanilla (conversion faite par {@link VanillaItemQueue}). */
    public final int guiX, guiY;

    /** Dessiner aussi le fond de case et la barre de durabilité vanilla. */
    public final boolean vanillaExtras;

    public VanillaItemIcon(Object itemStack, int guiX, int guiY, boolean vanillaExtras) {
        this.itemStack = itemStack;
        this.guiX = guiX;
        this.guiY = guiY;
        this.vanillaExtras = vanillaExtras;
    }
}
