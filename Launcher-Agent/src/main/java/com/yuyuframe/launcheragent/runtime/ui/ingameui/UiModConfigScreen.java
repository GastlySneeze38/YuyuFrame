package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiColorPicker;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiDropdown;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiKeybindButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiLabel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiPanel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSlider;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiToggle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Page de config d'un mod — bouton retour + titre fixes en haut, sous-sidebar
 * de catégories à gauche (façon OneConfig), carte de contenu scrollable à
 * droite ne montrant QUE la catégorie active.
 *
 * Les widgets de réglage sont construits UNE SEULE FOIS PAR CATÉGORIE (voir
 * {@link #categoryWidgets}) — changer d'onglet fait juste
 * scroll.clear()+re-add() les widgets de la catégorie choisie (mêmes
 * instances, donc aucun état perdu : un slider en cours de réglage dans
 * "Affichage" garde sa valeur si on va voir "Contrôles" puis qu'on revient).
 *
 * Les VALEURS elles-mêmes vivent dans {@link ModSettings} (pas dans les
 * widgets) : au redimensionnement de fenêtre, buildLayout() recrée tous les
 * widgets, mais les initialise depuis ce modèle — rien n'est perdu. Données
 * toujours factices (aucun mod réel enregistré, voir runtime.ui.modules).
 */
public class UiModConfigScreen extends UiScreenBase {

    private static final float HEADER_H = 64f;
    private static final float SIDE_MARGIN = 48f;
    private static final float SUB_SIDEBAR_W = 150f;
    private static final float CONTENT_MAX_W = 480f;
    private static final float ROW_H = 26f, ROW_GAP = 6f;
    private static final float LABEL_SCALE = 0.42f;

    private static final String[] CATEGORIES = { "Général", "Affichage", "Contrôles" };

    private static final class ModSettings {
        boolean modEnabled = true;
        boolean customHud = true;
        float hudOpacity = 80f;
        UiColor accentColor = new UiColor(139, 124, 255, 255);
        int hudModeIndex = 0;
        String quickMenuKey = "RSHIFT";
    }

    private final Object lastScreen;
    private final String modName;
    private final ModSettings settings = new ModSettings();
    private final Map<String, List<UiWidget>> categoryWidgets = new LinkedHashMap<>();
    private String activeCategory = CATEGORIES[0];
    private UiScrollContainer scroll;
    private float panelX, panelY, panelW, panelH;
    private int lastLayoutWidth = -1, lastLayoutHeight = -1;

    public UiModConfigScreen(Object lastScreen, String modName) {
        super(modName);
        this.lastScreen = lastScreen;
        this.modName = modName;
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
            renderer.drawText(UiFont.BOLD, modName, SIDE_MARGIN + 40, screenHeight - 40, UiTheme.TEXT_PRIMARY, 0.55f, screenWidth, screenHeight);
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

        for (int i = 0; i < CATEGORIES.length; i++) {
            widgets.add(new CategoryTab(SIDE_MARGIN, panelTop - 20f - i * 30f, SUB_SIDEBAR_W, CATEGORIES[i]));
        }

        float rowX = panelX + 16f;
        float rowW = panelW - 32f;
        scroll = new UiScrollContainer(rowX, panelY + 16f, rowW, panelH - 32f);

        categoryWidgets.clear();
        categoryWidgets.put("Général", buildGeneralRows(rowX, rowW));
        categoryWidgets.put("Affichage", buildDisplayRows(rowX, rowW));
        categoryWidgets.put("Contrôles", buildControlsRows(rowX, rowW));

        switchCategory(activeCategory);
    }

    private void switchCategory(String category) {
        activeCategory = category;
        scroll.clear();
        List<UiWidget> rows = categoryWidgets.get(category);
        if (rows != null) for (UiWidget w : rows) scroll.add(w);
    }

    private List<UiWidget> buildGeneralRows(float x, float w) {
        List<UiWidget> rows = new ArrayList<>();
        float cursor = 0f;
        cursor = toggleRow(rows, x, w, cursor, "Activer le mod",
            "Active ou désactive entièrement ce mod.", settings.modEnabled, v -> settings.modEnabled = v);
        cursor = toggleRow(rows, x, w, cursor, "HUD personnalisé",
            "Remplace le HUD vanilla par l'overlay du mod.", settings.customHud, v -> settings.customHud = v);
        return rows;
    }

    private List<UiWidget> buildDisplayRows(float x, float w) {
        List<UiWidget> rows = new ArrayList<>();
        float cursor = 0f;
        cursor = sliderRow(rows, x, w, cursor, "Opacité HUD",
            "Transparence de l'overlay, de 0 (invisible) à 100 (opaque).",
            0f, 100f, 1f, settings.hudOpacity, v -> settings.hudOpacity = v);
        cursor = colorRow(rows, x, w, cursor, "Couleur accent",
            "Couleur principale utilisée par l'overlay du mod.", settings.accentColor, v -> settings.accentColor = v);
        cursor = dropdownRow(rows, x, w, cursor, "Mode d'affichage",
            "Niveau de détail affiché par le HUD.",
            Arrays.asList("Minimal", "Complet", "Désactivé"), settings.hudModeIndex, v -> settings.hudModeIndex = v);
        return rows;
    }

    private List<UiWidget> buildControlsRows(float x, float w) {
        List<UiWidget> rows = new ArrayList<>();
        float cursor = 0f;
        cursor = keybindRow(rows, x, w, cursor, "Menu rapide",
            "Touche qui ouvre le menu rapide du mod en jeu.", settings.quickMenuKey, v -> settings.quickMenuKey = v);
        return rows;
    }

    // ── Helpers de ligne — accumulent dans `rows` (PAS directement dans scroll :
    // une catégorie non active n'est jamais ajoutée au conteneur, voir switchCategory) ──

    private float rowLabel(List<UiWidget> rows, float x, float rowY, String label, String tooltip) {
        rows.add(new UiLabel(x, rowY + ROW_H / 2f - 4f, label, UiTheme.TEXT_PRIMARY, LABEL_SCALE).tooltip(tooltip));
        return rowY;
    }

    private float toggleRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                             boolean initial, Consumer<Boolean> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, rowY, label, tooltip);
        rows.add(new UiToggle(x + w - 34f, rowY + (ROW_H - 18f) / 2f, initial, onChange));
        return rowY - ROW_GAP;
    }

    private float sliderRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                             float min, float max, float step, float initial, Consumer<Float> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, rowY, label, tooltip);
        float sliderW = 150f;
        rows.add(new UiSlider(x + w - sliderW, rowY + (ROW_H - 16f) / 2f, sliderW, min, max, step, initial, onChange));
        return rowY - ROW_GAP;
    }

    private float colorRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                            UiColor initial, Consumer<UiColor> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, rowY, label, tooltip);
        rows.add(new UiColorPicker(x + w - 32f, rowY + (ROW_H - 20f) / 2f, initial, onChange));
        // Marge supplémentaire : le panneau déroulant du color picker s'ouvre
        // vers le bas et empièterait sur la ligne suivante sans cette réserve.
        return rowY - ROW_GAP - 90f;
    }

    private float dropdownRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                               List<String> options, int initialIndex, IntConsumer onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, rowY, label, tooltip);
        float dw = 130f;
        rows.add(new UiDropdown(x + w - dw, rowY + (ROW_H - 20f) / 2f, dw, 20f, options, initialIndex, onChange));
        // Même raison que colorRow : réserve la hauteur du panneau déroulé.
        return rowY - ROW_GAP - (options.size() * 20f + 8f);
    }

    private float keybindRow(List<UiWidget> rows, float x, float w, float cursor, String label, String tooltip,
                              String initialKey, Consumer<String> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(rows, x, rowY, label, tooltip);
        float kw = 90f;
        rows.add(new UiKeybindButton(x + w - kw, rowY + (ROW_H - 20f) / 2f, kw, 20f, initialKey, onChange));
        return rowY - ROW_GAP;
    }

    private final class CategoryTab extends UiWidget {
        private final String name;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        CategoryTab(float x, float y, float w, String name) {
            super(x, y, w, 26f);
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
            renderer.drawText(name, x + 12, y + h / 2f - 4f, active ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_MUTED, 0.4f, vpWidth, vpHeight);
        }

        @Override
        public void onClick() { switchCategory(name); }
    }

    private final class BackButton extends UiWidget {
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        BackButton() { super(SIDE_MARGIN, screenHeight - HEADER_H + 18f, 32f, 32f); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            UiColor bg = UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hoverAnim.get());
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            String arrow = "<";
            float tw = renderer.textWidth(arrow, 0.5f);
            renderer.drawText(arrow, x + (w - tw) / 2f, y + h / 2f - 6f, UiTheme.TEXT_PRIMARY, 0.5f, vpWidth, vpHeight);
        }

        @Override
        public void onClick() { closeTo(lastScreen); }
    }
}
