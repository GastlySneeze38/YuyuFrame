package com.yuyuframe.launcheragent.apigraphic.era.gl3.pass;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;

/**
 * Flux de sommets RÉUTILISÉ d'un dessin à l'autre : un VAO, un VBO, un tampon
 * CPU direct qui ne fait que grandir.
 *
 * <p>C'est le correctif de l'optimisation listée pour gl3 : le chemin
 * historique ({@code UiRenderer.drawQuadModern}) alloue un tampon et réécrit
 * le VBO pour CHAQUE primitive. Ici le tampon CPU est gardé, et le VBO est
 * réalloué (« orphelinage ») seulement quand la taille requise dépasse sa
 * capacité ; sinon {@code glBufferSubData} ne réécrit que ce qui sert.
 *
 * <p>Un flux par format de sommet (disposition fixée à la construction).
 */
public final class Gl3VertexStream {

    private final int[] attributeSizes;
    private final int floatsPerVertex;

    private int vao = -1, vbo = -1;
    private int gpuCapacityFloats;
    private FloatBuffer cpu = BufferUtils.createFloatBuffer(256);

    /** @param attributeSizes nombre de composantes de chaque attribut, dans l'ordre des index (0, 1, …) */
    public Gl3VertexStream(int... attributeSizes) {
        this.attributeSizes = attributeSizes;
        int total = 0;
        for (int s : attributeSizes) total += s;
        this.floatsPerVertex = total;
    }

    /** Vide le tampon CPU et garantit la place pour {@code vertices} sommets. */
    public FloatBuffer begin(int vertices) {
        int needed = vertices * floatsPerVertex;
        if (cpu.capacity() < needed) {
            cpu = BufferUtils.createFloatBuffer(Math.max(needed, cpu.capacity() * 2));
        }
        cpu.clear();
        return cpu;
    }

    /** Envoie ce qui a été écrit depuis {@link #begin} et dessine en {@code GL_TRIANGLES}. */
    public void drawTriangles() {
        cpu.flip();
        int floats = cpu.remaining();
        if (floats == 0) return;
        ensureGpuObjects();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        if (floats > gpuCapacityFloats) {
            gpuCapacityFloats = Math.max(floats, gpuCapacityFloats * 2);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) gpuCapacityFloats * Float.BYTES, GL15.GL_STREAM_DRAW);
        }
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, cpu);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, floats / floatsPerVertex);
        GL30.glBindVertexArray(0);
    }

    private void ensureGpuObjects() {
        if (vao != -1) return;
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        int stride = floatsPerVertex * Float.BYTES;
        long offset = 0L;
        for (int i = 0; i < attributeSizes.length; i++) {
            GL20.glEnableVertexAttribArray(i);
            GL20.glVertexAttribPointer(i, attributeSizes[i], GL11.GL_FLOAT, false, stride, offset);
            offset += (long) attributeSizes[i] * Float.BYTES;
        }
        GL30.glBindVertexArray(0);
    }

    /**
     * Quad en deux triangles, chaque sommet suivi des mêmes attributs
     * « par forme » ({@code extra}) — le cas de tous les rects de ce paquet.
     */
    public static void quad(FloatBuffer out, float x1, float y1, float x2, float y2, float... extra) {
        vertex(out, x1, y1, extra);
        vertex(out, x2, y1, extra);
        vertex(out, x2, y2, extra);
        vertex(out, x1, y1, extra);
        vertex(out, x2, y2, extra);
        vertex(out, x1, y2, extra);
    }

    private static void vertex(FloatBuffer out, float x, float y, float[] extra) {
        out.put(x).put(y);
        out.put(extra);
    }
}
