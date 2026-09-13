package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.apigraphic.hud.HudRegistry;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.layout.LayoutSolver;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyNode;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.HudSnapEngine;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiHudBox;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * Éditeur HUD — une boîte glissable par élément enregistré (HudRegistry),
 * ouvert depuis l'item "Modifier le HUD" en bas de la sidebar de l'accueil.
 *
 * Fond quasi-transparent (voir overlayColor) : le jeu (monde + HUD vanilla,
 * hotbar/vie/faim) reste visible en direct derrière, comme le vrai éditeur
 * HUD OneConfig — nos Screen custom ne pausent pas la simulation (ce n'est
 * pas le menu pause vanilla), le monde tournait déjà derrière nos autres
 * écrans, simplement masqué par leur fond presque opaque.
 *
 * Pas de bandeau de titre : la plupart des éléments HUD (dont 3 de nos 4
 * factices) s'ancrent en haut de l'écran — un bandeau fixe là-haut gênerait
 * leur positionnement exactement comme le faisait l'ancien bouton retour en
 * haut-gauche (voir BackButton, déplacé au centre pour la même raison).
 *
 * Layout reconstruit au redimensionnement (comme les autres écrans) — sans
 * rien perdre : la position de chaque UiHudBox vit dans son HudElement
 * (ancre+décalage), pas dans le widget lui-même, donc recréer les boîtes
 * recalcule juste leur position écran à partir du même modèle.
 */
public class UiHudEditorScreen extends UiScreenBase {

    private static final UiColor EDITOR_OVERLAY = new UiColor(6, 6, 10, 60);

    /**
     * Moins d'étages que les autres écrans (4) — la seule surface de verre ici
     * est le petit bouton "Retour" au centre, et CET écran est justement celui
     * où le jeu doit rester le plus fluide et le plus lisible (on positionne
     * des éléments HUD par rapport à ce qu'on voit en direct). Payer la chaîne
     * complète pour un unique bouton n'aurait pas de contrepartie visible.
     */
    private static final int GLASS_PASSES = 3;

    private final Object lastScreen;
    private int lastLayoutWidth = -1, lastLayoutHeight = -1;
    private List<UiHudBox> hudBoxes = new ArrayList<>();

    public UiHudEditorScreen(Object lastScreen) {
        super("Édition du HUD");
        this.lastScreen = lastScreen;
        this.escapeTarget = lastScreen;
    }

    @Override
    protected UiColor overlayColor() {
        return EDITOR_OVERLAY;
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (screenWidth > 0 && screenHeight > 0
                && (screenWidth != lastLayoutWidth || screenHeight != lastLayoutHeight)) {
            buildLayout();
            lastLayoutWidth = screenWidth;
            lastLayoutHeight = screenHeight;
        }
        // Le VOILE de fond reste volontairement quasi-transparent (voir
        // EDITOR_OVERLAY/javadoc de classe) : c'est le seul écran où voir le
        // jeu net EST la fonction. Seul le bouton "Retour" est en verre.
        UiRenderer.get(getClass().getClassLoader()).beginGlassFrame(GLASS_PASSES, screenWidth, screenHeight);
        super.uiDraw(mouseX, mouseY);
        try {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());

            // Aides de placement — PAR-DESSUS les boîtes.
            drawPlacementAids(renderer);
            // Voir UiModConfigScreen — ré-appliqué pour couvrir les lignes de guide ci-dessus.
            drawRevealVeil(renderer);
        } catch (Throwable ignored) {}
    }

    private void buildLayout() {
        widgets.clear();
        BackButton back = new BackButton();
        // Bouton CENTRÉ via Taffy (justifyContent + alignItems) au lieu du
        // `(screenWidth - W) / 2` du constructeur — c'est le seul élément
        // positionné de cet écran, les boîtes HUD tirant leur position de leur
        // propre modèle (ancre + décalage), jamais d'un calcul d'écran.
        TaffyStyle rootStyle = new TaffyStyle()
            .size(TaffyStyle.px(screenWidth), TaffyStyle.px(screenHeight))
            .justifyContent("center")
            .alignItems("center");
        TaffyNode root = new TaffyNode("screen", rootStyle);
        root.child(LayoutSolver.box("back", BackButton.W, BackButton.H));
        LayoutSolver.Solved layout = LayoutSolver.solve(root, screenWidth, screenHeight);
        // Repli : le constructeur a déjà posé le centrage manuel.
        if (layout != null) layout.apply("back", back);
        widgets.add(back);

        hudBoxes = new ArrayList<>();
        for (HudElement element : HudRegistry.elements()) {
            final UiHudBox box = new UiHudBox(element, screenWidth, screenHeight, hudBoxes);
            box.setOnSelect(() -> select(box));
            // La sélection survit à la reconstruction (redimensionnement de fenêtre).
            if (selectedElement == element) box.setSelected(true);
            hudBoxes.add(box);
            widgets.add(box);
        }
    }

    // ── Sélection et déplacement fin au clavier ────────────────────────────

    /** Élément sélectionné (dernier cliqué) — par élément et non par boîte, les boîtes étant recréées au redimensionnement. */
    private HudElement selectedElement;

    private void select(UiHudBox box) {
        selectedElement = box.element();
        for (UiHudBox b : hudBoxes) b.setSelected(b == box);
    }

    private UiHudBox selectedBox() {
        for (UiHudBox b : hudBoxes) if (b.element() == selectedElement) return b;
        return null;
    }

    /**
     * Flèches : déplace l'élément sélectionné d'un pixel GUI, de dix avec Maj.
     * Reçu par le vrai {@code keyPressed} de l'écran, donc répété en
     * maintenant la touche, comme dans n'importe quel éditeur.
     */
    @Override
    protected boolean onKeyPressed(int glfwKeyCode) {
        UiHudBox box = selectedBox();
        if (box == null) return false;
        int step = shiftHeld() ? 10 : 1;
        switch (glfwKeyCode) {
            case 263: box.nudge(-step, 0, screenWidth, screenHeight); return true; // GLFW_KEY_LEFT
            case 262: box.nudge(step, 0, screenWidth, screenHeight); return true;  // GLFW_KEY_RIGHT
            case 265: box.nudge(0, step, screenWidth, screenHeight); return true;  // GLFW_KEY_UP (Y vers le haut)
            case 264: box.nudge(0, -step, screenWidth, screenHeight); return true; // GLFW_KEY_DOWN
            default: return false;
        }
    }

    // ── Aides de placement ─────────────────────────────────────────────────

    private static final UiColor GUIDE_SCREEN = UiTheme.ACCENT;
    private static final UiColor GUIDE_ELEMENT = new UiColor(90, 200, 255, 255);
    private static final UiColor GUIDE_VANILLA = new UiColor(120, 220, 140, 255);
    private static final UiColor GUIDE_SPACING = new UiColor(235, 120, 225, 255);
    private static final UiColor VANILLA_OUTLINE = new UiColor(120, 220, 140, 70);
    private static final UiColor MEASURE_LINE = new UiColor(255, 255, 255, 140);
    private static final UiColor LABEL_BG = new UiColor(10, 10, 14, 200);
    private static final float LABEL_SCALE = 0.34f;

    /**
     * Pendant un glisser : contours des éléments vanilla aimantables, guides
     * colorés par nature de repère (écran, élément, vanilla, espacement ; pâles
     * = proches mais pas accrochés), et distances aux voisins en pixels GUI.
     * Élément sélectionné au repos : ses distances seules, pour régler aux
     * flèches. Redimensionnement : le repère de taille accroché.
     */
    private void drawPlacementAids(UiRenderer renderer) {
        UiHudBox active = null;
        for (UiHudBox b : hudBoxes) if (b.isDragging() || b.isResizing()) { active = b; break; }
        UiHudBox focus = active != null ? active : selectedBox();
        if (focus == null) return;

        float scale = guiScale();
        List<HudSnapEngine.Rect> targets =
            focus.snapTargets(screenHeight, scale);

        if (active != null && active.isDragging()) {
            for (HudSnapEngine.Rect t : targets) {
                if (t.kind == HudSnapEngine.Kind.VANILLA) {
                    outline(renderer, t.x, t.y, t.w, t.h, VANILLA_OUTLINE);
                }
            }
            HudSnapEngine.Result snap = active.activeSnap();
            if (snap != null) {
                for (HudSnapEngine.Guide g : snap.guides) {
                    drawGuide(renderer, g);
                }
            }
        }

        if (active != null && active.isResizing()) {
            String label = active.resizeSnapLabel();
            if (label != null) drawLabel(renderer, label, focus.x + focus.w / 2f, focus.y - 12f);
            return;
        }

        for (HudSnapEngine.Measure m :
                HudSnapEngine.measure(
                    focus.x, focus.y, focus.w, focus.h, targets, screenWidth, screenHeight, scale)) {
            line(renderer, m.x1, m.y1, m.x2, m.y2, MEASURE_LINE);
            drawLabel(renderer, String.valueOf(m.gui), (m.x1 + m.x2) / 2f, (m.y1 + m.y2) / 2f);
        }
    }

    private float guiScale() {
        float s = UiRenderer.guiScale(screenWidth);
        return s > 0f ? s : 1f;
    }

    private void drawGuide(UiRenderer renderer,
                           HudSnapEngine.Guide g) {
        UiColor base;
        switch (g.kind) {
            case ELEMENT: base = GUIDE_ELEMENT; break;
            case VANILLA: base = GUIDE_VANILLA; break;
            case SPACING: base = GUIDE_SPACING; break;
            default: base = GUIDE_SCREEN;
        }
        UiColor c = g.preview ? new UiColor(Math.round(base.r * 255f), Math.round(base.g * 255f),
            Math.round(base.b * 255f), 70) : base;
        if (g.vertical) line(renderer, g.pos, g.from, g.pos, g.to, c);
        else line(renderer, g.from, g.pos, g.to, g.pos, c);
    }

    /** Segment d'un pixel d'épaisseur, horizontal ou vertical. */
    private void line(UiRenderer renderer, float x1, float y1, float x2, float y2, UiColor c) {
        if (Math.abs(x1 - x2) < 0.01f) {
            renderer.drawRoundedRect(x1 - 0.5f, Math.min(y1, y2), x1 + 0.5f, Math.max(y1, y2), 0, c, screenWidth, screenHeight);
        } else {
            renderer.drawRoundedRect(Math.min(x1, x2), y1 - 0.5f, Math.max(x1, x2), y1 + 0.5f, 0, c, screenWidth, screenHeight);
        }
    }

    private void outline(UiRenderer renderer, float x, float y, float w, float h, UiColor c) {
        line(renderer, x, y, x + w, y, c);
        line(renderer, x, y + h, x + w, y + h, c);
        line(renderer, x, y, x, y + h, c);
        line(renderer, x + w, y, x + w, y + h, c);
    }

    /** Étiquette centrée sur (cx, cy), sur fond sombre pour rester lisible sur le jeu. */
    private void drawLabel(UiRenderer renderer, String text, float cx, float cy) {
        float tw = renderer.textWidth(text, LABEL_SCALE);
        float th = 12f;
        float pad = 3f;
        float x = Math.max(0f, Math.min(screenWidth - tw - 2 * pad, cx - tw / 2f - pad));
        float y = Math.max(0f, Math.min(screenHeight - th, cy - th / 2f));
        renderer.drawRoundedRect(x, y, x + tw + 2 * pad, y + th, 3f, LABEL_BG, screenWidth, screenHeight);
        renderer.drawText(text, x + pad, y + 3f, UiTheme.TEXT_PRIMARY, LABEL_SCALE, screenWidth, screenHeight);
    }

    /**
     * Au centre de l'écran plutôt qu'en haut-gauche — c'est justement
     * l'endroit où les éléments HUD sont le MOINS souvent placés (la plupart
     * s'ancrent aux coins/bords), donc le bouton ne gêne quasiment jamais le
     * positionnement d'une boîte, contrairement à son ancienne place en
     * haut-gauche (l'un des coins les plus utilisés).
     */
    private final class BackButton extends UiWidget {
        static final float W = 100f, H = 36f;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        BackButton() { super((screenWidth - W) / 2f, (screenHeight - H) / 2f, W, H); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float hover = hoverAnim.get();
            UiColor bg = UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hover);
            UiColor editorBorder = renderer.isGlassAvailable()
                ? UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.GLASS_BORDER_HOVER, hover) : null;
            renderer.drawGlassPanel(x, y, x + w, y + h, UiTheme.RADIUS_MD,
                UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_FIELD, bg,
                editorBorder, Math.max(1f, UiTheme.scaled(1f)), vpWidth, vpHeight);
            // "«" (chevron double, U+00AB) plutôt que "<" — voir UiModConfigScreen.BackButton pour le détail du choix.
            // Glisse vers la gauche au survol : affordance "on te ramène en arrière".
            String label = "« " + Lang.tr("Retour");
            float tw = renderer.textWidth(label, 0.44f);
            float slide = hover * UiTheme.scaled(3f);
            renderer.drawText(label, x + (w - tw) / 2f - slide, y + h / 2f - 5f, UiTheme.TEXT_PRIMARY, 0.44f, vpWidth, vpHeight);
        }

        @Override
        public void onClick() { closeTo(lastScreen); }
    }
}
