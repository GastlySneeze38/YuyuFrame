package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiGradientType;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

/**
 * Le fond des écrans plein écran : le monde, assombri vers le bas, et deux
 * grandes lueurs aux couleurs de l'accent.
 *
 * <p>Les lueurs donnent de la couleur au verre même quand le monde est noir
 * (la nuit, une grotte) ; elles suivent l'accent choisi par le joueur. Elles
 * sont dessinées APRÈS la capture du flou ({@code beginGlassFrame}) : le verre
 * floute le monde seul, et les lueurs restent nettes autour des panneaux.
 *
 * <p>Deux dégradés radiaux et un linéaire sur tout l'écran, une fois par
 * image : le prix de trois rectangles.
 */
public final class UiAurora {
    private UiAurora() {}

    private static final float[] RADIAL = { 0f, 1f };
    private static final float[] VEIL = { 0f, 1f };

    public static void draw(UiRenderer r, int vw, int vh) {
        if (vw <= 0 || vh <= 0) return;
        UiColor base = UiTheme.palette().overlayBg;
        // Le monde entier passe au flou (la chaîne déjà calculée pour l'image,
        // sans coût de plus) : les panneaux s'en détachent, le décor reste là.
        r.drawGlassPanel(0, 0, vw, vh, 0f, UiTheme.GLASS_TINT, 0.18f, base, null, 0f, vw, vh);
        // Voile : léger en haut, plus dense en bas, où le texte est le plus fin.
        r.drawMultiStopGradientRect(0, 0, vw, vh, 0f, UiGradientType.LINEAR, 0, vh, 0, 0,
            new UiColor[]{ base.withAlpha(0.30f), base.withAlpha(0.72f) }, VEIL, vw, vh);

        float size = Math.max(vw, vh);
        UiColor accent = UiTheme.ACCENT;
        // Haut gauche : l'accent tel quel.
        glow(r, vw * 0.02f, vh * 1.02f, size * 0.55f, accent.withAlpha(0.55f), vw, vh);
        // Bas droit : l'accent poussé vers le violet, pour que les deux lueurs ne se répètent pas.
        UiColor violet = UiColor.lerp(accent, new UiColor(124, 58, 237, 255), 0.6f);
        glow(r, vw * 0.98f, vh * -0.04f, size * 0.58f, violet.withAlpha(0.48f), vw, vh);
    }

    private static void glow(UiRenderer r, float cx, float cy, float radius, UiColor color, int vw, int vh) {
        r.drawMultiStopGradientRect(0, 0, vw, vh, 0f, UiGradientType.RADIAL, cx, cy, cx + radius, cy,
            new UiColor[]{ color, color.withAlpha(0f) }, RADIAL, vw, vh);
    }
}
