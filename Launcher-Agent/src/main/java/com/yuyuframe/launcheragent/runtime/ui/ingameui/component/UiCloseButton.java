package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

/**
 * Bouton de fermeture d'un écran : petit carré de verre et sa croix. Au
 * survol il GRANDIT un peu (un changement de couleur passait inaperçu sur du
 * verre) et s'éclaircit.
 */
public final class UiCloseButton extends UiInteractive {

    private static final String ICON = UiIcon.named("multiply");

    private final Runnable action;

    public UiCloseButton(Runnable action) {
        this.action = action;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        updateStates(mouseX, mouseY);
        // Grossit autour du CENTRE, sinon il semblerait glisser vers le bas-droite.
        float grow = hover * UiTheme.px(UiTheme.Space.S2);
        float bx = x - grow, by = y - grow, bw = w + grow * 2f, bh = h + grow * 2f;

        // Fermer un menu n'est pas destructeur : le survol éclaircit, il ne rougit pas.
        UiSurface.field(UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hover))
            .border(UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.GLASS_BORDER_HOVER, hover))
            .draw(renderer, bx, by, bw, bh, vpWidth, vpHeight);

        float pulse = pulse();
        if (pulse >= 0f) {
            float spread = UiTheme.px(UiTheme.Space.S4) * pulse;
            renderer.drawRoundedRect(bx - spread, by - spread, bx + bw + spread, by + bh + spread,
                UiTheme.RADIUS_SM + spread,
                UiTheme.ACCENT.withAlpha(0.30f * Math.max(0f, 1f - pulse)), vpWidth, vpHeight);
        }

        // Une vraie icône : une lettre « x » a la graisse et la ligne de base du texte.
        float icon = Math.round(Math.min(bw, bh) * 0.42f);
        UiIcon.crisp(renderer, ICON, bx + (bw - icon) / 2f, by + (bh - icon) / 2f, icon, 0.7f + 0.3f * hover,
            vpWidth, vpHeight);
    }

    @Override
    protected void onActivate() {
        if (action != null) action.run();
    }
}
