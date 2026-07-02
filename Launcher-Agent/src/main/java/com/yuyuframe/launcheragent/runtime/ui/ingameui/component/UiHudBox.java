package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.ui.HudConfigStore;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudPanelRenderer;
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
    /**
     * Pas d'ÉCHELLE (pas de pixels) du redimensionnement — après inspection
     * du fonctionnement réel d'OneConfig : une taille de base + un seul
     * multiplicateur, redimensionné UNIQUEMENT en diagonale (jamais largeur
     * OU hauteur indépendamment). Ça explique à la fois pourquoi leur texte
     * reste toujours bien placé (jamais étiré sur un seul axe) ET pourquoi
     * leurs propositions de taille sont plus fréquentes que les nôtres avant
     * ce correctif (un cran tous les 0.1 de scale, sur toute la plage —
     * beaucoup plus dense qu'un unique point d'accroche "taille naturelle").
     */
    private static final float SCALE_GRID = 0.1f;

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
    private boolean snappedToNaturalSize;

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
        // Voir HudElement.refreshSize() : un contenu de largeur variable
        // (FPS/Ping) doit rester à jour même dans l'éditeur (le jeu tourne
        // toujours derrière l'écran d'édition) — sauté pendant un drag/resize
        // actif pour ne jamais contredire le geste de l'utilisateur en cours.
        if (!dragging && !resizing) {
            element.refreshSize();
            x = element.screenX(vpWidth);
            y = element.screenY(vpHeight);
            w = element.w;
            h = element.h;
        }

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

        // Poignée de redimensionnement — cachée si l'élément est verrouillé
        // (voir HudElement.locked, réglage générique façon OneConfig) : rien
        // à saisir puisque pollContinuous() ignore aussi le glisser/redimensionner.
        if (element.locked) return;
        gripHoverAnim.setTarget(resizing || overGrip(mouseX, mouseY) ? 1f : 0f);
        // Vert quand aligné sur la taille NATURELLE du contenu (voir
        // NATURAL_SIZE_SNAP) — confirmation visuelle explicite qu'on est sur
        // "la taille proposée", pas juste un cran de grille quelconque.
        UiColor gripColor = snappedToNaturalSize
            ? new UiColor(120, 220, 140, 255)
            : UiColor.lerp(new UiColor(255, 255, 255, 100), UiTheme.ACCENT, gripHoverAnim.get());
        renderer.drawRoundedRect(x + w - GRIP_SIZE, y, x + w, y + GRIP_SIZE, 2f, gripColor, vpWidth, vpHeight);
    }

    @Override
    public void pollContinuous(UiInputPoller input) {
        if (element.locked) return;
        if (resizing) {
            // Sauvegarde à la FIN du geste (relâchement), pas à chaque frame
            // de glissement — écrire sur disque 60x/seconde pendant un resize
            // serait un gaspillage inutile pour un résultat identique.
            if (!input.leftDown) { resizing = false; snappedToNaturalSize = false; HudConfigStore.save(); return; }

            // Redimensionnement DIAGONAL UNIQUEMENT : le déplacement souris
            // est PROJETÉ sur la diagonale du rectangle "naturel" (largeur ET
            // hauteur ensemble), jamais décomposé en largeur/hauteur libres —
            // le rectangle garde donc TOUJOURS ses proportions naturelles,
            // le texte ne se retrouve jamais étiré/écrasé sur un seul axe.
            float[] natural = element.naturalSize();
            float natW = natural[0], natH = natural[1];
            float diagLen = (float) Math.sqrt(natW * natW + natH * natH);
            float dirX = natW / diagLen, dirY = natH / diagLen;

            float rawW = (float) input.mouseX - resizeAnchorX;
            float rawH = resizeAnchorTopY - (float) input.mouseY;
            float projected = rawW * dirX + rawH * dirY; // distance le long de la diagonale
            float rawScale = projected / diagLen;

            // Plafonné en plus par l'espace réellement disponible (jamais
            // au-delà du bord de l'écran) — recalculé en scale plutôt qu'en
            // pixels bruts pour ne jamais casser les proportions même en
            // butant contre un bord.
            float maxScaleForScreen = Math.min(
                (input.fbWidth - resizeAnchorX) / natW,
                resizeAnchorTopY / natH
            );
            float maxScale = Math.min(HudElement.MAX_SCALE, maxScaleForScreen);

            float snappedScale = Math.round(rawScale / SCALE_GRID) * SCALE_GRID;
            snappedScale = Math.max(HudElement.MIN_SCALE, Math.min(maxScale, snappedScale));
            // Vert quand PILE sur la taille naturelle (scale=1) — le cran le
            // plus significatif parmi tous ceux de la grille 0.1.
            snappedToNaturalSize = Math.abs(snappedScale - 1f) < 0.001f;

            element.setScale(snappedScale);
            w = element.w;
            h = element.h;
            y = resizeAnchorTopY - h;
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
            HudConfigStore.save();
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
