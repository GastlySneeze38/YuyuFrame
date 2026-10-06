package com.yuyuframe.launcheragent.celeritas.gl.functions;

import com.yuyuframe.launcheragent.celeritas.gl.binding.GL15;
import static com.yuyuframe.launcheragent.celeritas.gl.binding.LWJGLServiceProvider.LWJGL;

import com.yuyuframe.launcheragent.celeritas.gl.buffer.GlBuffer;
import com.yuyuframe.launcheragent.celeritas.gl.buffer.GlBufferMapFlags;
import com.yuyuframe.launcheragent.celeritas.gl.buffer.GlBufferTarget;
import com.yuyuframe.launcheragent.celeritas.gl.buffer.GlMutableBuffer;
import com.yuyuframe.launcheragent.celeritas.gl.device.RenderDevice;
import com.yuyuframe.launcheragent.celeritas.gl.util.EnumBitField;
import com.yuyuframe.launcheragent.celeritas.gl.binding.GLExtension;

import java.nio.ByteBuffer;

public enum BufferMapRangeFunctions {
    CORE {
        @Override
        public ByteBuffer mapBufferRange(GlBuffer buffer, long offset, long length, EnumBitField<GlBufferMapFlags> flags) {
            return LWJGL.glMapBufferRange(GlBufferTarget.ARRAY_BUFFER.getTargetParameter(), offset, length, flags.getBitField());
        }
    },
    MAP_FULL_AND_SLICE {
        @Override
        public ByteBuffer mapBufferRange(GlBuffer buffer, long offset, long length, EnumBitField<GlBufferMapFlags> flags) {
            if (flags.contains(GlBufferMapFlags.EXPLICIT_FLUSH)) {
                throw new UnsupportedOperationException("Explicit flush not supported for MAP_FULL_AND_SLICE strategy");
            }
            int access;
            if (flags.contains(GlBufferMapFlags.WRITE) && flags.contains(GlBufferMapFlags.READ)) {
                access = GL15.GL_READ_WRITE;
            } else if (flags.contains(GlBufferMapFlags.WRITE)) {
                access = GL15.GL_WRITE_ONLY;
            } else {
                access = GL15.GL_READ_ONLY;
            }
            ByteBuffer buf = LWJGL.glMapBuffer(GlBufferTarget.ARRAY_BUFFER.getTargetParameter(), access);
            if (buf == null) {
                return null;
            }
            // Avoid slicing the buffer if we can prove that the full buffer is being mapped
            if (buffer instanceof GlMutableBuffer mutBuffer && mutBuffer.getSize() == length && offset == 0) {
                return buf;
            }
            return buf.position(Math.toIntExact(offset))
                    .limit(Math.toIntExact(offset) + Math.toIntExact(length))
                    .slice();
        }
    };

    public abstract ByteBuffer mapBufferRange(GlBuffer buffer, long offset, long length, EnumBitField<GlBufferMapFlags> flags);

    public static BufferMapRangeFunctions pickBest(RenderDevice device) {
        if (LWJGL.isOpenGLVersionSupported(3, 0)
                || LWJGL.isExtensionSupported(GLExtension.ARB_map_buffer_range)) {
            return CORE;
        } else {
            return MAP_FULL_AND_SLICE;
        }
    }
}
