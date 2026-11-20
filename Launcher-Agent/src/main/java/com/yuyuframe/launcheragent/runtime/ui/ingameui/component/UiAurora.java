package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiGradientType;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

/**
 * Le fond des écrans plein écran : le monde, net et légèrement voilé (plus
 * vers le bas). Base neutre depuis le 2026-11-21, sans décor : le nouveau
 * viendra ici. Dessiné APRÈS la capture du flou ({@code beginGlassFrame}) :
 * le verre des panneaux floute le monde seul.
 *
 * <p>Un dégradé linéaire sur tout l'écran, une fois par image.
 */
public final class UiAurora {
    private UiAurora() {}

    private static final float[] VEIL = { 0f, 1f };

    public static void draw(UiRenderer r, int vw, int vh) {
        if (vw <= 0 || vh <= 0) return;
        UiColor base = UiTheme.palette().overlayBg;
        // Le rendu clair d'origine (rétabli le 2026-11-21) : le monde reste NET
        // derrière l'écran, seuls les panneaux de verre le floutent. Le flou
        // plein écran et le voile à 72 % en bas noircissaient tout.
        // Voile léger, un peu plus dense en bas, où le texte est le plus fin.
        r.drawMultiStopGradientRect(0, 0, vw, vh, 0f, UiGradientType.LINEAR, 0, vh, 0, 0,
            new UiColor[]{ base.withAlpha(0.18f), base.withAlpha(0.45f) }, VEIL, vw, vh);
        // Les deux lueurs d'accent (haut gauche, bas droit) sont retirées le
        // 2026-11-21 : le fond repart d'une base neutre, le nouveau décor
        // viendra par-dessus.
    }
}
