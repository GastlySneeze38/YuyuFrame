package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.McTexture;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiInteractive;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSurface;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;

import java.awt.image.BufferedImage;
import java.util.function.Supplier;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * Grand bouton d'action de l'accueil : un objet du jeu et un libellé.
 * Plein (accent, une seule fois par écran) ou doux (verre).
 */
public final class CtaButton extends UiInteractive {

    private final String label;
    private final boolean primary;
    private final Supplier<BufferedImage> icon;
    private final String iconKey;
    private final Runnable action;

    public CtaButton(String label, boolean primary, String iconKey, Supplier<BufferedImage> icon, Runnable action) {
        this.label = label;
        this.primary = primary;
        this.iconKey = iconKey;
        this.icon = icon;
        this.action = action;
    }

    public float preferredWidth() {
        float w = UiText.width(UiFont.BOLD, Lang.tr(label), px(UiTheme.Text.LABEL)) + px(UiTheme.Space.S24) * 2f;
        if (icon != null) w += px(UiTheme.Space.S24) + px(UiTheme.Space.S12);
        return w;
    }

    @Override
    public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
        if (w <= 0f || h <= 0f) return;
        updateStates(mouseX, mouseY);
        float radius = px(UiTheme.Space.S14);
        // Enfoncé : le bouton rentre d'un cheveu dans ses bornes.
        float inset = Math.min(w, h) * 0.03f * press;
        float bx = x + inset, by = y + inset, bw = w - 2f * inset, bh = h - 2f * inset;
        if (primary) {
            r.drawRoundedRect(bx - px(UiTheme.Space.S4), by - px(UiTheme.Space.S6), bx + bw + px(UiTheme.Space.S4),
                by + bh, radius + px(UiTheme.Space.S4), UiTheme.ACCENT.withAlpha(0.18f + 0.10f * hover), vw, vh);
            r.drawGradientRect(bx, by, bx + bw, by + bh, radius,
                UiTheme.accentDark(), UiColor.lerp(UiTheme.ACCENT, UiTheme.accentLight(), hover), vw, vh);
            r.drawRoundedRect(bx + radius, by + bh - UiTheme.hairline(), bx + bw - radius, by + bh, 0f,
                new UiColor(255, 255, 255, 70), vw, vh);
        } else {
            UiSurface.field(UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hover)).radius(radius)
                .border(UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.GLASS_BORDER_HOVER, hover))
                .draw(r, bx, by, bw, bh, vw, vh);
        }

        float s = px(UiTheme.Text.LABEL);
        String text = Lang.tr(label);
        float contentW = UiText.width(UiFont.BOLD, text, s);
        float iconS = px(UiTheme.Space.S24);
        BufferedImage img = icon == null ? null : icon.get();
        if (img != null) contentW += iconS + px(UiTheme.Space.S12);
        float cx = bx + (bw - contentW) / 2f;
        if (img != null) {
            McTexture.draw(r, img, iconKey, cx, by + (bh - iconS) / 2f, iconS, iconS, 1f, vw, vh);
            cx += iconS + px(UiTheme.Space.S12);
        }
        UiColor color = primary ? UiTheme.TEXT_ON_ACCENT : UiTheme.TEXT_PRIMARY;
        r.drawText(UiFont.BOLD, text, cx, UiText.centeredBaseline(UiFont.BOLD, by, bh, s), color, s, vw, vh);
    }

    @Override
    protected void onActivate() {
        if (action != null) action.run();
    }
}
