package com.yuyuframe.launcheragent.apigraphic.hud;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;

/**
 * Dessin des éléments HUD — couche BASSE, sans aucune notion de politique
 * d'affichage.
 *
 * <p>Séparé de {@code runtime.ui.hud.HudOverlayRenderer} lors du refacto du
 * 2026-08-27 : ce dernier mélangeait deux responsabilités de niveaux
 * différents — le DESSIN (boucle sur les éléments, chaîne de flou partagée,
 * lot de texte) et la POLITIQUE (quel écran vanilla est ouvert, que disent les
 * réglages, la touche F1 est-elle enfoncée). Seul le dessin appartient au
 * moteur graphique ; la politique connaît {@code GlobalUiSettings} et les
 * classes du jeu, donc elle reste côté {@code runtime}.
 *
 * <p>Concrètement, c'est ce qui permet à ce fichier de ne dépendre QUE de
 * {@code apigraphic} : sans cette coupe, déplacer le HUD dans le moteur aurait
 * fait entrer {@code GlobalUiSettings} (un {@code LauncherModule} annoté
 * {@code @Config}, de la politique applicative pure) dans la couche de rendu.
 */
public final class HudRenderer {
    private HudRenderer() {}

    /** Dessine TOUS les éléments enregistrés — cas du jeu sans écran ouvert. */
    public static void drawAll(UiRenderer renderer, int vpWidth, int vpHeight) {
        draw(renderer, true, vpWidth, vpHeight);
    }

    /**
     * Dessine les éléments visibles par-dessus un écran vanilla ouvert.
     *
     * @param globalShow décision DÉJÀ prise par l'appelant (voir
     *        {@code runtime.ui.hud.HudOverlayRenderer}) — passée en booléen
     *        plutôt qu'en écran à classifier ici, précisément pour que cette
     *        classe n'ait pas à connaître les types d'écrans du jeu ni les
     *        réglages. Un élément dont {@code showWhenScreenOpen} est vrai
     *        reste affiché même quand {@code globalShow} est faux.
     */
    public static void drawPersistent(UiRenderer renderer, boolean globalShow, int vpWidth, int vpHeight) {
        draw(renderer, globalShow, vpWidth, vpHeight);
    }

    private static void draw(UiRenderer renderer, boolean globalShow, int vpWidth, int vpHeight) {
        // Une seule chaîne de flou pour TOUS les panneaux de cette frame (sans
        // effet si l'option "Fond flouté (HUD)" est désactivée) — voir
        // HudPanelRenderer#ensureGlassChain.
        HudPanelRenderer.ensureGlassChain(renderer, vpWidth, vpHeight);
        // Tout le texte du HUD en UNE passe par police au lieu d'une par
        // chaîne — voir UiRenderer.beginTextBatch. L'ordre Z convient ici : le
        // texte se retrouve au-dessus de TOUS les fonds de panneaux, ce qui est
        // exactement le rendu voulu (et les panneaux ne se chevauchent pas
        // entre eux).
        renderer.beginTextBatch();
        for (HudElement element : HudRegistry.elements()) {
            if (!globalShow && !element.showWhenScreenOpen) continue;
            // Voir HudElement.refreshSize() : un contenu de largeur variable
            // (FPS/Ping) doit être remesuré à CHAQUE frame, pas une seule fois
            // à la construction — sinon la boîte reste figée sur le texte de
            // repli initial pendant que le vrai texte affiché change de largeur.
            element.refreshSize();
            float x = element.screenX(vpWidth);
            float y = element.screenY(vpHeight);
            HudPanelRenderer.draw(renderer, element, x, y, element.w, element.h, vpWidth, vpHeight);
        }
        renderer.endTextBatch(vpWidth, vpHeight);
    }
}
