package com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.fog;

import com.yuyuframe.launcheragent.celeritas.chunk.fog.FogService;
import com.yuyuframe.launcheragent.celeritas.chunk.shader.ChunkFogMode;
import org.lwjgl.opengl.GL11;

/**
 * Le brouillard de vanilla, relu dans l'état OpenGL fixe.
 *
 * <p>Écart avec l'amont (2026-10-06) : l'état est relu <b>une fois par
 * image</b> et non à chaque appel. Le moteur l'interroge à chaque passe
 * (opaque, découpée, translucide) : quatre {@code glGet} par passe, et chacun
 * oblige le pilote à rattraper tout ce qu'il a en attente. Vanilla pose le
 * même brouillard pour toutes les couches de blocs d'une image, donc une
 * lecture suffit. {@link #invalidate()} est appelé une fois par image, après
 * la préparation du terrain.
 */
public class GLStateManagerFogService implements FogService {
    public static final GLStateManagerFogService INSTANCE = new GLStateManagerFogService();

    public static float fogColorRed, fogColorGreen, fogColorBlue;

    private static boolean cached;
    private static ChunkFogMode mode = ChunkFogMode.NONE;
    private static float start, end, density;

    /** La prochaine lecture reviendra à OpenGL. */
    public static void invalidate() {
        cached = false;
    }

    private static void refresh() {
        if (cached) {
            return;
        }
        mode = GL11.glGetBoolean(GL11.GL_FOG)
                ? ChunkFogMode.fromGLMode(GL11.glGetInteger(GL11.GL_FOG_MODE))
                : ChunkFogMode.NONE;
        // glGetFloat et non glGetInteger comme l'amont : ce sont des flottants.
        start = GL11.glGetFloat(GL11.GL_FOG_START);
        end = GL11.glGetFloat(GL11.GL_FOG_END);
        density = GL11.glGetFloat(GL11.GL_FOG_DENSITY);
        cached = true;
    }

    @Override
    public float getFogEnd() {
        refresh();
        return end;
    }

    @Override
    public float getFogStart() {
        refresh();
        return start;
    }

    @Override
    public float getFogDensity() {
        refresh();
        return density;
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
        refresh();
        return mode;
    }
}
