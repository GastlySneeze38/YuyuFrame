package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.core.UiTheme;

/**
 * Bulle d'aide flottante près du curseur — dessinée en dernier par
 * UiScreenBase/UiScrollContainer (par-dessus tout le reste, jamais clippée
 * par un scissor de contenu). Positionnée en bas-droite du curseur par
 * défaut, bascule au-dessus si ça sortirait du viewport en bas.
 */
public final class UiTooltip {
    private UiTooltip() {}

    // Fondu d'apparition ajouté (voir audit runtime/ui/ : apparition/
    // disparition d'un coup, seul composant sans AUCUNE animation à part
    // UiSlider/UiColorPicker). Une seule bulle affichée à la fois dans toute
    // l'UI (curseur unique) — état STATIQUE partagé légitime, pas un widget
    // par instance comme UiAnimatedFloat ailleurs. L'horloge repart de zéro
    // dès que le TEXTE change (nouveau widget survolé) plutôt que de suivre
    // un widget précis, qui n'existe pas ici (voir signature, appelée avec
    // juste une chaîne).
    private static String lastText;
    private static long shownAtMs = -1L;
    private static final long FADE_MS = 120L;

    public static void draw(UiRenderer renderer, String text, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        if (text == null || text.isEmpty()) {
            lastText = null;
            shownAtMs = -1L;
            return;
        }
        if (!text.equals(lastText)) {
            lastText = text;
            shownAtMs = System.currentTimeMillis();
        }
        float alpha = Math.min(1f, (System.currentTimeMillis() - shownAtMs) / (float) FADE_MS);

        // Recalculées à chaque appel (pas de champ statique figé) : cette
        // classe est un utilitaire 100% statique, dessinée chaque frame — pas
        // de coût à recalculer depuis UiTheme.UI_SCALE à chaque fois, comme
        // ConfigScreenBuilder.
        float SCALE = UiTheme.scaled(0.4f);
        float PAD = UiTheme.scaled(6f);
        float CURSOR_OFFSET = UiTheme.scaled(14f);

        float tw = renderer.textWidth(text, SCALE);
        float th = UiFont.REGULAR.lineHeight(SCALE);
        float boxW = tw + PAD * 2f;
        float boxH = th + PAD * 2f;

        float edgeMargin = UiTheme.scaled(4f);
        float x = (float) mouseX + CURSOR_OFFSET;
        if (x + boxW > vpWidth) x = vpWidth - boxW - edgeMargin;

        // Sous le curseur par défaut (Y-up : "sous" = y plus petit) — bascule
        // au-dessus si ça sortirait du bas du viewport.
        float yTop = (float) mouseY - CURSOR_OFFSET;
        float yBottom = yTop - boxH;
        if (yBottom < edgeMargin) {
            yBottom = (float) mouseY + CURSOR_OFFSET;
            yTop = yBottom + boxH;
        }

        renderer.drawRoundedRect(x, yBottom, x + boxW, yTop, UiTheme.RADIUS_SM, UiTheme.PANEL_BG_ALT.multiplyAlpha(alpha), vpWidth, vpHeight);
        renderer.drawText(text, x + PAD, yBottom + PAD + UiFont.REGULAR.descent * SCALE * UiFont.SIZE_CORRECTION,
            UiTheme.TEXT_PRIMARY.multiplyAlpha(alpha), SCALE, vpWidth, vpHeight);
    }
}
