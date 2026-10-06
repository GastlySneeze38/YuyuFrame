package com.yuyuframe.launcheragent.celeritas.gl.device;

@FunctionalInterface
public interface MultiDrawBatchFactory {
    MultiDrawBatch create(int capacity);
}
