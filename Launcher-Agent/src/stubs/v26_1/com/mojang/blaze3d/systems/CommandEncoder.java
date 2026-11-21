package com.mojang.blaze3d.systems;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.nio.ByteBuffer;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * Stub compile-only 26.1.2 — règles de l'unité : voir {@link RenderSystem}.
 * CLASSE ici, INTERFACE en 1.21.11 (voir {@link GpuDevice}).
 */
public abstract class CommandEncoder {

    private CommandEncoder() {
    }

    public RenderPass createRenderPass(Supplier<String> label, GpuTextureView colorTexture, OptionalInt clearColor) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void writeToBuffer(GpuBufferSlice target, ByteBuffer data) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void writeToTexture(GpuTexture target, NativeImage source) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void writeToTexture(GpuTexture target, NativeImage source, int mipLevel, int depth,
                               int destX, int destY, int width, int height, int skipPixels, int skipRows) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Copie d'une texture vers un tampon ; {@code callback} quand le GPU a fini (javap, comme Screenshot). */
    public void copyTextureToBuffer(GpuTexture source, com.mojang.blaze3d.buffers.GpuBuffer target, long offset,
                                    Runnable callback, int mipLevel) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public com.mojang.blaze3d.buffers.GpuBuffer.MappedView mapBuffer(com.mojang.blaze3d.buffers.GpuBuffer buffer,
                                                                    boolean read, boolean write) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
