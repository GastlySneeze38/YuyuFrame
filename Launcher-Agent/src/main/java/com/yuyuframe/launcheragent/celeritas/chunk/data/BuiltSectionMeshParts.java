package com.yuyuframe.launcheragent.celeritas.chunk.data;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import com.yuyuframe.launcheragent.celeritas.gl.util.VertexRange;
import com.yuyuframe.launcheragent.celeritas.util.NativeBuffer;
import com.yuyuframe.launcheragent.celeritas.model.quad.properties.ModelQuadFacing;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.ChunkBuildBuffers;
import com.yuyuframe.launcheragent.celeritas.chunk.sorting.SortState;
import com.yuyuframe.launcheragent.celeritas.chunk.terrain.TerrainRenderPass;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public record BuiltSectionMeshParts(NativeBuffer vertexBuffer, @Nullable NativeBuffer indexBuffer, @Nullable SortState sortState, Map<ModelQuadFacing, VertexRange> ranges) {
    public void free() {
        vertexBuffer.free();
        if (indexBuffer != null) {
            indexBuffer.free();
        }
    }

    public static Reference2ReferenceMap<TerrainRenderPass, BuiltSectionMeshParts> groupFromBuildBuffers(ChunkBuildBuffers buffers, float relativeCameraX, float relativeCameraY, float relativeCameraZ) {
        Reference2ReferenceMap<TerrainRenderPass, BuiltSectionMeshParts> meshes = new Reference2ReferenceOpenHashMap<>();

        for (TerrainRenderPass pass : buffers.getBuilderPasses()) {
            BuiltSectionMeshParts mesh = buffers.createMesh(pass, relativeCameraX, relativeCameraY, relativeCameraZ);

            if (mesh != null) {
                meshes.put(pass, mesh);
            }
        }

        return meshes;
    }
}
