package com.yuyuframe.launcheragent.celeritas.chunk.compile;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import com.yuyuframe.launcheragent.celeritas.gl.util.VertexRange;
import com.yuyuframe.launcheragent.celeritas.model.quad.properties.ModelQuadFacing;
import com.yuyuframe.launcheragent.celeritas.chunk.RenderPassConfiguration;
import com.yuyuframe.launcheragent.celeritas.chunk.RenderSection;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.buffers.BakedChunkModelBuilder;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.buffers.ChunkModelBuilder;
import com.yuyuframe.launcheragent.celeritas.chunk.data.BuiltRenderSectionData;
import com.yuyuframe.launcheragent.celeritas.chunk.data.BuiltSectionMeshParts;
import com.yuyuframe.launcheragent.celeritas.chunk.terrain.TerrainRenderPass;
import com.yuyuframe.launcheragent.celeritas.chunk.terrain.material.Material;
import com.yuyuframe.launcheragent.celeritas.util.NativeBuffer;
import com.yuyuframe.launcheragent.celeritas.chunk.sorting.SortState;
import com.yuyuframe.launcheragent.celeritas.chunk.vertex.format.ChunkVertexEncoder;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * A collection of temporary buffers for each worker thread which will be used to build chunk meshes for given render
 * passes. This makes a best-effort attempt to pick a suitable size for each scratch buffer, but will never try to
 * shrink a buffer.
 */
public final class ChunkBuildBuffers {
    private static final ModelQuadFacing[] ONLY_UNASSIGNED = new ModelQuadFacing[] { ModelQuadFacing.UNASSIGNED };
    private final Reference2ReferenceOpenHashMap<TerrainRenderPass, BakedChunkModelBuilder> builders = new Reference2ReferenceOpenHashMap<>();

    private final RenderPassConfiguration<?> renderPassConfiguration;

    public RenderPassConfiguration<?> getRenderPassConfiguration() {
        return this.renderPassConfiguration;
    }

    private BuiltRenderSectionData renderData;
    private int sectionIndex;

    public ChunkBuildBuffers(RenderPassConfiguration<?> configuration) {
        this.renderPassConfiguration = configuration;
    }

    public void init(BuiltRenderSectionData renderData, int sectionIndex) {
        this.renderData = renderData;
        this.sectionIndex = sectionIndex;
        for (var builder : this.builders.values()) {
            builder.begin(renderData, sectionIndex);
        }
    }

    public BuiltRenderSectionData getSectionContextBundle() {
        return this.renderData;
    }

    public ChunkModelBuilder get(Material material) {
        return this.get(material.pass);
    }

    private ChunkModelBuilder createBuilder(TerrainRenderPass pass) {
        var vertexType = this.renderPassConfiguration.getVertexTypeForPass(pass);
        var builder = new BakedChunkModelBuilder(vertexType.createEncoder(), vertexType.getVertexFormat().getStride(), pass);
        Objects.requireNonNull(renderData, "Buffers have not been started");
        builder.begin(renderData, sectionIndex);
        this.builders.put(pass, builder);
        return builder;
    }

    public ChunkModelBuilder get(TerrainRenderPass pass) {
        var builder = this.builders.get(pass);
        return builder != null ? builder : createBuilder(pass);
    }

    public Set<TerrainRenderPass> getBuilderPasses() {
        return this.builders.keySet();
    }

    /**
     * Creates immutable baked chunk meshes from all non-empty scratch buffers. This is used after all blocks
     * have been rendered to pass the finished meshes over to the graphics card. This function can be called multiple
     * times to return multiple copies.
     */
    public BuiltSectionMeshParts createMesh(TerrainRenderPass pass, float camX, float camY, float camZ) {
        var builder = this.builders.get(pass);

        if (builder == null || builder.isEmpty()) {
            return null;
        }

        List<ByteBuffer> vertexBuffers = new ArrayList<>();
        var vertexRanges = new EnumMap<ModelQuadFacing, VertexRange>(ModelQuadFacing.class);

        int vertexCount = 0;

        ModelQuadFacing[] facingsToUpload = pass.isSorted() ? ONLY_UNASSIGNED : ModelQuadFacing.VALUES;
        SortState sortState = pass.isSorted() ? builder.getVertexBuffer(ModelQuadFacing.UNASSIGNED).getSortState() : null;

        for (ModelQuadFacing facing : facingsToUpload) {
            var buffer = builder.getVertexBuffer(facing);

            if (buffer.isEmpty()) {
                continue;
            }

            vertexBuffers.add(buffer.slice());
            vertexRanges.put(facing, new VertexRange(vertexCount, buffer.count()));

            vertexCount += buffer.count();
        }

        if (vertexCount == 0) {
            return null;
        }

        var vertexType = renderPassConfiguration.getVertexTypeForPass(pass);
        var primitiveType = renderPassConfiguration.getPrimitiveTypeForPass(pass);

        var mergedBuffer = new NativeBuffer(vertexCount * vertexType.getVertexFormat().getStride());
        var mergedBufferBuilder = mergedBuffer.getDirectBuffer();

        for (var buffer : vertexBuffers) {
            mergedBufferBuilder.put(buffer);
        }

        mergedBufferBuilder.flip();

        NativeBuffer mergedIndexBuffer;

        if (pass.isSorted()) {
            int numPrimitives = vertexCount / primitiveType.getVerticesPerPrimitive();
            // Generate the canonical index buffer
            mergedIndexBuffer = new NativeBuffer(primitiveType.getIndexBufferSize(numPrimitives));

            // Do the initial sort now
            primitiveType.generateSortedIndexBuffer(mergedIndexBuffer.getDirectBuffer(), numPrimitives, sortState, camX, camY, camZ);
        } else {
            mergedIndexBuffer = null;
        }

        // Uncompacted: the consumer needs the sorting level before it throws away the data behind it.
        return new BuiltSectionMeshParts(mergedBuffer, mergedIndexBuffer, sortState, vertexRanges);
    }

    public void destroy() {
        this.sectionIndex = 0;
        this.renderData = null;
        for (var builder : this.builders.values()) {
            builder.destroy();
        }
    }
}
