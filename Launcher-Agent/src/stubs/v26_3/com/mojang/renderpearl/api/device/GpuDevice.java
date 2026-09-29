package com.mojang.renderpearl.api.device;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Stub compile-only 26.3 — règles de l'unité : voir {@link com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>26.3 : INTERFACE (classe abstraite en 26.2) — implémentée par
 * {@code renderpearl.frontend.FrontendGpuDevice}. {@code precompilePipeline}
 * a disparu : {@code compilePipeline(pipeline, source, executor)} rend un futur
 * de {@code CompiledRenderPipeline$Pending}, que {@code PipelineCache.get}
 * attend ({@code join}) puis termine ({@code finishCompile}). Relevé par javap.
 */
public interface GpuDevice {

    CommandEncoder createCommandEncoder();

    GpuTexture createTexture(Supplier<String> label, int usage, GpuFormat format,
                             int width, int height, int depthOrLayers, int mipLevels);

    GpuTextureView createTextureView(GpuTexture texture);

    GpuBuffer createBuffer(Supplier<String> label, int usage, long size);

    CompletableFuture<CompiledRenderPipeline.Pending> compilePipeline(RenderPipeline pipeline, ShaderSource source,
                                                                      Executor executor);
}
