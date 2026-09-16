package com.yuyuframe.launcheragent.runtime.ui.hud;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

/**
 * Classification d'un écran vanilla/mod ouvert, pour décider — module HUD
 * par module HUD — si le HUD doit rester visible à travers (voir
 * GlobalUiSettings.showHudInInventory/InContainers/InChat, et
 * HudOverlayRenderer.renderPersistent).
 *
 * Demandé explicitement par l'utilisateur ("il pose moi des questions pour
 * savoir dans quelle interface il reste par défaut et quelle interface on
 * peut choisir de oui ou non avec les réglages") — réponses retenues :
 * réglage GLOBAL (tous les modules HUD), un interrupteur SÉPARÉ par type
 * d'écran, visible par défaut dans Inventaire/Conteneurs/Tchat (PAS le menu
 * pause, jamais demandé).
 *
 * <h2>Un seul chemin</h2>
 *
 * La classification est une OPÉRATION du point d'accès
 * {@link AccessPoint#SCREEN_KIND} : trois {@code instanceof} typés dans la
 * liaison de la version, zéro réflexion (2026-09-13). Liée sur les trois
 * versions supportées (1.8.9, 1.21.11, 26.1.2) ; la résolution par nom Yarn
 * des tranches gelées a été supprimée le 2026-09-16 avec ces versions.
 */
public enum HudScreenKind {
    INVENTORY, CONTAINER, CHAT, OTHER;

    /**
     * {@code screen} peut être {@code null} (aucun écran ouvert — l'appelant
     * ne devrait alors même pas passer par renderPersistent, voir
     * HudOverlayRenderer.render() pour ce cas).
     */
    public static HudScreenKind classify(Object screen) {
        if (screen == null) return OTHER;
        Object kind = AccessorRegistry.get(AccessPoint.SCREEN_KIND, screen);
        if (kind instanceof String) {
            try {
                return valueOf((String) kind);
            } catch (IllegalArgumentException e) {
                reportOnce("genre d'écran inconnu rendu par la liaison : " + kind);
            }
        }
        // null = liaison absente ou en échec, journalisée par AccessorRegistry.
        return OTHER;
    }

    private static boolean reported;

    private static void reportOnce(String message) {
        if (reported) return;
        reported = true;
        LauncherLog.err("[HudScreenKind] " + message);
    }
}
