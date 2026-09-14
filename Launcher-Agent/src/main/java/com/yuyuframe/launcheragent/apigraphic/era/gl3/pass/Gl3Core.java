package com.yuyuframe.launcheragent.apigraphic.era.gl3.pass;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Infrastructure partagée des passes gl3 ajoutées le 2026-09-14 (rayon par
 * coin, lots, verre, fusion) — pendant de {@code Blaze3DCore}.
 *
 * <h2>Appels LWJGL typés, pas {@code GlBridge}</h2>
 *
 * Les passes historiques ({@link Gl3PrimitiveRenderer}, {@link Gl3TextRenderer})
 * passent encore par {@code GlBridge}, qui n'était qu'un pont réflexif et ne
 * fait plus que déléguer à LWJGL. Le nouveau code appelle LWJGL directement :
 * même comportement, une indirection de moins, et des types ({@code float[]},
 * tampons) que le pont n'exposait pas.
 *
 * <h2>Conventions communes</h2>
 *
 * Coordonnées en pixels de framebuffer, origine en bas à gauche, Y vers le
 * haut — celles du moteur et de {@code gl_FragCoord}, ce qui permet aux
 * fragment shaders de calculer leurs formes sans varying de position.
 */
public final class Gl3Core {

    private Gl3Core() {
    }

    /**
     * Compile et lie un programme, attributs liés dans l'ordre donné (index 0,
     * 1, …). {@code -1} en cas d'échec, journalisé avec le log GLSL : un
     * programme mal lié ne lève rien côté Java et ne dessine rien.
     */
    public static int program(String label, String vertexSrc, String fragmentSrc, String... attributes) {
        int vs = 0, fs = 0, prog = 0;
        try {
            vs = shader(GL20.GL_VERTEX_SHADER, vertexSrc, label + "/vertex");
            fs = shader(GL20.GL_FRAGMENT_SHADER, fragmentSrc, label + "/fragment");
            if (vs == 0 || fs == 0) return -1;
            prog = GL20.glCreateProgram();
            GL20.glAttachShader(prog, vs);
            GL20.glAttachShader(prog, fs);
            for (int i = 0; i < attributes.length; i++) GL20.glBindAttribLocation(prog, i, attributes[i]);
            GL20.glLinkProgram(prog);
            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == 0) {
                LauncherLog.err("[Gl3] ÉCHEC LINK " + label + " : " + GL20.glGetProgramInfoLog(prog));
                GL20.glDeleteProgram(prog);
                return -1;
            }
            LauncherLog.ui(1, "[Gl3] programme " + label + " lié (program=" + prog + ")");
            return prog;
        } catch (Throwable t) {
            LauncherLog.err("[Gl3] programme " + label + " : " + t);
            return -1;
        } finally {
            if (vs != 0) GL20.glDeleteShader(vs);
            if (fs != 0) GL20.glDeleteShader(fs);
        }
    }

    private static int shader(int type, String src, String label) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
            LauncherLog.err("[Gl3] ÉCHEC COMPILATION " + label + " : " + GL20.glGetShaderInfoLog(s));
            GL20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    private static final float[] ORTHO = new float[16];

    /**
     * Projection orthographique {@code (0..w, 0..h)} — même matrice que
     * {@code UiRenderer.uploadProjectionModern}, sans tampon alloué.
     */
    public static void ortho(int location, float w, float h) {
        ORTHO[0] = 2f / w;  ORTHO[1] = 0f;      ORTHO[2] = 0f;   ORTHO[3] = 0f;
        ORTHO[4] = 0f;      ORTHO[5] = 2f / h;  ORTHO[6] = 0f;   ORTHO[7] = 0f;
        ORTHO[8] = 0f;      ORTHO[9] = 0f;      ORTHO[10] = -1f; ORTHO[11] = 0f;
        ORTHO[12] = -1f;    ORTHO[13] = -1f;    ORTHO[14] = 0f;  ORTHO[15] = 1f;
        GL20.glUniformMatrix4fv(location, false, ORTHO);
    }

    /**
     * État commun à toutes les formes de l'UI : ni profondeur ni cull, blend
     * alpha classique. Le scissor n'est PAS touché (il porte le clip des
     * conteneurs à défilement, voir {@code UiScrollContainer}).
     */
    public static void uiState() {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    /**
     * Rendu hors écran (chaîne de flou) : sauvegarde du framebuffer lié, du
     * viewport, du scissor et du stencil, remis tels quels par
     * {@link #endOffscreen}. Le scissor et le stencil sont coupés pendant la
     * chaîne : un clip de conteneur la tronquerait.
     */
    public static final class OffscreenScope {
        private int drawFbo, readFbo;
        private final int[] viewport = new int[4];
        private boolean scissor, stencil;

        public void begin() {
            drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
        }

        public void end() {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            if (stencil) GL11.glEnable(GL11.GL_STENCIL_TEST);
        }
    }

    /**
     * Texture RGBA8 filtrée en bilinéaire, bords bloqués — support des niveaux
     * de flou et des copies du fond. Laisse l'unité 0 active et la texture liée.
     */
    public static int createTexture(int width, int height) {
        int tex = GL11.glGenTextures();
        bindTexture0(tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        return tex;
    }

    /** Unité 0 active puis liaison — l'unité active peut avoir été laissée ailleurs par le jeu. */
    public static void bindTexture0(int texture) {
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    /** Rayon borné à la demi-dimension la plus courte du rect, jamais négatif. */
    public static float clampRadius(float radius, float x1, float y1, float x2, float y2) {
        float max = Math.min(Math.abs(x2 - x1), Math.abs(y2 - y1)) * 0.5f;
        return Math.max(0f, Math.min(radius, max));
    }

    /**
     * Fonction SDF « boîte arrondie à rayon par coin » en GLSL, partagée par
     * le rect, le verre et la fusion. Rayons {@code (bas-gauche, bas-droit,
     * haut-gauche, haut-droit)} en repère Y vers le haut ; renvoie la
     * couverture 0..1 du pixel, anticrénelée sur un demi-pixel de part et
     * d'autre du bord.
     */
    public static final String GLSL_ROUNDED_BOX =
        "float roundedBoxCoverage(vec2 fragXY, vec4 rect, vec4 radii) {\n" +
        "    vec2 halfSize = (rect.zw - rect.xy) * 0.5;\n" +
        "    vec2 p = fragXY - (rect.xy + rect.zw) * 0.5;\n" +
        "    float r = (p.x > 0.0) ? ((p.y < 0.0) ? radii.y : radii.w)\n" +
        "                          : ((p.y < 0.0) ? radii.x : radii.z);\n" +
        "    r = min(r, min(halfSize.x, halfSize.y));\n" +
        "    vec2 q = abs(p) - halfSize + vec2(r);\n" +
        "    float dist = min(max(q.x, q.y), 0.0) + length(max(q, vec2(0.0))) - r;\n" +
        "    return 1.0 - smoothstep(-0.5, 0.5, dist);\n" +
        "}\n";
}
