package com.yuyuframe.launcheragent.celeritas.gl.shader.uniform;

import com.yuyuframe.launcheragent.celeritas.gl.binding.MemoryStack;
import com.yuyuframe.launcheragent.celeritas.gl.binding.GL30;
import static com.yuyuframe.launcheragent.celeritas.gl.binding.LWJGLServiceProvider.LWJGL;


import java.nio.FloatBuffer;

public class GlUniformFloatArray extends GlUniform<float[]> {
    public GlUniformFloatArray(int index) {
        super(index);
    }

    @Override
    public void set(float[] value) {
        try (MemoryStack stack = LWJGL.stackPush()) {
            FloatBuffer buf = stack.callocFloat(value.length);
            buf.put(value);

            LWJGL.glUniform1fv(this.index, buf);
        }
    }

    public void set(FloatBuffer value) {
        LWJGL.glUniform1fv(this.index, value);
    }
}
