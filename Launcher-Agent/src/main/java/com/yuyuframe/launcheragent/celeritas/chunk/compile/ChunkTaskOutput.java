package com.yuyuframe.launcheragent.celeritas.chunk.compile;

import com.yuyuframe.launcheragent.celeritas.chunk.RenderSection;

public abstract class ChunkTaskOutput {
    public final RenderSection render;
    public final int buildTime;

    protected ChunkTaskOutput(RenderSection render, int buildTime) {
        this.render = render;
        this.buildTime = buildTime;
    }

    public void delete() {

    }
}
