package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.awt.image.BufferedImage;

/**
 * Le décor des pages horizontales (modules, missions), 2026-11-21 : en bas de
 * l'écran, un treillis de losanges — des « pics » qui remontent — teinté de
 * l'accent, qui se dégrade vers le haut jusqu'à disparaître dans le jeu
 * clair. Chaque losange s'éclaire vers un coin, les jointures sont des
 * traits fins, et quelques arêtes accrochent la lumière.
 *
 * <p>Calculé UNE fois, pixel par pixel, dans une image affichée comme une
 * texture : refaite seulement quand la taille ou l'accent change.
 */
public final class BackdropPeaks {
    private BackdropPeaks() {}

    /** Part de la hauteur de l'écran couverte par le décor. */
    public static final float HEIGHT_SHARE = 0.62f;
    /** Opacité tout en bas : jamais 100 %, le monde se devine. */
    private static final float MAX_ALPHA = 0.92f;

    private static BufferedImage image;
    private static int imageW, imageH, imageAccent;
    private static boolean failed;

    /** Le décor, dans le rectangle donné (coin bas-gauche, repère de l'écran). */
    public static void draw(UiRenderer r, float x, float y, float w, float h, int vw, int vh) {
        int iw = Math.max(1, Math.round(w)), ih = Math.max(1, Math.round(h));
        if (failed || iw < 64 || ih < 64) return;
        UiColor a = UiTheme.ACCENT;
        int accent = (Math.round(a.r * 255) << 16) | (Math.round(a.g * 255) << 8) | Math.round(a.b * 255);
        if (image == null || iw != imageW || ih != imageH || accent != imageAccent) {
            try {
                image = render(iw, ih, accent);
                imageW = iw;
                imageH = ih;
                imageAccent = accent;
            } catch (Throwable t) {
                failed = true;
                LauncherLog.err("[BackdropPeaks] décor du fond impossible", t);
                return;
            }
        }
        r.drawIcon("yf-backdrop-peaks:" + iw + "x" + ih + ":" + accent, image, x, y, w, h, vw, vh);
    }

    private static float smooth(float e0, float e1, float v) {
        float t = Math.max(0f, Math.min(1f, (v - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }

    /** Un nombre pseudo-aléatoire stable pour une case du treillis. */
    private static float hash(int u, int v) {
        int n = u * 374761393 + v * 668265263;
        n = (n ^ (n >>> 13)) * 1274126177;
        return ((n ^ (n >>> 16)) & 0xFFFF) / 65535f;
    }

    private static BufferedImage render(int w, int h, int accentRgb) {
        float ar = (accentRgb >> 16) & 255, ag = (accentRgb >> 8) & 255, ab = accentRgb & 255;
        // Le fond sombre et la teinte : l'accent assombri, comme le rouge de l'exemple.
        float dr = 12, dg = 11, db = 20;
        float tr = ar * 0.55f, tg = ag * 0.30f, tb = ab * 0.62f;
        // Des losanges larges : environ quatre sur la largeur de l'écran.
        float cell = Math.max(h * 0.55f, w / 4.2f);
        // Le treillis est décalé pour qu'une pointe tombe au milieu de l'écran.
        float ox = w * 0.5f, oy = 0f;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        float line = Math.max(1f, h / 700f);
        for (int py = 0; py < h; py++) {
            // t : 0 en haut du décor, 1 tout en bas.
            float t = py / (float) Math.max(1, h - 1);
            float alpha = MAX_ALPHA * smooth(0f, 0.75f, t);
            float tint = 0.25f + 0.75f * t * t;
            for (int px = 0; px < w; px++) {
                float X = px - ox, Y = py - oy;
                // Repère tourné de 45° : les cases sont des losanges.
                float u = (X + Y) / cell, v = (Y - X) / cell;
                int cu = (int) Math.floor(u), cv = (int) Math.floor(v);
                float fu = fract(u), fv = fract(v);

                // Chaque losange s'éclaire vers son coin bas-gauche, plus ou moins selon la case.
                float rnd = hash(cu, cv);
                float light = (1f - fu) * fv;
                light = light * light * (0.5f + 0.9f * rnd);

                float k = tint * (0.55f + 0.9f * light);
                float r = dr + (tr - dr) * k, g = dg + (tg - dg) * k, b = db + (tb - db) * k;

                // Les jointures : un trait sombre ; une arête sur cinq accroche la lumière.
                float du = Math.min(fu, 1f - fu) * cell, dv = Math.min(fv, 1f - fv) * cell;
                float edge = Math.min(du, dv);
                if (edge < line * 1.5f) {
                    float s = 1f - edge / (line * 1.5f);
                    boolean lit = (du < dv ? hash(cu * 7 + 3, cv) : hash(cu, cv * 5 + 1)) > 0.8f;
                    if (lit) {
                        float glow = s * (0.4f + 0.6f * t);
                        r += (ar * 1.15f - r) * glow;
                        g += (ag * 0.6f - g) * glow;
                        b += (ab * 1.1f - b) * glow;
                    } else {
                        r *= 1f - 0.45f * s;
                        g *= 1f - 0.45f * s;
                        b *= 1f - 0.45f * s;
                    }
                }
                int a = Math.round(255f * alpha);
                row[px] = (a << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
            img.setRGB(0, py, w, 1, row, 0, w);
        }
        return img;
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }
}
