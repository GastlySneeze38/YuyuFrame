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

    /** {@code bfl.k()} */
    public static void disableBlend() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfl.a(IIII)} */
    public static void blendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfl.o()} */
    public static void enableCull() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfl.p()} */
    public static void disableCull() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfl.a(ZZZZ)} */
    public static void colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public static void color(float r, float g, float b, float a) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
