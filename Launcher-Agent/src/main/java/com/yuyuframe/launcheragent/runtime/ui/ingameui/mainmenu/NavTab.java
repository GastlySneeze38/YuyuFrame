package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiInteractive;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;

import java.util.function.IntSupplier;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * Un onglet de la barre du haut : un mot, et une pilule teintée d'accent
 * quand il est ouvert. Un compteur facultatif (missions en cours) se pose
 * après le mot, en pastille d'accent.
 */
public final class NavTab extends UiInteractive {

    private final String label;
    private final boolean active;
    private final IntSupplier count;
    private final Runnable action;

    public NavTab(String label, boolean active, IntSupplier count, Runnable action) {
        this.label = label;
        this.active = active;
        this.count = count;
        this.action = action;
    }

    /** Largeur voulue : le mot, la pastille, et le rembourrage. */
    public float preferredWidth() {
        float w = UiText.width(UiFont.BOLD, Lang.tr(label), scale()) + px(UiTheme.Space.S18) * 2f;
        int n = count == null ? 0 : count.getAsInt();
        if (n > 0) w += badgeWidth(n) + px(UiTheme.Space.S8);
        return w;
    }

    private static float scale() {
        return px(UiTheme.Text.LABEL);
    }

    private static float badgeWidth(int n) {
        return Math.max(px(UiTheme.Space.S20), UiText.width(UiFont.BOLD, String.valueOf(n), px(UiTheme.Text.XS))
            + px(UiTheme.Space.S12));
    }

    @Override
    public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
        if (w <= 0f || h <= 0f) return;
        updateStates(mouseX, mouseY);
        float radius = px(UiTheme.Space.S12);
        if (active) {
            r.drawRoundedRect(x, y, x + w, y + h, radius, UiTheme.ACCENT.withAlpha(0.26f), vw, vh);
        } else if (hover > 0.01f) {
            r.drawRoundedRect(x, y, x + w, y + h, radius, UiTheme.SIDEBAR_HOVER.multiplyAlpha(hover), vw, vh);
        }
        float s = scale();
        UiColor color = active ? UiTheme.TEXT_PRIMARY : UiColor.lerp(UiTheme.TEXT_MUTED, UiTheme.TEXT_PRIMARY, hover);
        String text = Lang.tr(label);
        float tx = x + px(UiTheme.Space.S18);
        r.drawText(UiFont.BOLD, text, tx, UiText.centeredBaseline(UiFont.BOLD, y, h, s), color, s, vw, vh);

        int n = count == null ? 0 : count.getAsInt();
        if (n > 0) {
            float bw = badgeWidth(n), bh = px(UiTheme.Space.S20);
            float bx = tx + UiText.width(UiFont.BOLD, text, s) + px(UiTheme.Space.S8), by = y + (h - bh) / 2f;
            r.drawRoundedRect(bx, by, bx + bw, by + bh, px(UiTheme.Space.S6), UiTheme.ACCENT, vw, vh);
            float bs = px(UiTheme.Text.XS);
            UiText.drawCentered(r, UiFont.BOLD, String.valueOf(n), bx, bw,
                UiText.centeredBaseline(UiFont.BOLD, by, bh, bs), UiTheme.TEXT_ON_ACCENT, bs, vw, vh);
        }
    }

    @Override
    protected void onActivate() {
        if (action != null) action.run();
    }
}
