package com.yuyuframe.launcheragent.apigraphic.widget;

import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;

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

    /** Dessine cet écran. mouseX/Y en pixels physiques écran. */
    void uiDraw(double mouseX, double mouseY);

    /**
     * L'image entière de cet écran — c'est CE que les mixins appellent chaque
     * frame, pas {@link #uiDraw} directement : un écran peut ainsi envelopper
     * tout son dessin (y compris ce que ses sous-classes ajoutent autour de
     * {@code super.uiDraw}) dans sa transition d'entrée ou de sortie. Par
     * défaut, c'est juste {@link #uiDraw}.
     */
    default void uiFrame(double mouseX, double mouseY) {
        uiDraw(mouseX, mouseY);
    }

    /** Reçoit les événements d'input pollés (souris/clavier) — voir UiInputPoller. */
    void uiPollInput(UiInputPoller input);
}
