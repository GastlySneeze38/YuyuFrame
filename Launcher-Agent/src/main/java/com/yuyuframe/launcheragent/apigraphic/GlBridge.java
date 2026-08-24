package com.yuyuframe.launcheragent.apigraphic;

import com.yuyuframe.launcheragent.runtime.mapping.McReflect;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Pont réflexion vers OpenGL (org.lwjgl.opengl.GL11/13/15/20/30 — noms publics,
 * pas obfusqués, identiques LWJGL2/LWJGL3, donc pas besoin de MappingsRegistry
 * ici) + routage GlStateManager (bindTexture/activeTexture, voir leur javadoc
 * respective) + capture/restauration d'état legacy — extrait de UiRenderer
 * (voir sa javadoc de classe pour l'architecture générale à 2 pipelines).
 */
final class GlBridge {

    private final Map<String, Method> glMethods = new HashMap<>();
    ClassLoader gameClassLoader;

    private Method gl(String cls, String method, Class<?>... params) throws Exception {
        String key = cls + "#" + method + java.util.Arrays.toString(params);
        Method m = glMethods.get(key);
        if (m != null) return m;
        Class<?> c = Class.forName(cls, true, gameClassLoader);
        m = c.getMethod(method, params);
        glMethods.put(key, m);
        return m;
    }

    /** Résolution réflexion brute générique (même cache/classloader que {@link #gl}) — pour des classes hors org.lwjgl.opengl (ex: MemoryUtil, voir UiTextRenderer#ensureNativeTextureApiResolved). */
    Method rawMethod(String cls, String method, Class<?>... params) throws Exception {
        return gl(cls, method, params);
    }

    int glGetError() throws Exception {
        return (int) gl("org.lwjgl.opengl.GL11", "glGetError").invoke(null);
    }
    /** Vide tous les codes d'erreur en attente, retourne le premier non-zéro rencontré (0 = aucune erreur). */
    int drainGlErrors() throws Exception {
        int first = 0, code;
        int guard = 0;
        while ((code = glGetError()) != 0 && guard++ < 16) {
            if (first == 0) first = code;
        }
        return first;
    }

    int glCreateShader(int type) throws Exception {
        return (int) gl("org.lwjgl.opengl.GL20", "glCreateShader", int.class).invoke(null, type);
    }
    void glShaderSource(int shader, String src) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glShaderSource", int.class, CharSequence.class).invoke(null, shader, src);
    }
    void glCompileShader(int shader) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glCompileShader", int.class).invoke(null, shader);
    }
    int glCreateProgram() throws Exception {
        return (int) gl("org.lwjgl.opengl.GL20", "glCreateProgram").invoke(null);
    }
    void glAttachShader(int program, int shader) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glAttachShader", int.class, int.class).invoke(null, program, shader);
    }
    void glLinkProgram(int program) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glLinkProgram", int.class).invoke(null, program);
    }
    int glGetUniformLocation(int program, String name) throws Exception {
        return (int) gl("org.lwjgl.opengl.GL20", "glGetUniformLocation", int.class, CharSequence.class)
            .invoke(null, program, name);
    }
    int glGetShaderi(int shader, int pname) throws Exception {
        return (int) gl("org.lwjgl.opengl.GL20", "glGetShaderi", int.class, int.class).invoke(null, shader, pname);
    }
    String glGetShaderInfoLog(int shader) throws Exception {
        return (String) gl("org.lwjgl.opengl.GL20", "glGetShaderInfoLog", int.class).invoke(null, shader);
    }
    int glGetProgrami(int program, int pname) throws Exception {
        return (int) gl("org.lwjgl.opengl.GL20", "glGetProgrami", int.class, int.class).invoke(null, program, pname);
    }
    String glGetProgramInfoLog(int program) throws Exception {
        return (String) gl("org.lwjgl.opengl.GL20", "glGetProgramInfoLog", int.class).invoke(null, program);
    }
    void glUseProgram(int program) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glUseProgram", int.class).invoke(null, program);
    }
    void glUniform1f(int loc, float v) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glUniform1f", int.class, float.class).invoke(null, loc, v);
    }
    void glUniform1i(int loc, int v) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glUniform1i", int.class, int.class).invoke(null, loc, v);
    }
    void glUniform2f(int loc, float a, float b) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glUniform2f", int.class, float.class, float.class).invoke(null, loc, a, b);
    }
    void glUniform4f(int loc, float a, float b, float c, float d) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glUniform4f", int.class, float.class, float.class, float.class, float.class)
            .invoke(null, loc, a, b, c, d);
    }
    void glColor4f(float r, float g, float b, float a) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glColor4f", float.class, float.class, float.class, float.class)
            .invoke(null, r, g, b, a);
    }
    void glBegin(int mode) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glBegin", int.class).invoke(null, mode);
    }
    void glVertex2f(float x, float y) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glVertex2f", float.class, float.class).invoke(null, x, y);
    }
    void glEnd() throws Exception {
        gl("org.lwjgl.opengl.GL11", "glEnd").invoke(null);
    }
    void glEnable(int cap) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glEnable", int.class).invoke(null, cap);
    }
    void glDisable(int cap) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glDisable", int.class).invoke(null, cap);
    }
    boolean glIsEnabled(int cap) throws Exception {
        return (boolean) gl("org.lwjgl.opengl.GL11", "glIsEnabled", int.class).invoke(null, cap);
    }
    void glBlendFunc(int sfactor, int dfactor) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glBlendFunc", int.class, int.class).invoke(null, sfactor, dfactor);
    }
    void glClear(int mask) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glClear", int.class).invoke(null, mask);
    }

    private Method glStateManagerActiveTexture;
    private boolean glStateManagerActiveTextureResolved;

    /**
     * BUG TROUVÉ (era E, 1.21.11 — cause RÉELLE de la corruption de texte
     * "aléatoire d'un lancement à l'autre", après avoir écarté rastérisation/
     * SDF/upload GPU/GC, tous confirmés innocents par diagnostic direct) :
     * {@code GlStateManager} (la couche GL de Blaze3D) maintient DEUX caches
     * logiciels — un par unité de texture bindée (déjà connu, voir
     * {@link #glBindTexture}) ET un pour l'UNITÉ ACTIVE elle-même (champ
     * `activeTexture`, vérifié par désassemblage de
     * {@code com.mojang.blaze3d.opengl.GlStateManager._activeTexture(int)} :
     * si le cache dit déjà cette unité, le vrai {@code glActiveTexture} est
     * SAUTÉ). Nos appels précédents en {@code GL13.glActiveTexture} brut
     * changeaient l'unité RÉELLE sans jamais mettre à jour ce cache — un
     * appel Blaze3D ultérieur (n'importe quel rendu vanilla après le nôtre,
     * variable d'une frame/d'un lancement à l'autre selon ce qui a été
     * dessiné juste avant) qui CROIT être déjà sur la bonne unité saute son
     * propre {@code glActiveTexture}, laissant la VRAIE unité active être
     * celle où NOUS l'avons laissée — son {@code bindTexture} suivant se
     * retrouve alors à binder SA texture sur NOTRE unité (ou vice-versa),
     * un draw échantillonnant une texture totalement étrangère avec des UV
     * qui n'ont aucun sens pour elle = bruit visuel, exactement le symptôme
     * observé, non-déterministe puisqu'il dépend de l'historique de rendu de
     * CETTE frame précise. Seul {@code glBindTexture} avait été routé via
     * GlStateManager jusqu'ici (fix plus ancien, pour un bug similaire sur
     * les icônes d'armure) — {@code glActiveTexture} ne l'a jamais été,
     * sur AUCUN bracket, ce trou existant depuis toujours mais invisible
     * tant que rien ne changeait volontairement d'unité de texture avant
     * cette session (drawTextModern, ajouté pour l'era E, est le premier
     * code de ce projet à le faire explicitement).
     */
    void glActiveTexture(int texture) throws Exception {
        if (!glStateManagerActiveTextureResolved) {
            glStateManagerActiveTextureResolved = true;
            glStateManagerActiveTexture = resolveGlStateManagerMethod("activeTexture", "_activeTexture");
        }
        if (glStateManagerActiveTexture != null) {
            try {
                glStateManagerActiveTexture.invoke(null, texture);
                return;
            } catch (Throwable ignored) {} // repli sur l'appel brut ci-dessous
        }
        gl("org.lwjgl.opengl.GL13", "glActiveTexture", int.class).invoke(null, texture);
    }

    /**
     * Résout {@code GlStateManager.<oldName>(int)} (package
     * {@code com.mojang.blaze3d.platform}, brackets antérieurs à Blaze3D) ou,
     * à défaut, {@code GlStateManager.<newName>(int)} (package
     * {@code com.mojang.blaze3d.opengl}, era E/Blaze3D 1.21.6+ — nom de
     * méthode préfixé {@code _}, vérifié par désassemblage direct du jar
     * client 1.21.11, PAS supposé). Classes NON obfusquées (bibliothèque
     * Blaze3D fournie telle quelle, jamais remappée par Yarn) — {@link
     * McReflect#rawClass} est le bon outil, PAS {@code yarnClass}/
     * {@code MappingsRegistry} (réservés aux classes obfusquées "net.minecraft").
     */
    private static Method resolveGlStateManagerMethod(String oldName, String newName) {
        try {
            Class<?> oldClass = McReflect.rawClass("com.mojang.blaze3d.platform.GlStateManager");
            if (oldClass != null) {
                try { return oldClass.getMethod(oldName, int.class); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> newClass = McReflect.rawClass("com.mojang.blaze3d.opengl.GlStateManager");
            if (newClass != null) {
                try { return newClass.getMethod(newName, int.class); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
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

    static final class LegacyGlState {
        final boolean[] enabled;
        final int blendSrc, blendDst;
        LegacyGlState(boolean[] enabled, int blendSrc, int blendDst) {
            this.enabled = enabled;
            this.blendSrc = blendSrc;
            this.blendDst = blendDst;
        }
    }

    /** Capture l'état AVANT modification — voir {@link #LEGACY_TOGGLE_CAPS}. */
    LegacyGlState captureLegacyGlState() throws Exception {
        boolean[] enabled = new boolean[LEGACY_TOGGLE_CAPS.length];
        for (int i = 0; i < LEGACY_TOGGLE_CAPS.length; i++) enabled[i] = glIsEnabled(LEGACY_TOGGLE_CAPS[i]);
        int blendSrc = glGetInteger(0x0BE1); // GL_BLEND_SRC
        int blendDst = glGetInteger(0x0BE0); // GL_BLEND_DST
        return new LegacyGlState(enabled, blendSrc, blendDst);
    }

    /** {@code null} si la capture n'a jamais réussi (exception avant) — no-op silencieux dans ce cas. */
    void restoreLegacyGlState(LegacyGlState state) {
        if (state == null) return;
        for (int i = 0; i < LEGACY_TOGGLE_CAPS.length; i++) {
            try {
                if (state.enabled[i]) glEnable(LEGACY_TOGGLE_CAPS[i]);
                else glDisable(LEGACY_TOGGLE_CAPS[i]);
            } catch (Throwable ignored) {}
        }
        try { glBlendFunc(state.blendSrc, state.blendDst); } catch (Throwable ignored) {}
    }
    void matrixMode(int mode) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glMatrixMode", int.class).invoke(null, mode);
    }
    void pushMatrix() throws Exception {
        gl("org.lwjgl.opengl.GL11", "glPushMatrix").invoke(null);
    }
    void popMatrix() throws Exception {
        gl("org.lwjgl.opengl.GL11", "glPopMatrix").invoke(null);
    }
    void loadIdentity() throws Exception {
        gl("org.lwjgl.opengl.GL11", "glLoadIdentity").invoke(null);
    }
    void glOrtho(double left, double right, double bottom, double top, double near, double far) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glOrtho", double.class, double.class, double.class, double.class, double.class, double.class)
            .invoke(null, left, right, bottom, top, near, far);
    }
    void glTranslatef(float x, float y, float z) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glTranslatef", float.class, float.class, float.class).invoke(null, x, y, z);
    }
    void glScalef(float x, float y, float z) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glScalef", float.class, float.class, float.class).invoke(null, x, y, z);
    }
    void glTexCoord2f(float u, float v) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glTexCoord2f", float.class, float.class).invoke(null, u, v);
    }
    int glGenTextures() throws Exception {
        return (int) gl("org.lwjgl.opengl.GL11", "glGenTextures").invoke(null);
    }
    void glFinish() throws Exception {
        gl("org.lwjgl.opengl.GL11", "glFinish").invoke(null);
    }

    /**
     * Équivalent de {@code java.lang.ref.Reference.reachabilityFence(Object)}
     * (JDK 9+) via réflexion — ce fichier compile en {@code --release 8}
     * (compat multi-version, voir build.bat), qui masque toute API postérieure
     * à Java 8 à la COMPILATION (contrairement à un simple `-source 8`) : un
     * appel direct à `reachabilityFence` ne compile pas ("cannot find
     * symbol"), même si la JVM d'exécution réelle (21 ici) le possède bien.
     * Résolu paresseusement, mis en cache, jamais réessayé après un premier
     * échec (même motif que les autres wrappers `gl*` de ce fichier).
     */
    private static volatile Method reachabilityFenceMethod;
    static void reachabilityFence(Object ref) {
        try {
            Method m = reachabilityFenceMethod;
            if (m == null) {
                m = java.lang.ref.Reference.class.getMethod("reachabilityFence", Object.class);
                reachabilityFenceMethod = m;
            }
            m.invoke(null, ref);
        } catch (Throwable ignored) {
            // Best-effort — sans cette méthode (JDK < 9, ne devrait jamais
            // arriver au runtime réel), aucune protection supplémentaire,
            // comportement identique à avant ce fix.
        }
    }
    private Method glStateManagerBindTexture;
    private boolean glStateManagerBindTextureResolved;

    /**
     * Route le bind GL_TEXTURE_2D via {@code GlStateManager.bindTexture(int)}
     * (Yarn "bfl.i", `method_9839`) plutôt que l'appel LWJGL brut — CRUCIAL :
     * `GlStateManager` maintient son PROPRE cache Java de "texture active par
     * unité" (`field_10722 activeTexture` / `field_10715 TEXTURES`, vérifié
     * dans mappings-1.8.9.tiny) et SAUTE le vrai `glBindTexture` GL s'il croit
     * que la texture demandée est déjà active. Nos appels précédents en
     * `GL11.glBindTexture` brut changeaient la texture RÉELLE sans jamais
     * mettre à jour ce cache — désynchronisant la croyance de GlStateManager
     * de l'état GL réel. Résultat concret : après un drawText() (police,
     * bind brut), l'appel vanilla suivant à `renderInGuiWithOverrides`
     * (armor icon) demande à re-binder l'atlas de blocs via
     * `GlStateManager.bindTexture(...)`, qui CROIT l'avoir déjà fait (son
     * cache dit "atlas déjà actif") et SAUTE le bind réel — l'icône se
     * retrouve alors dessinée avec la texture RÉELLEMENT active, notre atlas
     * de police SDF, d'où les formes blanches fragmentées. Router NOS PROPRES
     * binds à travers ce même GlStateManager élimine le désync à la racine
     * (nos binds ET ceux de vanilla passent désormais par la même source de
     * vérité), au lieu d'un fix ponctuel côté rendu d'item seulement.
     */
    void glBindTexture(int target, int texture) throws Exception {
        if (target == 0x0DE1 && !glStateManagerBindTextureResolved) { // GL_TEXTURE_2D
            glStateManagerBindTextureResolved = true;
            // Era E (Blaze3D 1.21.6+) : classe déplacée vers
            // com.mojang.blaze3d.opengl.GlStateManager, méthode renommée
            // "_bindTexture" (vérifié par désassemblage direct, voir
            // resolveGlStateManagerMethod) — l'ancienne résolution ne
            // couvrait que "com/mojang/blaze3d/platform/GlStateManager"/
            // "bindTexture" (brackets antérieurs), silencieusement null sur
            // 1.21.11, d'où un repli permanent sur l'appel brut désynchronisant
            // le cache de GlStateManager (cause racine du texte corrompu).
            glStateManagerBindTexture = resolveGlStateManagerMethod("bindTexture", "_bindTexture");
        }
        if (target == 0x0DE1 && glStateManagerBindTexture != null) {
            try {
                glStateManagerBindTexture.invoke(null, texture);
                return;
            } catch (Throwable ignored) {} // repli sur l'appel brut ci-dessous
        }
        gl("org.lwjgl.opengl.GL11", "glBindTexture", int.class, int.class).invoke(null, target, texture);
    }
    void glTexParameteri(int target, int pname, int param) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glTexParameteri", int.class, int.class, int.class).invoke(null, target, pname, param);
    }
    void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                               int format, int type, java.nio.ByteBuffer pixels) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glTexImage2D", int.class, int.class, int.class, int.class, int.class,
            int.class, int.class, int.class, java.nio.ByteBuffer.class)
            .invoke(null, target, level, internalFormat, width, height, border, format, type, pixels);
    }
    void glScissor(int x, int y, int w, int h) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glScissor", int.class, int.class, int.class, int.class).invoke(null, x, y, w, h);
    }
    void glGenerateMipmap(int target) throws Exception {
        // GL30 (promu depuis GL_ARB_framebuffer_object) — dispo aussi bien
        // sous LWJGL2 (1.8.9, contexte GL2.1) que LWJGL3 (1.21), l'extension
        // sous-jacente étant supportée par tout GPU ~2006+.
        gl("org.lwjgl.opengl.GL30", "glGenerateMipmap", int.class).invoke(null, target);
    }

    // ── GL réflexion — pipeline MODERNE uniquement (VAO/VBO, GL15/GL20/GL30) ──

    void glDrawArrays(int mode, int first, int count) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glDrawArrays", int.class, int.class, int.class).invoke(null, mode, first, count);
    }
    int glGetInteger(int pname) throws Exception {
        return (int) gl("org.lwjgl.opengl.GL11", "glGetInteger", int.class).invoke(null, pname);
    }
    int glGenVertexArrays() throws Exception {
        return (int) gl("org.lwjgl.opengl.GL30", "glGenVertexArrays").invoke(null);
    }
    void glBindVertexArray(int array) throws Exception {
        gl("org.lwjgl.opengl.GL30", "glBindVertexArray", int.class).invoke(null, array);
    }
    int glGenBuffers() throws Exception {
        return (int) gl("org.lwjgl.opengl.GL15", "glGenBuffers").invoke(null);
    }
    void glBindBuffer(int target, int buffer) throws Exception {
        gl("org.lwjgl.opengl.GL15", "glBindBuffer", int.class, int.class).invoke(null, target, buffer);
    }
    void glBufferData(int target, FloatBuffer data, int usage) throws Exception {
        gl("org.lwjgl.opengl.GL15", "glBufferData", int.class, FloatBuffer.class, int.class).invoke(null, target, data, usage);
    }
    void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glVertexAttribPointer", int.class, int.class, int.class, boolean.class, int.class, long.class)
            .invoke(null, index, size, type, normalized, stride, pointer);
    }
    void glEnableVertexAttribArray(int index) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glEnableVertexAttribArray", int.class).invoke(null, index);
    }
    void glBindAttribLocation(int program, int index, String name) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glBindAttribLocation", int.class, int.class, CharSequence.class).invoke(null, program, index, name);
    }
    void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glUniformMatrix4fv", int.class, boolean.class, FloatBuffer.class).invoke(null, location, transpose, value);
    }
    void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.ByteBuffer pixels) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glReadPixels", int.class, int.class, int.class, int.class, int.class, int.class, java.nio.ByteBuffer.class)
            .invoke(null, x, y, width, height, format, type, pixels);
    }
}
