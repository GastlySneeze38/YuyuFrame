package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiGradientType;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

/**
 * Le fond des écrans plein écran : le rendu clair — le monde NET, sous un
 * voile léger un peu plus dense en bas. Seuls les panneaux de verre floutent
 * ce qu'ils couvrent. Le décor de l'accueil (bande sombre et texte en relief)
 * est à part : {@link BackdropWords}.
 *
 * <p>Dessiné APRÈS la capture du flou ({@code beginGlassFrame}).
 */
public final class UiAurora {
    private UiAurora() {}

    private static final float[] VEIL = { 0f, 1f };
    /** Opacité du voile en haut et en bas de l'écran. */
    private static final float TOP_ALPHA = 0.18f, BOTTOM_ALPHA = 0.45f;

    public static void draw(UiRenderer r, int vw, int vh) {
        if (vw <= 0 || vh <= 0) return;
        UiColor base = UiTheme.palette().overlayBg;
        // y vers le haut : le premier arrêt, en (0, vh), est le haut de l'écran.
        r.drawMultiStopGradientRect(0, 0, vw, vh, 0f, UiGradientType.LINEAR, 0, vh, 0, 0,
            new UiColor[]{ base.withAlpha(TOP_ALPHA), base.withAlpha(BOTTOM_ALPHA) }, VEIL, vw, vh);
        // Le voile assombrit le fond : les textes posés dessus le savent.
        com.yuyuframe.launcheragent.apigraphic.effect.UiBackdropTone.cover(
            (u, v) -> BOTTOM_ALPHA + (TOP_ALPHA - BOTTOM_ALPHA) * v);
    }
}
