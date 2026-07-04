package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.runtime.ui.ConfigScreenBuilder;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiPanel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Page de config d'un module — bouton retour + titre fixes en haut, sous-
 * sidebar de catégories à gauche (façon OneConfig), carte de contenu
 * scrollable à droite ne montrant QUE la catégorie active.
 *
 * Cet écran ne connaît plus AUCUN module en particulier : les lignes de
 * réglage sont générées par réflexion à partir des champs annotés du module
 * (voir {@link ConfigScreenBuilder}) — un futur module se contente de
 * déclarer ses champs, jamais de code d'écran.
 *
 * Les widgets de réglage sont reconstruits à chaque {@link #buildLayout()}
 * (redimensionnement) directement depuis les VALEURS ACTUELLES des champs du
 * module (pas un modèle séparé) : rien n'est perdu, le module EST la source
 * de vérité de ses propres réglages.
 */
public class UiModConfigScreen extends UiScreenBase {

    // Non static/final — recalculées à chaque buildLayout() depuis
    // UiTheme.UI_SCALE (voir GlobalUiSettings, réglage "Taille de
    // l'interface"), même motif que UiMainMenuScreen/UiModGroupConfigScreen.
    private float HEADER_H = 72f;
    private float SIDE_MARGIN = 48f;
    private float SUB_SIDEBAR_W = 180f;
    private float CONTENT_MAX_W = 620f;

    private final Object lastScreen;
    private final LauncherModule module;
    private LinkedHashMap<String, List<UiWidget>> categoryWidgets = new LinkedHashMap<>();
    private String activeCategory;
    private UiScrollContainer scroll;
    private float panelX, panelY, panelW, panelH;
    private int lastLayoutWidth = -1, lastLayoutHeight = -1;
    private float lastUiScale = -1f;

    public UiModConfigScreen(Object lastScreen, LauncherModule module) {
        super(module.name);
        this.lastScreen = lastScreen;
        this.module = module;
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (screenWidth > 0 && screenHeight > 0
                && (screenWidth != lastLayoutWidth || screenHeight != lastLayoutHeight || UiTheme.UI_SCALE != lastUiScale)) {
            buildLayout();
            lastLayoutWidth = screenWidth;
            lastLayoutHeight = screenHeight;
            lastUiScale = UiTheme.UI_SCALE;
        }
        super.uiDraw(mouseX, mouseY);
        try {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
            renderer.drawText(UiFont.BOLD, module.name, SIDE_MARGIN + UiTheme.scaled(46f), screenHeight - UiTheme.scaled(44f),
                UiTheme.TEXT_PRIMARY, UiTheme.scaled(0.68f), screenWidth, screenHeight);
            UiPanel.draw(renderer, panelX, panelY, panelW, panelH, null, screenWidth, screenHeight);
            if (scroll != null) scroll.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            // Ré-appliqué ici (déjà dessiné une fois dans super.uiDraw()) — voir
            // sa javadoc : sans ça, le titre/panneau ci-dessus apparaîtrait
            // d'un coup sec, jamais couvert par le voile.
            drawRevealVeil(renderer);
        } catch (Throwable ignored) {}
    }

    @Override
    public void uiPollInput(UiInputPoller input) {
        super.uiPollInput(input);
        if (scroll != null) scroll.pollInput(input);
    }

    private void buildLayout() {
        HEADER_H = UiTheme.scaled(72f);
        SIDE_MARGIN = UiTheme.scaled(48f);
        SUB_SIDEBAR_W = UiTheme.scaled(180f);
        CONTENT_MAX_W = UiTheme.scaled(620f);

        widgets.clear();
        widgets.add(new BackButton());

        float panelXLocal = SIDE_MARGIN + SUB_SIDEBAR_W + UiTheme.scaled(16f);
        float panelWLocal = Math.min(CONTENT_MAX_W, screenWidth - panelXLocal - SIDE_MARGIN);
        float panelTop = screenHeight - HEADER_H;
        float panelBottom = UiTheme.scaled(20f);
        this.panelX = panelXLocal;
        this.panelY = panelBottom;
        this.panelW = panelWLocal;
        this.panelH = panelTop - panelBottom;

        float rowX = panelX + UiTheme.scaled(16f);
        float rowW = panelW - UiTheme.scaled(32f);
        scroll = new UiScrollContainer(rowX, panelY + UiTheme.scaled(16f), rowW, panelH - UiTheme.scaled(32f));

        categoryWidgets = ConfigScreenBuilder.build(module, rowX, rowW);
        if (activeCategory == null || !categoryWidgets.containsKey(activeCategory)) {
            activeCategory = categoryWidgets.isEmpty() ? null : categoryWidgets.keySet().iterator().next();
        }

        float tabH = UiTheme.scaled(34f), tabGap = UiTheme.scaled(38f), tabTopGap = UiTheme.scaled(24f);
        int i = 0;
        for (String category : categoryWidgets.keySet()) {
            widgets.add(new CategoryTab(SIDE_MARGIN, panelTop - tabTopGap - i * tabGap, SUB_SIDEBAR_W, tabH, category));
            i++;
        }

        switchCategory(activeCategory);
    }

    private void switchCategory(String category) {
        activeCategory = category;
        scroll.clear();
        if (category == null) return;
        List<UiWidget> rows = categoryWidgets.get(category);
        if (rows != null) for (UiWidget w : rows) scroll.add(w);
    }

    private final class CategoryTab extends UiWidget {
        private final String name;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        CategoryTab(float x, float y, float w, float h, String name) {
            super(x, y, w, h);
            this.name = name;
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            boolean active = name.equals(activeCategory);
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float edgeW = UiTheme.scaled(3f), edgeInset = UiTheme.scaled(3f), edgeRadius = UiTheme.scaled(1.5f);
            if (active) {
                renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, UiTheme.SIDEBAR_ACTIVE, vpWidth, vpHeight);
                renderer.drawRoundedRect(x, y + edgeInset, x + edgeW, y + h - edgeInset, edgeRadius, UiTheme.ACCENT, vpWidth, vpHeight);
            } else {
                UiColor bg = UiColor.lerp(UiColor.TRANSPARENT, UiTheme.SIDEBAR_HOVER, hoverAnim.get());
                renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            }
            renderer.drawText(name, x + UiTheme.scaled(14f), y + h / 2f - UiTheme.scaled(5f), active ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_MUTED, UiTheme.scaled(0.48f), vpWidth, vpHeight);
        }

        @Override
        public void onClick() { switchCategory(name); }
    }

    private final class BackButton extends UiWidget {
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        BackButton() { super(SIDE_MARGIN, screenHeight - HEADER_H + UiTheme.scaled(20f), UiTheme.scaled(38f), UiTheme.scaled(38f)); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float hover = hoverAnim.get();
            UiColor bg = UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hover);
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            // "«" (chevron double, U+00AB, présent dans l'atlas Latin-1 de
            // UiFont) plutôt que "<" — un simple signe "inférieur à" détourné
            // en flèche, jugé "moche" par l'utilisateur. Police BOLD (plus
            // épaisse, un vrai pictogramme plutôt qu'un caractère de
            // ponctuation) + léger glissement vers la gauche au survol
            // (affordance "on te tire vers l'arrière").
            String arrow = "«";
            float scale = UiTheme.scaled(0.7f);
            float tw = renderer.textWidth(UiFont.BOLD, arrow, scale);
            float slide = hover * UiTheme.scaled(3f);
            renderer.drawText(UiFont.BOLD, arrow, x + (w - tw) / 2f - slide, y + h / 2f - UiTheme.scaled(7f), UiTheme.TEXT_PRIMARY, scale, vpWidth, vpHeight);
        }

        @Override
        public void onClick() { closeTo(lastScreen); }
    }
}
