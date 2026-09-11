package com.mojang.blaze3d.systems;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.nio.ByteBuffer;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * Stub compile-only 1.21.11 — règles de l'unité : voir {@link RenderSystem}.
 *
 * <p>Omis car obfusqués : les {@code writeToTexture(...)} (paramètre
 * {@code fyh} = NativeImage).
 */
public interface CommandEncoder {

    RenderPass createRenderPass(Supplier<String> label, GpuTextureView colorTexture, OptionalInt clearColor);

    void clearColorTexture(GpuTexture texture, int color);

    void writeToBuffer(GpuBufferSlice target, ByteBuffer data);

    void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target);

    void copyTextureToTexture(GpuTexture source, GpuTexture target, int mipLevel,
                              int destX, int destY, int sourceX, int sourceY, int width, int height);

    void presentTexture(GpuTextureView texture);
}
