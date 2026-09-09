package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

/**
 * Section visuelle statique (fond arrondi + titre) — pas un UiWidget
 * interactif (aucun clic à gérer), juste un helper de dessin réutilisé par
 * les écrans de config pour regrouper des réglages liés.
 */
public final class UiPanel {
    private UiPanel() {}

    // Ombre discrète ajoutée (voir audit runtime/ui/ : drawShadow n'était
    // utilisé que dans UiMainMenuScreen — CE helper est pourtant le panneau
    // le plus réutilisé de tout le moteur, écran de config après écran de
    // config). Alpha bas/flou modéré : donne juste un peu de profondeur,
    // jamais un halo visible qui distrairait du contenu du panneau.
    private static final UiColor SHADOW_COLOR = new UiColor(0, 0, 0, 70);
    private static final float SHADOW_BLUR = 8f;

    public static void draw(UiRenderer renderer, float x, float y, float w, float h, String title, int vpWidth, int vpHeight) {
        // Ombre AVANT le fond (le verre étant opaque dans ses bornes, il la
        // recouvre proprement là où elle tombe sur lui — une ombre posée
        // par-dessus ternirait le flou).
        renderer.drawShadow(x, y, x + w, y + h, UiTheme.RADIUS_MD, SHADOW_BLUR, 0f, SHADOW_COLOR, vpWidth, vpHeight);
        // Surface de verre (rework 2026-08-27) — CE helper est le panneau le
        // plus réutilisé du moteur (tous les écrans de config), le convertir
        // ici propage le style d'un coup au lieu de retoucher chaque écran.
        renderer.drawGlassPanel(x, y, x + w, y + h, UiTheme.RADIUS_MD,
            UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_PANEL, UiTheme.PANEL_BG,
            renderer.isGlassAvailable() ? UiTheme.GLASS_BORDER : null,
            Math.max(1f, UiTheme.scaled(1f)), vpWidth, vpHeight);
        if (renderer.isGlassAvailable()) {
            float hairline = Math.max(1f, UiTheme.scaled(1f));
            renderer.drawRoundedRect(x + UiTheme.RADIUS_MD, y + h - hairline, x + w - UiTheme.RADIUS_MD, y + h, 0f,
                UiTheme.GLASS_HAIRLINE, vpWidth, vpHeight);
        }
        if (title != null && !title.isEmpty()) {
            renderer.drawText(UiFont.BOLD, title, x + UiTheme.scaled(16f), y + h - UiTheme.scaled(28f), UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.5f), vpWidth, vpHeight);
        }
    }
}
