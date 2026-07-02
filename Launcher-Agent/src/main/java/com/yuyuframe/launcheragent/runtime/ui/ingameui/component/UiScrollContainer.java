package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat;
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
 *
 * {@code scrollTarget} est la position "logique" (clampée, utilisée pour le
 * calcul de la barre) ; le défilement RENDU/testé au clic passe par
 * {@code scrollAnim} (UiAnimatedFloat) pour une décélération douce plutôt
 * qu'un saut instantané par cran de molette.
 */
public class UiScrollContainer {

    private static final float SCROLL_STEP_PX = 36f;
    // Marge haut/bas — les UiLabel ont une hauteur de widget nulle (leur y est
    // la ligne de base, voir UiLabel), donc contentTop/Bottom ne couvrent pas
    // les ascendantes/descendantes du texte réellement dessiné. Sans cette
    // marge, la première/dernière ligne se ferait tronquer pile au bord du
    // scissor en position de scroll extrême.
    private static final float EDGE_PADDING = 14f;

    private static final float SCROLLBAR_W = 6f;
    private static final float SCROLLBAR_MARGIN = 4f;
    private static final float SCROLLBAR_MIN_H = 24f;

    private final float vx, vy, vw, vh; // viewport en espace écran, (vx,vy) = coin bas-gauche
    private final List<UiWidget> content = new ArrayList<>();
    private final List<Float> baseY = new ArrayList<>();
    private float contentTop = 0f, contentBottom = 0f;

    private float scrollTarget;
    private final UiAnimatedFloat scrollAnim = new UiAnimatedFloat(0f, 16f);
    private float lastAnimatedScroll;

    private boolean thumbDragging;
    private float lastDragMouseY;

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
        scrollTarget = 0f;
        scrollAnim.setTarget(0f);
        contentTop = 0f;
        contentBottom = 0f;
    }

    private float maxScroll() {
        return Math.max(0f, (contentTop - contentBottom) + EDGE_PADDING * 2 - vh);
    }

    private float clampScroll(float v) {
        return Math.max(0f, Math.min(maxScroll(), v));
    }

    private void applyOffsets() {
        lastAnimatedScroll = scrollAnim.get();
        float off = (vy + vh - EDGE_PADDING - contentTop) + lastAnimatedScroll;
        for (int i = 0; i < content.size(); i++) content.get(i).y = baseY.get(i) + off;
    }

    private boolean visible(UiWidget w) {
        return w.y + w.h >= vy && w.y <= vy + vh;
    }

    private boolean hasScrollbar() { return maxScroll() > 0.5f; }

    private float thumbHeight() {
        float contentHeight = (contentTop - contentBottom) + EDGE_PADDING * 2f;
        float ratio = contentHeight > 0 ? Math.min(1f, vh / contentHeight) : 1f;
        return Math.max(SCROLLBAR_MIN_H, vh * ratio);
    }

    private float thumbY(float scrollValue) {
        float th = thumbHeight();
        float max = maxScroll();
        float t = max > 0 ? scrollValue / max : 0f;
        float travel = vh - th;
        return vy + vh - th - t * travel;
    }

    public void pollInput(UiInputPoller input) {
        boolean overViewport = input.mouseX >= vx && input.mouseX <= vx + vw
            && input.mouseY >= vy && input.mouseY <= vy + vh;
        if (overViewport && input.scrollDelta != 0) {
            // Molette positive ("vers le haut") = veut voir le début du contenu -> scrollTarget diminue.
            scrollTarget = clampScroll(scrollTarget - input.scrollDelta * SCROLL_STEP_PX);
            scrollAnim.setTarget(scrollTarget);
        }

        if (hasScrollbar()) {
            float th = thumbHeight();
            float tx = vx + vw - SCROLLBAR_W - SCROLLBAR_MARGIN;
            if (!thumbDragging) {
                float ty = thumbY(lastAnimatedScroll);
                boolean overThumb = input.mouseX >= tx - 3 && input.mouseX <= tx + SCROLLBAR_W + 3
                    && input.mouseY >= ty && input.mouseY <= ty + th;
                if (input.leftClicked && overThumb) {
                    thumbDragging = true;
                    lastDragMouseY = (float) input.mouseY;
                }
            } else if (!input.leftDown) {
                thumbDragging = false;
            } else {
                float travel = vh - th;
                if (travel > 0.01f) {
                    float dy = (float) input.mouseY - lastDragMouseY;
                    scrollTarget = clampScroll(scrollTarget - dy * (maxScroll() / travel));
                    scrollAnim.setTarget(scrollTarget);
                }
                lastDragMouseY = (float) input.mouseY;
            }
        } else {
            thumbDragging = false;
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
        String hoveredTooltip = null;
        for (UiWidget w : content) {
            if (!visible(w)) continue;
            w.draw(renderer, mouseX, mouseY, vpWidth, vpHeight);
            if (w.tooltip != null && w.contains(mouseX, mouseY)) hoveredTooltip = w.tooltip;
        }
        renderer.endScissor();

        drawScrollbar(renderer, vpWidth, vpHeight);
        if (hoveredTooltip != null) UiTooltip.draw(renderer, hoveredTooltip, mouseX, mouseY, vpWidth, vpHeight);
    }

    private void drawScrollbar(UiRenderer renderer, int vpWidth, int vpHeight) {
        if (!hasScrollbar()) return;
        float th = thumbHeight();
        float tx = vx + vw - SCROLLBAR_W - SCROLLBAR_MARGIN;
        float ty = thumbY(lastAnimatedScroll);
        renderer.drawRoundedRect(tx, vy, tx + SCROLLBAR_W, vy + vh, SCROLLBAR_W / 2f, UiTheme.TRACK_OFF, vpWidth, vpHeight);
        renderer.drawRoundedRect(tx, ty, tx + SCROLLBAR_W, ty + th, SCROLLBAR_W / 2f,
            thumbDragging ? UiTheme.ACCENT : UiTheme.TEXT_MUTED, vpWidth, vpHeight);
    }
}
