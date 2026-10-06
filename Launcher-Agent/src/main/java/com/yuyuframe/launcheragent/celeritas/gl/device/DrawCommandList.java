package com.yuyuframe.launcheragent.celeritas.gl.device;

import com.yuyuframe.launcheragent.celeritas.gl.buffer.GlBuffer;
import com.yuyuframe.launcheragent.celeritas.gl.tessellation.GlIndexType;
import com.yuyuframe.launcheragent.celeritas.gl.tessellation.GlPrimitiveType;

public interface DrawCommandList extends AutoCloseable {
    void multiDrawElementsBaseVertex(DirectMultiDrawBatch batch, GlPrimitiveType primitiveType, GlIndexType indexType);

    void multiDrawElementsIndirect(GlBuffer indirectBuffer, int count, GlPrimitiveType primitiveType, GlIndexType indexType);

    void endTessellating();

    void flush();

    @Override
    default void close() {
        this.flush();
    }
}
