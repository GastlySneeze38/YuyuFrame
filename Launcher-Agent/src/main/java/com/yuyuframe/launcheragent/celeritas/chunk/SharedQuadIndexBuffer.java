package com.yuyuframe.launcheragent.celeritas.chunk;

import com.yuyuframe.launcheragent.celeritas.gl.buffer.GlBuffer;
import com.yuyuframe.launcheragent.celeritas.gl.buffer.GlBufferMapFlags;
import com.yuyuframe.launcheragent.celeritas.gl.buffer.GlBufferUsage;
import com.yuyuframe.launcheragent.celeritas.gl.buffer.GlMutableBuffer;
import com.yuyuframe.launcheragent.celeritas.gl.device.CommandList;
import com.yuyuframe.launcheragent.celeritas.gl.util.EnumBitField;
import com.yuyuframe.launcheragent.celeritas.chunk.compile.sorting.ChunkPrimitiveType;

public class SharedQuadIndexBuffer {
    private final GlMutableBuffer buffer;
    private final ChunkPrimitiveType primitiveType;

    private int maxPrimitives;

    public SharedQuadIndexBuffer(CommandList commandList, ChunkPrimitiveType primitiveType) {
        this.buffer = commandList.createMutableBuffer();
        this.primitiveType = primitiveType;
    }

    public void ensureCapacity(CommandList commandList, int elementCount) {
        int primitiveCount = elementCount / primitiveType.getIndexBufferElementsPerPrimitive();

        if (primitiveCount > this.maxPrimitives) {
            this.grow(commandList, this.getNextSize(primitiveCount));
        }
    }

    private int getNextSize(int primitiveCount) {
        return Math.max(this.maxPrimitives * 2, primitiveCount + 16384);
    }

    private void grow(CommandList commandList, int primitiveCount) {
        var bufferSize = primitiveType.getIndexBufferSize(primitiveCount);

        commandList.allocateStorage(this.buffer, bufferSize, GlBufferUsage.STATIC_DRAW);

        var mapped = commandList.mapBuffer(this.buffer, 0, bufferSize, EnumBitField.of(GlBufferMapFlags.INVALIDATE_BUFFER, GlBufferMapFlags.WRITE, GlBufferMapFlags.UNSYNCHRONIZED));
        this.primitiveType.generateSimpleIndexBuffer(mapped.getMemoryBuffer(), primitiveCount);

        commandList.unmap(mapped);

        this.maxPrimitives = primitiveCount;
    }


    public GlBuffer getBufferObject() {
        return this.buffer;
    }

    public void delete(CommandList commandList) {
        commandList.deleteBuffer(this.buffer);
    }
}
