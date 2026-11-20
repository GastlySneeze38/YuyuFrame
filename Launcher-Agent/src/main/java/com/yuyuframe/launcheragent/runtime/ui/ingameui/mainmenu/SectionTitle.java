package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * Titre d'une section de la grille (« Favoris », « Autres modules »), comme
 * « Accès rapide » à l'accueil : le titre en gras, et une précision grise
 * facultative à sa droite. Posé sur le décor, donc avec l'ombre de
 * {@link UiText#drawOnBackdrop}.
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
        float s = px(UiTheme.Text.XL);
        float baseline = UiText.centeredBaseline(UiFont.BOLD, y, h, s);
        UiText.drawOnBackdrop(renderer, UiFont.BOLD, label, x, baseline, UiTheme.TEXT_PRIMARY.multiplyAlpha(clipFade), s,
            vpWidth, vpHeight);
        if (hint == null || hint.isEmpty()) return;
        float hx = x + UiText.width(UiFont.BOLD, label, s) + px(UiTheme.Space.S16);
        float hs = px(UiTheme.Text.MD);
        UiText.drawOnBackdrop(renderer, UiFont.REGULAR, UiText.fit(UiFont.REGULAR, hint, x + w - hx, hs), hx, baseline,
            UiTheme.TEXT_MUTED.multiplyAlpha(clipFade), hs, vpWidth, vpHeight);
    }
}
