package com.yuyuframe.launcheragent.screen;

import com.yuyuframe.launcheragent.runtime.ui.UiButton;

/**
 * Écran principal du moteur config custom — équivalent de OneConfigGui.create().
 *
 * Zone de contenu (slots des mods) volontairement vide pour l'instant : les
 * mods (YuyuPvP, etc.) s'enregistreront ici une fois le pipeline de base
 * validé en jeu (rendu + input, voir GlobalUiRenderMixin). Un seul widget
 * "Fermer" pour prouver le clic de bout en bout.
 *
 * Layout recalculé à chaque uiDraw() via screenWidth/screenHeight (mis à jour
 * par uiPollInput(), voir UiScreenBase) plutôt qu'au constructeur — ces
 * dimensions ne sont connues qu'une fois la première frame pollée.
 */
public class UiMainMenuScreen extends UiScreenBase {

    private static final float CLOSE_W = 100f, CLOSE_H = 24f, MARGIN = 12f;

    private final Object lastScreen;
    private UiButton closeButton;

    public UiMainMenuScreen(Object lastScreen) {
        super("YuyuFrame");
        this.lastScreen = lastScreen;
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        // Bouton positionné en bas-droite — recréé si la taille d'écran a
        // changé (resize fenêtre) pour rester ancré au bon endroit.
        if (closeButton == null || closeButton.x != screenWidth - CLOSE_W - MARGIN) {
            widgets.remove(closeButton);
            closeButton = new UiButton(
                screenWidth - CLOSE_W - MARGIN, MARGIN, CLOSE_W, CLOSE_H,
                () -> closeTo(lastScreen)
            );
            widgets.add(closeButton);
        }
        super.uiDraw(mouseX, mouseY);
    }
}
