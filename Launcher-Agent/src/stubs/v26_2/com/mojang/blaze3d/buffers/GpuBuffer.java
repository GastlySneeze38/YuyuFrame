package com.mojang.blaze3d.buffers;

/**
 * Stub compile-only 26.1.2 — règles de l'unité : voir
 * {@code com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>Constantes d'usage SANS valeur de compilation (bloc {@code static}), pour
 * que javac ne les inline pas. Elles sont DISTINCTES de celles de
 * {@code GpuTexture} malgré des noms identiques — bug historique
 * {@code USAGE_COPY_DST}.
 */
public abstract class GpuBuffer {

    public static final int USAGE_COPY_DST;
    public static final int USAGE_VERTEX;
    public static final int USAGE_UNIFORM;

    static {
        USAGE_COPY_DST = stub();
        USAGE_VERTEX = stub();
        USAGE_UNIFORM = stub();
    }

    private static int stub() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Privé : le vrai constructeur est {@code (int, long)} — un tampon se crée par {@code GpuDevice.createBuffer}. */
    private GpuBuffer() {
    }

    public GpuBufferSlice slice(long offset, long length) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Tampon entier — 26.2 : {@code RenderPass.setVertexBuffer} prend une slice (vérifié par javap). */
    public GpuBufferSlice slice() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
