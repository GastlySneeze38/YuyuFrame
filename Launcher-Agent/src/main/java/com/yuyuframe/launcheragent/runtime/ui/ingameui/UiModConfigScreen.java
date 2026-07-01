package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiColorPicker;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiKeybindButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiLabel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSlider;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiToggle;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Page de config d'un mod — bouton retour + titre fixes en haut, contenu
 * scrollable en dessous (UiScrollContainer) avec sections de réglages
 * factices (voir buildLayout). Les données affichées ne sont PAS encore
 * reliées à un vrai mod (aucun mod n'est enregistré côté agent pour
 * l'instant, voir runtime.ui.modules) — juste de quoi valider le rendu/
 * interactions des 4 widgets de config (toggle/slider/color/keybind) avant
 * de brancher YuyuPvP etc.
 *
 * Layout construit UNE SEULE FOIS (voir {@code built}) : les widgets portent
 * un état interne (slider en cours de glissement, color picker déplié,
 * keybind en écoute) qui serait perdu si on reconstruisait tout chaque frame
 * (contrairement à UiMainMenuScreen.CloseButton, sans état).
 */
public class UiModConfigScreen extends UiScreenBase {

    private static final float HEADER_H = 64f;
    private static final float SIDE_MARGIN = 48f;
    private static final float CONTENT_MAX_W = 520f;
    private static final float ROW_H = 26f, ROW_GAP = 6f, SECTION_GAP = 20f;
    private static final float LABEL_SCALE = 0.42f;

    private final Object lastScreen;
    private final String modName;
    private UiScrollContainer scroll;
    private boolean built;

    public UiModConfigScreen(Object lastScreen, String modName) {
        super(modName);
        this.lastScreen = lastScreen;
        this.modName = modName;
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (!built && screenWidth > 0 && screenHeight > 0) {
            buildLayout();
            built = true;
        }
        super.uiDraw(mouseX, mouseY);
        try {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
            renderer.drawText(UiFont.BOLD, modName, SIDE_MARGIN + 40, screenHeight - 40, UiTheme.TEXT_PRIMARY, 0.55f, screenWidth, screenHeight);
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

        float contentW = Math.min(CONTENT_MAX_W, screenWidth - SIDE_MARGIN * 2);
        float contentX = (screenWidth - contentW) / 2f;
        float viewportH = screenHeight - HEADER_H - 20f;
        scroll = new UiScrollContainer(contentX, 20f, contentW, viewportH);

        float cursor = 0f;
        cursor = sectionHeader(contentX, "Général", cursor);
        cursor = toggleRow(contentX, contentW, cursor, "Activer le mod", true, v -> {});
        cursor = toggleRow(contentX, contentW, cursor, "HUD personnalisé", true, v -> {});

        cursor -= SECTION_GAP;
        cursor = sectionHeader(contentX, "Affichage", cursor);
        cursor = sliderRow(contentX, contentW, cursor, "Opacité HUD", 0f, 100f, 1f, 80f, v -> {});
        cursor = colorRow(contentX, contentW, cursor, "Couleur accent", new UiColor(139, 124, 255, 255), v -> {});

        cursor -= SECTION_GAP;
        cursor = sectionHeader(contentX, "Contrôles", cursor);
        cursor = keybindRow(contentX, contentW, cursor, "Menu rapide", "RSHIFT", v -> {});
    }

    private float sectionHeader(float contentX, String title, float cursor) {
        float y = cursor - 14f;
        scroll.add(new UiLabel(contentX, y, title.toUpperCase(Locale.ROOT), UiTheme.TEXT_MUTED, 0.3f, UiFont.BOLD));
        return y - 12f;
    }

    private float rowLabel(float contentX, float rowY, String label) {
        scroll.add(new UiLabel(contentX, rowY + ROW_H / 2f - 4f, label, UiTheme.TEXT_PRIMARY, LABEL_SCALE));
        return rowY;
    }

    private float toggleRow(float contentX, float contentW, float cursor, String label, boolean initial, Consumer<Boolean> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(contentX, rowY, label);
        scroll.add(new UiToggle(contentX + contentW - 34f, rowY + (ROW_H - 18f) / 2f, initial, onChange));
        return rowY - ROW_GAP;
    }

    private float sliderRow(float contentX, float contentW, float cursor, String label, float min, float max, float step, float initial, Consumer<Float> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(contentX, rowY, label);
        float sliderW = 150f;
        scroll.add(new UiSlider(contentX + contentW - sliderW, rowY + (ROW_H - 16f) / 2f, sliderW, min, max, step, initial, onChange));
        return rowY - ROW_GAP;
    }

    private float colorRow(float contentX, float contentW, float cursor, String label, UiColor initial, Consumer<UiColor> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(contentX, rowY, label);
        scroll.add(new UiColorPicker(contentX + contentW - 32f, rowY + (ROW_H - 20f) / 2f, initial, onChange));
        // Marge supplémentaire : le panneau déroulant du color picker s'ouvre
        // vers le bas et empièterait sur la ligne suivante sans cette réserve.
        return rowY - ROW_GAP - 90f;
    }

    private float keybindRow(float contentX, float contentW, float cursor, String label, String initialKey, Consumer<String> onChange) {
        float rowY = cursor - ROW_H;
        rowLabel(contentX, rowY, label);
        float w = 90f;
        scroll.add(new UiKeybindButton(contentX + contentW - w, rowY + (ROW_H - 20f) / 2f, w, 20f, initialKey, onChange));
        return rowY - ROW_GAP;
    }

    private final class BackButton extends UiWidget {
        BackButton() { super(SIDE_MARGIN, screenHeight - HEADER_H + 18f, 32f, 32f); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            boolean hovered = contains(mouseX, mouseY);
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, hovered ? UiTheme.CARD_HOVER : UiTheme.CARD_BG, vpWidth, vpHeight);
            String arrow = "<";
            float tw = renderer.textWidth(arrow, 0.5f);
            renderer.drawText(arrow, x + (w - tw) / 2f, y + h / 2f - 6f, UiTheme.TEXT_PRIMARY, 0.5f, vpWidth, vpHeight);
        }

        @Override
        public void onClick() { closeTo(lastScreen); }
    }
}
