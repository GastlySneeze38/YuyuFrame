package com.yuyuframe.launcheragent.runtime.hud;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
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
    // Rayon 2, valeur OneConfig (BasicHud : cornerRadius=2).
    private static final float RADIUS = 2f;
    // Visibilité paquet (pas private) : réutilisées par HudElement.naturalSize()
    // pour calculer la taille "naturelle" du contenu à scale=1 (taille par
    // défaut de la boîte + seuil minimal de redimensionnement lisible) — une
    // seule source de vérité pour ces constantes plutôt que dupliquées.
    // Relevé de 5 à 9 : la boîte colle maintenant EXACTEMENT à la largeur du
    // texte (voir HudElement.refreshSize()), donc PADDING est tout ce qui
    // sépare le texte du bord de la carte — à 5px avec le texte agrandi
    // (TEXT_SCALE 0.55), "FPS"/"ms" touchaient quasiment le bord ("collé à
    // la boîte").
    static final float PADDING = 9f;
    // LINE_H/TEXT_SCALE relevés à 20/0.55 (étaient 15/0.42) — la formule de
    // taille elle-même n'avait pas de bug (naturalW/H suivent exactement ce
    // que drawText utilise réellement, voir HudElement.naturalSize), mais à
    // 0.42 le texte réel ne fait qu'environ 15px de haut à l'écran (aucune
    // compensation de GUI Scale dans ce pipeline, contrairement à vanilla) :
    // trop petit pour lire d'un coup d'œil en jeu, et la boîte — dérivée
    // directement de cette taille de texte — paraissait donc minuscule elle
    // aussi. Les deux montent ensemble puisque la boîte suit le texte.
    static final float LINE_H = 20f;
    static final float TEXT_SCALE = 0.55f;

    public static void draw(UiRenderer renderer, HudElement element, float x, float y, float w, float h, int vpWidth, int vpHeight) {
        renderer.drawRoundedRect(x, y, x + w, y + h, RADIUS, PANEL_BG, vpWidth, vpHeight);

        // Marge = base (PADDING, commune à TOUS les HUD) + extra optionnel du
        // module (element.paddingX/Y, façon OneConfig, 0 par défaut) — SEUL
        // endroit du moteur qui calcule cette marge, mise à l'échelle par
        // element.scale comme le reste (x/y/w/h reçus ici sont déjà en espace
        // écran = naturalSize()*scale, voir HudElement.naturalSize() qui
        // utilise exactement le même PADDING+paddingX/Y pour dimensionner la
        // boîte — sans le *scale ici, la marge restait fixe en pixels pendant
        // que la boîte grandissait/rétrécissait autour, d'où une marge tantôt
        // trop grande tantôt trop petite selon le réglage de taille).
        float padX = (PADDING + element.paddingX) * element.scale;
        float padY = (PADDING + element.paddingY) * element.scale;
        float cx = x + padX;
        float cy = y + padY;
        float cw = Math.max(0f, w - padX * 2f);
        float ch = Math.max(0f, h - padY * 2f);

        if (element.customRenderer != null) {
            try {
                // cx/cy/cw/ch sont DÉJÀ la zone de contenu (marge déjà retirée)
                // — le renderer du module dessine directement dedans, sans
                // reconnaître sa propre marge (voir HudElement.CustomRenderer).
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
        float blockTop = cy + (ch + blockHeight) / 2f;
        // drawText positionne SA LIGNE DE BASE à y (voir UiRenderer.drawText),
        // pas le haut du glyphe — utiliser "blockTop - lineH" comme avant
        // revient à supposer que le glyphe occupe TOUTE sa ligne au-dessus de
        // la ligne de base (aucune place réservée pour la descente sous la
        // ligne de base). En pratique la plupart des textes de HUD n'ont pas
        // de descente (chiffres, majuscules — "120 FPS") : l'encre réelle ne
        // remplit que la portion "ascent" au-dessus de la ligne de base, donc
        // se retrouvait collée en bas de sa propre ligne réservée, laissant
        // tout le vide en haut ("le padding du haut est plus grand"). Utiliser
        // la vraie métrique ascent de la police place la ligne de base au bon
        // endroit : le haut de l'encre touche exactement blockTop, la descente
        // (inutilisée ici mais réservée) occupe l'espace en dessous.
        float ascentPx = UiFont.REGULAR.ascent * textScale * UiFont.SIZE_CORRECTION;
        float ty = blockTop - ascentPx;
        for (String line : lines) {
            renderer.drawText(line, cx, ty, UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
            ty -= lineH;
        }
    }
}
