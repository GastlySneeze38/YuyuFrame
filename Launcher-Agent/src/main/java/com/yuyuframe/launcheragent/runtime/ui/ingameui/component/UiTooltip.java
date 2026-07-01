package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;

/**
 * Bulle d'aide flottante près du curseur — dessinée en dernier par
 * UiScreenBase/UiScrollContainer (par-dessus tout le reste, jamais clippée
 * par un scissor de contenu). Positionnée en bas-droite du curseur par
 * défaut, bascule au-dessus si ça sortirait du viewport en bas.
 */
public final class UiTooltip {
    private UiTooltip() {}

    private static final float SCALE = 0.4f;
    private static final float PAD = 6f;
    private static final float CURSOR_OFFSET = 14f;

    public static void draw(UiRenderer renderer, String text, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        if (text == null || text.isEmpty()) return;

        float tw = renderer.textWidth(text, SCALE);
        float th = UiFont.REGULAR.lineHeight(SCALE);
        float boxW = tw + PAD * 2f;
        float boxH = th + PAD * 2f;

        float x = (float) mouseX + CURSOR_OFFSET;
        if (x + boxW > vpWidth) x = vpWidth - boxW - 4f;

        // Sous le curseur par défaut (Y-up : "sous" = y plus petit) — bascule
        // au-dessus si ça sortirait du bas du viewport.
        float yTop = (float) mouseY - CURSOR_OFFSET;
        float yBottom = yTop - boxH;
        if (yBottom < 4f) {
            yBottom = (float) mouseY + CURSOR_OFFSET;
            yTop = yBottom + boxH;
        }

        renderer.drawRoundedRect(x, yBottom, x + boxW, yTop, UiTheme.RADIUS_SM, UiTheme.PANEL_BG_ALT, vpWidth, vpHeight);
        renderer.drawText(text, x + PAD, yBottom + PAD + UiFont.REGULAR.descent * SCALE * UiFont.SIZE_CORRECTION,
            UiTheme.TEXT_PRIMARY, SCALE, vpWidth, vpHeight);
    }
}
