package com.yuyuframe.launcheragent.celeritas.chunk.vertex.format;

import com.yuyuframe.launcheragent.celeritas.chunk.vertex.format.impl.CompactChunkVertex;
import com.yuyuframe.launcheragent.celeritas.chunk.vertex.format.impl.VanillaLikeChunkVertex;

public class ChunkMeshFormats {
    public static final ChunkVertexType COMPACT = new CompactChunkVertex();
    public static final ChunkVertexType VANILLA_LIKE = new VanillaLikeChunkVertex();
}
