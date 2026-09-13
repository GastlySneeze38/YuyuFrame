package com.yuyuframe.launcheragent.runtime.ui.hud;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;
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
 * <h2>Deux chemins, selon la tranche</h2>
 *
 * Sur les tranches liées (26.1.2, 1.21.11), la classification est une
 * OPÉRATION du point d'accès {@link AccessPoint#SCREEN_KIND} : trois
 * {@code instanceof} typés dans la liaison de la version, zéro réflexion
 * (2026-09-13).
 *
 * <p>Les brackets GELÉS (1.8.9, 1.16.5, 1.20.4, 1.21.4) n'ont pas de liaisons :
 * la résolution par nom Yarn reste leur seul recours, et elle ne s'exécute
 * plus que là. Même partage que {@code VanillaGuiScale}.
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
        if (AccessorRegistry.isBound(AccessPoint.SCREEN_KIND)) {
            Object kind = AccessorRegistry.get(AccessPoint.SCREEN_KIND, screen);
            if (kind instanceof String) {
                try {
                    return valueOf((String) kind);
                } catch (IllegalArgumentException e) {
                    reportOnce("genre d'écran inconnu rendu par la liaison : " + kind);
                }
            }
            // null = liaison en échec, déjà journalisée par AccessorRegistry.
            return OTHER;
        }
        return classifyFrozenBracket(screen);
    }

    /**
     * Brackets gelés uniquement. InventoryScreen HÉRITE de HandledScreen
     * (l'inventaire du joueur EST un conteneur du point de vue du jeu) —
     * INVENTORY est donc testé avant CONTAINER.
     */
    private static HudScreenKind classifyFrozenBracket(Object screen) {
        try {
            if (isInstance(screen, "net/minecraft/client/gui/screen/ChatScreen", "net.minecraft.client.gui.screens.ChatScreen")) {
                return CHAT;
            }
            if (isInstance(screen, "net/minecraft/client/gui/screen/ingame/InventoryScreen", "net.minecraft.client.gui.screens.inventory.InventoryScreen")) {
                return INVENTORY;
            }
            if (isInstance(screen, "net/minecraft/client/gui/screen/ingame/HandledScreen", "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen")) {
                return CONTAINER;
            }
        } catch (Throwable t) {
            // Ce catch était MUET : un échec de résolution rendait OTHER, donc
            // le HUD disparaissait derrière l'inventaire sans une ligne de log.
            reportOnce("classification par réflexion en échec : " + t);
        }
        return OTHER;
    }

    private static boolean isInstance(Object obj, String yarnClass, String realClassFallback) {
        Class<?> c = McReflect.yarnClass(yarnClass, realClassFallback);
        return c != null && c.isInstance(obj);
    }

    private static boolean reported;

    private static void reportOnce(String message) {
        if (reported) return;
        reported = true;
        LauncherLog.err("[HudScreenKind] " + message);
    }
}
