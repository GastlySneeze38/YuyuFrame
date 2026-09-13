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

    /** Épaisseur des lignes de guide, en pixels écran. */
    private static final float GUIDE_THICKNESS = 2f;

    /**
     * Pendant un glisser : une ligne VIOLETTE pleine, sur toute la largeur ou
     * hauteur de l'écran, par repère réellement accroché.
     *
     * <p>Retour utilisateur (2026-09-13) : la v1129 affichait aussi des
     * distances chiffrées, des guides pâles pour les repères proches, des
     * contours vanilla et des couleurs par nature de repère — « des valeurs
     * avec des lignes à moitié visibles au lieu de la ligne violette très
     * visible et très compréhensible ». On revient à ce langage : le moteur
     * (HudSnapEngine) calcule toujours tout, seul l'affichage est réduit aux
     * repères accrochés, dans une seule couleur.
     */
    private void drawPlacementAids(UiRenderer renderer) {
        for (UiHudBox box : hudBoxes) {
            if (!box.isDragging()) continue;
            HudSnapEngine.Result snap = box.activeSnap();
            if (snap == null) return;
            for (HudSnapEngine.Guide g : snap.guides) {
                if (g.preview) continue;
                float half = GUIDE_THICKNESS / 2f;
                if (g.vertical) {
                    renderer.drawRoundedRect(g.pos - half, 0, g.pos + half, screenHeight, 0,
                        UiTheme.ACCENT, screenWidth, screenHeight);
                } else {
                    renderer.drawRoundedRect(0, g.pos - half, screenWidth, g.pos + half, 0,
                        UiTheme.ACCENT, screenWidth, screenHeight);
                }
            }
            return;
        }
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
