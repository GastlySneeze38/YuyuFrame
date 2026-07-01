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

        // Marges génériques (voir HudElement.paddingX/Y, façon OneConfig) —
        // rétrécit simplement la zone de contenu utile, panneau de fond
        // inchangé (dessiné juste au-dessus, sur x/y/w/h d'origine).
        float cx = x + element.paddingX;
        float cy = y + element.paddingY;
        float cw = Math.max(0f, w - element.paddingX * 2f);
        float ch = Math.max(0f, h - element.paddingY * 2f);

        if (element.customRenderer != null) {
            try {
                element.customRenderer.draw(renderer, cx, cy, cw, ch, element.scale, vpWidth, vpHeight);
            } catch (Throwable ignored) {}
            return;
        }

        // Empile les lignes depuis le HAUT du panneau (comme un vrai panneau
        // HUD à contenu variable) — pas centré verticalement, pour rester
        // cohérent quel que soit le nombre de lignes ou la hauteur choisie.
        String[] lines;
        try {
            lines = element.content.lines();
        } catch (Throwable t) {
            lines = new String[]{ "--" };
        }
        float textScale = TEXT_SCALE * element.scale;
        float lineH = LINE_H * element.scale;
        float ty = cy + ch - PADDING - 4f;
        for (String line : lines) {
            renderer.drawText(line, cx + PADDING, ty, UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
            ty -= lineH;
        }
    }
}
