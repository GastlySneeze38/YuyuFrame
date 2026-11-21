package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * Le décor derrière la colonne verticale de l'accueil (2026-11-21) : une
 * bande sombre — un noir qui se dégrade en opacité sans jamais tout
 * couvrir, arrivé en fondu depuis le jeu clair (aucune coupure) — et dedans
 * l'illustration {@code /textures/backdrop/home-words.png} (mur de mots
 * « YUYU / FRAME » en relief, vagues, repères en losange).
 *
 * <p>L'illustration est OPAQUE (son propre fond violet sombre). Pour qu'elle
 * se pose sur le noir sans former un rectangle, son fond devient transparent :
 * chaque pixel prend une opacité selon ce qu'il a de plus clair que le fond
 * (les lettres et les crêtes des vagues ressortent, le fond disparaît). Elle
 * s'estompe ensuite en haut, en bas et sur le passage de gauche.
 *
 * <p>Tout est composé UNE fois dans une image, affichée comme une texture :
 * refaite seulement quand la taille change.
 */
public final class BackdropWords {
    private BackdropWords() {}

    private static final String ART = "/textures/backdrop/home-words.png";
    /** Le noir de la bande (légèrement bleuté). */
    private static final int BLACK_RGB = (10 << 16) | (9 << 8) | 14;
    /** Opacité du noir, en haut et en bas de la bande : jamais 100 %, le monde se devine. */
    private static final float TOP_ALPHA = 0.55f, BOTTOM_ALPHA = 0.90f;
    /** Luminosité du fond de l'illustration (devient transparent) et celle à partir de laquelle un pixel est plein. */
    private static final float ART_FLOOR = 34f, ART_FULL = 78f;
    /** Hauteur maximale de l'image composée : celle de l'illustration source. */
    private static final float MAX_IMAGE_H = 640f;

    private static BufferedImage art;
    private static BufferedImage image;
    private static int imageW, imageH;
    private static boolean failed;

    /** Largeur du passage entre le jeu clair et la bande : le noir et l'illustration y arrivent en fondu. */
    public static float fadeWidth() {
        return UiTheme.px(280f);
    }

    /**
     * La bande entière. Rectangle en coin bas-gauche, repère de l'écran ; ses
     * {@link #fadeWidth()} premiers pixels sont le passage depuis le jeu
     * clair — aucune coupure nette.
     *
     * <p>Le noir est peint dans la MÊME image que l'illustration, pixel par
     * pixel : fait en deux dégradés du moteur, leur jointure laissait un trait
     * vertical. Pas de verre non plus : un flou ne se dégrade pas, son bord
     * faisait une coupure nette.
     */
    public static void drawBand(UiRenderer r, float x, float y, float w, float h, int vw, int vh) {
        if (w <= 0f || h <= 0f) return;
        // Composée au plus à la hauteur de l'illustration (640) puis étirée :
        // plus grande, elle n'aurait rien de plus net, et elle occupait une
        // bonne part de l'atlas d'icônes (2048²), qui se vidait alors en
        // pleine image — bande coupée, traits parasites (2026-11-21).
        float k = Math.min(1f, MAX_IMAGE_H / h);
        int iw = Math.max(1, Math.round(w * k)), ih = Math.max(1, Math.round(h * k));
        if (failed || iw < 32 || ih < 32) return;
        if (image == null || iw != imageW || ih != imageH) {
            try {
                image = render(iw, ih, Math.round(Math.min(fadeWidth(), w * 0.5f) * k));
                imageW = iw;
                imageH = ih;
            } catch (Throwable t) {
                failed = true;
                LauncherLog.err("[BackdropWords] décor du fond impossible", t);
                return;
            }
        }
        r.drawIcon("yf-backdrop-words:" + iw + "x" + ih, image, x, y, w, h, vw, vh);
    }

    private static BufferedImage loadArt() throws Exception {
        if (art != null) return art;
        try (InputStream in = BackdropWords.class.getResourceAsStream(ART)) {
            if (in == null) throw new IllegalStateException("ressource absente : " + ART);
            art = ImageIO.read(in);
        }
        return art;
    }

    private static BufferedImage render(int w, int h, int leftFade) throws Exception {
        BufferedImage src = loadArt();
        int sw = src.getWidth(), sh = src.getHeight();
        // L'illustration tient sur toute la hauteur (entière, sans déformation), calée à droite.
        double scale = h / (double) sh;
        double drawnW = sw * scale, drawnH = sh * scale;
        double ox = w - drawnW, oy = (h - drawnH) / 2.0;
        float edge = h * 0.10f;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        float br = (BLACK_RGB >> 16) & 255, bg = (BLACK_RGB >> 8) & 255, bb = BLACK_RGB & 255;
        for (int py = 0; py < h; py++) {
            // Image : y vers le bas, donc py = 0 est le haut de la bande.
            float vertical = TOP_ALPHA + (BOTTOM_ALPHA - TOP_ALPHA) * (py / (float) Math.max(1, h - 1));
            float vfade = Math.min(1f, Math.min(py, h - 1 - py) / edge);
            vfade = vfade * vfade * (3f - 2f * vfade);
            int sy = (int) Math.floor((py - oy) / scale);
            for (int px = 0; px < w; px++) {
                float ramp = ramp(px, leftFade);
                // 1. Le noir.
                float aBlack = vertical * ramp;
                float r = br, g = bg, b = bb, a = aBlack;
                // 2. L'illustration par-dessus, son fond rendu transparent.
                int sx = (int) Math.floor((px - ox) / scale);
                if (sx >= 0 && sx < sw && sy >= 0 && sy < sh) {
                    int c = src.getRGB(sx, sy);
                    float cr = (c >> 16) & 255, cg = (c >> 8) & 255, cb = c & 255;
                    float light = 0.3f * cr + 0.59f * cg + 0.11f * cb;
                    float t = Math.max(0f, Math.min(1f, (light - ART_FLOOR) / (ART_FULL - ART_FLOOR)));
                    float aArt = t * ramp(px, Math.round(leftFade * 1.4f)) * vfade;
                    if (aArt > 0f) {
                        // Composition « par-dessus » de l'illustration sur le noir.
                        float outA = aArt + a * (1f - aArt);
                        r = (cr * aArt + r * a * (1f - aArt)) / outA;
                        g = (cg * aArt + g * a * (1f - aArt)) / outA;
                        b = (cb * aArt + b * a * (1f - aArt)) / outA;
                        a = outA;
                    }
                }
                row[px] = (Math.round(255f * a) << 24) | (Math.round(r) << 16) | (Math.round(g) << 8) | Math.round(b);
            }
            img.setRGB(0, py, w, 1, row, 0, w);
        }
        return img;
    }

    /**
     * Opacité du noir de la bande en un point : {@code localX} depuis son bord
     * gauche, {@code v} de 0 (bas) à 1 (haut) — la couverture sombre déclarée
     * aux textes posés dessus ({@code UiBackdropTone.cover}).
     */
    public static float alphaAt(float localX, float bandW, float v) {
        if (localX < 0f) return 0f;
        float vertical = BOTTOM_ALPHA + (TOP_ALPHA - BOTTOM_ALPHA) * Math.max(0f, Math.min(1f, v));
        return vertical * ramp(Math.round(localX), Math.round(Math.min(fadeWidth(), bandW * 0.5f)));
    }

    /** Montée du passage de gauche, départ et arrivée en douceur. */
    private static float ramp(int px, int fade) {
        float t = fade <= 0 ? 1f : Math.min(1f, px / (float) fade);
        return t * t * (3f - 2f * t);
    }
}
