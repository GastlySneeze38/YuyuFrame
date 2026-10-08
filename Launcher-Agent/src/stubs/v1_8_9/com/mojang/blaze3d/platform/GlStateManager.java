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

    /** {@code bfl.a(IF)} */
    public static void alphaFunc(int function, float reference) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfl.e()} */
    public static void enableLighting() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfl.f()} */
    public static void disableLighting() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfl.i()} */
    public static void disableDepthTest() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfl.j()} */
    public static void enableDepthTest() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfl.a(Z)} */
    public static void depthMask(boolean write) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfl.d(I)} — GL_LINEAR, GL_EXP ou GL_EXP2. */
    public static void fogMode(int mode) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfl.b(F)} */
    public static void fogStart(float start) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfl.c(F)} */
    public static void fogEnd(float end) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfl.w()} */
    public static void enableTexture() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfl.x()} */
    public static void disableTexture() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bfl.i(I)} */
    public static void bindTexture(int id) {
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

    /** {@code bfl.G()} — oublie la couleur mémorisée : le prochain {@code color} est envoyé quoi qu'il arrive. */
    public static void clearColor() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
