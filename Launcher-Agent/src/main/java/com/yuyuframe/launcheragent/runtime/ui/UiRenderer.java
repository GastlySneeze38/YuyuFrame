package com.yuyuframe.launcheragent.runtime.ui;

import com.yuyuframe.launcheragent.runtime.log.DiagFile;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Rendu de rects arrondis via shader GLSL, indépendant de tout mod loader et
 * de toute lib externe (pas de NanoVG/LWJGL3 backporté, pas d'Elementa/
 * UniversalCraft) — même technique que Elementa/UIRoundedRectangle.kt (un quad
 * + un fragment shader qui calcule la distance au bord arrondi), mais réécrite
 * de zéro pour tourner sur le contexte GL DÉJÀ ouvert par le jeu (LWJGL2 en
 * 1.8.9, LWJGL3 en 1.21+ — les deux exposent org.lwjgl.opengl.GL20/GL11 sous
 * le même nom de paquet, donc résolus par réflexion comme le reste de
 * l'agent : org.lwjgl n'est PAS obfusqué, pas besoin de MappingsRegistry ici).
 *
 * GLSL 120 volontairement (pas 330+) : compatible profil de compatibilité
 * GL2.1 (1.8.9/LWJGL2) ET GL3.2+ compat (1.21+/LWJGL3) sans variante par
 * version — à valider en jeu sur les deux (voir docs/LauncherAgent/index.md).
 */
public final class UiRenderer {

    private static final String VERTEX_SRC =
        "void main() {\n" +
        "    gl_Position = ftransform();\n" +
        "    gl_FrontColor = gl_Color;\n" +
        "}\n";

    // u_Rect = (left, top, right, bottom) en coordonnées écran (mêmes unités
    // que gl_FragCoord, donc "scaled" GUI * scaleFactor — l'appelant doit
    // passer des coordonnées déjà en pixels physiques, pas en unités GUI).
    private static final String FRAGMENT_SRC =
        "uniform vec4 u_Rect;\n" +
        "uniform float u_Radius;\n" +
        "void main() {\n" +
        "    vec2 p = gl_FragCoord.xy;\n" +
        "    vec2 innerMin = u_Rect.xy + vec2(u_Radius);\n" +
        "    vec2 innerMax = u_Rect.zw - vec2(u_Radius);\n" +
        "    vec2 clamped = clamp(p, innerMin, innerMax);\n" +
        "    float dist = length(p - clamped);\n" +
        "    float alpha = 1.0 - smoothstep(u_Radius - 1.0, u_Radius, dist);\n" +
        "    gl_FragColor = vec4(gl_Color.rgb, gl_Color.a * alpha);\n" +
        "}\n";

    private int program = -1;
    private int uRect = -1;
    private int uRadius = -1;
    private boolean initFailed = false;
    private boolean diagOnce = true; // TEMPORAIRE (diagnostic) — retirer une fois validé en jeu

    private final Map<String, Method> glMethods = new HashMap<>();
    private ClassLoader gameClassLoader;

    private static UiRenderer instance;

    public static UiRenderer get(ClassLoader gameClassLoader) {
        if (instance == null) instance = new UiRenderer();
        instance.gameClassLoader = gameClassLoader;
        return instance;
    }

    private void ensureInit() {
        if (program != -1 || initFailed) return;
        try {
            int vsh = glCreateShader(0x8B31); // GL_VERTEX_SHADER
            glShaderSource(vsh, VERTEX_SRC);
            glCompileShader(vsh);

            int fsh = glCreateShader(0x8B30); // GL_FRAGMENT_SHADER
            glShaderSource(fsh, FRAGMENT_SRC);
            glCompileShader(fsh);

            program = glCreateProgram();
            glAttachShader(program, vsh);
            glAttachShader(program, fsh);
            glLinkProgram(program);

            uRect = glGetUniformLocation(program, "u_Rect");
            uRadius = glGetUniformLocation(program, "u_Radius");

            LauncherLog.ui(1, "[UiRenderer] shader compilé, program=" + program
                + " uRect=" + uRect + " uRadius=" + uRadius);
            DiagFile.log("UiRenderer.ensureInit OK, program=" + program + " uRect=" + uRect + " uRadius=" + uRadius);
        } catch (Throwable t) {
            initFailed = true;
            LauncherLog.err("[UiRenderer] échec compilation shader — repli sur rects non arrondis : " + t);
            DiagFile.log("UiRenderer.ensureInit EXCEPTION: " + t);
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            DiagFile.log(sw.toString());
        }
    }

    /**
     * Dessine un rect avec coins arrondis, en pixels physiques écran (x1,y1)-(x2,y2).
     * radius=0 → rect plein classique. Fallback silencieux vers un quad plein
     * (pas d'arrondi) si la compilation shader a échoué sur cette version/GPU.
     */
    public void drawRoundedRect(float x1, float y1, float x2, float y2, float radius, UiColor color) {
        ensureInit();
        boolean useShader = program != -1 && !initFailed;

        try {
            // État GL hérité de ce que le jeu a laissé à ce point précis du
            // render loop (texture encore bindée, depth test actif, blend non
            // configuré pour notre alpha...) — glPushAttrib/glPopAttrib
            // (legacy OpenGL, dispo GL2.1+) isole notre dessin sans affecter
            // la frame suivante du jeu.
            pushAttrib(0x00004000 | 0x00000001 | 0x00040000); // GL_ENABLE_BIT | GL_CURRENT_BIT | GL_TEXTURE_BIT
            glDisable(0x0DE1); // GL_TEXTURE_2D
            glDisable(0x0B71); // GL_DEPTH_TEST
            glEnable(0x0BE2);  // GL_BLEND
            glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            if (useShader) {
                glUseProgram(program);
                glUniform4f(uRect, x1, y1, x2, y2);
                glUniform1f(uRadius, radius);
            }
            drawQuad(x1, y1, x2, y2, color);
            if (diagOnce) {
                diagOnce = false;
                DiagFile.log("drawRoundedRect OK — (" + x1 + "," + y1 + ")-(" + x2 + "," + y2
                    + ") radius=" + radius + " useShader=" + useShader);
            }
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawRoundedRect: " + t);
            DiagFile.log("drawRoundedRect EXCEPTION: " + t);
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            DiagFile.log(sw.toString());
        } finally {
            try {
                if (useShader) glUseProgram(0);
            } catch (Throwable ignored) {}
            try {
                popAttrib();
            } catch (Throwable ignored) {}
        }
    }

    private void drawQuad(float x1, float y1, float x2, float y2, UiColor c) {
        try {
            glColor4f(c.r, c.g, c.b, c.a);
            glBegin(7); // GL_QUADS
            glVertex2f(x1, y1);
            glVertex2f(x1, y2);
            glVertex2f(x2, y2);
            glVertex2f(x2, y1);
            glEnd();
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawQuad: " + t);
        }
    }

    // ── GL calls via réflexion (org.lwjgl.opengl.GL20/GL11 — noms publics,
    // pas obfusqués, identiques LWJGL2/LWJGL3, donc pas besoin de MappingsRegistry) ──

    private Method gl(String cls, String method, Class<?>... params) throws Exception {
        String key = cls + "#" + method + java.util.Arrays.toString(params);
        Method m = glMethods.get(key);
        if (m != null) return m;
        Class<?> c = Class.forName(cls, true, gameClassLoader);
        m = c.getMethod(method, params);
        glMethods.put(key, m);
        return m;
    }

    private int glCreateShader(int type) throws Exception {
        return (int) gl("org.lwjgl.opengl.GL20", "glCreateShader", int.class).invoke(null, type);
    }
    private void glShaderSource(int shader, String src) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glShaderSource", int.class, CharSequence.class).invoke(null, shader, src);
    }
    private void glCompileShader(int shader) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glCompileShader", int.class).invoke(null, shader);
    }
    private int glCreateProgram() throws Exception {
        return (int) gl("org.lwjgl.opengl.GL20", "glCreateProgram").invoke(null);
    }
    private void glAttachShader(int program, int shader) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glAttachShader", int.class, int.class).invoke(null, program, shader);
    }
    private void glLinkProgram(int program) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glLinkProgram", int.class).invoke(null, program);
    }
    private int glGetUniformLocation(int program, String name) throws Exception {
        return (int) gl("org.lwjgl.opengl.GL20", "glGetUniformLocation", int.class, CharSequence.class)
            .invoke(null, program, name);
    }
    private void glUseProgram(int program) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glUseProgram", int.class).invoke(null, program);
    }
    private void glUniform1f(int loc, float v) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glUniform1f", int.class, float.class).invoke(null, loc, v);
    }
    private void glUniform4f(int loc, float a, float b, float c, float d) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glUniform4f", int.class, float.class, float.class, float.class, float.class)
            .invoke(null, loc, a, b, c, d);
    }
    private void glColor4f(float r, float g, float b, float a) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glColor4f", float.class, float.class, float.class, float.class)
            .invoke(null, r, g, b, a);
    }
    private void glBegin(int mode) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glBegin", int.class).invoke(null, mode);
    }
    private void glVertex2f(float x, float y) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glVertex2f", float.class, float.class).invoke(null, x, y);
    }
    private void glEnd() throws Exception {
        gl("org.lwjgl.opengl.GL11", "glEnd").invoke(null);
    }
    private void glEnable(int cap) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glEnable", int.class).invoke(null, cap);
    }
    private void glDisable(int cap) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glDisable", int.class).invoke(null, cap);
    }
    private void glBlendFunc(int sfactor, int dfactor) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glBlendFunc", int.class, int.class).invoke(null, sfactor, dfactor);
    }
    private void pushAttrib(int mask) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glPushAttrib", int.class).invoke(null, mask);
    }
    private void popAttrib() throws Exception {
        gl("org.lwjgl.opengl.GL11", "glPopAttrib").invoke(null);
    }
}
