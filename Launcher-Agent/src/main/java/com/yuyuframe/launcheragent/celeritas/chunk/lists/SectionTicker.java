package com.yuyuframe.launcheragent.celeritas.chunk.lists;

import java.util.List;

public interface SectionTicker {
    void tickVisibleRenders();
    void onRenderListUpdated(List<ChunkRenderList> renderLists);

    default String getDebugString() {
        return "";
    }
}
