package com.yuyuframe.launcheragent.celeritas.gl.buffer;

import com.yuyuframe.launcheragent.celeritas.gl.attribute.GlVertexFormat;
import com.yuyuframe.launcheragent.celeritas.util.NativeBuffer;

/**
 * Helper type for tagging the vertex format alongside the raw buffer data.
 */
public record IndexedVertexData(GlVertexFormat vertexFormat,
                                NativeBuffer vertexBuffer,
                                NativeBuffer indexBuffer) {
    public void delete() {
        this.vertexBuffer.free();
        this.indexBuffer.free();
    }
}
