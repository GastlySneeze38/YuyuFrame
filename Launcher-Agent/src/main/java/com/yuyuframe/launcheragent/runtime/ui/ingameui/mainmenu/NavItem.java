package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiIcon;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiInteractive;

/**
 * Une entrée de la navigation de la barre latérale : icône, libellé, liseré
 * d'accent sur le bord gauche. L'entrée active porte un fond d'accent ; les
 * autres s'allument au survol, et leur libellé glisse un peu à droite pour
 * laisser la place au liseré.
 */
public final class NavItem extends UiInteractive {

    private final String label;
    private final String iconUrl;
    private final boolean active;
    private final Runnable action;

    /** Bornes posées par la mise en page de l'écran. */
    public NavItem(String label, String iconUrl, boolean active, Runnable action) {
        this.label = label;
        this.iconUrl = iconUrl;
        this.active = active;
        this.action = action;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        if (w <= 0f || h <= 0f) return;
        updateStates(mouseX, mouseY);
        float edgeInset = UiTheme.px(UiTheme.Space.S3), edgeW = UiTheme.px(UiTheme.Space.S3);
        float edgeRadius = UiTheme.px(UiTheme.Radius.XS);
        if (active) {
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, UiTheme.SIDEBAR_ACTIVE, vpWidth, vpHeight);
            // Liseré en dégradé (accent en bas, plus clair en haut) : le même jeu de lumière partout.
            renderer.drawGradientRect(x, y + edgeInset, x + edgeW, y + h - edgeInset, edgeRadius,
                UiTheme.ACCENT, UiTheme.accentLight(), vpWidth, vpHeight);
        } else {
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM,
                UiColor.lerp(UiColor.TRANSPARENT, UiTheme.SIDEBAR_HOVER, hover), vpWidth, vpHeight);
            UiColor edgeBottom = UiColor.lerp(UiColor.TRANSPARENT, UiTheme.ACCENT_DIM, hover);
            UiColor edgeTop = UiColor.lerp(UiColor.TRANSPARENT, UiTheme.accentLight().multiplyAlpha(UiTheme.ACCENT_DIM.a), hover);
            renderer.drawGradientRect(x, y + edgeInset, x + edgeW, y + h - edgeInset, edgeRadius, edgeBottom, edgeTop, vpWidth, vpHeight);
        }
        UiColor textColor = active ? UiTheme.TEXT_PRIMARY : UiColor.lerp(UiTheme.TEXT_MUTED, UiTheme.TEXT_PRIMARY, hover);
        // Glissement minuscule : au-delà, la navigation tressaute à chaque passage.
        float slide = hover * UiTheme.px(UiTheme.Space.S3);
        float iconS = UiTheme.px(UiTheme.Space.S16);
        float textX = x + UiTheme.px(UiTheme.Space.S12) + slide;
        if (iconUrl != null) {
            // Pâle au repos, pleine au survol ou sur l'entrée active : elle suit le texte.
            float iconAlpha = active ? 1f : 0.55f + 0.45f * hover;
            UiIcon.crisp(renderer, iconUrl, textX, y + (h - iconS) / 2f, iconS, iconAlpha, vpWidth, vpHeight);
            textX += iconS + UiTheme.px(UiTheme.Space.S10);
        }
        renderer.drawText(Lang.tr(label), textX, y + h / 2f - UiTheme.px(UiTheme.Space.S5), textColor,
            UiTheme.px(UiTheme.Text.LABEL), vpWidth, vpHeight);
    }

    @Override
    protected void onActivate() {
        if (action != null) action.run();
    }
}
