package com.yuyuframe.launcheragent.celeritas.gl.shader.uniform;

import com.yuyuframe.launcheragent.celeritas.gl.binding.GL30;
import static com.yuyuframe.launcheragent.celeritas.gl.binding.LWJGLServiceProvider.LWJGL;


public class GlUniformFloat extends GlUniform<Float> {
    public GlUniformFloat(int index) {
        super(index);
    }

    @Override
    public void set(Float value) {
        this.setFloat(value);
    }

    public void setFloat(float value) {
        LWJGL.glUniform1f(this.index, value);
    }
}
