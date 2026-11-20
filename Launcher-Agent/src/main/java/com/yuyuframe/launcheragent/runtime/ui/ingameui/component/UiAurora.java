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

    public static void draw(UiRenderer r, int vw, int vh) {
        if (vw <= 0 || vh <= 0) return;
        UiColor base = UiTheme.palette().overlayBg;
        // y vers le haut : le premier arrêt, en (0, vh), est le haut de l'écran.
        r.drawMultiStopGradientRect(0, 0, vw, vh, 0f, UiGradientType.LINEAR, 0, vh, 0, 0,
            new UiColor[]{ base.withAlpha(0.18f), base.withAlpha(0.45f) }, VEIL, vw, vh);
    }
}
