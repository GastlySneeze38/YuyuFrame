package com.mojang.blaze3d.textures;

/** Stub compile-only 1.21.11 — règles de l'unité : voir {@code com.mojang.blaze3d.systems.RenderSystem}. */
public abstract class GpuTextureView implements AutoCloseable {

    /** Privé : une vue se crée par {@code GpuDevice.createTextureView}. */
    private GpuTextureView() {
    }

    public GpuTexture texture() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public int getWidth(int mipLevel) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public int getHeight(int mipLevel) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    @Override
    public abstract void close();

    public abstract boolean isClosed();
}
