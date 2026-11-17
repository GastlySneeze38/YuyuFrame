package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

/**
 * Section visuelle statique (surface de verre + titre) — pas un widget, juste
 * un dessin réutilisé par les écrans de réglages pour regrouper des réglages
 * liés. La surface elle-même est {@link UiSurface#panel()}.
 */
public final class UiPanel {
    private UiPanel() {}

    public static void draw(UiRenderer renderer, float x, float y, float w, float h, String title, int vpWidth, int vpHeight) {
        UiSurface.panel().draw(renderer, x, y, w, h, vpWidth, vpHeight);
        if (title != null && !title.isEmpty()) {
            renderer.drawText(UiFont.BOLD, title, x + UiTheme.px(UiTheme.Space.S16), y + h - UiTheme.px(UiTheme.Space.S28),
                UiTheme.TEXT_SECONDARY, UiTheme.px(UiTheme.Text.VALUE), vpWidth, vpHeight);
        }
    }
}
