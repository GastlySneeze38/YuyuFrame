package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.runtime.ui.ConfigScreenBuilder;
import com.yuyuframe.launcheragent.runtime.ui.HudConfigStore;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleGroup;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiLabel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiPanel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiToggle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Page de config d'un {@link ModuleGroup} — même carcasse que
 * {@link UiModConfigScreen} (bouton retour, sous-sidebar d'onglets, carte de
 * contenu scrollable), mais UN ONGLET PAR MODULE MEMBRE du groupe (pas par
 * catégorie de champ d'un seul module) : chaque onglet montre le toggle
 * d'activation propre à CE module (déplacé ici puisqu'un module groupé n'a
 * plus sa propre carte pour le porter) suivi de ses réglages annotés
 * habituels (via {@link ConfigScreenBuilder}, TOUTES catégories confondues à
 * la suite les unes des autres — la plupart de nos modules n'en ont qu'une
 * seule de toute façon).
 */
public class UiModGroupConfigScreen extends UiScreenBase {

    private static final float HEADER_H = 72f;
    private static final float SIDE_MARGIN = 48f;
    private static final float SUB_SIDEBAR_W = 180f;
    private static final float CONTENT_MAX_W = 620f;
    private static final float ROW_H = 34f, ROW_GAP = 8f;
    private static final float LABEL_SCALE = 0.5f;

    private final Object lastScreen;
    private final ModuleGroup group;
    private LinkedHashMap<String, List<UiWidget>> tabWidgets = new LinkedHashMap<>();
    private String activeTab;
    private UiScrollContainer scroll;
    private float panelX, panelY, panelW, panelH;
    private int lastLayoutWidth = -1, lastLayoutHeight = -1;

    public UiModGroupConfigScreen(Object lastScreen, ModuleGroup group) {
        super(group.name);
        this.lastScreen = lastScreen;
        this.group = group;
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
            renderer.drawText(UiFont.BOLD, group.name, SIDE_MARGIN + 46, screenHeight - 44, UiTheme.TEXT_PRIMARY, 0.68f, screenWidth, screenHeight);
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

        tabWidgets = new LinkedHashMap<>();
        for (LauncherModule member : group.members) {
            if (member == null) continue;
            List<UiWidget> rows = new ArrayList<>();

            // Toggle d'activation du module EN PREMIER dans son onglet — même
            // convention que le toggle de carte sur l'écran d'accueil (voir
            // UiMainMenuScreen), juste déplacé ici puisqu'un module groupé n'a
            // plus sa propre carte pour le porter.
            float rowY = -ROW_H;
            rows.add(new UiLabel(rowX, rowY + ROW_H / 2f - 5f, "Activé", UiTheme.TEXT_PRIMARY, LABEL_SCALE));
            rows.add(new UiToggle(rowX + rowW - 44f, rowY + (ROW_H - 24f) / 2f, member.isEnabled(),
                v -> { member.setEnabled(v); HudConfigStore.save(); }));
            float cursor = rowY - ROW_GAP;

            // ConfigScreenBuilder positionne chaque catégorie en partant d'un
            // curseur à 0 (indépendant) — UiWidget expose x/y en public
            // mutable (même pattern que searchField.x/y dans
            // UiMainMenuScreen), donc on décale tout du "cursor" courant pour
            // enchaîner juste après notre toggle "Activé", puis on avance le
            // curseur jusqu'au point le plus bas atteint par CETTE catégorie
            // avant de passer à la suivante.
            LinkedHashMap<String, List<UiWidget>> memberCategories = ConfigScreenBuilder.build(member, rowX, rowW);
            for (List<UiWidget> categoryRows : memberCategories.values()) {
                if (categoryRows.isEmpty()) continue;
                float shift = cursor;
                float minY = Float.MAX_VALUE;
                for (UiWidget w : categoryRows) {
                    w.y += shift;
                    if (w.y < minY) minY = w.y;
                }
                rows.addAll(categoryRows);
                cursor = minY - ROW_GAP;
            }

            tabWidgets.put(member.name, rows);
        }
        if (activeTab == null || !tabWidgets.containsKey(activeTab)) {
            activeTab = tabWidgets.isEmpty() ? null : tabWidgets.keySet().iterator().next();
        }

        int i = 0;
        for (String tab : tabWidgets.keySet()) {
            widgets.add(new TabItem(SIDE_MARGIN, panelTop - 24f - i * 38f, SUB_SIDEBAR_W, tab));
            i++;
        }

        switchTab(activeTab);
    }

    private void switchTab(String tab) {
        activeTab = tab;
        scroll.clear();
        if (tab == null) return;
        List<UiWidget> rows = tabWidgets.get(tab);
        if (rows != null) for (UiWidget w : rows) scroll.add(w);
    }

    private final class TabItem extends UiWidget {
        private final String name;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        TabItem(float x, float y, float w, String name) {
            super(x, y, w, 34f);
            this.name = name;
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            boolean active = name.equals(activeTab);
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
        public void onClick() { switchTab(name); }
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
