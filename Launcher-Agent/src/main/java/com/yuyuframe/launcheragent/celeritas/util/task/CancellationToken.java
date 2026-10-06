package com.yuyuframe.launcheragent.celeritas.util.task;

public interface CancellationToken {
    boolean isCancelled();

    void setCancelled();
}
