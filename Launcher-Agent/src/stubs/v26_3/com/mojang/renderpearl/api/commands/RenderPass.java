package com.mojang.renderpearl.api.commands;

import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;

/**
 * Stub compile-only 26.3 — règles de l'unité : voir {@link com.mojang.blaze3d.systems.RenderSystem}.
 *
 * <p>26.3 : INTERFACE (classe abstraite en 26.2), qui étend
 * {@code com.mojang.renderpearl.util.UncheckedAutoCloseable} — d'où le
 * {@code close()} déclaré ici. Deux changements d'API (javap) :
 * <ul>
 *   <li>{@code setPipeline} prend un {@link CompiledRenderPipeline}, plus un
 *       {@code RenderPipeline} ;</li>
 *   <li>{@code bindTexture(nom, vue, sampler)} devient une surcharge de
 *       {@code setUniform}.</li>
 * </ul>
 */
public interface RenderPass extends AutoCloseable {

    void setPipeline(CompiledRenderPipeline pipeline);

    void setUniform(String name, GpuTextureView textureView, GpuSampler sampler);

    void setUniform(String name, GpuBufferSlice slice);

    void disableScissor();

    void setVertexBuffer(int slot, GpuBufferSlice buffer);

    void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType);

    void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance);

    @Override
    void close();
}
