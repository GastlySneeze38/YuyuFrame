package com.mojang.blaze3d.systems;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gl.GpuSampler;

/**
 * Stub compile-only 1.21.11 — règles de l'unité : voir {@link RenderSystem}.
 * {@code bindTexture}/{@code setIndexBuffer} utilisent des types obfusqués,
 * stubés sous leur nom Yarn ({@code GpuSampler}, {@code VertexFormat$IndexType}).
 */
public interface RenderPass extends AutoCloseable {

    void setPipeline(RenderPipeline pipeline);

    void bindTexture(String name, GpuTextureView textureView, GpuSampler sampler);

    void setIndexBuffer(GpuBuffer indexBuffer, VertexFormat.IndexType indexType);

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
