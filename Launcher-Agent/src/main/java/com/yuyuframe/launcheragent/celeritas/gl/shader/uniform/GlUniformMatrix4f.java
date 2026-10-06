package com.yuyuframe.launcheragent.celeritas.gl.shader.uniform;

import com.yuyuframe.launcheragent.celeritas.gl.binding.GL30;
import static com.yuyuframe.launcheragent.celeritas.gl.binding.LWJGLServiceProvider.LWJGL;

import org.joml.Matrix4fc;
import com.yuyuframe.launcheragent.celeritas.gl.binding.MemoryStack;

import java.nio.FloatBuffer;

public class GlUniformMatrix4f extends GlUniform<Matrix4fc>  {
    public GlUniformMatrix4f(int index) {
        super(index);
    }

    @Override
    public void set(Matrix4fc value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.callocFloat(16);
            value.get(buf);

            LWJGL.glUniformMatrix4fv(this.index, false, buf);
        }
    }
}
