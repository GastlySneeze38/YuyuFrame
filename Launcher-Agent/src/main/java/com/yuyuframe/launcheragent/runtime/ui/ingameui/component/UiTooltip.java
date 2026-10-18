package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

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
    private static long lastDrawnMs = -1L;
    private static final long FADE_MS = 260L;

    public static void draw(UiRenderer renderer, String text, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        if (text == null || text.isEmpty()) {
            lastText = null;
            shownAtMs = -1L;
            return;
        }
        long now = System.currentTimeMillis();
        // Une bulle qui n'a pas été dessinée depuis quelques images a été
        // quittée : la revoir rejoue son apparition, même sur le même widget.
        if (!text.equals(lastText) || now - lastDrawnMs > 100L) {
            lastText = text;
            shownAtMs = now;
        }
        lastDrawnMs = now;
        float linear = Math.min(1f, (System.currentTimeMillis() - shownAtMs) / (float) FADE_MS);
        // Ease-out : la bulle arrive vite et se pose en douceur.
        float alpha = 1f - (1f - linear) * (1f - linear) * (1f - linear);

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

        // Elle jaillit du curseur : elle grandit depuis le point le plus
        // proche de lui, avec un petit rebond, en devenant opaque.
        float pivotX = Math.max(x, Math.min(x + boxW, (float) mouseX));
        float pivotY = Math.abs(yTop - (float) mouseY) < Math.abs(yBottom - (float) mouseY) ? yTop : yBottom;
        float c1 = 1.5f, u = linear - 1f;
        float pop = 1f + (c1 + 1f) * u * u * u + c1 * u * u;
        renderer.pushLayer(alpha, 0.7f + 0.3f * pop, pivotX, pivotY, 0f, 0f);
        try {
            renderer.drawShadow(x, yBottom, x + boxW, yTop, UiTheme.RADIUS_SM, 10f, 0f,
                new com.yuyuframe.launcheragent.apigraphic.value.UiColor(0, 0, 0, 90), vpWidth, vpHeight);
            renderer.drawRoundedRect(x - 1f, yBottom - 1f, x + boxW + 1f, yTop + 1f, UiTheme.RADIUS_SM + 1f,
                UiTheme.ACCENT.withAlpha(0.4f), vpWidth, vpHeight);
            renderer.drawRoundedRect(x, yBottom, x + boxW, yTop, UiTheme.RADIUS_SM, UiTheme.PANEL_BG_ALT, vpWidth, vpHeight);
            renderer.drawText(text, x + PAD, yBottom + PAD + UiFont.REGULAR.descent * SCALE * UiFont.SIZE_CORRECTION,
                UiTheme.TEXT_PRIMARY, SCALE, vpWidth, vpHeight);
        } finally {
            renderer.popLayer();
        }
    }
}
