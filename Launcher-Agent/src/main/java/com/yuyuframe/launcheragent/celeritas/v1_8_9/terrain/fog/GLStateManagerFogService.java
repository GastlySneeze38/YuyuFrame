package com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.fog;

import com.yuyuframe.launcheragent.celeritas.chunk.fog.FogService;
import com.yuyuframe.launcheragent.celeritas.chunk.shader.ChunkFogMode;
import org.lwjgl.opengl.GL11;

public class GLStateManagerFogService implements FogService {
    public static final GLStateManagerFogService INSTANCE = new GLStateManagerFogService();

    public static float fogColorRed, fogColorGreen, fogColorBlue;

    @Override
    public float getFogEnd() {
        return GL11.glGetInteger(GL11.GL_FOG_END);
    }

    @Override
    public float getFogStart() {
        return GL11.glGetInteger(GL11.GL_FOG_START);
    }

    @Override
    public float getFogDensity() {
        return GL11.glGetFloat(GL11.GL_FOG_DENSITY);
    }

    @Override
    public int getFogShapeIndex() {
        return 0;
    }

    @Override
    public float getFogCutoff() {
        return getFogEnd();
    }

    @Override
    public float[] getFogColor() {
        return new float[]{fogColorRed, fogColorGreen, fogColorBlue, 1.0F};
    }

    @Override
    public ChunkFogMode getFogMode() {
        if (!GL11.glGetBoolean(GL11.GL_FOG)) {
            return ChunkFogMode.NONE;
        }
        return ChunkFogMode.fromGLMode(GL11.glGetInteger(GL11.GL_FOG_MODE));
    }
}
