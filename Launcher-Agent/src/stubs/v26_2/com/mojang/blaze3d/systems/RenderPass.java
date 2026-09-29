package com.mojang.blaze3d.systems;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

/**
 * Stub compile-only 26.2 — règles de l'unité : voir {@link RenderSystem}.
 *
 * <p>Écarts avec la 26.1.2, vérifiés par javap sur le jar client 26.2 :
 * <ul>
 *   <li>{@code setVertexBuffer} prend une {@code GpuBufferSlice} ;</li>
 *   <li>{@code setIndexBuffer} prend un {@code com.mojang.blaze3d.IndexType}
 *       (sorti de {@code VertexFormat}) ;</li>
 *   <li>{@code drawIndexed} a CINQ entiers, dans l'ordre Vulkan
 *       {@code (indexCount, instanceCount, firstIndex, baseVertex, firstInstance)}
 *       — relevé sur l'appel de {@code GuiRenderer} (count, 1, firstIndex,
 *       baseVertex, 0) et sur la validation de {@code firstInstance}.</li>
 * </ul>
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

    public void setVertexBuffer(int slot, GpuBufferSlice buffer) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void close() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
