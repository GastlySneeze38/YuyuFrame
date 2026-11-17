package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSurface;

/**
 * Le fond de la barre latérale et le logo en tête. Widget jamais cliquable :
 * il est ajouté en premier pour être dessiné SOUS la navigation.
 *
 * <p>Verre pleine hauteur (la surface la plus teintée de l'écran, son texte
 * doit rester lisible sur n'importe quel décor), liseré sur la tranche droite
 * — la seule qui ne touche pas un bord d'écran. Sans verre, un dégradé opaque
 * (plus clair et violacé en haut).
 */
public final class SidebarPanel extends UiWidget {

    public SidebarPanel() {
        super(0f, 0f, 0f, 0f);
    }

    @Override
    public boolean contains(double mx, double my) {
        return false;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        if (w <= 0f || h <= 0f) return;
        if (renderer.isGlassAvailable()) {
            // L'ombre de toute la bande ne se voit qu'où elle déborde : un
            // dégradé sombre qui mord sur le contenu à droite.
            UiSurface.of(UiTheme.Glass.SIDEBAR).radius(0f).noBorder().hairlineRight()
                .shadow(UiTheme.SHADOW_PANEL, 10f, 0f, 0f)
                .draw(renderer, x, y, w, h, vpWidth, vpHeight);
        } else {
            renderer.drawShadow(x, y, x + w, y + h, 0f, 10f, 0f, UiTheme.SHADOW_PANEL, vpWidth, vpHeight);
            UiColor base = UiTheme.SIDEBAR_BG;
            UiColor top = new UiColor(Math.min(1f, base.r + 0.07f), Math.min(1f, base.g + 0.05f), Math.min(1f, base.b + 0.14f), base.a);
            renderer.drawGradientRect(x, y, x + w, y + h, 0, base, top, vpWidth, vpHeight);
        }
        drawLogo(renderer, vpWidth, vpHeight);
    }

    /**
     * « YuyuFrame » centré dans la barre, sur un halo d'accent en pilule
     * calculé sur les VRAIES métriques du texte (largeur, ascendante,
     * descendante) : trois essais de coordonnées à l'œil n'avaient jamais
     * centré le halo. Le halo rétrécit ses marges haute ET basse ensemble
     * s'il manque de place sous le bord de l'écran — rogner seulement le haut
     * décalait la pilule par rapport au texte.
     */
    private void drawLogo(UiRenderer renderer, int vpWidth, int vpHeight) {
        float margin = UiTheme.px(UiTheme.Space.S24);
        float scale = UiTheme.px(UiTheme.Text.TITLE);
        float titleW = renderer.textWidth(UiFont.BOLD, "YuyuFrame", scale);
        float titleX = x + Math.max(margin / 2f, (w - titleW) / 2f);
        float baseline = y + h - UiTheme.px(42f);
        float cs = scale * UiFont.SIZE_CORRECTION;
        float titleTop = baseline + UiFont.BOLD.ascent * cs;
        float titleBottom = baseline - UiFont.BOLD.descent * cs;

        float padX = UiTheme.px(UiTheme.Space.S14), padY = UiTheme.px(UiTheme.Space.S8);
        float blur = UiTheme.px(UiTheme.Space.S14);
        float maxTopPad = (y + h - blur - UiTheme.px(UiTheme.Space.S6)) - titleTop;
        float actualPadY = Math.max(0f, Math.min(padY, maxTopPad));
        float y1 = titleBottom - actualPadY, y2 = titleTop + actualPadY;
        renderer.drawShadow(titleX - padX, y1, titleX + titleW + padX, y2, (y2 - y1) / 2f, blur, 0f,
            UiTheme.ACCENT.multiplyAlpha(0.28f), vpWidth, vpHeight);
        // Le halo laisse le titre par endroits sur un fond presque transparent : ombre portée.
        renderer.drawTextShadowed(UiFont.BOLD, "YuyuFrame", titleX, baseline,
            UiTheme.TEXT_PRIMARY, UiTheme.TEXT_SHADOW, 1f, -1f, scale, vpWidth, vpHeight);
    }
}
