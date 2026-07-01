package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;

/**
 * Bouton minimal — rect arrondi, s'éclaircit au survol. Pas de texte pour
 * l'instant (rendu de texte via FontRenderer vanilla pas encore câblé — voir
 * docs/LauncherAgent/index.md, prochaine étape après validation du pipeline
 * clic+dessin).
 */
public class UiButton extends UiWidget {

    private static final UiColor BASE = new UiColor(45, 45, 50, 230);
    private static final UiColor HOVER = new UiColor(65, 65, 72, 230);
    private static final float RADIUS = 4f;

    private final Runnable action;

    public UiButton(float x, float y, float w, float h, Runnable action) {
        super(x, y, w, h);
        this.action = action;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        boolean hovered = contains(mouseX, mouseY);
        renderer.drawRoundedRect(x, y, x + w, y + h, RADIUS, hovered ? HOVER : BASE, vpWidth, vpHeight);
    }

    @Override
    public void onClick() {
        if (action != null) action.run();
    }
}
