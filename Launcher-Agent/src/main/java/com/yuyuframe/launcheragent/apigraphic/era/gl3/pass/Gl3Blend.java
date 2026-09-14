package com.yuyuframe.launcheragent.apigraphic.era.gl3.pass;

import com.yuyuframe.launcheragent.apigraphic.value.UiBlendMode;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.lwjgl.opengl.GL20;

import java.nio.FloatBuffer;

/**
 * Modes de fusion gl3 (multiply, screen, overlay) — pendant de
 * {@code Blaze3DBlend}, mêmes formules.
 *
 * <p>Un {@code glBlendFunc} ne sait exprimer que des combinaisons LINÉAIRES
 * de la source et de la destination ; overlay ne l'est pas. La couleur
 * résultante se calcule donc dans le shader, à partir d'une copie du fond
 * ({@link Gl3Backdrop}), puis se pose en blend alpha standard pour respecter
 * l'opacité de la forme.
 *
 * <p>Coût : une copie de la zone d'écran par rect, comme la capture par
 * appel de Blaze3D. À réserver aux accents visuels, pas aux fonds de
 * centaines d'éléments.
 */
public final class Gl3Blend {

    private static final String VERTEX_SRC =
        "#version 150\n" +
        "in vec2 aPos;\n" +
        "uniform mat4 uProjection;\n" +
        "void main() {\n" +
        "    gl_Position = uProjection * vec4(aPos, 0.0, 1.0);\n" +
        "}\n";

    private static final String FRAGMENT_SRC =
        "#version 150\n" +
        "uniform sampler2D u_Backdrop;\n" +
        "uniform vec4 u_Rect;\n" +
        "uniform vec4 u_Radii;\n" +
        "uniform vec2 u_ScreenSize;\n" +
        "uniform vec4 u_TopColor;\n" +
        "uniform int u_Mode;\n" +
        "out vec4 fragColor;\n" +
        Gl3Core.GLSL_ROUNDED_BOX +
        "void main() {\n" +
        "    float coverage = roundedBoxCoverage(gl_FragCoord.xy, u_Rect, u_Radii);\n" +
        "    float a = coverage * u_TopColor.a;\n" +
        "    if (a < 0.01) discard;\n" +
        "    vec3 dst = texture(u_Backdrop, gl_FragCoord.xy / u_ScreenSize).rgb;\n" +
        "    vec3 top = u_TopColor.rgb;\n" +
        "    vec3 blended;\n" +
        "    if (u_Mode == 0) {\n" +
        "        blended = top * dst;\n" +
        "    } else if (u_Mode == 1) {\n" +
        "        blended = vec3(1.0) - (vec3(1.0) - top) * (vec3(1.0) - dst);\n" +
        "    } else {\n" +
        "        vec3 multiplyBranch = 2.0 * top * dst;\n" +
        "        vec3 screenBranch = vec3(1.0) - 2.0 * (vec3(1.0) - top) * (vec3(1.0) - dst);\n" +
        "        blended = mix(multiplyBranch, screenBranch, step(vec3(0.5), dst));\n" +
        "    }\n" +
        "    fragColor = vec4(blended, a);\n" +
        "}\n";

    private final Gl3Backdrop backdrop;
    private final Gl3VertexStream quad = new Gl3VertexStream(2);

    private int program = -1;
    private int uProj, uBackdrop, uRect, uRadii, uScreen, uTop, uMode;
    private boolean initFailed;
    private int failureLogs;

    public Gl3Blend(Gl3Backdrop backdrop) {
        this.backdrop = backdrop;
    }

    /** Rayons dans l'ordre POSITIONNEL de {@link Gl3Rect}. */
    public boolean draw(float x1, float y1, float x2, float y2,
                        float r0, float r1, float r2, float r3,
                        UiColor topColor, UiBlendMode mode, int vpWidth, int vpHeight) {
        if (!ensureProgram()) return false;
        try {
            float minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
            float minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
            int backdropTex = backdrop.capture(vpWidth, vpHeight);

            Gl3Core.uiState();
            GL20.glUseProgram(program);
            Gl3Core.ortho(uProj, vpWidth, vpHeight);
            GL20.glUniform1i(uBackdrop, 0);
            GL20.glUniform4f(uRect, minX, minY, maxX, maxY);
            GL20.glUniform4f(uRadii,
                Gl3Core.clampRadius(r0, minX, minY, maxX, maxY), Gl3Core.clampRadius(r1, minX, minY, maxX, maxY),
                Gl3Core.clampRadius(r2, minX, minY, maxX, maxY), Gl3Core.clampRadius(r3, minX, minY, maxX, maxY));
            GL20.glUniform2f(uScreen, vpWidth, vpHeight);
            GL20.glUniform4f(uTop, topColor.r, topColor.g, topColor.b, topColor.a);
            GL20.glUniform1i(uMode, mode.code);
            Gl3Core.bindTexture0(backdropTex);
            FloatBuffer out = quad.begin(6);
            Gl3VertexStream.quad(out, minX, minY, maxX, maxY);
            quad.drawTriangles();
            return true;
        } catch (Throwable t) {
            if (failureLogs++ < 5) LauncherLog.err("[Gl3Blend] draw : " + t);
            return false;
        } finally {
            GL20.glUseProgram(0);
            Gl3Core.bindTexture0(0);
        }
    }

    private boolean ensureProgram() {
        if (program != -1) return true;
        if (initFailed) return false;
        int p = Gl3Core.program("blend", VERTEX_SRC, FRAGMENT_SRC, "aPos");
        if (p == -1) {
            initFailed = true;
            return false;
        }
        uProj = GL20.glGetUniformLocation(p, "uProjection");
        uBackdrop = GL20.glGetUniformLocation(p, "u_Backdrop");
        uRect = GL20.glGetUniformLocation(p, "u_Rect");
        uRadii = GL20.glGetUniformLocation(p, "u_Radii");
        uScreen = GL20.glGetUniformLocation(p, "u_ScreenSize");
        uTop = GL20.glGetUniformLocation(p, "u_TopColor");
        uMode = GL20.glGetUniformLocation(p, "u_Mode");
        program = p;
        return true;
    }
}
