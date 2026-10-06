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

    /**
     * La couleur est relue dans l'état OpenGL elle aussi (2026-10-06). L'amont
     * la relève dans trois champs privés de {@code GameRenderer} ; ici ces
     * champs ({@code bfk.Q/R/S}) ont des homonymes de même type à la casse
     * près ({@code q/r/s}, la distance de la caméra à la troisième personne),
     * et l'accessor Mixin lisait ceux-là : un brouillard jaune vif (4, 4, 0).
     */
    private static final float[] color = { 0.0F, 0.0F, 0.0F, 1.0F };
    private static final float[] colorScratch = new float[4];

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
        GL11.glGetFloatv(GL11.GL_FOG_COLOR, colorScratch);
        color[0] = colorScratch[0];
        color[1] = colorScratch[1];
        color[2] = colorScratch[2];
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
        refresh();
        return new float[]{color[0], color[1], color[2], 1.0F};
    }

    @Override
    public ChunkFogMode getFogMode() {
        refresh();
        return mode;
    }
}
