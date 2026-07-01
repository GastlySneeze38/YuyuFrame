package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.runtime.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.hud.HudRegistry;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiHudBox;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;

/**
 * Éditeur HUD — une boîte glissable par élément enregistré (HudRegistry),
 * ouvert depuis l'item "Modifier le HUD" en bas de la sidebar de l'accueil.
 * Écran en pause (fond assombri, comme tous nos autres écrans custom) plutôt
 * que overlay par-dessus le jeu vivant — plus simple, réutilise UiScreenBase
 * tel quel ; voir docs/LauncherAgent/index.md pour la version "jeu vivant"
 * envisagée mais pas retenue pour cette première passe.
 *
 * Layout reconstruit au redimensionnement (comme les autres écrans) — sans
 * rien perdre : la position de chaque UiHudBox vit dans son HudElement
 * (ancre+décalage), pas dans le widget lui-même, donc recréer les boîtes
 * recalcule juste leur position écran à partir du même modèle.
 */
public class UiHudEditorScreen extends UiScreenBase {

    private static final float MARGIN = 48f;

    private final Object lastScreen;
    private int lastLayoutWidth = -1, lastLayoutHeight = -1;

    public UiHudEditorScreen(Object lastScreen) {
        super("Édition du HUD");
        this.lastScreen = lastScreen;
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (screenWidth > 0 && screenHeight > 0
                && (screenWidth != lastLayoutWidth || screenHeight != lastLayoutHeight)) {
            buildLayout();
            lastLayoutWidth = screenWidth;
            lastLayoutHeight = screenHeight;
        }
        super.uiDraw(mouseX, mouseY);
        try {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
            renderer.drawText(UiFont.BOLD, "Édition du HUD", MARGIN + 40, screenHeight - 40, UiTheme.TEXT_PRIMARY, 0.55f, screenWidth, screenHeight);
            renderer.drawText("Glisse les éléments pour les repositionner", MARGIN + 40, screenHeight - 58,
                UiTheme.TEXT_SECONDARY, 0.38f, screenWidth, screenHeight);
        } catch (Throwable ignored) {}
    }

    private void buildLayout() {
        widgets.clear();
        widgets.add(new BackButton());
        for (HudElement element : HudRegistry.elements()) {
            widgets.add(new UiHudBox(element, screenWidth, screenHeight));
        }
    }

    private final class BackButton extends UiWidget {
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        BackButton() { super(MARGIN, screenHeight - 64f + 18f, 32f, 32f); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            UiColor bg = UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hoverAnim.get());
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            String arrow = "<";
            float tw = renderer.textWidth(arrow, 0.5f);
            renderer.drawText(arrow, x + (w - tw) / 2f, y + h / 2f - 6f, UiTheme.TEXT_PRIMARY, 0.5f, vpWidth, vpHeight);
        }

        @Override
        public void onClick() { closeTo(lastScreen); }
    }
}
