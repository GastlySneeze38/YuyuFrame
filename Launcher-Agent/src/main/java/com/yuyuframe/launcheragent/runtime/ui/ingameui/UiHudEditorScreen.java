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

    /** Les boîtes sont là où le HUD sera : elles ne montent pas en cascade à l'ouverture. */
    @Override
    protected boolean cascadeWidgets() {
        return false;
    }

    /**
     * Un geste qui reconstruit l'écran (moment choisi, module éteint), remis
     * au début de l'image suivante : il naît pendant la lecture des clics, qui
     * parcourt la liste des widgets — la vider à ce moment-là la casserait.
     */
    private Runnable pending;

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (pending != null) {
            Runnable run = pending;
            pending = null;
            run.run();
        }
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
        // Le temps de dessiner les boîtes, les éléments sans rien de réel à
        // montrer (pas d'armure, aucun effet, pas de joueur depuis
        // l'écran-titre) affichent un exemple : on règle un HUD plein.
        com.yuyuframe.launcheragent.apigraphic.hud.HudPreview.set(true);
        try {
            super.uiDraw(mouseX, mouseY);
        } finally {
            com.yuyuframe.launcheragent.apigraphic.hud.HudPreview.set(false);
        }
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

        // Le menu des moments, au-dessus du bouton Retour — seulement si un
        // élément en déclare un.
        List<String> moments = new ArrayList<>();
        for (HudElement element : HudRegistry.elements()) {
            if (element.moment != null && !moments.contains(element.moment)) moments.add(element.moment);
        }
        if (!moments.contains(shownMoment)) shownMoment = null;
        MomentMenu menu = moments.isEmpty() ? null : new MomentMenu(back, moments);
        ElementActions actions = new ElementActions();

        hudBoxes = new ArrayList<>();
        for (HudElement element : HudRegistry.elements()) {
            // Un moment choisi : seuls ses éléments, et ceux qui sont toujours là.
            if (shownMoment != null && element.moment != null && !shownMoment.equals(element.moment)) continue;
            final UiHudBox box = new UiHudBox(element, screenWidth, screenHeight, hudBoxes);
            box.setOnSelect(() -> select(box));
            box.setCovered((mx, my) -> (menu != null && menu.covers(mx, my)) || actions.covers(mx, my));
            // La sélection survit à la reconstruction (redimensionnement de fenêtre).
            if (selectedElement == element) box.setSelected(true);
            hudBoxes.add(box);
            widgets.add(box);
        }
        // Après les boîtes : par-dessus elles.
        widgets.add(actions);
        if (menu != null) widgets.add(menu);
    }

    // ── Les moments d'affichage ────────────────────────────────────────────

    /** Le moment montré (voir {@link HudElement#moment}) ; {@code null} : tous. */
    private String shownMoment;

    /**
     * Trois petits points au-dessus du bouton Retour : la liste des moments
     * d'affichage (« Tout », « Début du duel », « Fin du duel »…). En choisir
     * un ne montre que ses éléments, plus ceux qui sont toujours à l'écran :
     * des éléments jamais visibles ensemble en jeu ne s'empilent plus ici.
     */
    private final class MomentMenu extends UiWidget {
        private final List<String> options = new ArrayList<>();
        private final UiAnimatedFloat hover = new UiAnimatedFloat(0f, 16f);
        private boolean open;
        private final float rowH = UiTheme.scaled(30f);
        private final float listW = UiTheme.scaled(210f);

        MomentMenu(UiWidget back, List<String> moments) {
            super(0f, 0f, UiTheme.scaled(36f), UiTheme.scaled(28f));
            x = back.x + back.w / 2f - w / 2f;
            y = back.y + back.h + UiTheme.scaled(10f);
            options.add(null);
            options.addAll(moments);
        }

        /** Les lignes de la liste, au-dessus du bouton, du haut vers le bas. */
        private float listLeft() {
            return x + w / 2f - listW / 2f;
        }

        private float listBottom() {
            return y + h + UiTheme.scaled(6f);
        }

        private float listTop() {
            return listBottom() + rowH * options.size() + UiTheme.scaled(8f);
        }

        private boolean onButton(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }

        private int rowAt(double mx, double my) {
            if (!open || mx < listLeft() || mx > listLeft() + listW) return -1;
            float top = listTop() - UiTheme.scaled(4f);
            for (int i = 0; i < options.size(); i++) {
                float rowTop = top - i * rowH;
                if (my <= rowTop && my > rowTop - rowH) return i;
            }
            return -1;
        }

        /** Le bouton, et la liste quand elle est ouverte. */
        boolean covers(double mx, double my) {
            return onButton(mx, my) || (open && mx >= listLeft() && mx <= listLeft() + listW && my >= listBottom() && my <= listTop());
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            hover.setTarget(onButton(mouseX, mouseY) || open ? 1f : 0f);
            float t = hover.get();
            UiColor bg = UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, t);
            r.drawGlassPanel(x, y, x + w, y + h, h / 2f, UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_FIELD, bg,
                r.isGlassAvailable() ? UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.ACCENT, t) : null, Math.max(1f, UiTheme.scaled(1f)), vw, vh);
            // Les trois points, dessinés : un vrai rond chacun.
            float dot = UiTheme.scaled(4f);
            float gap = UiTheme.scaled(4f);
            float cx = x + w / 2f, cy = y + h / 2f;
            for (int i = -1; i <= 1; i++) {
                float dx = cx + i * (dot + gap) - dot / 2f;
                r.drawRoundedRect(dx, cy - dot / 2f, dx + dot, cy + dot / 2f, dot / 2f,
                    shownMoment != null ? UiTheme.ACCENT : UiTheme.TEXT_PRIMARY, vw, vh);
            }
            if (!open) return;
            float left = listLeft();
            r.drawGlassPanel(left, listBottom(), left + listW, listTop(), UiTheme.RADIUS_MD, UiTheme.GLASS_TINT,
                UiTheme.GLASS_STRENGTH_FIELD, UiTheme.CARD_BG, r.isGlassAvailable() ? UiTheme.GLASS_BORDER : null,
                Math.max(1f, UiTheme.scaled(1f)), vw, vh);
            float top = listTop() - UiTheme.scaled(4f);
            int hovered = rowAt(mouseX, mouseY);
            float scale = UiTheme.scaled(0.4f);
            for (int i = 0; i < options.size(); i++) {
                String option = options.get(i);
                float rowTop = top - i * rowH;
                boolean chosen = option == null ? shownMoment == null : option.equals(shownMoment);
                if (i == hovered || chosen) {
                    r.drawRoundedRect(left + UiTheme.scaled(4f), rowTop - rowH + UiTheme.scaled(2f), left + listW - UiTheme.scaled(4f),
                        rowTop - UiTheme.scaled(2f), UiTheme.scaled(6f), chosen ? UiTheme.ACCENT.withAlpha(0.35f) : UiTheme.CARD_HOVER, vw, vh);
                }
                String label = Lang.tr(option == null ? "Tout afficher" : option);
                r.drawText(label, left + UiTheme.scaled(14f), rowTop - rowH / 2f - UiTheme.scaled(5f),
                    chosen ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_SECONDARY, scale, vw, vh);
            }
        }

        @Override
        public void pollContinuous(com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller input) {
            if (!input.leftClicked) return;
            if (onButton(input.mouseX, input.mouseY)) {
                open = !open;
                return;
            }
            int row = rowAt(input.mouseX, input.mouseY);
            if (row >= 0) {
                String chosen = options.get(row);
                open = false;
                pending = () -> {
                    shownMoment = chosen;
                    buildLayout();
                };
                return;
            }
            open = false;
        }
    }

    // ── Les commandes d'un élément ─────────────────────────────────────────

    private static final String POWER_ICON = com.yuyuframe.launcheragent.runtime.ui.LauncherModule.icons8("shutdown");
    private static final String GEAR_ICON = com.yuyuframe.launcheragent.runtime.ui.LauncherModule.icons8("settings");

    /** Le module qui affiche cet élément, {@code null} s'il n'est à aucun. */
    private static com.yuyuframe.launcheragent.runtime.ui.LauncherModule ownerOf(HudElement element) {
        for (com.yuyuframe.launcheragent.runtime.ui.LauncherModule module : com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry.all()) {
            if (module instanceof com.yuyuframe.launcheragent.runtime.ui.HudElementOwner
                    && ((com.yuyuframe.launcheragent.runtime.ui.HudElementOwner) module).hudElements().contains(element)) {
                return module;
            }
        }
        return null;
    }

    /**
     * Deux petits ronds de verre au-dessus de l'élément sélectionné : éteindre
     * son module, ou ouvrir ses réglages. Ils suivent la boîte pendant qu'on
     * la déplace, et passent dessous quand elle touche le haut de l'écran.
     */
    private final class ElementActions extends UiWidget {
        private final UiAnimatedFloat hoverPower = new UiAnimatedFloat(0f, 16f);
        private final UiAnimatedFloat hoverGear = new UiAnimatedFloat(0f, 16f);
        private final float size = UiTheme.scaled(26f);
        private final float gap = UiTheme.scaled(6f);

        ElementActions() {
            super(0f, 0f, 0f, 0f);
        }

        /** Place les deux ronds d'après la boîte sélectionnée ; faux s'il n'y en a pas. */
        private boolean place() {
            UiHudBox box = selectedBox();
            if (box == null || ownerOf(box.element()) == null) {
                w = h = 0f;
                return false;
            }
            w = size * 2f + gap;
            h = size;
            x = box.x + box.w - w;
            float above = box.y + box.h + gap;
            y = above + size <= screenHeight ? above : box.y - gap - size;
            return true;
        }

        private boolean onPower(double mx, double my) {
            return w > 0f && mx >= x && mx <= x + size && my >= y && my <= y + size;
        }

        private boolean onGear(double mx, double my) {
            return w > 0f && mx >= x + size + gap && mx <= x + w && my >= y && my <= y + size;
        }

        boolean covers(double mx, double my) {
            return onPower(mx, my) || onGear(mx, my);
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            if (!place()) return;
            hoverPower.setTarget(onPower(mouseX, mouseY) ? 1f : 0f);
            hoverGear.setTarget(onGear(mouseX, mouseY) ? 1f : 0f);
            drawIconButton(r, x, POWER_ICON, "O", hoverPower.get(), new UiColor(255, 88, 96, 255), vw, vh);
            drawIconButton(r, x + size + gap, GEAR_ICON, "...", hoverGear.get(), UiTheme.ACCENT, vw, vh);
        }

        /** Un rond de verre, teinté de {@code tone} au survol, avec son icône (ou une lettre le temps qu'elle arrive). */
        private void drawIconButton(UiRenderer r, float bx, String icon, String fallback, float t, UiColor tone, int vw, int vh) {
            UiColor bg = UiColor.lerp(UiTheme.CARD_BG, tone, t * 0.55f).withAlpha(1f);
            r.drawGlassPanel(bx, y, bx + size, y + size, size / 2f, UiColor.lerp(UiTheme.GLASS_TINT, tone, t * 0.6f),
                UiTheme.GLASS_STRENGTH_FIELD, bg, r.isGlassAvailable() ? UiColor.lerp(UiTheme.GLASS_BORDER, tone, t) : null,
                Math.max(1f, UiTheme.scaled(1f)), vw, vh);
            java.awt.image.BufferedImage image = com.yuyuframe.launcheragent.apigraphic.asset.UiRemoteImage.get(icon);
            if (image != null) {
                float s = size * (0.52f + 0.06f * t);
                r.drawIcon(icon, image, bx + (size - s) / 2f, y + (size - s) / 2f, s, s, vw, vh);
            } else {
                float scale = UiTheme.scaled(0.4f);
                float tw = r.textWidth(fallback, scale);
                r.drawText(fallback, bx + (size - tw) / 2f, y + size / 2f - UiTheme.scaled(5f), UiTheme.TEXT_PRIMARY, scale, vw, vh);
            }
        }

        @Override
        public void pollContinuous(com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller input) {
            if (!input.leftClicked || !place()) return;
            UiHudBox box = selectedBox();
            com.yuyuframe.launcheragent.runtime.ui.LauncherModule module = box == null ? null : ownerOf(box.element());
            if (module == null) return;
            if (onPower(input.mouseX, input.mouseY)) {
                // Éteint : son élément quitte le HUD, donc l'éditeur.
                pending = () -> {
                    module.setEnabled(false);
                    com.yuyuframe.launcheragent.runtime.ui.HudConfigStore.save();
                    selectedElement = null;
                    buildLayout();
                };
            } else if (onGear(input.mouseX, input.mouseY)) {
                pending = () -> closeTo(new UiModConfigScreen(UiHudEditorScreen.this, module));
            }
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
     * visible et très compréhensible ». Retirés, du moteur comme de l'écran.
     */
    private void drawPlacementAids(UiRenderer renderer) {
        for (UiHudBox box : hudBoxes) {
            if (!box.isDragging()) continue;
            HudSnapEngine.Result snap = box.activeSnap();
            if (snap == null) return;
            for (HudSnapEngine.Guide g : snap.guides) {
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
