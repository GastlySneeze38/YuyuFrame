package com.yuyuframe.launcheragent.apigraphic.era.gl3.pass;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.lwjgl.opengl.GL20;

import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;

/**
 * Icône arbitraire dessinée depuis l'atlas partagé ({@link Gl3IconAtlas}) —
 * remplace, pour tout ce qui y rentre, le chemin « une texture par icône » de
 * {@link Gl3PrimitiveRenderer#icon}.
 *
 * <p>Même shader que ce chemin (texture passée telle quelle, opacité
 * multipliée par {@code u_Alpha} pour les fondus d'images asynchrones), même
 * correspondance d'UV (haut visuel = haut de l'image), mais :
 * <ul>
 *   <li>toutes les icônes partagent la texture de l'atlas : plus de
 *       changement de texture d'une icône à l'autre ;</li>
 *   <li>flux de sommets réutilisé ({@link Gl3VertexStream}) : plus
 *       d'allocation de tampon ni de réallocation de VBO par icône.</li>
 * </ul>
 *
 * <p>Comme sur Blaze3D, chaque icône reste un appel de dessin : les regrouper
 * par-dessus des rects ou du texte dessinés entre deux icônes changerait
 * l'ordre d'affichage.
 */
public final class Gl3Icon {

    private static final String VERTEX_SRC =
        "#version 150\n" +
        "in vec2 aPos;\n" +
        "in vec2 aTexCoord;\n" +
        "uniform mat4 uProjection;\n" +
        "out vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = uProjection * vec4(aPos, 0.0, 1.0);\n" +
        "    vTexCoord = aTexCoord;\n" +
        "}\n";

    private static final String FRAGMENT_SRC =
        "#version 150\n" +
        "uniform sampler2D u_Tex;\n" +
        "uniform float u_Alpha;\n" +
        "in vec2 vTexCoord;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec4 c = texture(u_Tex, vTexCoord);\n" +
        "    fragColor = vec4(c.rgb, c.a * u_Alpha);\n" +
        "}\n";

    private final Gl3IconAtlas atlas = new Gl3IconAtlas();
    private final Gl3VertexStream stream = new Gl3VertexStream(2, 2);

    private int program = -1, uProjection, uTex, uAlpha;
    private boolean initFailed;
    private int failureLogs;

    /**
     * {@code x,y} = coin BAS-gauche, repère Y vers le haut.
     *
     * @return {@code false} si l'icône n'est pas dans l'atlas (plein, trop
     *         grande) ou si le programme ne compile pas : l'appelant la dessine
     *         alors par le chemin « une texture par icône »
     */
    public boolean draw(String cacheKey, BufferedImage img, float x, float y, float w, float h,
                        float alpha, int vpWidth, int vpHeight) {
        if (!ensureProgram()) return false;
        float[] uv = atlas.uv(cacheKey, img);
        if (uv == null) return false;
        try {
            Gl3Core.uiState();
            GL20.glUseProgram(program);
            Gl3Core.ortho(uProjection, vpWidth, vpHeight);
            GL20.glUniform1i(uTex, 0);
            GL20.glUniform1f(uAlpha, alpha);
            Gl3Core.bindTexture0(atlas.texture());

            float u0 = uv[0], vTop = uv[1], u1 = uv[2], vBottom = uv[3];
            FloatBuffer out = stream.begin(6);
            out.put(x).put(y + h).put(u0).put(vTop);
            out.put(x).put(y).put(u0).put(vBottom);
            out.put(x + w).put(y).put(u1).put(vBottom);
            out.put(x).put(y + h).put(u0).put(vTop);
            out.put(x + w).put(y).put(u1).put(vBottom);
            out.put(x + w).put(y + h).put(u1).put(vTop);
            stream.drawTriangles();
            return true;
        } catch (Throwable t) {
            if (failureLogs++ < 5) LauncherLog.err("[Gl3Icon] draw '" + cacheKey + "' : " + t);
            return false;
        } finally {
            GL20.glUseProgram(0);
            Gl3Core.bindTexture0(0);
        }
    }

    private boolean ensureProgram() {
        if (program != -1) return true;
        if (initFailed) return false;
        int p = Gl3Core.program("icon", VERTEX_SRC, FRAGMENT_SRC, "aPos", "aTexCoord");
        if (p == -1) {
            initFailed = true;
            return false;
        }
        uProjection = GL20.glGetUniformLocation(p, "uProjection");
        uTex = GL20.glGetUniformLocation(p, "u_Tex");
        uAlpha = GL20.glGetUniformLocation(p, "u_Alpha");
        program = p;
        return true;
    }
}
