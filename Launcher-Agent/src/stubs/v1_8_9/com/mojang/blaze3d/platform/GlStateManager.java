package com.mojang.blaze3d.platform;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code bfl}).
 *
 * <p>Passer par lui (et non par GL directement) pour tout ce que la 1.8.9
 * garde en cache : sinon son cache diverge de l'état GL réel.
 */
public final class GlStateManager {

    private GlStateManager() {
    }

    public static void enableRescaleNormal() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public static void disableRescaleNormal() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public static void enableBlend() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public static void color(float r, float g, float b, float a) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
