package com.yuyuframe.launcheragent.celeritas.gl.shader.uniform;

import com.yuyuframe.launcheragent.celeritas.gl.binding.GL32;
import static com.yuyuframe.launcheragent.celeritas.gl.binding.LWJGLServiceProvider.LWJGL;

import com.yuyuframe.launcheragent.celeritas.gl.buffer.GlBuffer;

public class GlUniformBlock {
    private final int binding;

    public GlUniformBlock(int uniformBlockBinding) {
        this.binding = uniformBlockBinding;
    }

    public void bindBuffer(GlBuffer buffer) {
        LWJGL.glBindBufferBase(GL32.GL_UNIFORM_BUFFER, this.binding, buffer.handle());
    }
}
