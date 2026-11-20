package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiGradientType;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

/**
 * Les bannières de profil du launcher, redessinées par le moteur.
 *
 * <p>Ce sont des dégradés, pas des images : chacune est recopiée de
 * {@code Frontend/src/lib/profileBanners.ts} ({@code BANNERS}), couche par
 * couche (le fond linéaire d'abord, les lueurs radiales par-dessus, comme
 * l'ordre CSS lu de la fin). Une bannière ajoutée au launcher s'ajoute ICI
 * aussi ; inconnue, elle prend le dégradé de la couleur choisie, comme le
 * launcher ({@code bannerBackground}).
 *
 * <p>Les ellipses CSS deviennent des cercles (le moteur ne fait que des
 * dégradés radiaux circulaires) : le rayon est la moyenne des deux.
 */
public final class ProfileBanner {
    private ProfileBanner() {}

    public static void draw(UiRenderer r, String code, String accentHex, float x, float y, float w, float h, float radius,
                            int vw, int vh) {
        switch (code == null ? "" : code) {
            case "aurora":
                linear(r, x, y, w, h, radius, 135, "#10233a", "#1b1440", vw, vh);
                radial(r, x, y, w, h, radius, 0.90f, 0.20f, 0.90f, 1.20f, "#7b72e9cc", 0.6f, vw, vh);
                radial(r, x, y, w, h, radius, 0.15f, 0.00f, 1.20f, 1.40f, "#3ee6b8aa", 0.6f, vw, vh);
                break;
            case "sunset":
                linear3(r, x, y, w, h, radius, 160, "#ff8a5c", "#e8487a", 0.45f, "#5b2a86", vw, vh);
                break;
            case "ocean":
                linear(r, x, y, w, h, radius, 160, "#0c4a6e", "#0b1f3a", vw, vh);
                radial(r, x, y, w, h, radius, 0.80f, 0.00f, 1.00f, 1.20f, "#5ad1ff99", 0.6f, vw, vh);
                break;
            case "forest":
                linear(r, x, y, w, h, radius, 160, "#1f5b3a", "#0f2418", vw, vh);
                radial(r, x, y, w, h, radius, 0.20f, 0.00f, 0.90f, 1.20f, "#8ee06f88", 0.6f, vw, vh);
                break;
            case "nether":
                linear(r, x, y, w, h, radius, 160, "#6b1414", "#2a0707", vw, vh);
                radial(r, x, y, w, h, radius, 0.70f, 1.00f, 0.90f, 1.20f, "#ff7a2aaa", 0.6f, vw, vh);
                break;
            case "end":
                linear(r, x, y, w, h, radius, 160, "#2a1d4a", "#0d0a17", vw, vh);
                radial(r, x, y, w, h, radius, 0.30f, 0.20f, 0.80f, 1.10f, "#d7c6ff55", 0.6f, vw, vh);
                break;
            case "night":
                linear(r, x, y, w, h, radius, 180, "#0b1230", "#05060f", vw, vh);
                radial(r, x, y, w, h, radius, 0.80f, 0.10f, 0.60f, 0.80f, "#f5f3ff33", 0.6f, vw, vh);
                break;
            case "candy":
                linear3(r, x, y, w, h, radius, 135, "#ff9ad5", "#b28dff", 0.5f, "#7fd3ff", vw, vh);
                break;
            case "gold":
                linear(r, x, y, w, h, radius, 150, "#c9922e", "#5a3b0c", vw, vh);
                radial(r, x, y, w, h, radius, 0.25f, 0.00f, 0.90f, 1.20f, "#fff2b366", 0.6f, vw, vh);
                break;
            case "mono":
                linear(r, x, y, w, h, radius, 160, "#3a3a44", "#141418", vw, vh);
                break;
            default: {
                // Comme le launcher : un dégradé de la couleur choisie, sinon de l'accent.
                UiColor c = parse(accentHex);
                if (c == null) c = UiTheme.ACCENT;
                r.drawRoundedRect(x, y, x + w, y + h, radius, UiTheme.palette().panelBg, vw, vh);
                float[] l = line(x, y, w, h, 135);
                r.drawMultiStopGradientRect(x, y, x + w, y + h, radius, UiGradientType.LINEAR, l[0], l[1], l[2], l[3],
                    new UiColor[]{ c.withAlpha(0.40f), c.withAlpha(0.08f) }, new float[]{ 0f, 0.75f }, vw, vh);
            }
        }
    }

    // ── Couches ─────────────────────────────────────────────────────────────

    private static void linear(UiRenderer r, float x, float y, float w, float h, float radius, float angle,
                               String from, String to, int vw, int vh) {
        float[] l = line(x, y, w, h, angle);
        r.drawMultiStopGradientRect(x, y, x + w, y + h, radius, UiGradientType.LINEAR, l[0], l[1], l[2], l[3],
            new UiColor[]{ parse(from), parse(to) }, new float[]{ 0f, 1f }, vw, vh);
    }

    private static void linear3(UiRenderer r, float x, float y, float w, float h, float radius, float angle,
                                String a, String b, float bAt, String c, int vw, int vh) {
        float[] l = line(x, y, w, h, angle);
        r.drawMultiStopGradientRect(x, y, x + w, y + h, radius, UiGradientType.LINEAR, l[0], l[1], l[2], l[3],
            new UiColor[]{ parse(a), parse(b), parse(c) }, new float[]{ 0f, bAt, 1f }, vw, vh);
    }

    /**
     * Lueur radiale CSS : centre en fraction de la boîte (depuis le haut à
     * gauche, comme en CSS), rayons en fraction de la largeur et de la hauteur,
     * transparente à {@code fadeAt} de son rayon.
     */
    private static void radial(UiRenderer r, float x, float y, float w, float h, float radius,
                               float cx, float cy, float rx, float ry, String color, float fadeAt, int vw, int vh) {
        float px = x + cx * w;
        float py = y + h - cy * h;
        float rad = (rx * w + ry * h) / 2f;
        UiColor c = parse(color);
        r.drawMultiStopGradientRect(x, y, x + w, y + h, radius, UiGradientType.RADIAL, px, py, px + rad, py,
            new UiColor[]{ c, c.withAlpha(0f) }, new float[]{ 0f, fadeAt }, vw, vh);
    }

    /**
     * La ligne d'un dégradé CSS à {@code angle} degrés (0 = vers le haut, sens
     * horaire), dans le repère du moteur (Y vers le haut) : {@code x0, y0, x1, y1}.
     * Sa longueur est celle que prend CSS, pour que les coins tombent sur 0 et 1.
     */
    private static float[] line(float x, float y, float w, float h, float angle) {
        double a = Math.toRadians(angle);
        float dx = (float) Math.sin(a), dy = (float) Math.cos(a);
        float len = Math.abs(w * dx) + Math.abs(h * dy);
        float cx = x + w / 2f, cy = y + h / 2f;
        return new float[]{ cx - dx * len / 2f, cy - dy * len / 2f, cx + dx * len / 2f, cy + dy * len / 2f };
    }

    /** {@code #rrggbb} ou {@code #rrggbbaa} ; {@code null} si illisible. */
    public static UiColor parse(String hex) {
        if (hex == null) return null;
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        if (s.length() != 6 && s.length() != 8) return null;
        try {
            int r = Integer.parseInt(s.substring(0, 2), 16);
            int g = Integer.parseInt(s.substring(2, 4), 16);
            int b = Integer.parseInt(s.substring(4, 6), 16);
            int a = s.length() == 8 ? Integer.parseInt(s.substring(6, 8), 16) : 255;
            return new UiColor(r, g, b, a);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
