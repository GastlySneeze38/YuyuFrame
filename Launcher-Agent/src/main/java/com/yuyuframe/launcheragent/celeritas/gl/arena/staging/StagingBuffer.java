package com.yuyuframe.launcheragent.celeritas.gl.arena.staging;

import com.yuyuframe.launcheragent.celeritas.gl.buffer.GlBuffer;
import com.yuyuframe.launcheragent.celeritas.gl.device.CommandList;

import java.nio.ByteBuffer;

public interface StagingBuffer {
    void enqueueCopy(CommandList commandList, ByteBuffer data, GlBuffer dst, long writeOffset);

    void flush(CommandList commandList);

    void delete(CommandList commandList);

    void flip();
}
