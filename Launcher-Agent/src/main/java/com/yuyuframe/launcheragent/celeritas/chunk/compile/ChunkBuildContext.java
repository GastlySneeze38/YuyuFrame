package com.yuyuframe.launcheragent.celeritas.chunk.compile;

import com.yuyuframe.launcheragent.celeritas.chunk.RenderPassConfiguration;

public class ChunkBuildContext {
    public final ChunkBuildBuffers buffers;

    public ChunkBuildContext(RenderPassConfiguration renderPassConfiguration) {
        this.buffers = new ChunkBuildBuffers(renderPassConfiguration);
    }

    public void cleanup() {
        this.buffers.destroy();
    }
}
