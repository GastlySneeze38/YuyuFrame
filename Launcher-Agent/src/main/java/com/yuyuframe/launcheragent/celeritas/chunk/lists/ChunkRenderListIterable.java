package com.yuyuframe.launcheragent.celeritas.chunk.lists;

import com.yuyuframe.launcheragent.celeritas.chunk.terrain.TerrainRenderPass;

import java.util.Iterator;

public interface ChunkRenderListIterable {
    Iterator<ChunkRenderList> iterator(boolean reverse);

    int getNumRegions();

    ChunkRenderList getRegion(int index);

    default Iterator<ChunkRenderList> iterator() {
        return this.iterator(false);
    }

    /**
     * {@return true if there are sections that need rendering for the given pass}
     */
    default boolean hasPass(TerrainRenderPass pass) {
        return true;
    }
}
