package com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.matrix;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import com.yuyuframe.launcheragent.celeritas.chunk.ChunkRenderMatrices;

import java.nio.FloatBuffer;

/**
 * Relit les matrices de projection et de vue dans l'état OpenGL fixe, là où
 * vanilla les a posées.
 *
 * <p>Par rapport à l'amont : tampons alloués par LWJGL et non par
 * {@code GlAllocationUtils} du jeu (aucun type du jeu ici), et
 * {@code glGetFloatv}, le nom LWJGL 3 de {@code glGetFloat}.
 */
public class PrimitiveChunkMatrixGetter {
    private static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);

    /**
     * Les matrices de l'image en cours. Relues une fois par image et non à
     * chaque passe (2026-10-06) : vanilla ne touche pas à la caméra entre ses
     * couches de blocs, et chaque {@code glGet} oblige le pilote à rattraper
     * ce qu'il a en attente.
     */
    private static ChunkRenderMatrices cached;

    /** La prochaine lecture reviendra à OpenGL. */
    public static void invalidate() {
        cached = null;
    }

    /** Les matrices déjà lues pour cette image, ou {@code null} — ne relit jamais OpenGL. */
    public static ChunkRenderMatrices peek() {
        return cached;
    }

    public static ChunkRenderMatrices getMatrices() {
        ChunkRenderMatrices matrices = cached;
        if (matrices == null) {
            GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, PROJECTION);
            GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
            matrices = new ChunkRenderMatrices(
                    new Matrix4f(PROJECTION),
                    new Matrix4f(MODELVIEW)
            );
            cached = matrices;
        }
        return matrices;
    }
}
