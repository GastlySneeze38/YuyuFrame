package com.yuyuframe.launcheragent.apigraphic;

/**
 * Marqueur implémenté par nos écrans custom (UiScreenBase, runtime.ui.ingameui)
 * — jamais réécrit par ScreenStubPatcher (interface 100% à nous, pas
 * net.minecraft.*), donc l'instanceof fonctionne normalement dans
 * GlobalUiRenderMixin sans passer par MappingsRegistry.
 *
 * L'écran lui-même n'a besoin de surcharger AUCUNE méthode Screen à risque
 * (render/mouseClicked/keyPressed — types record sans stub compilable en
 * 1.21+, voir docs/LauncherAgent/index.md) : il ne sert qu'à déclencher les
 * effets de bord vanilla normaux d'un écran ouvert (pause, curseur libéré,
 * clavier/souris jeu bloqués). Tout le dessin et l'input réel passent par
 * cette interface, appelée depuis un point d'accroche global unique
 * (GameRenderer.render, TAIL) — jamais depuis Screen lui-même.
 */
public interface UiDrawable {

    /** Dessine cet écran — appelé chaque frame depuis GlobalUiRenderMixin. mouseX/Y en pixels physiques écran. */
    void uiDraw(double mouseX, double mouseY);

    /** Reçoit les événements d'input pollés (souris/clavier) — voir UiInputPoller. */
    void uiPollInput(UiInputPoller input);
}
