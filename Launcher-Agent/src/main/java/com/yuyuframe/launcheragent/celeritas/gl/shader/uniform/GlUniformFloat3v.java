package com.yuyuframe.launcheragent.celeritas.gl.shader.uniform;

import com.yuyuframe.launcheragent.celeritas.gl.binding.GL30;
import static com.yuyuframe.launcheragent.celeritas.gl.binding.LWJGLServiceProvider.LWJGL;


public class GlUniformFloat3v extends GlUniform<float[]> {
    public GlUniformFloat3v(int index) {
        super(index);
    }

    @Override
    public void set(float[] value) {
        if (value.length != 3) {
            throw new IllegalArgumentException("value.length != 3");
        }

        LWJGL.glUniform3fv(this.index, value);
    }

    public void set(float x, float y, float z) {
        LWJGL.glUniform3f(this.index, x, y, z);
    }
}
