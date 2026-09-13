package com.mojang.blaze3d.systems;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * Stub compile-only 26.1.2 — règles de l'unité : voir {@link RenderSystem}.
 * CLASSE ici, INTERFACE en 1.21.11 (voir {@link GpuDevice}).
 */
public abstract class RenderPass {

    private RenderPass() {
    }

    public void setPipeline(RenderPipeline pipeline) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void bindTexture(String name, GpuTextureView textureView, GpuSampler sampler) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void setUniform(String name, GpuBufferSlice slice) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void disableScissor() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void setVertexBuffer(int slot, GpuBuffer buffer) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void setIndexBuffer(GpuBuffer indexBuffer, VertexFormat.IndexType indexType) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void drawIndexed(int baseVertex, int firstIndex, int indexCount, int instanceCount) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void close() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
