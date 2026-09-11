package com.mojang.blaze3d.textures;

/**
 * Stub compile-only 1.21.11 — règles de l'unité : voir
 * {@code com.mojang.blaze3d.systems.RenderSystem}. Constantes d'usage SANS
 * valeur de compilation (bloc {@code static}), pour que javac ne les inline pas.
 */
public abstract class GpuTexture implements AutoCloseable {

    public static final int USAGE_COPY_DST;
    public static final int USAGE_COPY_SRC;
    public static final int USAGE_TEXTURE_BINDING;
    public static final int USAGE_RENDER_ATTACHMENT;

    static {
        USAGE_COPY_DST = stub();
        USAGE_COPY_SRC = stub();
        USAGE_TEXTURE_BINDING = stub();
        USAGE_RENDER_ATTACHMENT = stub();
    }

    private static int stub() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Privé : le vrai constructeur a 7 paramètres — une texture se crée par {@code GpuDevice.createTexture}. */
    private GpuTexture() {
    }

    public int getWidth(int mipLevel) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public int getHeight(int mipLevel) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public TextureFormat getFormat() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    @Override
    public abstract void close();

    public abstract boolean isClosed();
}
