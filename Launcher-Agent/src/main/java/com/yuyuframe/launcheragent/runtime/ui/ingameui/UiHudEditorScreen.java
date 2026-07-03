package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.runtime.ui.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudRegistry;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiHudBox;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * Éditeur HUD — une boîte glissable par élément enregistré (HudRegistry),
 * ouvert depuis l'item "Modifier le HUD" en bas de la sidebar de l'accueil.
 *
 * Fond quasi-transparent (voir overlayColor) : le jeu (monde + HUD vanilla,
 * hotbar/vie/faim) reste visible en direct derrière, comme le vrai éditeur
 * HUD OneConfig — nos Screen custom ne pausent pas la simulation (ce n'est
 * pas le menu pause vanilla), le monde tournait déjà derrière nos autres
 * écrans, simplement masqué par leur fond presque opaque.
 *
 * Pas de bandeau de titre : la plupart des éléments HUD (dont 3 de nos 4
 * factices) s'ancrent en haut de l'écran — un bandeau fixe là-haut gênerait
 * leur positionnement exactement comme le faisait l'ancien bouton retour en
 * haut-gauche (voir BackButton, déplacé au centre pour la même raison).
 *
 * Layout reconstruit au redimensionnement (comme les autres écrans) — sans
 * rien perdre : la position de chaque UiHudBox vit dans son HudElement
 * (ancre+décalage), pas dans le widget lui-même, donc recréer les boîtes
 * recalcule juste leur position écran à partir du même modèle.
 */
public class UiHudEditorScreen extends UiScreenBase {

    private static final UiColor EDITOR_OVERLAY = new UiColor(6, 6, 10, 60);

    private final Object lastScreen;
    private int lastLayoutWidth = -1, lastLayoutHeight = -1;
    private List<UiHudBox> hudBoxes = new ArrayList<>();

    public UiHudEditorScreen(Object lastScreen) {
        super("Édition du HUD");
        this.lastScreen = lastScreen;
    }

    @Override
    protected UiColor overlayColor() {
        return EDITOR_OVERLAY;
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

            // Lignes de guide d'alignement — dessinées PAR-DESSUS les boîtes,
            // uniquement pendant qu'une boîte est effectivement en train de
            // s'accrocher à ce repère (voir UiHudBox.pollContinuous).
            for (UiHudBox box : hudBoxes) {
                Float gx = box.snapGuideX();
                if (gx != null) renderer.drawRoundedRect(gx - 0.5f, 0, gx + 0.5f, screenHeight, 0, UiTheme.ACCENT, screenWidth, screenHeight);
                Float gy = box.snapGuideY();
                if (gy != null) renderer.drawRoundedRect(0, gy - 0.5f, screenWidth, gy + 0.5f, 0, UiTheme.ACCENT, screenWidth, screenHeight);
            }
        } catch (Throwable ignored) {}
    }

    private void buildLayout() {
        widgets.clear();
        widgets.add(new BackButton());

        hudBoxes = new ArrayList<>();
        for (HudElement element : HudRegistry.elements()) {
            UiHudBox box = new UiHudBox(element, screenWidth, screenHeight, hudBoxes);
            hudBoxes.add(box);
            widgets.add(box);
        }
    }

    /**
     * Au centre de l'écran plutôt qu'en haut-gauche — c'est justement
     * l'endroit où les éléments HUD sont le MOINS souvent placés (la plupart
     * s'ancrent aux coins/bords), donc le bouton ne gêne quasiment jamais le
     * positionnement d'une boîte, contrairement à son ancienne place en
     * haut-gauche (l'un des coins les plus utilisés).
     */
    private final class BackButton extends UiWidget {
        private static final float W = 100f, H = 36f;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        BackButton() { super((screenWidth - W) / 2f, (screenHeight - H) / 2f, W, H); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            UiColor bg = UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hoverAnim.get());
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_MD, bg, vpWidth, vpHeight);
            String label = "< Retour";
            float tw = renderer.textWidth(label, 0.44f);
            renderer.drawText(label, x + (w - tw) / 2f, y + h / 2f - 5f, UiTheme.TEXT_PRIMARY, 0.44f, vpWidth, vpHeight);
        }

        @Override
        public void onClick() { closeTo(lastScreen); }
    }
}
