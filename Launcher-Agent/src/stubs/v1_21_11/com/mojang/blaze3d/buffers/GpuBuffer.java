package com.mojang.blaze3d.buffers;

/**
 * Stub compile-only 1.21.11 — règles de l'unité : voir
 * {@code com.mojang.blaze3d.systems.RenderSystem}. Constantes d'usage SANS
 * valeur de compilation (bloc {@code static}), pour que javac ne les inline pas.
 */
public abstract class GpuBuffer implements AutoCloseable {

    public static final int USAGE_COPY_DST;
    public static final int USAGE_COPY_SRC;
    public static final int USAGE_VERTEX;
    public static final int USAGE_INDEX;
    public static final int USAGE_UNIFORM;

    static {
        USAGE_COPY_DST = stub();
        USAGE_COPY_SRC = stub();
        USAGE_VERTEX = stub();
        USAGE_INDEX = stub();
        USAGE_UNIFORM = stub();
    }

    private static int stub() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Privé : le vrai constructeur est {@code (int, long)} — interdit un {@code new} qui compilerait sans exister en jeu. */
    private GpuBuffer() {
    }

    public long size() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public GpuBufferSlice slice(long offset, long length) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public GpuBufferSlice slice() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public abstract boolean isClosed();

    @Override
    public abstract void close();
}
