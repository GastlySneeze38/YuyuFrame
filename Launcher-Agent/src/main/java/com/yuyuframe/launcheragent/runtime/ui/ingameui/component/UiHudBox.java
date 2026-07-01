package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.hud.HudPanelRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;

import java.util.List;

/**
 * Représente un {@link HudElement} dans l'éditeur (UiHudEditorScreen) — MÊME
 * rendu que le panneau réel affiché en jeu (voir HudPanelRenderer, partagé
 * avec HudOverlayRenderer), pas un simple contour : l'éditeur doit montrer
 * exactement ce que le joueur verra une fois sorti du mode édition. Glissable
 * librement, avec alignement automatique (centre écran + bords des autres
 * boîtes, façon OneConfig).
 *
 * Écrit sa position dans le modèle (ancre+décalage) à CHAQUE frame de drag,
 * pas seulement au relâchement : le modèle reflète toujours exactement ce qui
 * est affiché, aucune étape de "commit" séparée.
 */
public class UiHudBox extends UiWidget {

    private static final float SNAP_THRESHOLD = 6f;
    private static final float BORDER_W = 2f;
    private static final float GRIP_SIZE = 10f;
    private static final float MIN_SIZE = 20f;

    private final HudElement element;
    private final List<UiHudBox> siblings; // toutes les boîtes de l'éditeur (soi-même inclus) — pour l'alignement bord-à-bord
    private boolean dragging;
    private float grabDX, grabDY;
    private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);
    private final UiAnimatedFloat gripHoverAnim = new UiAnimatedFloat(0f, 16f);

    private Float snapGuideX; // position (espace écran) de la ligne de guide verticale affichée cette frame, null = aucune
    private Float snapGuideY;

    // Redimensionnement — poignée au coin visuellement bas-droite (loin de
    // l'étiquette de nom, en haut), ancrée sur le coin OPPOSÉ (visuellement
    // haut-gauche : x et le bord haut y+h restent fixes pendant tout le
    // glissement, seuls w/h — et donc y, recalculé pour garder le haut fixe — bougent).
    private boolean resizing;
    private float resizeAnchorX, resizeAnchorTopY;

    public UiHudBox(HudElement element, int vpWidth, int vpHeight, List<UiHudBox> siblings) {
        super(element.screenX(vpWidth), element.screenY(vpHeight), element.w, element.h);
        this.element = element;
        this.siblings = siblings;
    }

    public Float snapGuideX() { return snapGuideX; }
    public Float snapGuideY() { return snapGuideY; }

    private boolean overGrip(double mx, double my) {
        return mx >= x + w - GRIP_SIZE && mx <= x + w && my >= y && my <= y + GRIP_SIZE;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);

        // Panneau + contenu — dessin PARTAGÉ avec HudOverlayRenderer (rendu réel
        // en jeu) : voir HudPanelRenderer pour le pourquoi.
        HudPanelRenderer.draw(renderer, element, x, y, w, h, vpWidth, vpHeight);

        // Liseré d'accent — SEUL indice visuel qu'on est en train d'éditer
        // (survol/glissement) : le panneau au repos est identique au rendu final.
        float editT = dragging ? 1f : hoverAnim.get();
        if (editT > 0.01f) {
            UiColor edge = UiColor.lerp(UiColor.TRANSPARENT, UiTheme.ACCENT, editT);
            renderer.drawRoundedRect(x, y + h - BORDER_W, x + w, y + h, 0, edge, vpWidth, vpHeight); // haut
            renderer.drawRoundedRect(x, y, x + w, y + BORDER_W, 0, edge, vpWidth, vpHeight);         // bas
            renderer.drawRoundedRect(x, y, x + BORDER_W, y + h, 0, edge, vpWidth, vpHeight);         // gauche
            renderer.drawRoundedRect(x + w - BORDER_W, y, x + w, y + h, 0, edge, vpWidth, vpHeight); // droite
        }

        // Poignée de redimensionnement — coin visuellement bas-droite.
        gripHoverAnim.setTarget(resizing || overGrip(mouseX, mouseY) ? 1f : 0f);
        UiColor gripColor = UiColor.lerp(new UiColor(255, 255, 255, 100), UiTheme.ACCENT, gripHoverAnim.get());
        renderer.drawRoundedRect(x + w - GRIP_SIZE, y, x + w, y + GRIP_SIZE, 2f, gripColor, vpWidth, vpHeight);
    }

    @Override
    public void pollContinuous(UiInputPoller input) {
        if (resizing) {
            if (!input.leftDown) { resizing = false; return; }
            // Bornée des deux côtés : MIN_SIZE en bas, et jamais au-delà du
            // bord de l'écran en haut (resizeAnchorX/TopY sont fixes pendant
            // tout le redimensionnement, voir leur déclaration).
            float newW = Math.max(MIN_SIZE, Math.min((float) input.mouseX - resizeAnchorX, input.fbWidth - resizeAnchorX));
            float newH = Math.max(MIN_SIZE, Math.min(resizeAnchorTopY - (float) input.mouseY, resizeAnchorTopY));
            w = newW;
            h = newH;
            y = resizeAnchorTopY - h;
            element.setSize(w, h);
            element.setScreenPosition(x, y, input.fbWidth, input.fbHeight);
            return;
        }

        if (!dragging) {
            if (input.leftClicked && overGrip(input.mouseX, input.mouseY)) {
                resizing = true;
                resizeAnchorX = x;
                resizeAnchorTopY = y + h;
                return;
            }
            if (input.leftClicked && contains(input.mouseX, input.mouseY)) {
                dragging = true;
                grabDX = (float) input.mouseX - x;
                grabDY = (float) input.mouseY - y;
            }
            return;
        }
        if (!input.leftDown) {
            dragging = false;
            snapGuideX = null;
            snapGuideY = null;
            return;
        }

        float rawX = (float) input.mouseX - grabDX;
        float rawY = (float) input.mouseY - grabDY;
        snapGuideX = null;
        snapGuideY = null;

        // Centre de l'écran — priorité la plus haute (alignement le plus utile).
        float centerX = input.fbWidth / 2f;
        if (Math.abs((rawX + w / 2f) - centerX) < SNAP_THRESHOLD) {
            rawX = centerX - w / 2f;
            snapGuideX = centerX;
        }
        float centerY = input.fbHeight / 2f;
        if (Math.abs((rawY + h / 2f) - centerY) < SNAP_THRESHOLD) {
            rawY = centerY - h / 2f;
            snapGuideY = centerY;
        }

        // Bords des autres boîtes (gauche-gauche, droite-droite, bas-bas, haut-haut).
        for (UiHudBox other : siblings) {
            if (other == this) continue;
            if (snapGuideX == null) {
                if (Math.abs(rawX - other.x) < SNAP_THRESHOLD) { rawX = other.x; snapGuideX = rawX; }
                else if (Math.abs((rawX + w) - (other.x + other.w)) < SNAP_THRESHOLD) { rawX = other.x + other.w - w; snapGuideX = rawX + w; }
            }
            if (snapGuideY == null) {
                if (Math.abs(rawY - other.y) < SNAP_THRESHOLD) { rawY = other.y; snapGuideY = rawY; }
                else if (Math.abs((rawY + h) - (other.y + other.h)) < SNAP_THRESHOLD) { rawY = other.y + other.h - h; snapGuideY = rawY + h; }
            }
        }

        // Jamais (même partiellement) hors écran — clampé APRÈS le snapping
        // pour ne jamais le contredire près d'un bord valide.
        x = Math.max(0f, Math.min(input.fbWidth - w, rawX));
        y = Math.max(0f, Math.min(input.fbHeight - h, rawY));
        element.setScreenPosition(x, y, input.fbWidth, input.fbHeight);
    }
}
