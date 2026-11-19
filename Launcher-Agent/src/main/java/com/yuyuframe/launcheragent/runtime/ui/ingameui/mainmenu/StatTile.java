package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.MinecraftFont;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.McTexture;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiInteractive;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSurface;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;

import java.awt.image.BufferedImage;
import java.util.function.Supplier;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * Un chiffre de l'accueil : un objet du jeu, la valeur dans la police du jeu,
 * et ce qu'elle mesure. Cliquable quand il mène quelque part (le rang ouvre
 * l'historique des duels).
 */
public final class StatTile extends UiInteractive {

    private final Supplier<BufferedImage> icon;
    private final String iconKey;
    private final String value;
    private final String label;
    private final Runnable action;

    public StatTile(String iconKey, Supplier<BufferedImage> icon, String value, String label, Runnable action) {
        this.iconKey = iconKey;
        this.icon = icon;
        this.value = value;
        this.label = label;
        this.action = action;
        this.disabled = action == null;
    }

    @Override
    public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
        if (w <= 0f || h <= 0f) return;
        updateStates(mouseX, mouseY);
        UiSurface.card(hover).radius(px(UiTheme.Space.S18)).draw(r, x, y, w, h, vw, vh);

        float pad = px(UiTheme.Space.S20);
        float iconS = px(UiTheme.Space.S32);
        float top = y + h - pad;
        BufferedImage img = icon.get();
        if (img != null) McTexture.draw(r, img, iconKey, x + pad, top - iconS, iconS, iconS, 1f, vw, vh);

        float labelScale = px(UiTheme.Text.SM);
        r.drawText(UiFont.REGULAR, UiText.fit(UiFont.REGULAR, Lang.tr(label), w - 2f * pad, labelScale), x + pad, y + pad,
            UiTheme.TEXT_MUTED, labelScale, vw, vh);
        UiFont pixel = MinecraftFont.accent(true);
        float valueScale = px(UiTheme.Text.XL) * 1.25f;
        r.drawText(pixel, UiText.fit(pixel, value, w - 2f * pad, valueScale), x + pad,
            y + pad + UiText.lineHeight(UiFont.REGULAR, labelScale) + px(UiTheme.Space.S6),
            UiTheme.TEXT_PRIMARY, valueScale, vw, vh);
    }

    @Override
    protected void onActivate() {
        if (action != null) action.run();
    }
}
