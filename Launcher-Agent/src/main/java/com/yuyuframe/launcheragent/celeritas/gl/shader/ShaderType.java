package com.yuyuframe.launcheragent.celeritas.gl.shader;

import com.yuyuframe.launcheragent.celeritas.gl.binding.GL20;
import com.yuyuframe.launcheragent.celeritas.gl.binding.GL32;
import com.yuyuframe.launcheragent.celeritas.gl.binding.GL42;
import com.yuyuframe.launcheragent.celeritas.gl.binding.GL43;
import static com.yuyuframe.launcheragent.celeritas.gl.binding.LWJGLServiceProvider.LWJGL;

/**
 * An enumeration over the supported OpenGL shader types.
 */
public enum ShaderType {
    VERTEX(GL20.GL_VERTEX_SHADER, "vsh"),
    FRAGMENT(GL20.GL_FRAGMENT_SHADER, "fsh"),
    GEOM(GL32.GL_GEOMETRY_SHADER, "gsh"),
    TESS_CTRL(GL42.GL_TESS_CONTROL_SHADER, "tcs"),
    TESS_EVALUATE(GL42.GL_TESS_EVALUATION_SHADER, "tes"),
    COMPUTE(GL43.GL_COMPUTE_SHADER, "csh");

    @Deprecated
    public static final ShaderType TESSELATION_CONTROL = ShaderType.TESS_CTRL;
    @Deprecated
    public static final ShaderType TESSELATION_EVAL = ShaderType.TESS_EVALUATE;
    @Deprecated
    public static final ShaderType GEOMETRY = ShaderType.GEOM;

    public final int id;
    public final String fileExtension;

    ShaderType(int id, String fileExtension) {
        this.id = id;
        this.fileExtension = fileExtension;
    }
}
