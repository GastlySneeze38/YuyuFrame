package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;

/**
 * Section visuelle statique (fond arrondi + titre) — pas un UiWidget
 * interactif (aucun clic à gérer), juste un helper de dessin réutilisé par
 * les écrans de config pour regrouper des réglages liés.
 */
public final class UiPanel {
    private UiPanel() {}

    public static void draw(UiRenderer renderer, float x, float y, float w, float h, String title, int vpWidth, int vpHeight) {
        renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_MD, UiTheme.PANEL_BG, vpWidth, vpHeight);
        if (title != null && !title.isEmpty()) {
            renderer.drawText(UiFont.BOLD, title, x + UiTheme.scaled(16f), y + h - UiTheme.scaled(28f), UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.5f), vpWidth, vpHeight);
        }
    }
}
