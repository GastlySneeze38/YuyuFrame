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

    /**
     * Mutable — GlobalUiSettings (runtime.modules) le réassigne directement
     * ("Opacité du HUD", DISTINCTE de UiTheme.CARD_BG qui vise les cartes du
     * menu, pas les panneaux en jeu). Alpha 120/255 — valeur RÉELLE par défaut
     * d'OneConfig (cc.polyfrost.oneconfig.hud.BasicHud, vérifiée sur son
     * repo) : notre 210/255 précédent était bien plus opaque/lourd que la
     * référence, d'où le comparatif demandé.
     */
    public static UiColor PANEL_BG = new UiColor(10, 10, 14, 120);
    // Rayon 2 et marge 5 — mêmes valeurs qu'OneConfig (BasicHud : cornerRadius=2,
    // paddingX/Y=5) plutôt que nos 4/8 précédents, plus lourds visuellement.
    private static final float RADIUS = 2f;
    // Visibilité paquet (pas private) : réutilisées par HudElement.naturalSize()
    // pour calculer la taille "naturelle" du contenu à scale=1 (taille par
    // défaut de la boîte + seuil minimal de redimensionnement lisible) — une
    // seule source de vérité pour ces constantes plutôt que dupliquées.
    static final float PADDING = 5f;
    static final float LINE_H = 15f;
    static final float TEXT_SCALE = 0.42f;

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

        String[] lines;
        try {
            lines = element.content.lines();
        } catch (Throwable t) {
            lines = new String[]{ "--" };
        }

        // PAS d'agrandissement automatique ici (ancien "autoFit" supprimé) :
        // depuis que w/h dérivent TOUJOURS de naturalSize()*scale (voir
        // HudElement.setScale), cw/ch sont déjà proportionnels à scale par
        // construction — comparer cw/ch à naturalSize() ET multiplier par
        // scale revenait à appliquer scale DEUX FOIS (cw/natural ≈ scale, donc
        // scale*autoFit ≈ scale²) : le texte grossissait en carré du réglage
        // au lieu de suivre linéairement ("il s'agrandit trop par rapport à
        // la card"). element.scale seul suffit désormais.
        float textScale = TEXT_SCALE * element.scale;
        float lineH = LINE_H * element.scale;

        // Bloc CENTRÉ verticalement dans la boîte plutôt que collé en haut —
        // sinon une boîte redimensionnée plus grande que son contenu (poignée
        // de l'éditeur) laissait tout le vide s'accumuler en bas, comme si le
        // texte "ne s'adaptait pas" à la taille choisie.
        float blockHeight = lines.length * lineH;
        float ty = cy + (ch + blockHeight) / 2f - lineH;
        for (String line : lines) {
            renderer.drawText(line, cx + PADDING, ty, UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
            ty -= lineH;
        }
    }
}
