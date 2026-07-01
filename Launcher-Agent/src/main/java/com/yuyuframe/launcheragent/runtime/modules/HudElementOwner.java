package com.yuyuframe.launcheragent.runtime.modules;

import com.yuyuframe.launcheragent.runtime.hud.HudElement;

/**
 * Implémenté par un module qui possède un élément HUD (voir
 * runtime.modules.builtin.SingleHudModule) — permet à
 * {@link ConfigScreenBuilder} d'ajouter automatiquement les réglages
 * génériques façon OneConfig (verrouillage, échelle, marges, réinitialisation
 * de position) sans connaître le module concret.
 */
public interface HudElementOwner {
    HudElement hudElement();
}
