package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;

/**
 * Section visuelle statique (fond arrondi + titre) — pas un UiWidget
 * interactif (aucun clic à gérer), juste un helper de dessin réutilisé par
 * les écrans de config pour regrouper des réglages liés.
 */
public final class UiPanel {
    private UiPanel() {}

    // Ombre discrète ajoutée (voir audit runtime/ui/ : drawShadow n'était
    // utilisé que dans UiMainMenuScreen — CE helper est pourtant le panneau
    // le plus réutilisé de tout le moteur, écran de config après écran de
    // config). Alpha bas/flou modéré : donne juste un peu de profondeur,
    // jamais un halo visible qui distrairait du contenu du panneau.
    private static final UiColor SHADOW_COLOR = new UiColor(0, 0, 0, 70);
    private static final float SHADOW_BLUR = 8f;

    public static void draw(UiRenderer renderer, float x, float y, float w, float h, String title, int vpWidth, int vpHeight) {
        renderer.drawShadow(x, y, x + w, y + h, UiTheme.RADIUS_MD, SHADOW_BLUR, 0f, SHADOW_COLOR, vpWidth, vpHeight);
        renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_MD, UiTheme.PANEL_BG, vpWidth, vpHeight);
        if (title != null && !title.isEmpty()) {
            renderer.drawText(UiFont.BOLD, title, x + UiTheme.scaled(16f), y + h - UiTheme.scaled(28f), UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.5f), vpWidth, vpHeight);
        }
    }
}
