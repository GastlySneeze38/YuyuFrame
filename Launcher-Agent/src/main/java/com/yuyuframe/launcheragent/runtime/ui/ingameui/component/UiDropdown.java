package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.core.UiWidget;

import java.util.List;
import java.util.function.IntConsumer;
import com.yuyuframe.launcheragent.apigraphic.core.UiTheme;

/**
 * Combobox déroulant — même pattern que UiColorPicker : les lignes d'options
 * NE SONT PAS enregistrées dans la liste de widgets de l'écran/scroll parent,
 * ce widget les dessine/teste lui-même. Son propre contains()/onClick()
 * (hérité de UiWidget) reste borné au bouton d'en-tête (w,h) : cliquer une
 * option, positionnée EN DESSOUS de ces bounds, ne re-bascule donc jamais
 * accidentellement expanded via le dispatch de clic générique de l'écran.
 */
public class UiDropdown extends UiWidget {

    // Non static — dépendent de UiTheme.UI_SCALE au moment de la construction
    // (voir GlobalUiSettings, réglage "Taille de l'interface"), même motif que
    // UiSlider/UiToggle/UiColorPicker.
    private final float ROW_H = UiTheme.scaled(26f);
    private final float PANEL_PAD = UiTheme.scaled(5f);
    private final float textScale = UiTheme.scaled(0.48f);
    private final float textPad = UiTheme.scaled(8f);
    private final float textVOff = UiTheme.scaled(5f);

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
        renderer.drawText(label, x + textPad, y + h / 2f - textVOff, UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
        String arrow = expanded ? "^" : "v";
        float aw = renderer.textWidth(arrow, textScale);
        renderer.drawText(arrow, x + w - aw - textPad, y + h / 2f - textVOff, UiTheme.TEXT_SECONDARY, textScale, vpWidth, vpHeight);
    }

    /**
     * Liste déroulée ENTIÈRE — voir UiWidget#drawOverlay (même bug/même fix
     * que UiColorPicker : dessinée dans un second passage APRÈS tout le
     * reste de la liste par UiScrollContainer, ce panneau flotte désormais
     * TOUJOURS au-dessus, peu importe ce qui se trouve plus bas dans
     * l'écran). Seul le bouton d'en-tête reste dans draw().
     */
    @Override
    public void drawOverlay(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        if (!expanded) return;

        float panelTop = y - PANEL_PAD;
        float panelBottom = panelTop - options.size() * ROW_H;
        // Ombre ajoutée (voir audit runtime/ui/) : ce panneau flotte
        // au-dessus du contenu de l'écran en dessous (options, texte...) —
        // sans ombre rien ne le distinguait visuellement de ce qu'il
        // recouvre, contrairement à un vrai menu déroulant.
        renderer.drawShadow(x, panelBottom, x + w, panelTop, UiTheme.RADIUS_SM, 8f, 0f,
            new UiColor(0, 0, 0, 90), vpWidth, vpHeight);
        renderer.drawRoundedRect(x, panelBottom, x + w, panelTop, UiTheme.RADIUS_SM, UiTheme.PANEL_BG_ALT, vpWidth, vpHeight);

        float rowInset = UiTheme.scaled(2f), rowRadius = UiTheme.scaled(2f);
        for (int i = 0; i < options.size(); i++) {
            float rowTop = panelTop - i * ROW_H;
            float rowBottom = rowTop - ROW_H;
            boolean hoveredRow = mouseX >= x && mouseX <= x + w && mouseY >= rowBottom && mouseY <= rowTop;
            if (i == selectedIndex || hoveredRow) {
                renderer.drawRoundedRect(x + rowInset, rowBottom + 1, x + w - rowInset, rowTop - 1, rowRadius,
                    i == selectedIndex ? UiTheme.ACCENT_DIM : UiTheme.CARD_HOVER, vpWidth, vpHeight);
            }
            renderer.drawText(options.get(i), x + textPad, rowBottom + ROW_H / 2f - textVOff, UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
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
