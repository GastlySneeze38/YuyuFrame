package com.yuyuframe.launcheragent.runtime.ui;

import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;

/**
 * Implémenté par un module qui possède un élément HUD (voir
 * runtime.module.SingleHudModule) — permet à
 * {@link ConfigScreenBuilder} d'ajouter automatiquement les réglages
 * génériques façon OneConfig (verrouillage, échelle, marges, réinitialisation
 * de position) sans connaître le module concret.
 */
public interface HudElementOwner {
    /** L'élément principal : celui des réglages génériques de l'écran du module. */
    HudElement hudElement();

    /**
     * TOUS les éléments du module, le principal en premier. Un module qui en
     * a plusieurs (le Score PvP : panneau de fin de duel, annonce, mots des
     * bons coups) les rend tous ici, sinon seul le premier garderait sa
     * position d'une partie à l'autre ({@link HudConfigStore}).
     */
    default java.util.List<HudElement> hudElements() {
        return java.util.List.of(hudElement());
    }
}
