package com.mojang.blaze3d.buffers;

/**
 * Stub compile-only 1.21.11 — règles de l'unité : voir
 * {@code com.mojang.blaze3d.systems.RenderSystem}. C'est un {@code record}
 * dans le jeu ; déclaré ici en classe finale (compilation en {@code --release 8},
 * sans records) — même forme binaire pour nos appels : classe finale,
 * accesseurs {@code buffer()/offset()/length()}.
 */
public final class GpuBufferSlice {

    public GpuBufferSlice(GpuBuffer buffer, long offset, long length) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public GpuBufferSlice slice(long offset, long length) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public GpuBuffer buffer() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public long offset() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public long length() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
