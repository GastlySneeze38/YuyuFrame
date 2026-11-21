package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.DisplayTitle;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * Le titre d'une section dans une liste qui défile (« Favoris », « Hors
 * ligne 7 ») : dans la police des titres (2026-11-21, {@link DisplayTitle}),
 * et un complément court à côté (un compte). Posé sur le décor, il suit la
 * clarté du fond.
 */
final class SectionTitle extends UiWidget {

    private final String label;
    private final String hint;

    /** {@code label} et {@code hint} déjà traduits ; {@code hint} peut être {@code null}. */
    SectionTitle(String label, String hint) {
        super(0f, 0f, 0f, 0f);
        this.label = label;
        this.hint = hint;
    }

    @Override
    public boolean contains(double mx, double my) {
        return false;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        if (clipFade <= 0.01f) return;
        float size = DisplayTitle.sectionSize();
        // La ligne de base un peu au-dessus du bas de la boîte : les lettres de la police des titres descendent peu.
        float baseline = y + h * 0.28f;
        DisplayTitle.drawSection(renderer, label, x, baseline, size, clipFade, vpWidth, vpHeight);
        if (hint == null || hint.isEmpty()) return;
        float hx = x + DisplayTitle.width(label, size) + px(UiTheme.Space.S12);
        float hs = px(UiTheme.Text.LG);
        UiText.drawOnBackdrop(renderer, UiFont.BOLD, UiText.fit(UiFont.BOLD, hint, x + w - hx, hs), hx, baseline,
            UiTheme.TEXT_SECONDARY.multiplyAlpha(clipFade), hs, vpWidth, vpHeight);
    }
}
