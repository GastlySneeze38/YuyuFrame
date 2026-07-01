package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;

/**
 * Bouton minimal — rect arrondi, s'éclaircit en douceur au survol (voir
 * UiAnimatedFloat). Pas de texte pour l'instant (rendu de texte via
 * FontRenderer vanilla pas encore câblé — voir docs/LauncherAgent/index.md,
 * prochaine étape après validation du pipeline clic+dessin).
 */
public class UiButton extends UiWidget {

    private static final UiColor BASE = new UiColor(45, 45, 50, 230);
    private static final UiColor HOVER = new UiColor(65, 65, 72, 230);
    private static final float RADIUS = 4f;
    private static final float HOVER_ANIM_SPEED = 16f;

    private final Runnable action;
    private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, HOVER_ANIM_SPEED);

    public UiButton(float x, float y, float w, float h, Runnable action) {
        super(x, y, w, h);
        this.action = action;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
        UiColor color = UiColor.lerp(BASE, HOVER, hoverAnim.get());
        renderer.drawRoundedRect(x, y, x + w, y + h, RADIUS, color, vpWidth, vpHeight);
    }

    @Override
    public void onClick() {
        if (action != null) action.run();
    }
}
