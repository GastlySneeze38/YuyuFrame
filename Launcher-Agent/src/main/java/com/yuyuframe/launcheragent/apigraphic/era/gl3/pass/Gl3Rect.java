package com.yuyuframe.launcheragent.apigraphic.era.gl3.pass;

import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.lwjgl.opengl.GL20;

import java.nio.FloatBuffer;

/**
 * Rects arrondis à RAYON PAR COIN et rects EN LOT — pendant gl3 de
 * {@code Blaze3DRect} (rayon par coin natif) et de {@code queueRectBatch}.
 *
 * <h2>Un seul shader pour les deux</h2>
 *
 * Tout ce qui décrit la forme voyage PAR SOMMET (rect, quatre rayons,
 * couleur) au lieu d'uniformes : un rect isolé est un lot d'un élément, et un
 * lot de N rects ne coûte qu'UN appel de dessin — là où l'ancien chemin gl3
 * changeait d'uniformes et réécrivait le VBO pour chaque rect, et empilait 2 à
 * 3 rects pour simuler des rayons différents par coin.
 *
 * <h2>Ordre des rayons</h2>
 *
 * {@code (bas-gauche, bas-droit, haut-gauche, haut-droit)} en repère Y vers le
 * haut, c'est-à-dire l'ordre et le sens de la composition historique de la
 * façade ({@code UiRenderer.drawRoundedRect} à quatre rayons, bande plate du
 * bas pour les deux premiers) : le rendu ne change pas, il devient exact.
 */
public final class Gl3Rect {

    private static final String VERTEX_SRC =
        "#version 150\n" +
        "in vec2 aPos;\n" +
        "in vec4 aRect;\n" +
        "in vec4 aRadii;\n" +
        "in vec4 aColor;\n" +
        "uniform mat4 uProjection;\n" +
        "flat out vec4 vRect;\n" +
        "flat out vec4 vRadii;\n" +
        "flat out vec4 vColor;\n" +
        "void main() {\n" +
        "    gl_Position = uProjection * vec4(aPos, 0.0, 1.0);\n" +
        "    vRect = aRect;\n" +
        "    vRadii = aRadii;\n" +
        "    vColor = aColor;\n" +
        "}\n";

    private static final String FRAGMENT_SRC =
        "#version 150\n" +
        "flat in vec4 vRect;\n" +
        "flat in vec4 vRadii;\n" +
        "flat in vec4 vColor;\n" +
        "out vec4 fragColor;\n" +
        Gl3Core.GLSL_ROUNDED_BOX +
        "void main() {\n" +
        "    float coverage = roundedBoxCoverage(gl_FragCoord.xy, vRect, vRadii);\n" +
        "    if (coverage <= 0.0) discard;\n" +
        "    fragColor = vec4(vColor.rgb, vColor.a * coverage);\n" +
        "}\n";

    /** 2 position + 4 rect + 4 rayons + 4 couleur. */
    private final Gl3VertexStream stream = new Gl3VertexStream(2, 4, 4, 4);
    private final float[] extra = new float[12];

    private int program = -1, uProjection = -1;
    private boolean initFailed;

    private boolean ensureProgram() {
        if (program != -1) return true;
        if (initFailed) return false;
        program = Gl3Core.program("rect", VERTEX_SRC, FRAGMENT_SRC, "aPos", "aRect", "aRadii", "aColor");
        if (program == -1) {
            initFailed = true;
            return false;
        }
        uProjection = GL20.glGetUniformLocation(program, "uProjection");
        return true;
    }

    /** Un rect, quatre rayons indépendants. */
    public boolean draw(float x1, float y1, float x2, float y2,
                        float radiusBottomLeft, float radiusBottomRight, float radiusTopLeft, float radiusTopRight,
                        UiColor color, int vpWidth, int vpHeight) {
        if (!ensureProgram()) return false;
        try {
            FloatBuffer out = stream.begin(6);
            put(out, x1, y1, x2, y2, radiusBottomLeft, radiusBottomRight, radiusTopLeft, radiusTopRight, color);
            submit(vpWidth, vpHeight);
            return true;
        } catch (Throwable t) {
            LauncherLog.err("[Gl3Rect] draw : " + t);
            return false;
        }
    }

    /** N rects au même rayon, une couleur chacun — UN appel de dessin. */
    public boolean drawBatch(float[][] bounds, UiColor[] colors, float radius, int vpWidth, int vpHeight) {
        if (!ensureProgram()) return false;
        try {
            FloatBuffer out = stream.begin(bounds.length * 6);
            for (int i = 0; i < bounds.length; i++) {
                float[] b = bounds[i];
                put(out, b[0], b[1], b[2], b[3], radius, radius, radius, radius, colors[i]);
            }
            submit(vpWidth, vpHeight);
            return true;
        } catch (Throwable t) {
            LauncherLog.err("[Gl3Rect] drawBatch : " + t);
            return false;
        }
    }

    private void put(FloatBuffer out, float x1, float y1, float x2, float y2,
                     float rBL, float rBR, float rTL, float rTR, UiColor color) {
        float minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        float minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        extra[0] = minX; extra[1] = minY; extra[2] = maxX; extra[3] = maxY;
        extra[4] = Gl3Core.clampRadius(rBL, minX, minY, maxX, maxY);
        extra[5] = Gl3Core.clampRadius(rBR, minX, minY, maxX, maxY);
        extra[6] = Gl3Core.clampRadius(rTL, minX, minY, maxX, maxY);
        extra[7] = Gl3Core.clampRadius(rTR, minX, minY, maxX, maxY);
        extra[8] = color.r; extra[9] = color.g; extra[10] = color.b; extra[11] = color.a;
        Gl3VertexStream.quad(out, minX, minY, maxX, maxY, extra);
    }

    private void submit(int vpWidth, int vpHeight) {
        Gl3Core.uiState();
        GL20.glUseProgram(program);
        Gl3Core.ortho(uProjection, vpWidth, vpHeight);
        try {
            stream.drawTriangles();
        } finally {
            GL20.glUseProgram(0);
        }
    }
}
