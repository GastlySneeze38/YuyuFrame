package com.mojang.renderpearl.api.commands;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.joml.Vector4fc;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Stub compile-only 26.3 — règles de l'unité : voir {@link com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>26.3 : INTERFACE (classe abstraite en 26.2) — le genre compte, un
 * {@code invokevirtual} sur une interface lève {@code IncompatibleClassChangeError}.
 * {@code createRenderPass(Supplier, vue, Optional)} est une méthode
 * {@code default}. Signatures relevées par javap sur le jar client 26.3.
 */
public interface CommandEncoder {

    default RenderPass createRenderPass(Supplier<String> label, GpuTextureView colorTexture, Optional<Vector4fc> clearColor) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    void writeToBuffer(GpuBufferSlice target, ByteBuffer data);

    void writeToTexture(GpuTexture target, NativeImage source);

    void writeToTexture(GpuTexture target, NativeImage source, int mipLevel, int depth, int destX, int destY);
}
