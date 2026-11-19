package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSurface;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * La barre du haut du menu, sur toute la largeur : une bande de verre, un
 * trait en bas, et à gauche la marque (le carré d'accent et « YuyuFrame »).
 * Les onglets et les boutons sont des widgets à part, posés dessus par la
 * mise en page de l'écran.
 */
public final class TopBar extends UiWidget {

    /** Largeur occupée par la marque, pour que l'écran y aligne les onglets. */
    public static float brandWidth() {
        return px(196f);
    }

    public TopBar() {
        super(0f, 0f, 0f, 0f);
    }

    @Override
    public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
        if (w <= 0f || h <= 0f) return;
        UiSurface.of(UiTheme.Glass.SIDEBAR).radius(0f).noBorder().draw(r, x, y, w, h, vw, vh);
        r.drawRoundedRect(x, y, x + w, y + UiTheme.hairline(), 0f, UiTheme.DIVIDER_STRONG, vw, vh);

        float pad = px(UiTheme.Space.S40);
        float mark = px(UiTheme.Space.S32);
        float mx = x + pad, my = y + (h - mark) / 2f;
        // Le carré d'accent : un léger dégradé et un reflet en haut, comme un bouton du launcher.
        r.drawGradientRect(mx, my, mx + mark, my + mark, px(UiTheme.Space.S8),
            UiTheme.accentDark(), UiTheme.ACCENT, vw, vh);
        r.drawRoundedRect(mx + px(UiTheme.Space.S8), my + mark - UiTheme.hairline(), mx + mark - px(UiTheme.Space.S8),
            my + mark, 0f, new UiColor(255, 255, 255, 60), vw, vh);
        float letterScale = px(UiTheme.Text.HEADING);
        UiText.drawCentered(r, UiFont.BOLD, "Y", mx, mark,
            UiText.centeredBaseline(UiFont.BOLD, my, mark, letterScale), UiTheme.TEXT_ON_ACCENT, letterScale, vw, vh);

        float nameScale = px(UiTheme.Text.SUBTITLE);
        r.drawText(UiFont.BOLD, "YuyuFrame", mx + mark + px(UiTheme.Space.S12),
            UiText.centeredBaseline(UiFont.BOLD, y, h, nameScale), UiTheme.TEXT_PRIMARY, nameScale, vw, vh);
    }
}
