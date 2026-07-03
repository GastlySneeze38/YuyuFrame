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

    private static final float HEADER_H = 72f;
    private static final float SIDE_MARGIN = 48f;
    private static final float SUB_SIDEBAR_W = 180f;
    private static final float CONTENT_MAX_W = 620f;

    private final Object lastScreen;
    private final LauncherModule module;
    private LinkedHashMap<String, List<UiWidget>> categoryWidgets = new LinkedHashMap<>();
    private String activeCategory;
    private UiScrollContainer scroll;
    private float panelX, panelY, panelW, panelH;
    private int lastLayoutWidth = -1, lastLayoutHeight = -1;

    public UiModConfigScreen(Object lastScreen, LauncherModule module) {
        super(module.name);
        this.lastScreen = lastScreen;
        this.module = module;
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (screenWidth > 0 && screenHeight > 0
                && (screenWidth != lastLayoutWidth || screenHeight != lastLayoutHeight)) {
            buildLayout();
            lastLayoutWidth = screenWidth;
            lastLayoutHeight = screenHeight;
        }
        super.uiDraw(mouseX, mouseY);
        try {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
            renderer.drawText(UiFont.BOLD, module.name, SIDE_MARGIN + 46, screenHeight - 44, UiTheme.TEXT_PRIMARY, 0.68f, screenWidth, screenHeight);
            UiPanel.draw(renderer, panelX, panelY, panelW, panelH, null, screenWidth, screenHeight);
            if (scroll != null) scroll.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
        } catch (Throwable ignored) {}
    }

    @Override
    public void uiPollInput(UiInputPoller input) {
        super.uiPollInput(input);
        if (scroll != null) scroll.pollInput(input);
    }

    private void buildLayout() {
        widgets.clear();
        widgets.add(new BackButton());

        float panelXLocal = SIDE_MARGIN + SUB_SIDEBAR_W + 16f;
        float panelWLocal = Math.min(CONTENT_MAX_W, screenWidth - panelXLocal - SIDE_MARGIN);
        float panelTop = screenHeight - HEADER_H;
        float panelBottom = 20f;
        this.panelX = panelXLocal;
        this.panelY = panelBottom;
        this.panelW = panelWLocal;
        this.panelH = panelTop - panelBottom;

        float rowX = panelX + 16f;
        float rowW = panelW - 32f;
        scroll = new UiScrollContainer(rowX, panelY + 16f, rowW, panelH - 32f);

        categoryWidgets = ConfigScreenBuilder.build(module, rowX, rowW);
        if (activeCategory == null || !categoryWidgets.containsKey(activeCategory)) {
            activeCategory = categoryWidgets.isEmpty() ? null : categoryWidgets.keySet().iterator().next();
        }

        int i = 0;
        for (String category : categoryWidgets.keySet()) {
            widgets.add(new CategoryTab(SIDE_MARGIN, panelTop - 24f - i * 38f, SUB_SIDEBAR_W, category));
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

        CategoryTab(float x, float y, float w, String name) {
            super(x, y, w, 34f);
            this.name = name;
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            boolean active = name.equals(activeCategory);
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            if (active) {
                renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, UiTheme.SIDEBAR_ACTIVE, vpWidth, vpHeight);
                renderer.drawRoundedRect(x, y + 3, x + 3, y + h - 3, 1.5f, UiTheme.ACCENT, vpWidth, vpHeight);
            } else {
                UiColor bg = UiColor.lerp(UiColor.TRANSPARENT, UiTheme.SIDEBAR_HOVER, hoverAnim.get());
                renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            }
            renderer.drawText(name, x + 14, y + h / 2f - 5f, active ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_MUTED, 0.48f, vpWidth, vpHeight);
        }

        @Override
        public void onClick() { switchCategory(name); }
    }

    private final class BackButton extends UiWidget {
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        BackButton() { super(SIDE_MARGIN, screenHeight - HEADER_H + 20f, 38f, 38f); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            UiColor bg = UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hoverAnim.get());
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            String arrow = "<";
            float tw = renderer.textWidth(arrow, 0.6f);
            renderer.drawText(arrow, x + (w - tw) / 2f, y + h / 2f - 7f, UiTheme.TEXT_PRIMARY, 0.6f, vpWidth, vpHeight);
        }

        @Override
        public void onClick() { closeTo(lastScreen); }
    }
}
