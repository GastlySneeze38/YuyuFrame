package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

/**
 * Bouton carré de verre portant une icône : retour, fermeture, aide,
 * suppression. Au survol il GRANDIT un peu (un changement de couleur passait
 * inaperçu sur du verre) et s'éclaircit. Une icône, jamais une lettre (« x »,
 * « « », « i ») : une lettre a la graisse et la ligne de base du texte.
 */
public class UiIconButton extends UiInteractive {

    private final String iconUrl;
    private final Runnable action;
    private boolean danger;

    public UiIconButton(String iconUrl, Runnable action) {
        this.iconUrl = iconUrl;
        this.action = action;
    }

    public UiIconButton(float x, float y, float w, float h, String iconUrl, Runnable action) {
        super(x, y, w, h);
        this.iconUrl = iconUrl;
        this.action = action;
    }

    /** Geste destructeur (supprimer une entrée) : le survol vire au rouge. */
    public UiIconButton danger() {
        this.danger = true;
        return this;
    }

    private boolean round;

    /** Bulle ronde au lieu du carré : les boutons de la navigation flottante de l'accueil. */
    public UiIconButton round() {
        this.round = true;
        return this;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        if (w <= 0f || h <= 0f) return;
        updateStates(mouseX, mouseY);
        // Grossit autour du CENTRE, sinon il semblerait glisser vers le bas-droite.
        float grow = hover * UiTheme.px(UiTheme.Space.S2);
        float bx = x - grow, by = y - grow, bw = w + grow * 2f, bh = h + grow * 2f;

        UiColor hot = danger ? UiTheme.DANGER.multiplyAlpha(0.45f) : UiTheme.CARD_HOVER;
        UiColor rim = danger ? UiTheme.DANGER : UiTheme.GLASS_BORDER_HOVER;
        float radius = round ? Math.min(bw, bh) / 2f : UiTheme.RADIUS_SM;
        UiSurface.field(UiColor.lerp(UiTheme.CARD_BG, hot, hover))
            .radius(radius)
            .border(UiColor.lerp(UiTheme.GLASS_BORDER, rim, hover))
            .draw(renderer, bx, by, bw, bh, vpWidth, vpHeight);

        float pulse = pulse();
        if (pulse >= 0f) {
            float spread = UiTheme.px(UiTheme.Space.S4) * pulse;
            renderer.drawRoundedRect(bx - spread, by - spread, bx + bw + spread, by + bh + spread,
                radius + spread,
                (danger ? UiTheme.DANGER : UiTheme.ACCENT).withAlpha(0.30f * Math.max(0f, 1f - pulse)), vpWidth, vpHeight);
        }

        float icon = Math.round(Math.min(bw, bh) * 0.42f);
        UiIcon.crisp(renderer, iconUrl, bx + (bw - icon) / 2f, by + (bh - icon) / 2f, icon, 0.7f + 0.3f * hover,
            vpWidth, vpHeight);
    }

    @Override
    protected void onActivate() {
        if (action != null) action.run();
    }
}
