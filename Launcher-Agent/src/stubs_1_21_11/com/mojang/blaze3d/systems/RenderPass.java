package com.mojang.blaze3d.systems;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;

/**
 * Stub compile-only 1.21.11 — règles de l'unité : voir {@link RenderSystem}.
 *
 * <p>Omis car obfusqués : {@code bindTexture(String, GpuTextureView, fzf)}
 * (GpuSampler) et {@code setIndexBuffer(GpuBuffer, VertexFormat$a)}.
 */
public interface RenderPass extends AutoCloseable {

    void setPipeline(RenderPipeline pipeline);

    void setUniform(String name, GpuBuffer buffer);

    void setUniform(String name, GpuBufferSlice slice);

    void enableScissor(int x, int y, int width, int height);

    void disableScissor();

    void setVertexBuffer(int slot, GpuBuffer buffer);

    void drawIndexed(int baseVertex, int firstIndex, int indexCount, int instanceCount);

    void draw(int firstVertex, int vertexCount);

    @Override
    void close();
}
