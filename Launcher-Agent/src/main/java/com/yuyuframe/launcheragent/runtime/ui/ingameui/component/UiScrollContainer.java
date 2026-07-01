package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * Défilement vertical + clipping (scissor) pour une liste de widgets dont la
 * hauteur totale dépasse la fenêtre visible (page de config avec beaucoup de
 * réglages). Les widgets ajoutés gardent leur y d'origine comme position "de
 * base" (baseY) — chaque frame, {@code widget.y} est recalculé à partir de
 * baseY + un décalage qui aligne le contenu sur le haut du viewport à
 * scroll=0, jamais muté de façon cumulative (pas de dérive possible).
 */
public class UiScrollContainer {

    private static final float SCROLL_STEP_PX = 36f;
    // Marge haut/bas — les UiLabel ont une hauteur de widget nulle (leur y est
    // la ligne de base, voir UiLabel), donc contentTop/Bottom ne couvrent pas
    // les ascendantes/descendantes du texte réellement dessiné. Sans cette
    // marge, la première/dernière ligne se ferait tronquer pile au bord du
    // scissor en position de scroll extrême.
    private static final float EDGE_PADDING = 12f;

    private final float vx, vy, vw, vh; // viewport en espace écran, (vx,vy) = coin bas-gauche
    private final List<UiWidget> content = new ArrayList<>();
    private final List<Float> baseY = new ArrayList<>();
    private float scrollY;
    private float contentTop = 0f, contentBottom = 0f;

    public UiScrollContainer(float vx, float vy, float vw, float vh) {
        this.vx = vx; this.vy = vy; this.vw = vw; this.vh = vh;
    }

    /** Enregistre un widget déjà positionné (w.y/w.x) dans son propre repère "contenu" (top-down, arbitraire). */
    public void add(UiWidget w) {
        if (content.isEmpty()) {
            contentTop = w.y + w.h;
            contentBottom = w.y;
        } else {
            contentTop = Math.max(contentTop, w.y + w.h);
            contentBottom = Math.min(contentBottom, w.y);
        }
        content.add(w);
        baseY.add(w.y);
    }

    public void clear() {
        content.clear();
        baseY.clear();
        scrollY = 0f;
        contentTop = 0f;
        contentBottom = 0f;
    }

    private float maxScroll() {
        return Math.max(0f, (contentTop - contentBottom) + EDGE_PADDING * 2 - vh);
    }

    /** Décalage appliqué à baseY pour que le haut du contenu affleure le haut du viewport à scroll=0. */
    private float renderOffset() {
        return (vy + vh - EDGE_PADDING - contentTop) + scrollY;
    }

    private void applyOffsets() {
        float off = renderOffset();
        for (int i = 0; i < content.size(); i++) content.get(i).y = baseY.get(i) + off;
    }

    private boolean visible(UiWidget w) {
        return w.y + w.h >= vy && w.y <= vy + vh;
    }

    public void pollInput(UiInputPoller input) {
        boolean overViewport = input.mouseX >= vx && input.mouseX <= vx + vw
            && input.mouseY >= vy && input.mouseY <= vy + vh;
        if (overViewport && input.scrollDelta != 0) {
            // Molette positive ("vers le haut") = veut voir le début du contenu -> scrollY diminue.
            scrollY -= input.scrollDelta * SCROLL_STEP_PX;
            scrollY = Math.max(0f, Math.min(maxScroll(), scrollY));
        }
        applyOffsets();

        for (UiWidget w : content) {
            if (!visible(w)) continue;
            w.pollContinuous(input);
        }
        if (input.leftClicked) {
            for (UiWidget w : content) {
                if (!visible(w)) continue;
                if (w.contains(input.mouseX, input.mouseY)) { w.onClick(); break; }
            }
        }
    }

    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        applyOffsets();
        renderer.beginScissor((int) vx, (int) vy, (int) vw, (int) vh);
        for (UiWidget w : content) {
            if (!visible(w)) continue;
            w.draw(renderer, mouseX, mouseY, vpWidth, vpHeight);
        }
        renderer.endScissor();
    }
}
