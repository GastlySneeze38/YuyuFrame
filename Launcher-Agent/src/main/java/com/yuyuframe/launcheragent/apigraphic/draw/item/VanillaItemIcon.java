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

    /**
     * Facteur à appliquer à l'icône vanilla (16 pixels GUI) pour qu'elle ait
     * la taille DEMANDÉE — {@code 1} quand la taille demandée vaut exactement
     * 16 pixels GUI (style « Vanilla » d'{@code ArmorDurabilityModule}).
     *
     * <p>Correctif du 2026-09-29 (petits écrans, capture utilisateur) : la
     * taille passée à {@code drawVanillaItemIcon} était perdue ici, vanilla
     * dessinait toujours ses 16 pixels GUI, soit {@code 16 × échelle GUI}
     * pixels réels. Le module, lui, réserve {@code taille} pixels et pose son
     * texte juste après : dès que l'échelle GUI dépassait la taille du HUD,
     * l'icône débordait sur le texte.
     */
    public final float scale;

    /** Coin haut-gauche NON arrondi (pixels GUI) — position exacte quand {@link #scale} ≠ 1. */
    public final float guiXExact, guiYExact;

    public VanillaItemIcon(Object itemStack, int guiX, int guiY, boolean vanillaExtras) {
        this(itemStack, guiX, guiY, vanillaExtras, 1f, guiX, guiY);
    }

    public VanillaItemIcon(Object itemStack, int guiX, int guiY, boolean vanillaExtras,
                           float scale, float guiXExact, float guiYExact) {
        this.itemStack = itemStack;
        this.guiX = guiX;
        this.guiY = guiY;
        this.vanillaExtras = vanillaExtras;
        this.scale = scale;
        this.guiXExact = guiXExact;
        this.guiYExact = guiYExact;
    }

    /**
     * À mettre à l'échelle ? Tolérance d'un pour mille : une taille calculée
     * « 16 × échelle GUI » ne doit pas basculer sur le chemin transformé pour
     * une erreur d'arrondi flottant.
     */
    public boolean scaled() {
        return Math.abs(scale - 1f) > 0.001f;
    }
}
