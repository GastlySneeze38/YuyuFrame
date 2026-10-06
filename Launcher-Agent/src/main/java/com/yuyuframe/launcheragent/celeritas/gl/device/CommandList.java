package com.yuyuframe.launcheragent.celeritas.gl.device;

import com.yuyuframe.launcheragent.celeritas.gl.array.GlVertexArray;
import com.yuyuframe.launcheragent.celeritas.gl.buffer.*;
import com.yuyuframe.launcheragent.celeritas.gl.sync.GlFence;
import com.yuyuframe.launcheragent.celeritas.gl.tessellation.GlPrimitiveType;
import com.yuyuframe.launcheragent.celeritas.gl.tessellation.GlTessellation;
import com.yuyuframe.launcheragent.celeritas.gl.tessellation.TessellationBinding;
import com.yuyuframe.launcheragent.celeritas.gl.util.EnumBitField;

import java.nio.ByteBuffer;

public interface CommandList extends AutoCloseable {
    GlMutableBuffer createMutableBuffer();

    GlImmutableBuffer createImmutableBuffer(long bufferSize, EnumBitField<GlBufferStorageFlags> flags);

    void bindVertexArray(GlVertexArray array);

    void uploadData(GlMutableBuffer glBuffer, ByteBuffer byteBuffer, GlBufferUsage usage);

    void uploadData(GlMutableBuffer glBuffer, long ptr, long bytes, GlBufferUsage usage);

    void uploadData(GlMutableBuffer glBuffer, long ptr, long bytes, GlBufferUsage usage, GlBufferTarget target);

    void copyBufferSubData(GlBuffer src, GlBuffer dst, long readOffset, long writeOffset, long bytes);

    void bindBuffer(GlBufferTarget target, GlBuffer buffer);

    void unbindVertexArray();

    void allocateStorage(GlMutableBuffer buffer, long bufferSize, GlBufferUsage usage);

    void deleteBuffer(GlBuffer buffer);

    void deleteVertexArray(GlVertexArray vertexArray);

    void flush();

    DrawCommandList beginTessellating(GlTessellation tessellation);

    void deleteTessellation(GlTessellation tessellation);

    @Override
    default void close() {
        this.flush();
    }

    GlBufferMapping mapBuffer(GlBuffer buffer, long offset, long length, EnumBitField<GlBufferMapFlags> flags);

    void unmap(GlBufferMapping map);

    void flushMappedRange(GlBufferMapping map, int offset, int length);

    GlFence createFence();
}
