package com.yuyuframe.launcheragent.celeritas.chunk.compile.executor;

import com.yuyuframe.launcheragent.celeritas.chunk.compile.ChunkBuildContext;
import com.yuyuframe.launcheragent.celeritas.util.task.CancellationToken;

public interface ChunkJob extends CancellationToken {
    void execute(ChunkBuildContext context);

    boolean isStarted();
}
