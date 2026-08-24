package com.mojang.blaze3d.platform;

/**
 * Stub compile-only (26.1+) — {@code _activeTexture}/{@code _bindTexture}
 * publiques (confirmé par désassemblage, voir {@code GlBridge#resolveGlStateManagerMethod}
 * pour l'ancien chemin réflexif équivalent). Le vrai package runtime peut
 * être {@code com.mojang.blaze3d.opengl.GlStateManager} selon la version —
 * ce stub cible le nom historique {@code com.mojang.blaze3d.platform}; à
 * ajuster si la résolution échoue en jeu (voir GlBridge pour les deux
 * chemins déjà tentés côté réflexion).
 */
public abstract class GlStateManager {
    public static void _activeTexture(int texture) {}
    public static void _bindTexture(int texture) {}
}
