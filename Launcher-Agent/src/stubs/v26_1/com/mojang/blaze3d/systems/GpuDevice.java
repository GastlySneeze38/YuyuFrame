package com.mojang.blaze3d.systems;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;

import java.util.function.Supplier;

/**
 * Stub compile-only 26.1.2 — règles de l'unité : voir {@link RenderSystem}.
 *
 * <p>CLASSE, et ce n'est pas un détail : en 1.21.11 le même nom désigne une
 * INTERFACE. Déclarer ce stub en interface ferait émettre à javac un
 * {@code invokeinterface} là où la JVM attend un {@code invokevirtual} —
 * {@code IncompatibleClassChangeError} au premier appel.
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

    public GpuTexture createTexture(Supplier<String> label, int usage, TextureFormat format,
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
