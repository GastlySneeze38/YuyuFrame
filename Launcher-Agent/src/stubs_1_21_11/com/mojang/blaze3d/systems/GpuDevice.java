package com.mojang.blaze3d.systems;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

/**
 * Stub compile-only 1.21.11 — règles de l'unité : voir {@link RenderSystem}.
 *
 * <p>Omis car obfusqués : {@code createSampler(...)} (renvoie {@code fzf}) et
 * {@code precompilePipeline(RenderPipeline, fyy)} — la surcharge qui prend le
 * {@code ShaderSource} maison. Celle à un argument (ci-dessous) utilise le
 * {@code ShaderSource} du jeu, qui ne connaît pas nos GLSL.
 */
public interface GpuDevice {

    CommandEncoder createCommandEncoder();

    GpuTexture createTexture(Supplier<String> label, int usage, TextureFormat format,
                             int width, int height, int depthOrLayers, int mipLevels);

    GpuTexture createTexture(String label, int usage, TextureFormat format,
                             int width, int height, int depthOrLayers, int mipLevels);

    GpuTextureView createTextureView(GpuTexture texture);

    GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels);

    GpuBuffer createBuffer(Supplier<String> label, int usage, long size);

    GpuBuffer createBuffer(Supplier<String> label, int usage, ByteBuffer data);

    int getMaxTextureSize();

    int getUniformOffsetAlignment();

    CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline);
}
