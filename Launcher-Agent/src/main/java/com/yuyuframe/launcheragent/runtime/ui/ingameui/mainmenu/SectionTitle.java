package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;

/**
 * Titre d'une section de la grille (« FAVORIS », « AUTRES MODULES »), posé
 * sur le décor : ombre portée obligatoire, et TEXT_SECONDARY plutôt que
 * TEXT_MUTED, qu'un fond variable dissout même avec une ombre.
 */
final class SectionTitle extends UiWidget {

    private final String label;

    /** {@code label} déjà traduit et en capitales. Bornes posées par la mise en page. */
    SectionTitle(String label) {
        super(0f, 0f, 0f, 0f);
        this.label = label;
    }

    @Override
    public boolean contains(double mx, double my) {
        return false;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        renderer.drawTextShadowed(label, x, y + h / 2f - UiTheme.px(UiTheme.Space.S4),
            UiTheme.TEXT_SECONDARY.multiplyAlpha(clipFade), UiTheme.TEXT_SHADOW.multiplyAlpha(clipFade),
            UiTheme.px(UiTheme.Text.CAPTION), vpWidth, vpHeight);
    }
}
