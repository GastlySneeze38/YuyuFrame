package com.yuyuframe.launcheragent.runtime.ui.graphicapi;

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

    private final Map<String, Method> glMethods = new HashMap<>();
    private ClassLoader gameClassLoader;

    // Un textureId GL par UiFont (REGULAR/BOLD) — uploadé une seule fois au
    // premier drawText(), jamais régénéré ensuite (l'atlas ne change pas).
    private final Map<UiFont, Integer> fontTextures = new HashMap<>();

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
        } catch (Throwable t) {
            initFailed = true;
            LauncherLog.err("[UiRenderer] échec compilation shader — repli sur rects non arrondis : " + t);
        }
    }

    /**
     * Dessine un rect avec coins arrondis, en pixels physiques écran (x1,y1)-(x2,y2).
     * radius=0 → rect plein classique. Fallback silencieux vers un quad plein
     * (pas d'arrondi) si la compilation shader a échoué sur cette version/GPU.
     *
     * @param vpWidth  largeur totale du viewport (framebuffer), PAS la largeur
     *                 de ce rect précis — nécessaire pour poser une projection
     *                 orthographique correcte (voir plus bas), indépendamment
     *                 de la taille du rect dessiné.
     * @param vpHeight idem, hauteur totale du viewport.
     */
    public void drawRoundedRect(float x1, float y1, float x2, float y2, float radius, UiColor color,
                                 int vpWidth, int vpHeight) {
        ensureInit();
        // radius<=0 : bypass total du shader — bug dégénéré sinon. Dans
        // "alpha = 1 - smoothstep(radius-1, radius, dist)", avec radius=0 tout
        // pixel intérieur a dist=0, qui tombe EXACTEMENT sur le bord haut du
        // smoothstep(-1, 0, 0) → 1.0, donc alpha=0 partout : rect totalement
        // invisible malgré un dessin "réussi" (aucune exception). Observé en
        // test 1.8.9 : le fond plein écran (radius=0) ne s'affichait jamais.
        boolean useShader = program != -1 && !initFailed && radius > 0f;

        try {
            // État GL hérité de ce que le jeu a laissé à ce point précis du
            // render loop (texture encore bindée, depth test actif, blend non
            // configuré pour notre alpha...) — glPushAttrib/glPopAttrib
            // (legacy OpenGL, dispo GL2.1+) isole notre dessin sans affecter
            // la frame suivante du jeu.
            pushAttrib(0x00004000 | 0x00000001 | 0x00040000); // GL_ENABLE_BIT | GL_CURRENT_BIT | GL_TEXTURE_BIT
            glDisable(0x0DE1); // GL_TEXTURE_2D
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE — sinon un quad mal orienté (winding) par rapport à ce que
                                // le rendu 3D du monde a laissé actif peut être silencieusement éliminé,
                                // sans erreur : dessin "réussi" en apparence, rien de visible en jeu.
            glEnable(0x0BE2);  // GL_BLEND
            glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            // ftransform() (vertex shader) applique la matrice modelview/projection
            // COURANTE — à ce point précis (TAIL de GameRenderer.render()), rien ne
            // garantit qu'elle soit une projection 2D pixel-space : ça peut encore
            // être la perspective 3D du monde, auquel cas nos coordonnées pixel
            // (ex: 1920,1057) sortent totalement du frustum et sont clippées, d'où
            // rien de visible malgré un dessin "réussi" côté code. On pose donc
            // NOTRE PROPRE ortho, empilée puis restaurée, indépendante de l'état
            // ambiant. Bas-gauche origine Y-up (glOrtho(0,w,0,h,...)) — cohérent
            // avec gl_FragCoord et le reste du pipeline (mouse/UI), pas de flip.
            matrixMode(0x1701); // GL_PROJECTION
            pushMatrix();
            loadIdentity();
            glOrtho(0, vpWidth, 0, vpHeight, -1, 1);
            matrixMode(0x1700); // GL_MODELVIEW
            pushMatrix();
            loadIdentity();

            if (useShader) {
                glUseProgram(program);
                glUniform4f(uRect, x1, y1, x2, y2);
                glUniform1f(uRadius, radius);
            }
            drawQuad(x1, y1, x2, y2, color);

            matrixMode(0x1700); // GL_MODELVIEW
            popMatrix();
            matrixMode(0x1701); // GL_PROJECTION
            popMatrix();
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawRoundedRect: " + t);
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

    // ── Texte (police bitmap UiFont) ──────────────────────────────────────────

    public float textWidth(String text, float scale) { return UiFont.REGULAR.textWidth(text, scale); }

    public float textWidth(UiFont font, String text, float scale) { return font.textWidth(text, scale); }

    public void drawText(String text, float x, float y, UiColor color, float scale, int vpWidth, int vpHeight) {
        drawText(UiFont.REGULAR, text, x, y, color, scale, vpWidth, vpHeight);
    }

    /**
     * Dessine {@code text} avec la ligne de base à {@code y} (espace pixels
     * framebuffer, origine bas-gauche — comme drawRoundedRect). Pas de shader
     * dédié : la teinte vient de glColor4f combiné à la texture (alpha de
     * l'atlas, RGB blanc uni) via GL_MODULATE, la technique standard de police
     * bitmap en pipeline fixe — posé explicitement plutôt que supposé, au cas
     * où le jeu aurait laissé GL_TEXTURE_ENV_MODE sur autre chose (GL_REPLACE
     * ferait disparaître toute teinte, GL_DECAL casserait l'alpha).
     */
    public void drawText(UiFont font, String text, float x, float y, UiColor color, float scale,
                          int vpWidth, int vpHeight) {
        if (text == null || text.isEmpty()) return;
        int texId = ensureFontTexture(font);
        if (texId < 0) return;

        try {
            pushAttrib(0x00004000 | 0x00000001 | 0x00040000); // GL_ENABLE_BIT | GL_CURRENT_BIT | GL_TEXTURE_BIT
            glEnable(0x0DE1);  // GL_TEXTURE_2D
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE
            glEnable(0x0BE2);  // GL_BLEND
            glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA
            glBindTexture(0x0DE1, texId);
            glTexEnvi(0x2300, 0x2200, 0x2100); // GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE

            matrixMode(0x1701); // GL_PROJECTION
            pushMatrix();
            loadIdentity();
            glOrtho(0, vpWidth, 0, vpHeight, -1, 1);
            matrixMode(0x1700); // GL_MODELVIEW
            pushMatrix();
            loadIdentity();

            glColor4f(color.r, color.g, color.b, color.a);

            float penX = x;
            float yTop = y + font.ascent * scale;
            float yBottom = y - font.descent * scale;
            glBegin(7); // GL_QUADS
            for (int i = 0; i < text.length(); i++) {
                UiFont.Glyph g = font.glyph(text.charAt(i));
                float gw = g.width * scale;
                glTexCoord2f(g.u0, g.v0); glVertex2f(penX, yTop);
                glTexCoord2f(g.u0, g.v1); glVertex2f(penX, yBottom);
                glTexCoord2f(g.u1, g.v1); glVertex2f(penX + gw, yBottom);
                glTexCoord2f(g.u1, g.v0); glVertex2f(penX + gw, yTop);
                penX += g.advance * scale;
            }
            glEnd();

            matrixMode(0x1700);
            popMatrix();
            matrixMode(0x1701);
            popMatrix();
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawText: " + t);
        } finally {
            try { glBindTexture(0x0DE1, 0); } catch (Throwable ignored) {}
            try { popAttrib(); } catch (Throwable ignored) {}
        }
    }

    private int ensureFontTexture(UiFont font) {
        Integer cached = fontTextures.get(font);
        if (cached != null) return cached;
        try {
            java.awt.image.BufferedImage img = font.atlasImage();
            int w = img.getWidth(), h = img.getHeight();

            // BufferedImage.getRGB renvoie du ARGB par ligne (row 0 = haut) —
            // reconverti en RGBA 1 octet/composante, ordre attendu par
            // glTexImage2D(GL_RGBA, GL_UNSIGNED_BYTE, ...). Row 0 uploadée en
            // premier = mappée à v=0 : cohérent avec la construction des UV
            // dans UiFont (v0 = haut du glyphe), donc AUCUN flip nécessaire ici.
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(w * h * 4);
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                img.getRGB(0, y, w, 1, row, 0, w);
                for (int x = 0; x < w; x++) {
                    int argb = row[x];
                    buf.put((byte) ((argb >> 16) & 0xFF)); // R
                    buf.put((byte) ((argb >> 8) & 0xFF));  // G
                    buf.put((byte) (argb & 0xFF));         // B
                    buf.put((byte) ((argb >> 24) & 0xFF)); // A
                }
            }
            buf.flip();

            int texId = glGenTextures();
            glBindTexture(0x0DE1, texId); // GL_TEXTURE_2D
            // GL_LINEAR (pas GL_NEAREST) : l'atlas est rasterisé à BASE_PX=32
            // puis réduit au dessin (scale ~0.3-0.55 pour du texte courant) —
            // l'échantillonnage plus-proche-voisin donnait un rendu en blocs
            // très visible une fois réduit. Bilinéaire lisse ça nettement.
            glTexParameteri(0x0DE1, 0x2801, 0x2601); // GL_TEXTURE_MIN_FILTER, GL_LINEAR
            glTexParameteri(0x0DE1, 0x2800, 0x2601); // GL_TEXTURE_MAG_FILTER, GL_LINEAR
            glTexImage2D(0x0DE1, 0, 0x1908, w, h, 0, 0x1908, 0x1401, buf); // GL_RGBA, GL_RGBA, GL_UNSIGNED_BYTE
            glBindTexture(0x0DE1, 0);

            fontTextures.put(font, texId);
            LauncherLog.ui(1, "[UiRenderer] atlas police uploadé (" + w + "x" + h + "), texId=" + texId);
            return texId;
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] ensureFontTexture: " + t);
            fontTextures.put(font, -1);
            return -1;
        }
    }

    // ── Scissor (clipping rectangulaire — utilisé par UiScrollContainer) ─────

    public void beginScissor(int x, int y, int w, int h) {
        try {
            glEnable(0x0C11); // GL_SCISSOR_TEST
            glScissor(x, y, w, h);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] beginScissor: " + t);
        }
    }

    public void endScissor() {
        try { glDisable(0x0C11); } catch (Throwable ignored) {}
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
    private void matrixMode(int mode) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glMatrixMode", int.class).invoke(null, mode);
    }
    private void pushMatrix() throws Exception {
        gl("org.lwjgl.opengl.GL11", "glPushMatrix").invoke(null);
    }
    private void popMatrix() throws Exception {
        gl("org.lwjgl.opengl.GL11", "glPopMatrix").invoke(null);
    }
    private void loadIdentity() throws Exception {
        gl("org.lwjgl.opengl.GL11", "glLoadIdentity").invoke(null);
    }
    private void glOrtho(double left, double right, double bottom, double top, double near, double far) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glOrtho", double.class, double.class, double.class, double.class, double.class, double.class)
            .invoke(null, left, right, bottom, top, near, far);
    }
    private void glTexCoord2f(float u, float v) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glTexCoord2f", float.class, float.class).invoke(null, u, v);
    }
    private int glGenTextures() throws Exception {
        return (int) gl("org.lwjgl.opengl.GL11", "glGenTextures").invoke(null);
    }
    private void glBindTexture(int target, int texture) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glBindTexture", int.class, int.class).invoke(null, target, texture);
    }
    private void glTexParameteri(int target, int pname, int param) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glTexParameteri", int.class, int.class, int.class).invoke(null, target, pname, param);
    }
    private void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                               int format, int type, java.nio.ByteBuffer pixels) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glTexImage2D", int.class, int.class, int.class, int.class, int.class,
            int.class, int.class, int.class, java.nio.ByteBuffer.class)
            .invoke(null, target, level, internalFormat, width, height, border, format, type, pixels);
    }
    private void glTexEnvi(int target, int pname, int param) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glTexEnvi", int.class, int.class, int.class).invoke(null, target, pname, param);
    }
    private void glScissor(int x, int y, int w, int h) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glScissor", int.class, int.class, int.class, int.class).invoke(null, x, y, w, h);
    }
}
