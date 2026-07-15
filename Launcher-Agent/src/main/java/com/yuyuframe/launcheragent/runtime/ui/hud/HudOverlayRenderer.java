package com.yuyuframe.launcheragent.runtime.ui.hud;

import com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings;
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
            // Voir HudElement.refreshSize() : un contenu de largeur variable
            // (FPS/Ping) doit être remesuré à CHAQUE frame, pas une seule fois
            // à la construction — sinon la boîte reste figée sur le texte de
            // repli initial pendant que le vrai texte affiché change de largeur.
            element.refreshSize();
            float x = element.screenX(vpWidth);
            float y = element.screenY(vpHeight);
            HudPanelRenderer.draw(renderer, element, x, y, element.w, element.h, vpWidth, vpHeight);
        }
    }

    /**
     * Variante appelée quand un écran NON custom (chat, inventaire, tout
     * autre GUI vanilla/mod) est ouvert — voir le Mixin global, branché juste
     * avant son propre "return" pour ce cas. Ne dessine que les éléments
     * dont {@link HudElement#showWhenScreenOpen} est vrai (opt-in par
     * élément, pas utilisé actuellement par aucun module) OU dont le réglage
     * GLOBAL correspondant au TYPE d'écran ouvert (voir {@link HudScreenKind},
     * {@link GlobalUiSettings#showHudInInventory}/InContainers/InChat) est
     * activé — demandé explicitement par l'utilisateur ("le HUD disparaît à
     * la moindre interface"), réglage GLOBAL (tous les modules HUD),
     * granularité PAR TYPE d'écran, visible par défaut dans
     * Inventaire/Conteneurs/Tchat (PAS le menu pause).
     *
     * @param currentScreen l'écran vanilla/mod actuellement ouvert (jamais
     *     {@code null} ici — {@link #render} couvre déjà le cas
     *     currentScreen == null) — classifié via {@link HudScreenKind}.
     */
    public static void renderPersistent(UiRenderer renderer, Object currentScreen, int vpWidth, int vpHeight) {
        HudScreenKind kind = HudScreenKind.classify(currentScreen);
        boolean globalShow;
        switch (kind) {
            case INVENTORY: globalShow = GlobalUiSettings.INSTANCE.showHudInInventory; break;
            case CONTAINER: globalShow = GlobalUiSettings.INSTANCE.showHudInContainers; break;
            case CHAT:      globalShow = GlobalUiSettings.INSTANCE.showHudInChat; break;
            default:        globalShow = false; break;
        }
        for (HudElement element : HudRegistry.elements()) {
            if (!globalShow && !element.showWhenScreenOpen) continue;
            element.refreshSize();
            float x = element.screenX(vpWidth);
            float y = element.screenY(vpHeight);
            HudPanelRenderer.draw(renderer, element, x, y, element.w, element.h, vpWidth, vpHeight);
        }
    }
}
