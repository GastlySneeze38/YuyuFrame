package com.yuyuframe.launcheragent.celeritas.gl.shader.uniform;

import com.yuyuframe.launcheragent.celeritas.gl.binding.GL30;
import static com.yuyuframe.launcheragent.celeritas.gl.binding.LWJGLServiceProvider.LWJGL;


public class GlUniformInt extends GlUniform<Integer> {
    public GlUniformInt(int index) {
        super(index);
    }

    @Override
    public void set(Integer value) {
        this.setInt(value);
    }

    public void setInt(int value) {
        LWJGL.glUniform1i(this.index, value);
    }
}
