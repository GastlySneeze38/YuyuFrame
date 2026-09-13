package com.mojang.blaze3d.textures;

/**
 * Stub compile-only 26.1.2 — règles de l'unité : voir
 * {@code com.mojang.blaze3d.systems.RenderSystem}. Constantes d'usage sans
 * valeur de compilation (voir {@code GpuBuffer}).
 */
public abstract class GpuTexture {

    public static final int USAGE_COPY_DST;
    public static final int USAGE_TEXTURE_BINDING;
    public static final int USAGE_RENDER_ATTACHMENT;

    static {
        USAGE_COPY_DST = stub();
        USAGE_TEXTURE_BINDING = stub();
        USAGE_RENDER_ATTACHMENT = stub();
    }

    private static int stub() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Privé : le vrai constructeur a 7 paramètres — une texture se crée par {@code GpuDevice.createTexture}. */
    private GpuTexture() {
    }

    public abstract void close();
}
