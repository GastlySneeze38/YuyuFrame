package com.yuyuframe.launcheragent.runtime.hud;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;

/**
 * Affichage HUD PERMANENT — dessine chaque élément enregistré
 * (HudRegistry.elements()) à sa position courante, chaque frame où AUCUN
 * écran n'est ouvert (voir GlobalUiRenderMixin/189, branché uniquement dans
 * la branche currentScreen == null : les vanilla HUD elements eux-mêmes ne
 * s'affichent pas quand un écran est ouvert, même règle ici).
 *
 * Pendant l'édition (UiHudEditorScreen ouvert), ce sont les UiHudBox de
 * l'écran lui-même qui dessinent (même rendu, voir HudPanelRenderer) — cet
 * overlay ne tourne donc jamais en même temps qu'eux, pas de double-dessin.
 */
public final class HudOverlayRenderer {
    private HudOverlayRenderer() {}

    public static void render(UiRenderer renderer, int vpWidth, int vpHeight) {
        for (HudElement element : HudRegistry.elements()) {
            float x = element.screenX(vpWidth);
            float y = element.screenY(vpHeight);
            HudPanelRenderer.draw(renderer, element, x, y, element.w, element.h, vpWidth, vpHeight);
        }
    }

    /**
     * Variante appelée quand un écran NON custom (chat, inventaire, tout
     * autre GUI vanilla/mod) est ouvert — voir le Mixin global, branché juste
     * avant son propre "return" pour ce cas. Ne dessine QUE les éléments
     * ayant explicitement demandé à rester visibles (voir
     * HudElement.showWhenScreenOpen, réglage générique façon OneConfig).
     */
    public static void renderPersistent(UiRenderer renderer, int vpWidth, int vpHeight) {
        for (HudElement element : HudRegistry.elements()) {
            if (!element.showWhenScreenOpen) continue;
            float x = element.screenX(vpWidth);
            float y = element.screenY(vpHeight);
            HudPanelRenderer.draw(renderer, element, x, y, element.w, element.h, vpWidth, vpHeight);
        }
    }
}
