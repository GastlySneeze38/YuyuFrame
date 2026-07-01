package com.yuyuframe.launcheragent.runtime.hud;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;

/**
 * Dessin du panneau HUD — PARTAGÉ entre l'éditeur (UiHudBox, qui ajoute par
 * dessus son propre liseré d'accent au survol/glissement et sa poignée de
 * redimensionnement) et l'affichage réel en jeu (HudOverlayRenderer, aucun
 * ajout) : même rendu dans les deux cas, demandé explicitement ("le rendu
 * dans l'éditeur doit être le même que in game").
 */
public final class HudPanelRenderer {
    private HudPanelRenderer() {}

    static final UiColor PANEL_BG = new UiColor(10, 10, 14, 210);
    private static final float RADIUS = 4f;
    private static final float PADDING = 8f;
    private static final float LINE_H = 14f;
    private static final float TEXT_SCALE = 0.4f;

    public static void draw(UiRenderer renderer, HudElement element, float x, float y, float w, float h, int vpWidth, int vpHeight) {
        renderer.drawRoundedRect(x, y, x + w, y + h, RADIUS, PANEL_BG, vpWidth, vpHeight);

        // Empile les lignes depuis le HAUT du panneau (comme un vrai panneau
        // HUD à contenu variable) — pas centré verticalement, pour rester
        // cohérent quel que soit le nombre de lignes ou la hauteur choisie.
        String[] lines;
        try {
            lines = element.content.lines();
        } catch (Throwable t) {
            lines = new String[]{ "--" };
        }
        float ty = y + h - PADDING - 4f;
        for (String line : lines) {
            renderer.drawText(line, x + PADDING, ty, UiTheme.TEXT_PRIMARY, TEXT_SCALE, vpWidth, vpHeight);
            ty -= LINE_H;
        }
    }
}
