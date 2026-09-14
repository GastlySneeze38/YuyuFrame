package com.yuyuframe.launcheragent.apigraphic.era.glsupport;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Pont vers OpenGL des ères GL (gl2, gl3) + capture/restauration d'état legacy
 * — extrait de UiRenderer (voir sa javadoc de classe pour l'architecture
 * générale à 2 pipelines).
 *
 * <h2>Plus aucune réflexion (2026-09-14)</h2>
 *
 * Chaque appel passait par {@code Class.forName} + {@code Method.invoke} sur
 * {@code org.lwjgl.opengl.GL*}, résolus dans le classloader du jeu : un coût à
 * CHAQUE appel GL de chaque primitive (boxing des arguments, contrôles
 * d'accès), et une entorse à D4. Deux raisons le justifiaient, toutes deux
 * tombées :
 * <ul>
 *   <li>LWJGL 2 (1.8.9) et LWJGL 3 cohabitaient : plus depuis que la 1.8.9
 *       tourne sur LWJGL 3 ;</li>
 *   <li>l'agent compilait en {@code --release 8} sans LWJGL sur le classpath :
 *       il compile désormais en {@code --release 25}, LWJGL 3.4.1 en
 *       dépendance de compilation ({@code build.bat}).</li>
 * </ul>
 * Toutes les méthodes utilisées ici existent à l'identique depuis LWJGL 3.0.
 * Elles se résolvent par le classloader qui a chargé cette classe — celui du
 * jeu (système en vanilla, Knot sous Fabric), où LWJGL est toujours présent.
 * Les signatures {@code throws Exception} sont conservées pour ne rien changer
 * chez les appelants.
 *
 * <h2>Routage par GlStateManager retiré</h2>
 *
 * {@code glBindTexture}/{@code glActiveTexture} passaient, quand la classe
 * était trouvée, par {@code GlStateManager} (résolu par réflexion) pour
 * garder son cache de texture synchrone. Sur les versions actives cela ne
 * servait plus : la 1.8.9 (classe obfusquée, jamais trouvée) resynchronise
 * tout l'état GL une fois par frame ({@code GlFrameState189}), et Blaze3D
 * n'utilise pas ce pont. Une tranche GL qu'on dégèlera (1.16.5, 1.17 –
 * 1.21.x) devra faire de même dans son hub, en appels typés.
 */
public final class GlBridge {

    public int glGetError() throws Exception {
        return GL11.glGetError();
    }
    /** Vide tous les codes d'erreur en attente, retourne le premier non-zéro rencontré (0 = aucune erreur). */
    public int drainGlErrors() throws Exception {
        int first = 0, code;
        int guard = 0;
        while ((code = glGetError()) != 0 && guard++ < 16) {
            if (first == 0) first = code;
        }
        return first;
    }

    public int glCreateShader(int type) throws Exception {
        return GL20.glCreateShader(type);
    }
    public void glShaderSource(int shader, String src) throws Exception {
        GL20.glShaderSource(shader, src);
    }
    public void glCompileShader(int shader) throws Exception {
        GL20.glCompileShader(shader);
    }
    public int glCreateProgram() throws Exception {
        return GL20.glCreateProgram();
    }
    public void glAttachShader(int program, int shader) throws Exception {
        GL20.glAttachShader(program, shader);
    }
    public void glLinkProgram(int program) throws Exception {
        GL20.glLinkProgram(program);
    }
    public int glGetUniformLocation(int program, String name) throws Exception {
        return GL20.glGetUniformLocation(program, name);
    }
    public int glGetShaderi(int shader, int pname) throws Exception {
        return GL20.glGetShaderi(shader, pname);
    }
    public String glGetShaderInfoLog(int shader) throws Exception {
        return GL20.glGetShaderInfoLog(shader);
    }
    public int glGetProgrami(int program, int pname) throws Exception {
        return GL20.glGetProgrami(program, pname);
    }
    public String glGetProgramInfoLog(int program) throws Exception {
        return GL20.glGetProgramInfoLog(program);
    }
    public void glUseProgram(int program) throws Exception {
        GL20.glUseProgram(program);
    }
    public void glUniform1f(int loc, float v) throws Exception {
        GL20.glUniform1f(loc, v);
    }
    public void glUniform1i(int loc, int v) throws Exception {
        GL20.glUniform1i(loc, v);
    }
    public void glUniform2f(int loc, float a, float b) throws Exception {
        GL20.glUniform2f(loc, a, b);
    }
    public void glUniform4f(int loc, float a, float b, float c, float d) throws Exception {
        GL20.glUniform4f(loc, a, b, c, d);
    }
    public void glColor4f(float r, float g, float b, float a) throws Exception {
        GL11.glColor4f(r, g, b, a);
    }
    public void glBegin(int mode) throws Exception {
        GL11.glBegin(mode);
    }
    public void glVertex2f(float x, float y) throws Exception {
        GL11.glVertex2f(x, y);
    }
    public void glEnd() throws Exception {
        GL11.glEnd();
    }
    public void glEnable(int cap) throws Exception {
        GL11.glEnable(cap);
    }
    public void glDisable(int cap) throws Exception {
        GL11.glDisable(cap);
    }
    public boolean glIsEnabled(int cap) throws Exception {
        return GL11.glIsEnabled(cap);
    }
    public void glBlendFunc(int sfactor, int dfactor) throws Exception {
        GL11.glBlendFunc(sfactor, dfactor);
    }
    public void glClear(int mask) throws Exception {
        GL11.glClear(mask);
    }

    /**
     * Unité de texture active. Appel GL brut — voir la javadoc de classe pour
     * le routage par {@code GlStateManager} qui vivait ici (et l'historique du
     * désynchronisme de son cache d'unité active, corrigé en 1.21.11).
     */
    public void glActiveTexture(int texture) throws Exception {
        GL13.glActiveTexture(texture);
    }

    /**
     * BUG TROUVÉ (retrouvé dans l'historique du projet, confirmé responsable
     * du crash NATIF 0xC0000409 sur 1.21.11 ET reproduit sur 1.21.4) : {@code
     * glPushAttrib}/{@code glPopAttrib} sont des fonctions de pile
     * d'attributs OpenGL 1.x très anciennes, quasiment jamais utilisées par
     * les applications modernes, et un point d'instabilité CONNU des pilotes
     * GPU récents — même sous Compatibility Profile (1.8.9), où elles
     * n'avaient encore jamais crashé jusqu'ici, mais rien ne garantit que ça
     * reste vrai sur tout pilote/GPU. Décision : ne plus JAMAIS les appeler,
     * nulle part dans ce fichier — remplacées partout par une capture/
     * restauration manuelle et CIBLÉE des seuls drapeaux GL réellement
     * modifiés par nos méthodes *Legacy (voir {@link #captureLegacyGlState}/
     * {@link #restoreLegacyGlState}), au lieu de s'en remettre à une pile
     * d'attributs entière pour un besoin bien plus restreint.
     */
    private static final int[] LEGACY_TOGGLE_CAPS = {
        0x0DE1, // GL_TEXTURE_2D
        0x0B71, // GL_DEPTH_TEST
        0x0B44, // GL_CULL_FACE
        0x0BC0, // GL_ALPHA_TEST
        0x0C11, // GL_SCISSOR_TEST
        0x0BE2, // GL_BLEND
    };

    public static final class LegacyGlState {
        final boolean[] enabled;
        final int blendSrc, blendDst;
        LegacyGlState(boolean[] enabled, int blendSrc, int blendDst) {
            this.enabled = enabled;
            this.blendSrc = blendSrc;
            this.blendDst = blendDst;
        }
    }

    /** Capture l'état AVANT modification — voir {@link #LEGACY_TOGGLE_CAPS}. */
    public LegacyGlState captureLegacyGlState() throws Exception {
        boolean[] enabled = new boolean[LEGACY_TOGGLE_CAPS.length];
        for (int i = 0; i < LEGACY_TOGGLE_CAPS.length; i++) enabled[i] = glIsEnabled(LEGACY_TOGGLE_CAPS[i]);
        int blendSrc = glGetInteger(0x0BE1); // GL_BLEND_SRC
        int blendDst = glGetInteger(0x0BE0); // GL_BLEND_DST
        return new LegacyGlState(enabled, blendSrc, blendDst);
    }

    /** {@code null} si la capture n'a jamais réussi (exception avant) — no-op silencieux dans ce cas. */
    public void restoreLegacyGlState(LegacyGlState state) {
        if (state == null) return;
        for (int i = 0; i < LEGACY_TOGGLE_CAPS.length; i++) {
            try {
                if (state.enabled[i]) glEnable(LEGACY_TOGGLE_CAPS[i]);
                else glDisable(LEGACY_TOGGLE_CAPS[i]);
            } catch (Throwable ignored) {}
        }
        try { glBlendFunc(state.blendSrc, state.blendDst); } catch (Throwable ignored) {}
    }
    public void matrixMode(int mode) throws Exception {
        GL11.glMatrixMode(mode);
    }
    public void pushMatrix() throws Exception {
        GL11.glPushMatrix();
    }
    public void popMatrix() throws Exception {
        GL11.glPopMatrix();
    }
    public void loadIdentity() throws Exception {
        GL11.glLoadIdentity();
    }
    public void glOrtho(double left, double right, double bottom, double top, double near, double far) throws Exception {
        GL11.glOrtho(left, right, bottom, top, near, far);
    }
    public void glTranslatef(float x, float y, float z) throws Exception {
        GL11.glTranslatef(x, y, z);
    }
    public void glScalef(float x, float y, float z) throws Exception {
        GL11.glScalef(x, y, z);
    }
    public void glTexCoord2f(float u, float v) throws Exception {
        GL11.glTexCoord2f(u, v);
    }
    public int glGenTextures() throws Exception {
        return GL11.glGenTextures();
    }
    public void glFinish() throws Exception {
        GL11.glFinish();
    }

    /**
     * Garde {@code ref} atteignable jusqu'ici — voir les appelants
     * ({@code FontAtlasTextures}, {@code IconTextures}) pour le pourquoi.
     * Appel direct depuis le passage en {@code --release 25} ; il passait par
     * réflexion tant que l'agent compilait en {@code --release 8}.
     */
    public static void reachabilityFence(Object ref) {
        java.lang.ref.Reference.reachabilityFence(ref);
    }

    /**
     * Liaison de texture. Appel GL brut — voir la javadoc de classe pour le
     * routage par {@code GlStateManager} qui vivait ici, et pourquoi la 1.8.9
     * n'en a plus besoin ({@code GlFrameState189}).
     */
    public void glBindTexture(int target, int texture) throws Exception {
        GL11.glBindTexture(target, texture);
    }
    public void glTexParameteri(int target, int pname, int param) throws Exception {
        GL11.glTexParameteri(target, pname, param);
    }
    public void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                               int format, int type, ByteBuffer pixels) throws Exception {
        GL11.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
    }
    public void glScissor(int x, int y, int w, int h) throws Exception {
        GL11.glScissor(x, y, w, h);
    }
    // ── Stencil (roadmap Phase 5.1, clip doux aux coins arrondis — voir GlRoundedClip) ──
    public void glStencilFunc(int func, int ref, int mask) throws Exception {
        GL11.glStencilFunc(func, ref, mask);
    }
    public void glStencilOp(int sfail, int dpfail, int dppass) throws Exception {
        GL11.glStencilOp(sfail, dpfail, dppass);
    }
    public void glStencilMask(int mask) throws Exception {
        GL11.glStencilMask(mask);
    }
    public void glColorMask(boolean r, boolean g, boolean b, boolean a) throws Exception {
        GL11.glColorMask(r, g, b, a);
    }
    public void glGenerateMipmap(int target) throws Exception {
        GL30.glGenerateMipmap(target);
    }

    // ── Pipeline MODERNE (VAO/VBO, GL15/GL20/GL30) ─────────────────────────

    public void glDrawArrays(int mode, int first, int count) throws Exception {
        GL11.glDrawArrays(mode, first, count);
    }
    public int glGetInteger(int pname) throws Exception {
        return GL11.glGetInteger(pname);
    }
    public int glGenVertexArrays() throws Exception {
        return GL30.glGenVertexArrays();
    }
    public void glBindVertexArray(int array) throws Exception {
        GL30.glBindVertexArray(array);
    }
    public int glGenBuffers() throws Exception {
        return GL15.glGenBuffers();
    }
    public void glBindBuffer(int target, int buffer) throws Exception {
        GL15.glBindBuffer(target, buffer);
    }
    public void glBufferData(int target, FloatBuffer data, int usage) throws Exception {
        GL15.glBufferData(target, data, usage);
    }
    public void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) throws Exception {
        GL20.glVertexAttribPointer(index, size, type, normalized, stride, pointer);
    }
    public void glEnableVertexAttribArray(int index) throws Exception {
        GL20.glEnableVertexAttribArray(index);
    }
    public void glBindAttribLocation(int program, int index, String name) throws Exception {
        GL20.glBindAttribLocation(program, index, name);
    }
    public void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value) throws Exception {
        GL20.glUniformMatrix4fv(location, transpose, value);
    }
    public void glReadPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) throws Exception {
        GL11.glReadPixels(x, y, width, height, format, type, pixels);
    }
}
