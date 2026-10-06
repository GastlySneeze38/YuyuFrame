package com.yuyuframe.launcheragent.celeritas.gl.buffer;

import com.yuyuframe.launcheragent.celeritas.gl.binding.GL20;
import static com.yuyuframe.launcheragent.celeritas.gl.binding.LWJGLServiceProvider.LWJGL;

import com.yuyuframe.launcheragent.celeritas.gl.GlObject;

public abstract class GlBuffer extends GlObject {
    private GlBufferMapping activeMapping;

    protected GlBuffer() {
        this.setHandle(LWJGL.glGenBuffers());
    }

    public GlBufferMapping getActiveMapping() {
        return this.activeMapping;
    }

    public void setActiveMapping(GlBufferMapping mapping) {
        this.activeMapping = mapping;
    }

    @Override
    protected void destroyInternal() {
        LWJGL.glDeleteBuffers(this.handle());
    }
}
