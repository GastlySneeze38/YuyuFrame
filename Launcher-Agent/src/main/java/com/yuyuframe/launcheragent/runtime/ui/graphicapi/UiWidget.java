package com.yuyuframe.launcheragent.runtime.ui.graphicapi;

/**
 * Widget dessiné/cliqué à la main — jamais un vrai ButtonWidget/ClickableWidget
 * vanilla (voir UiDrawable). Bounds en pixels framebuffer (même espace que
 * UiInputPoller.mouseX/Y et gl_FragCoord).
 */
public abstract class UiWidget {

    public float x, y, w, h;

    public UiWidget(float x, float y, float w, float h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    /**
     * Dessine ce widget — mouseX/mouseY en pixels framebuffer, pour l'état hover.
     * vpWidth/vpHeight : dimensions totales du viewport, requises par
     * UiRenderer pour poser sa projection orthographique (voir
     * UiRenderer.drawRoundedRect) — PAS les dimensions de ce widget.
     */
    public abstract void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight);

    /** Appelé quand ce widget est cliqué (leftClicked, curseur dans ses bounds). */
    public void onClick() {}

    /**
     * Appelé CHAQUE frame, pour tout widget (contrairement à onClick, un seul
     * "premier widget sous le curseur" par frame) — nécessaire au drag continu
     * (UiSlider) : leftDown doit rester suivi même quand la souris sort des
     * bounds pendant le glissement, ce qu'un simple contains()+onClick() ne
     * permet pas. No-op par défaut (boutons/toggles n'en ont pas besoin).
     */
    public void pollContinuous(UiInputPoller input) {}
}
