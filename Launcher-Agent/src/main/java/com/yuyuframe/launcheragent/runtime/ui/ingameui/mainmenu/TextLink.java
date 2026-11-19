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
 * Un lien en texte (« Modifier ») : couleur claire de l'accent, aligné à
 * droite de sa boîte. Pour une action secondaire qui ne mérite pas un bouton.
 */
public final class TextLink extends UiInteractive {

    private final String label;
    private final Runnable action;

    public TextLink(String label, Runnable action) {
        this.label = label;
        this.action = action;
    }

    public float preferredWidth() {
        return UiText.width(UiFont.BOLD, Lang.tr(label), px(UiTheme.Text.SM)) + px(UiTheme.Space.S8);
    }

    @Override
    public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
        if (w <= 0f || h <= 0f) return;
        updateStates(mouseX, mouseY);
        float s = px(UiTheme.Text.SM);
        String text = Lang.tr(label);
        float tw = UiText.width(UiFont.BOLD, text, s);
        UiColor color = UiColor.lerp(UiTheme.accentLight(), UiTheme.TEXT_PRIMARY, hover * 0.6f);
        r.drawText(UiFont.BOLD, text, x + w - tw, UiText.centeredBaseline(UiFont.BOLD, y, h, s), color, s, vw, vh);
    }

    @Override
    protected void onActivate() {
        if (action != null) action.run();
    }
}
