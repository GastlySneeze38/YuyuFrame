package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

/**
 * Bouton de fermeture d'un écran : petit carré de verre marqué « x ». Au
 * survol il GRANDIT un peu (un changement de couleur passait inaperçu sur du
 * verre) et vire au rouge — sur un bouton de fermeture, c'est là qu'on dit
 * que le geste fait quitter avant le clic.
 */
public final class UiCloseButton extends UiInteractive {

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

        UiSurface.field(UiColor.lerp(UiTheme.CARD_BG, UiTheme.DANGER.multiplyAlpha(0.55f), hover))
            .border(UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.DANGER, hover))
            .draw(renderer, bx, by, bw, bh, vpWidth, vpHeight);

        float pulse = pulse();
        if (pulse >= 0f) {
            float spread = UiTheme.px(UiTheme.Space.S4) * pulse;
            renderer.drawRoundedRect(bx - spread, by - spread, bx + bw + spread, by + bh + spread,
                UiTheme.RADIUS_SM + spread,
                UiTheme.DANGER.withAlpha(0.35f * Math.max(0f, 1f - pulse)), vpWidth, vpHeight);
        }

        float scale = UiTheme.px(UiTheme.Text.BUTTON * 0.98f);
        UiText.drawCentered(renderer, null, "x", bx, bw, by + bh / 2f - UiTheme.px(UiTheme.Space.S5),
            UiColor.lerp(UiTheme.TEXT_SECONDARY, UiTheme.TEXT_PRIMARY, hover), scale, vpWidth, vpHeight);
    }

    @Override
    protected void onActivate() {
        if (action != null) action.run();
    }
}
