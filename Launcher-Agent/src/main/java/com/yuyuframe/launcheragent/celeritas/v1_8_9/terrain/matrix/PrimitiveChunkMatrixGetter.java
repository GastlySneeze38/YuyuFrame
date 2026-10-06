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

    public static ChunkRenderMatrices getMatrices() {
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
        return new ChunkRenderMatrices(
                new Matrix4f(PROJECTION),
                new Matrix4f(MODELVIEW)
        );
    }
}
