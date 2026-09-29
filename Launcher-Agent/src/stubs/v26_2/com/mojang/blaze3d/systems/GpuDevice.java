package com.mojang.blaze3d.systems;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.util.function.Supplier;

/**
 * Stub compile-only 26.2 — règles de l'unité : voir {@link RenderSystem}.
 *
 * <p>CLASSE, comme en 26.1.2 (INTERFACE en 1.21.11) — voir le stub 26.1 pour
 * l'{@code IncompatibleClassChangeError} qu'un mauvais genre provoquerait.
 *
 * <p>26.2 : {@code createTexture} prend un {@code com.mojang.blaze3d.GpuFormat}
 * ({@code textures.TextureFormat} a disparu). Vérifié par javap.
 *
 * <p>{@code precompilePipeline(RenderPipeline, ShaderSource)} : la surcharge
 * qui prend NOTRE source GLSL. Celle à un argument lirait la source du jeu,
 * qui ne connaît pas nos shaders.
 */
public abstract class GpuDevice {

    private GpuDevice() {
    }

    public CommandEncoder createCommandEncoder() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public GpuTexture createTexture(Supplier<String> label, int usage, GpuFormat format,
                                    int width, int height, int depthOrLayers, int mipLevels) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public GpuTextureView createTextureView(GpuTexture texture) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public GpuBuffer createBuffer(Supplier<String> label, int usage, long size) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline, ShaderSource source) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
