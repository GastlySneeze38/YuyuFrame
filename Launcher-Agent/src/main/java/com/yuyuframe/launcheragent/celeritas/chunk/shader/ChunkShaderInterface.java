package com.yuyuframe.launcheragent.celeritas.chunk.shader;

import com.yuyuframe.launcheragent.celeritas.gl.tessellation.GlPrimitiveType;
import com.yuyuframe.launcheragent.celeritas.chunk.terrain.TerrainRenderPass;
import org.joml.Matrix4fc;

public interface ChunkShaderInterface {
    void setupState(TerrainRenderPass pass);
    default void restoreState() {}
    GlPrimitiveType getPrimitiveType();
    void setProjectionMatrix(Matrix4fc matrix);
    void setModelViewMatrix(Matrix4fc matrix);
    void setRegionOffset(float x, float y, float z);
    void setTextureSlot(ChunkShaderTextureSlot slot, int val);

    default void setSectionAges(long timestamp, long[] loadTimes) {

    }
}
