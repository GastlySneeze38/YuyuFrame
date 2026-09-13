package com.mojang.blaze3d.platform;

/**
 * Stub compile-only 26.1.2 — règles de l'unité : voir
 * {@code com.mojang.blaze3d.systems.RenderSystem}. Image CPU, remplie pixel par
 * pixel puis envoyée au GPU par {@code CommandEncoder.writeToTexture}.
 *
 * <p>Le constructeur à quatre arguments est PUBLIC (vérifié sur le jar) :
 * l'adaptateur réflexif passait par {@code getDeclaredConstructor} +
 * {@code setAccessible}, ce qui n'était pas nécessaire.
 */
public final class NativeImage {

    /** {@code NativeImage$Format} — seul {@code RGBA} nous sert. */
    public enum Format {
        RGBA,
        RGB,
        LUMINANCE_ALPHA,
        LUMINANCE
    }

    public NativeImage(Format format, int width, int height, boolean calloc) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /**
     * Pixel au format ABGR packé — {@code (a<<24)|(b<<16)|(g<<8)|r}, exactement
     * ce que le nom décrit. {@code setColor} n'existe plus en 26.1 (c'est le nom
     * de la 1.21.11).
     */
    public void setPixelABGR(int x, int y, int abgr) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void close() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
