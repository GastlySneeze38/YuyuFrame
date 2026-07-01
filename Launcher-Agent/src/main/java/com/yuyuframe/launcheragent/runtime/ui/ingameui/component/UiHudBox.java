package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;

/**
 * Représente un {@link HudElement} dans l'éditeur (UiHudEditorScreen) —
 * glissable librement, écrit sa position dans le modèle (ancre+décalage) à
 * chaque frame de drag, pas seulement au relâchement : le modèle reflète
 * toujours exactement ce qui est affiché, aucune étape de "commit" séparée.
 *
 * Le drag démarre/continue dans pollContinuous (pas onClick) — même pattern
 * que UiSlider — pour rester actif même si le curseur sort des bounds de la
 * boîte pendant le glissement.
 */
public class UiHudBox extends UiWidget {

    private final HudElement element;
    private boolean dragging;
    private float grabDX, grabDY;
    private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

    public UiHudBox(HudElement element, int vpWidth, int vpHeight) {
        super(element.screenX(vpWidth), element.screenY(vpHeight), element.w, element.h);
        this.element = element;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
        UiColor bg = dragging ? UiTheme.ACCENT_DIM : UiColor.lerp(UiTheme.PANEL_BG_ALT, UiTheme.CARD_HOVER, hoverAnim.get());
        renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);

        float tw = renderer.textWidth(element.displayName, 0.4f);
        renderer.drawText(element.displayName, x + (w - tw) / 2f, y + h / 2f - 4f, UiTheme.TEXT_PRIMARY, 0.4f, vpWidth, vpHeight);
    }

    @Override
    public void pollContinuous(UiInputPoller input) {
        if (!dragging) {
            if (input.leftClicked && contains(input.mouseX, input.mouseY)) {
                dragging = true;
                grabDX = (float) input.mouseX - x;
                grabDY = (float) input.mouseY - y;
            }
            return;
        }
        if (!input.leftDown) { dragging = false; return; }

        x = (float) input.mouseX - grabDX;
        y = (float) input.mouseY - grabDY;
        element.setScreenPosition(x, y, input.fbWidth, input.fbHeight);
    }
}
