package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.asset.UiRemoteImage;

import java.awt.image.BufferedImage;

/** Les icônes de l'interface (icons8, blanches, 100 px). */
public final class UiIcon {
    private UiIcon() {}

    private static final String BASE = "https://img.icons8.com/ios-filled/100/ffffff/";

    /** Adresse d'une icône icons8 « ios-filled » blanche par son nom. */
    public static String named(String name) { return BASE + name + ".png"; }

    /**
     * Une petite icône dessinée nette à {@code size} pixels.
     *
     * <p>La carte graphique ne réduit proprement qu'à moitié : de 100 px à
     * 16 px elle saute des pixels, et l'icône sort pixelisée. On lui donne
     * donc une copie déjà réduite à la bonne taille ({@link UiRemoteImage#scaled},
     * faite une fois, sur un autre fil), avec sa propre clé de texture par
     * taille. Tant que la copie n'est pas prête, rien n'est dessiné : une
     * image de moins pendant une image vaut mieux qu'un éclat pixelisé.
     */
    public static void crisp(UiRenderer renderer, String url, float x, float y, float size, float alpha,
                             int vw, int vh) {
        int n = Math.max(1, Math.round(size));
        BufferedImage img = UiRemoteImage.scaled(url, n, n);
        if (img == null) return;
        // Posée sur des pixels entiers, à sa taille exacte : un demi-pixel de
        // décalage suffirait à la rendre floue.
        renderer.drawIcon(url + "@" + n, img, Math.round(x), Math.round(y), n, n, alpha, vw, vh);
    }
}
