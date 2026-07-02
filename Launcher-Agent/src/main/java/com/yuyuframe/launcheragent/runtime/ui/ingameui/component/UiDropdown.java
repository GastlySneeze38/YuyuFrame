package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Combobox déroulant — même pattern que UiColorPicker : les lignes d'options
 * NE SONT PAS enregistrées dans la liste de widgets de l'écran/scroll parent,
 * ce widget les dessine/teste lui-même. Son propre contains()/onClick()
 * (hérité de UiWidget) reste borné au bouton d'en-tête (w,h) : cliquer une
 * option, positionnée EN DESSOUS de ces bounds, ne re-bascule donc jamais
 * accidentellement expanded via le dispatch de clic générique de l'écran.
 */
public class UiDropdown extends UiWidget {

    private static final float ROW_H = 26f;
    private static final float PANEL_PAD = 5f;

    private final List<String> options;
    private int selectedIndex;
    private boolean expanded;
    private final IntConsumer onChange;
    private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

    public UiDropdown(float x, float y, float w, float h, List<String> options, int initialIndex, IntConsumer onChange) {
        super(x, y, w, h);
        this.options = options;
        this.selectedIndex = Math.max(0, Math.min(options.size() - 1, initialIndex));
        this.onChange = onChange;
    }

    public int selectedIndex() { return selectedIndex; }
    public String selectedValue() { return options.get(selectedIndex); }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
        UiColor bg = expanded ? UiTheme.ACCENT_DIM : UiColor.lerp(UiTheme.PANEL_BG_ALT, UiTheme.CARD_HOVER, hoverAnim.get());
        renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);

        String label = options.get(selectedIndex);
        renderer.drawText(label, x + 8, y + h / 2f - 5f, UiTheme.TEXT_PRIMARY, 0.48f, vpWidth, vpHeight);
        String arrow = expanded ? "^" : "v";
        float aw = renderer.textWidth(arrow, 0.48f);
        renderer.drawText(arrow, x + w - aw - 8, y + h / 2f - 5f, UiTheme.TEXT_SECONDARY, 0.48f, vpWidth, vpHeight);

        if (!expanded) return;

        float panelTop = y - PANEL_PAD;
        float panelBottom = panelTop - options.size() * ROW_H;
        renderer.drawRoundedRect(x, panelBottom, x + w, panelTop, UiTheme.RADIUS_SM, UiTheme.PANEL_BG_ALT, vpWidth, vpHeight);

        for (int i = 0; i < options.size(); i++) {
            float rowTop = panelTop - i * ROW_H;
            float rowBottom = rowTop - ROW_H;
            boolean hoveredRow = mouseX >= x && mouseX <= x + w && mouseY >= rowBottom && mouseY <= rowTop;
            if (i == selectedIndex || hoveredRow) {
                renderer.drawRoundedRect(x + 2, rowBottom + 1, x + w - 2, rowTop - 1, 2f,
                    i == selectedIndex ? UiTheme.ACCENT_DIM : UiTheme.CARD_HOVER, vpWidth, vpHeight);
            }
            renderer.drawText(options.get(i), x + 8, rowBottom + ROW_H / 2f - 5f, UiTheme.TEXT_PRIMARY, 0.48f, vpWidth, vpHeight);
        }
    }

    @Override
    public void onClick() {
        expanded = !expanded;
    }

    @Override
    public void pollContinuous(UiInputPoller input) {
        if (!expanded || !input.leftClicked) return;

        float panelTop = y - PANEL_PAD;
        for (int i = 0; i < options.size(); i++) {
            float rowTop = panelTop - i * ROW_H;
            float rowBottom = rowTop - ROW_H;
            if (input.mouseX >= x && input.mouseX <= x + w && input.mouseY >= rowBottom && input.mouseY <= rowTop) {
                selectedIndex = i;
                expanded = false;
                if (onChange != null) onChange.accept(i);
                return;
            }
        }
        // Clic en dehors des options (mais pas forcément sur le bouton d'en-tête,
        // déjà géré par onClick via le dispatch générique) — referme le panneau,
        // sinon il resterait ouvert indéfiniment tant qu'on ne re-clique pas dessus.
        boolean onHeader = input.mouseX >= x && input.mouseX <= x + w && input.mouseY >= y && input.mouseY <= y + h;
        if (!onHeader) expanded = false;
    }
}
