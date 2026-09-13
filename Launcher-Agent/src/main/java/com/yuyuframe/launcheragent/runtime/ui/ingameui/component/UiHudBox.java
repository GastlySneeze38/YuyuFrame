package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.apigraphic.hud.HudPanelRenderer;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.ui.HudConfigStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Représente un {@link HudElement} dans l'éditeur (UiHudEditorScreen) — MÊME
 * rendu que le panneau réel affiché en jeu (voir HudPanelRenderer, partagé
 * avec HudOverlayRenderer), pas un simple contour : l'éditeur doit montrer
 * exactement ce que le joueur verra une fois sorti du mode édition. Glissable
 * librement, aimanté par {@link HudSnapEngine} (écran, autres éléments,
 * éléments vanilla, espacements), Alt maintenu pour suspendre l'aimant.
 *
 * Écrit sa position dans le modèle à CHAQUE frame de drag, pas seulement au
 * relâchement : le modèle reflète toujours exactement ce qui est affiché. Au
 * DÉPÔT seulement, l'ancre est choisie d'après la position
 * ({@link HudElement#setScreenPositionAutoAnchor}).
 */
public class UiHudBox extends UiWidget {

    private static final float BORDER_W = 2f;
    private static final float GRIP_SIZE = 10f;
    /**
     * Pas d'ÉCHELLE (pas de pixels) du redimensionnement — après inspection
     * du fonctionnement réel d'OneConfig : une taille de base + un seul
     * multiplicateur, redimensionné UNIQUEMENT en diagonale (jamais largeur
     * OU hauteur indépendamment). Utilisé quand aucun repère de taille
     * (voir {@link #snapScale}) n'est à portée.
     */
    private static final float SCALE_GRID = 0.1f;
    private static final UiColor SNAPPED_GREEN = new UiColor(120, 220, 140, 255);

    private final HudElement element;
    private final List<UiHudBox> siblings; // toutes les boîtes de l'éditeur (soi-même inclus)
    private boolean dragging;
    private float grabDX, grabDY;
    private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);
    private final UiAnimatedFloat gripHoverAnim = new UiAnimatedFloat(0f, 16f);

    /** Résultat d'aimantation de la frame de glisser en cours, {@code null} hors glisser. */
    private HudSnapEngine.Result activeSnap;
    /** Repères accrochés à la frame précédente — pour l'hystérésis du moteur. */
    private String prevKeyX, prevKeyY;

    // Redimensionnement — poignée au coin visuellement bas-droite (loin de
    // l'étiquette de nom, en haut), ancrée sur le coin opposé : x et le bord
    // haut y+h restent fixes pendant tout le glissement.
    private boolean resizing;
    private float resizeAnchorX, resizeAnchorTopY;
    /** Repère de taille accroché (« Taille naturelle », « Même largeur que FPS »…), {@code null} = cran de grille. */
    private String resizeSnapLabel;

    /** Sélection clavier (flèches) — posée par l'éditeur. */
    private boolean selected;
    private Runnable onSelect;

    public UiHudBox(HudElement element, int vpWidth, int vpHeight, List<UiHudBox> siblings) {
        super(element.screenX(vpWidth), element.screenY(vpHeight), element.w, element.h);
        this.element = element;
        this.siblings = siblings;
    }

    public HudElement element() { return element; }
    public boolean isDragging() { return dragging; }
    public boolean isResizing() { return resizing; }
    public HudSnapEngine.Result activeSnap() { return activeSnap; }
    public String resizeSnapLabel() { return resizeSnapLabel; }
    public void setSelected(boolean selected) { this.selected = selected; }
    public void setOnSelect(Runnable onSelect) { this.onSelect = onSelect; }

    private boolean overGrip(double mx, double my) {
        return mx >= x + w - GRIP_SIZE && mx <= x + w && my >= y && my <= y + GRIP_SIZE;
    }

    /** Échelle GUI (pixels écran par pixel GUI) — l'entier de vanilla quand il est lisible. */
    static float guiScale(int fbWidth) {
        float s = UiRenderer.guiScale(fbWidth);
        return s > 0f ? s : 1f;
    }

    /** Cibles d'aimantation : les AUTRES boîtes, puis les éléments vanilla fixes. */
    public List<HudSnapEngine.Rect> snapTargets(int fbHeight, float scale) {
        List<HudSnapEngine.Rect> out = new ArrayList<HudSnapEngine.Rect>();
        for (UiHudBox other : siblings) {
            if (other == this) continue;
            out.add(new HudSnapEngine.Rect(other.x, other.y, other.w, other.h,
                HudSnapEngine.Kind.ELEMENT, other.element.displayName));
        }
        out.addAll(HudSnapEngine.vanillaTargets(ClientData.guiSize(), fbHeight, scale));
        return out;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        // Voir HudElement.refreshSize() : un contenu de largeur variable
        // (FPS/Ping) doit rester à jour même dans l'éditeur — sauté pendant un
        // drag/resize actif pour ne jamais contredire le geste en cours.
        if (!dragging && !resizing) {
            element.refreshSize();
            x = element.screenX(vpWidth);
            y = element.screenY(vpHeight);
            w = element.w;
            h = element.h;
        }

        hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);

        // Panneau + contenu — dessin PARTAGÉ avec HudOverlayRenderer.
        HudPanelRenderer.draw(renderer, element, x, y, w, h, vpWidth, vpHeight);

        // Liseré d'accent : survol, glisser, ou sélection clavier.
        float editT = dragging || selected ? 1f : hoverAnim.get();
        if (editT > 0.01f) {
            UiColor edge = UiColor.lerp(UiColor.TRANSPARENT, UiTheme.ACCENT, editT);
            renderer.drawRoundedRect(x, y + h - BORDER_W, x + w, y + h, 0, edge, vpWidth, vpHeight); // haut
            renderer.drawRoundedRect(x, y, x + w, y + BORDER_W, 0, edge, vpWidth, vpHeight);         // bas
            renderer.drawRoundedRect(x, y, x + BORDER_W, y + h, 0, edge, vpWidth, vpHeight);         // gauche
            renderer.drawRoundedRect(x + w - BORDER_W, y, x + w, y + h, 0, edge, vpWidth, vpHeight); // droite
        }

        if (element.locked) return;
        gripHoverAnim.setTarget(resizing || overGrip(mouseX, mouseY) ? 1f : 0f);
        // Vert quand la taille est accrochée à un repère — confirmation qu'on
        // est sur « la taille proposée », pas un cran de grille quelconque.
        UiColor gripColor = resizing && resizeSnapLabel != null
            ? SNAPPED_GREEN
            : UiColor.lerp(new UiColor(255, 255, 255, 100), UiTheme.ACCENT, gripHoverAnim.get());
        renderer.drawRoundedRect(x + w - GRIP_SIZE, y, x + w, y + GRIP_SIZE, 2f, gripColor, vpWidth, vpHeight);
    }

    @Override
    public void pollContinuous(UiInputPoller input) {
        if (element.locked) return;
        if (resizing) {
            if (!input.leftDown) {
                resizing = false;
                resizeSnapLabel = null;
                element.setScreenPositionAutoAnchor(x, y, input.fbWidth, input.fbHeight);
                HudConfigStore.save();
                return;
            }
            pollResize(input);
            return;
        }

        if (!dragging) {
            if (input.leftClicked && overGrip(input.mouseX, input.mouseY)) {
                select();
                resizing = true;
                resizeAnchorX = x;
                resizeAnchorTopY = y + h;
                return;
            }
            if (input.leftClicked && contains(input.mouseX, input.mouseY)) {
                select();
                dragging = true;
                grabDX = (float) input.mouseX - x;
                grabDY = (float) input.mouseY - y;
            }
            return;
        }
        if (!input.leftDown) {
            dragging = false;
            activeSnap = null;
            prevKeyX = null;
            prevKeyY = null;
            element.setScreenPositionAutoAnchor(x, y, input.fbWidth, input.fbHeight);
            HudConfigStore.save();
            return;
        }

        float rawX = (float) input.mouseX - grabDX;
        float rawY = (float) input.mouseY - grabDY;
        float scale = guiScale(input.fbWidth);
        HudSnapEngine.Result res = HudSnapEngine.snap(rawX, rawY, w, h,
            snapTargets(input.fbHeight, scale), input.fbWidth, input.fbHeight, scale,
            prevKeyX, prevKeyY, !input.altDown);
        activeSnap = res;
        prevKeyX = res.keyX;
        prevKeyY = res.keyY;
        x = res.x;
        y = res.y;
        element.setScreenPosition(x, y, input.fbWidth, input.fbHeight);
    }

    private void select() {
        if (onSelect != null) onSelect.run();
    }

    /**
     * Redimensionnement DIAGONAL UNIQUEMENT : le déplacement souris est
     * projeté sur la diagonale du rectangle naturel, le rectangle garde donc
     * toujours ses proportions (texte jamais étiré sur un seul axe).
     */
    private void pollResize(UiInputPoller input) {
        float[] natural = element.naturalSize();
        float natW = natural[0], natH = natural[1];
        float diagLen = (float) Math.sqrt(natW * natW + natH * natH);
        float dirX = natW / diagLen, dirY = natH / diagLen;

        float rawW = (float) input.mouseX - resizeAnchorX;
        float rawH = resizeAnchorTopY - (float) input.mouseY;
        float rawScale = (rawW * dirX + rawH * dirY) / diagLen;

        // Plafonné par l'espace réellement disponible (jamais au-delà du bord).
        float maxScaleForScreen = Math.min((input.fbWidth - resizeAnchorX) / natW, resizeAnchorTopY / natH);
        float maxScale = Math.min(HudElement.MAX_SCALE, maxScaleForScreen);

        float snapped = input.altDown ? Float.NaN
            : snapScale(rawScale, natW, natH, diagLen, guiScale(input.fbWidth), maxScale);
        float finalScale;
        if (!Float.isNaN(snapped)) {
            finalScale = snapped;
        } else {
            resizeSnapLabel = null;
            finalScale = Math.round(rawScale / SCALE_GRID) * SCALE_GRID;
        }
        finalScale = Math.max(HudElement.MIN_SCALE, Math.min(maxScale, finalScale));

        element.setScale(finalScale);
        w = element.w;
        h = element.h;
        y = resizeAnchorTopY - h;
        element.setScreenPosition(x, y, input.fbWidth, input.fbHeight);
    }

    /**
     * Repères de TAILLE : taille naturelle, même échelle qu'un autre élément,
     * même largeur ou même hauteur qu'un autre. Le plus proche gagne, mesuré en
     * pixels le long de la diagonale — même zone d'aimantation que le glisser.
     *
     * @return l'échelle retenue (et {@link #resizeSnapLabel} renseigné), ou
     *     {@code NaN} si aucun repère n'est à portée
     */
    private float snapScale(float rawScale, float natW, float natH, float diagLen, float guiScale, float maxScale) {
        float threshold = HudSnapEngine.SNAP_GUI * guiScale;
        float best = Float.NaN;
        String bestLabel = null;
        float bestDist = Float.MAX_VALUE;

        List<Object[]> cands = new ArrayList<Object[]>();
        cands.add(new Object[]{ 1f, "Taille naturelle" });
        for (UiHudBox other : siblings) {
            if (other == this) continue;
            String name = other.element.displayName;
            cands.add(new Object[]{ other.element.scale, "Même échelle que " + name });
            if (natW > 0f) cands.add(new Object[]{ other.w / natW, "Même largeur que " + name });
            if (natH > 0f) cands.add(new Object[]{ other.h / natH, "Même hauteur que " + name });
        }
        for (Object[] c : cands) {
            float s = (Float) c[0];
            if (s < HudElement.MIN_SCALE || s > maxScale) continue;
            float dist = Math.abs(s - rawScale) * diagLen;
            if (dist <= threshold && dist < bestDist) {
                bestDist = dist;
                best = s;
                bestLabel = (String) c[1];
            }
        }
        resizeSnapLabel = bestLabel;
        return best;
    }

    /**
     * Déplacement fin au clavier, en pixels GUI (flèches ; ×10 avec Maj) —
     * sans aimant : c'est l'outil de précision, il doit faire exactement ce
     * qu'on lui demande. Sauvegardé et ré-ancré comme un dépôt.
     */
    public void nudge(int dxGui, int dyGui, int fbWidth, int fbHeight) {
        if (element.locked) return;
        float scale = guiScale(fbWidth);
        x = Math.max(0f, Math.min(fbWidth - w, x + dxGui * scale));
        y = Math.max(0f, Math.min(fbHeight - h, y + dyGui * scale));
        element.setScreenPositionAutoAnchor(x, y, fbWidth, fbHeight);
        HudConfigStore.save();
    }
}
