package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiInteractive;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * La dernière case de l'accès rapide : « + Ajouter un favori », plus
 * effacée que les tuiles. Elle mène à l'onglet Modules, où l'on met un
 * module en favori.
 */
public final class AddTile extends UiInteractive {

    private final String label;
    private final Runnable action;

    public AddTile(String label, Runnable action) {
        this.label = label;
        this.action = action;
    }

    @Override
    public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
        if (w <= 0f || h <= 0f) return;
        updateStates(mouseX, mouseY);
        float radius = px(UiTheme.Space.S16);
        r.drawRoundedRect(x, y, x + w, y + h, radius,
            UiColor.lerp(new UiColor(255, 255, 255, 8), new UiColor(255, 255, 255, 20), hover), vw, vh);
        float s = px(UiTheme.Text.MD);
        String text = "+  " + Lang.tr(label);
        UiColor color = UiColor.lerp(UiTheme.TEXT_MUTED, UiTheme.TEXT_PRIMARY, hover);
        UiText.drawCentered(r, UiFont.REGULAR, text, x, w, UiText.centeredBaseline(UiFont.REGULAR, y, h, s), color, s, vw, vh);
    }

    @Override
    protected void onActivate() {
        if (action != null) action.run();
    }
}
