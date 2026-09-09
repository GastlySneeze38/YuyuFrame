package com.yuyuframe.launcheragent.runtime.ui.hud;

import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;

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
 */
public enum HudScreenKind {
    INVENTORY, CONTAINER, CHAT, OTHER;

    /**
     * {@code screen} peut être {@code null} (aucun écran ouvert — appelant
     * ne devrait alors même pas passer par renderPersistent, voir
     * HudOverlayRenderer.render() pour ce cas). InventoryScreen HÉRITE de
     * HandledScreen (l'inventaire du joueur EST un conteneur du point de vue
     * du jeu) — teste donc INVENTORY avant CONTAINER, sinon InventoryScreen
     * matcherait toujours CONTAINER en premier.
     */
    public static HudScreenKind classify(Object screen) {
        if (screen == null) return OTHER;
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
        } catch (Throwable ignored) {}
        return OTHER;
    }

    private static boolean isInstance(Object obj, String yarnClass, String realClassFallback) {
        Class<?> c = McReflect.yarnClass(yarnClass, realClassFallback);
        return c != null && c.isInstance(obj);
    }
}
