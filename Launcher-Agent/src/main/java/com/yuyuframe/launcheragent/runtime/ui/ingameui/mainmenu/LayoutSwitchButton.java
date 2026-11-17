package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings;
import com.yuyuframe.launcheragent.runtime.ui.HudConfigStore;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiInteractive;

/**
 * À côté de la recherche : passe d'un agencement de la grille à l'autre
 * (Détaillé, Compacte, Grille d'icônes). Son pictogramme dessine
 * l'agencement COURANT : deux cartes côte à côte, deux lignes, ou 3x3.
 */
public final class LayoutSwitchButton extends UiInteractive {

    private static final String[] LAYOUT_NAMES = { "Détaillé", "Compacte", "Grille d'icônes" };

    private final Runnable onChanged;

    public LayoutSwitchButton(Runnable onChanged) {
        this.onChanged = onChanged;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        updateStates(mouseX, mouseY);
        int layout = GlobalUiSettings.INSTANCE.cardLayout;
        this.tooltip = Lang.tr("Affichage") + " : " + Lang.tr(LAYOUT_NAMES[layout]);

        renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM,
            UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hover), vpWidth, vpHeight);

        UiColor ink = UiColor.lerp(UiTheme.TEXT_SECONDARY, UiTheme.TEXT_PRIMARY, hover);
        float pad = UiTheme.px(UiTheme.Space.S8);
        float ix = x + pad, iy = y + pad, iw = w - pad * 2f, ih = h - pad * 2f;
        float r = UiTheme.px(UiTheme.Radius.XS);
        if (layout == 1) {
            // Compacte : deux lignes pleine largeur.
            float gap = UiTheme.px(UiTheme.Space.S3);
            float rh = (ih - gap) / 2f;
            renderer.drawRoundedRect(ix, iy, ix + iw, iy + rh, r, ink, vpWidth, vpHeight);
            renderer.drawRoundedRect(ix, iy + rh + gap, ix + iw, iy + ih, r, ink, vpWidth, vpHeight);
        } else if (layout == 2) {
            // Grille d'icônes : 3x3 petits carrés.
            float gap = UiTheme.px(2.5f);
            float cell = (iw - gap * 2f) / 3f;
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    float cx = ix + col * (cell + gap), cy = iy + row * (cell + gap);
                    renderer.drawRoundedRect(cx, cy, cx + cell, cy + cell, UiTheme.px(1f), ink, vpWidth, vpHeight);
                }
            }
        } else {
            // Détaillé : deux grandes cartes côte à côte.
            float gap = UiTheme.px(UiTheme.Space.S3);
            float cw = (iw - gap) / 2f;
            renderer.drawRoundedRect(ix, iy, ix + cw, iy + ih, r, ink, vpWidth, vpHeight);
            renderer.drawRoundedRect(ix + cw + gap, iy, ix + iw, iy + ih, r, ink, vpWidth, vpHeight);
        }
    }

    @Override
    protected void onActivate() {
        GlobalUiSettings.INSTANCE.cardLayout = (GlobalUiSettings.INSTANCE.cardLayout + 1) % 3;
        HudConfigStore.save();
        if (onChanged != null) onChanged.run();
    }
}
