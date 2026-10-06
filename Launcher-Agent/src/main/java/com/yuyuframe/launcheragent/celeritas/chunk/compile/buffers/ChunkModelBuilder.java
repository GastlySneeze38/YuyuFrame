package com.yuyuframe.launcheragent.celeritas.chunk.compile.buffers;

import com.yuyuframe.launcheragent.celeritas.model.quad.properties.ModelQuadFacing;
import com.yuyuframe.launcheragent.celeritas.chunk.data.BuiltRenderSectionData;
import com.yuyuframe.launcheragent.celeritas.chunk.vertex.builder.ChunkMeshBufferBuilder;
import com.yuyuframe.launcheragent.celeritas.chunk.vertex.format.ChunkVertexEncoder;

public interface ChunkModelBuilder {
    ChunkMeshBufferBuilder getVertexBuffer(ModelQuadFacing facing);

    BuiltRenderSectionData getSectionContextBundle();

    ChunkVertexEncoder getEncoder();
}
