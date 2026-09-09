package com.yuyuframe.launcheragent.apigraphic.render;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DCore;
import com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DText;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Rendu de texte (police bitmap SDF {@link UiFont}) — extrait de UiRenderer
 * (voir sa javadoc de classe pour l'architecture générale à 2 pipelines).
 * Shader SDF dédié (voir TEXT_FRAGMENT_SRC) : l'atlas encode une distance
 * signée au bord du glyphe dans son canal alpha, pas une couverture directe.
 */
public final class UiTextRenderer {

    private final UiRenderer owner;
    private final GlBridge gl;

    public UiTextRenderer(UiRenderer owner, GlBridge gl) {
        this.owner = owner;
        this.gl = gl;
    }

    // ── Forwarders GL (voir GlBridge) — gardent les corps de méthode ci-dessous identiques à l'original ──
    private void glDisable(int cap) throws Exception { gl.glDisable(cap); }
    private void glEnable(int cap) throws Exception { gl.glEnable(cap); }
    private void glBlendFunc(int sfactor, int dfactor) throws Exception { gl.glBlendFunc(sfactor, dfactor); }
    private void glActiveTexture(int texture) throws Exception { gl.glActiveTexture(texture); }
    private void glBindTexture(int target, int texture) throws Exception { gl.glBindTexture(target, texture); }
    private void glUseProgram(int program) throws Exception { gl.glUseProgram(program); }
    private void glUniform1i(int loc, int v) throws Exception { gl.glUniform1i(loc, v); }
    private void glUniform4f(int loc, float a, float b, float c, float d) throws Exception { gl.glUniform4f(loc, a, b, c, d); }
    private void matrixMode(int mode) throws Exception { gl.matrixMode(mode); }
    private void pushMatrix() throws Exception { gl.pushMatrix(); }
    private void popMatrix() throws Exception { gl.popMatrix(); }
    private void loadIdentity() throws Exception { gl.loadIdentity(); }
    private void glOrtho(double left, double right, double bottom, double top, double near, double far) throws Exception { gl.glOrtho(left, right, bottom, top, near, far); }
    private void glColor4f(float r, float g, float b, float a) throws Exception { gl.glColor4f(r, g, b, a); }
    private void glBegin(int mode) throws Exception { gl.glBegin(mode); }
    private void glTexCoord2f(float u, float v) throws Exception { gl.glTexCoord2f(u, v); }
    private void glVertex2f(float x, float y) throws Exception { gl.glVertex2f(x, y); }
    private void glEnd() throws Exception { gl.glEnd(); }
    private int glGenTextures() throws Exception { return gl.glGenTextures(); }
    private void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                               int format, int type, java.nio.ByteBuffer pixels) throws Exception {
        gl.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
    }
    private void glGenerateMipmap(int target) throws Exception { gl.glGenerateMipmap(target); }
    private void glTexParameteri(int target, int pname, int param) throws Exception { gl.glTexParameteri(target, pname, param); }
    private void glFinish() throws Exception { gl.glFinish(); }
    private int glGetInteger(int pname) throws Exception { return gl.glGetInteger(pname); }
    private int drainGlErrors() throws Exception { return gl.drainGlErrors(); }
    private GlBridge.LegacyGlState captureLegacyGlState() throws Exception { return gl.captureLegacyGlState(); }
    private void restoreLegacyGlState(GlBridge.LegacyGlState state) { gl.restoreLegacyGlState(state); }
    private static void reachabilityFence(Object ref) { GlBridge.reachabilityFence(ref); }

    // ── Forwarders vers les helpers partagés du pipeline MODERNE (voir UiRenderer) ──
    private void ensureModernBuffersInit() { owner.ensureModernBuffersInit(); }
    private FloatBuffer floatBuffer(int capacityFloats) { return owner.floatBuffer(capacityFloats); }
    private void putVertex(FloatBuffer buf, float x, float y, float u, float v) { owner.putVertex(buf, x, y, u, v); }
    private void drawTrianglesModern(FloatBuffer verts) { owner.drawTrianglesModern(verts); }
    private void uploadProjectionModern(int uniformLoc, int vpWidth, int vpHeight) throws Exception { owner.uploadProjectionModern(uniformLoc, vpWidth, vpHeight); }
    private int compileModernProgram(String vertexSrc, String fragmentSrc) throws Exception { return owner.compileModernProgram(vertexSrc, fragmentSrc); }
    private int glGetUniformLocation(int program, String name) throws Exception { return gl.glGetUniformLocation(program, name); }

    // ── Texte (police bitmap UiFont) ──────────────────────────────────────────

    // ── Shader de texte SDF (distance field) — voir UiFont pour le pourquoi :
    // l'alpha de l'atlas encode une distance signée au bord du glyphe, pas
    // une couverture directe. dFdx/dFdy/fwidth sont cœur GLSL 1.10+ pour un
    // fragment shader (aucune extension à déclarer), donc dispo aussi bien en
    // GL2.1 compat (1.8.9/LWJGL2) qu'en GL3.2+ compat (1.21+/LWJGL3).
    /** Réutilisé par {@link UiPrimitiveRenderer} pour le vertex shader legacy de l'icône générique (voir ensureIconShaderInit). */
    static final String TEXT_VERTEX_SRC =
        "void main() {\n" +
        "    gl_Position = ftransform();\n" +
        "    gl_FrontColor = gl_Color;\n" +
        "    gl_TexCoord[0] = gl_MultiTexCoord0;\n" +
        "}\n";

    // BIAS : sans lui, les traits fins (barres de "i"/"l"/"j") disparaissent
    // presque entièrement dans le texte le plus petit de l'UI (descriptions,
    // ~8px de haut affiché) — leur trait est alors plus étroit que la zone de
    // transition du champ de distance elle-même, donc quasiment aucun texel
    // n'atteint franchement "dedans" (dist > 0.5). Décaler la distance vers
    // "dedans" avant le seuillage épaissit légèrement TOUT le texte (effet
    // "gras" standard en rendu SDF) pour que ces traits fins restent visibles,
    // au prix d'un contour à peine plus épais partout ailleurs — imperceptible
    // sur le texte de taille normale/grande.
    private static final String TEXT_FRAGMENT_SRC =
        "uniform sampler2D u_Tex;\n" +
        "const float BIAS = 0.06;\n" +
        "void main() {\n" +
        "    float dist = texture2D(u_Tex, gl_TexCoord[0].xy).a + BIAS;\n" +
        "    float w = fwidth(dist);\n" +
        "    float alpha = smoothstep(0.5 - w, 0.5 + w, dist);\n" +
        "    gl_FragColor = vec4(gl_Color.rgb, gl_Color.a * alpha);\n" +
        "}\n";

    private int textProgram = -1;
    private int uTex = -1;
    private boolean textInitFailed = false;

    private static final String TEXT_FRAGMENT_SRC_MODERN =
        "#version 150\n" +
        "uniform sampler2D u_Tex;\n" +
        "uniform vec4 uColor;\n" +
        "in vec2 vTexCoord;\n" +
        "out vec4 fragColor;\n" +
        "const float BIAS = 0.06;\n" +
        "void main() {\n" +
        "    float dist = texture(u_Tex, vTexCoord).a + BIAS;\n" +
        "    float w = fwidth(dist);\n" +
        "    float alpha = smoothstep(0.5 - w, 0.5 + w, dist);\n" +
        "    fragColor = vec4(uColor.rgb, uColor.a * alpha);\n" +
        "}\n";

    private int textProgramModern = -1;
    private int uTexModern = -1, uColorTextModern = -1, uProjectionTextModern = -1;
    private boolean textInitFailedModern = false;

    private final Map<UiFont, Integer> fontTextures = new HashMap<>();

    private void ensureTextShaderInit() {
        if (textProgram != -1 || textInitFailed) return;
        try {
            int vsh = gl.glCreateShader(0x8B31); // GL_VERTEX_SHADER
            gl.glShaderSource(vsh, TEXT_VERTEX_SRC);
            gl.glCompileShader(vsh);

            int fsh = gl.glCreateShader(0x8B30); // GL_FRAGMENT_SHADER
            gl.glShaderSource(fsh, TEXT_FRAGMENT_SRC);
            gl.glCompileShader(fsh);

            textProgram = gl.glCreateProgram();
            gl.glAttachShader(textProgram, vsh);
            gl.glAttachShader(textProgram, fsh);
            gl.glLinkProgram(textProgram);

            uTex = glGetUniformLocation(textProgram, "u_Tex");

            LauncherLog.ui(1, "[UiRenderer] shader texte (SDF) compilé, program=" + textProgram + " uTex=" + uTex);
        } catch (Throwable t) {
            textInitFailed = true;
            LauncherLog.err("[UiRenderer] échec compilation shader texte SDF — texte non affiché : " + t);
        }
    }

    private void ensureTextShaderInitModern() {
        if (textProgramModern != -1 || textInitFailedModern) return;
        try {
            textProgramModern = compileModernProgram(UiRenderer.VERTEX_SRC_MODERN, TEXT_FRAGMENT_SRC_MODERN);
            uTexModern = glGetUniformLocation(textProgramModern, "u_Tex");
            uColorTextModern = glGetUniformLocation(textProgramModern, "uColor");
            uProjectionTextModern = glGetUniformLocation(textProgramModern, "uProjection");
            LauncherLog.ui(1, "[UiRenderer] shader texte moderne (SDF) compilé, program=" + textProgramModern);
        } catch (Throwable t) {
            textInitFailedModern = true;
            LauncherLog.err("[UiRenderer] échec compilation shader texte SDF moderne — texte non affiché : " + t);
        }
    }

    public float textWidth(String text, float scale) { return UiFont.REGULAR.textWidth(text, scale); }

    public float textWidth(UiFont font, String text, float scale) { return font.textWidth(text, scale); }

    /**
     * Tronque {@code text} (avec "...") pour tenir dans {@code maxWidth}
     * pixels à l'échelle donnée — sans effet (retourne {@code text} tel
     * quel) tant qu'il tient déjà dans cette largeur, donc directement
     * applicable partout SANS condition sur le mode d'échelle : un titre/
     * sous-titre ne déborde alors que quand il n'y a réellement plus la
     * place (ex: cartes du menu principal en "Taille de l'interface" =
     * Grande, voir UiMainMenuScreen.ModCard), jamais de retour à la ligne.
     * Déplacée ici depuis ModrinthContentScreen (où elle vivait à l'origine,
     * spécifique à cet écran) — devenue un besoin partagé, pas un utilitaire
     * propre à Modrinth.
     */
    public String truncate(String text, float scale, float maxWidth) {
        if (text == null) return "";
        if (maxWidth <= 0 || textWidth(text, scale) <= maxWidth) return text;
        String ellipsis = "...";
        int len = text.length();
        while (len > 0 && textWidth(text.substring(0, len) + ellipsis, scale) > maxWidth) len--;
        return len <= 0 ? ellipsis : text.substring(0, len) + ellipsis;
    }

    public void drawText(String text, float x, float y, UiColor color, float scale, int vpWidth, int vpHeight) {
        drawText(UiFont.REGULAR, text, x, y, color, scale, vpWidth, vpHeight);
    }

    /**
     * Dessine {@code text} avec la ligne de base à {@code y} (espace pixels
     * framebuffer, origine bas-gauche — comme drawRoundedRect). Shader SDF
     * dédié (voir TEXT_FRAGMENT_SRC/UiFont) : l'atlas encode une distance
     * signée au bord du glyphe, pas une couverture directe — un simple
     * GL_MODULATE fixe ne saurait pas l'interpréter (donnerait un halo flou
     * au lieu d'un bord net), d'où ce programme séparé de celui de
     * drawRoundedRect.
     */
    public void drawText(UiFont font, String text, float x, float y, UiColor color, float scale,
                          int vpWidth, int vpHeight) {
        if (text == null || text.isEmpty()) return;
        if (owner.isModern()) {
            drawTextModern(font, text, x, y, color, scale, vpWidth, vpHeight);
            return;
        }
        drawTextLegacy(font, text, x, y, color, scale, vpWidth, vpHeight);
    }

    /**
     * Variante avec ombre portée — capacité absente du moteur jusqu'ici
     * (voir audit runtime/ui/ : {@link #drawText} n'a aucun paramètre
     * shadow, aucun site n'appelait drawText deux fois avec un offset).
     * Composition pure sur {@link #drawText} (passe ombre décalée PUIS
     * passe principale) : aucune modification du shader SDF nécessaire,
     * fonctionne donc identiquement sur les 3 pipelines, era E Blaze3D
     * inclus (contrairement à drawGlow/drawSkeletonShimmer, qui eux
     * dépendent de {@link UiPrimitiveRenderer#drawFx}).
     */
    public void drawTextShadowed(UiFont font, String text, float x, float y, UiColor color, UiColor shadowColor,
                                  float shadowOffsetX, float shadowOffsetY, float scale, int vpWidth, int vpHeight) {
        if (text == null || text.isEmpty()) return;
        drawText(font, text, x + shadowOffsetX, y + shadowOffsetY, shadowColor, scale, vpWidth, vpHeight);
        drawText(font, text, x, y, color, scale, vpWidth, vpHeight);
    }

    public void drawTextShadowed(String text, float x, float y, UiColor color, UiColor shadowColor,
                                  float scale, int vpWidth, int vpHeight) {
        // Décalage 1px/1px à l'échelle du texte — convention "drop shadow"
        // standard (Minecraft vanilla utilise le même décalage relatif pour
        // son propre texte HUD).
        drawTextShadowed(UiFont.REGULAR, text, x, y, color, shadowColor, scale, scale, scale, vpWidth, vpHeight);
    }

    private void drawTextModern(UiFont font, String text, float x, float y, UiColor color, float scale,
                                 int vpWidth, int vpHeight) {
        // Era E (Blaze3D 1.21.6+) : passe EXCLUSIVEMENT par le vrai pipeline du
        // moteur (RenderPipelines.GUI_TEXT via GpuDevice/RenderPass, voir
        // Blaze3DText) — jamais de repli sur le pipeline SDF ci-dessous sur
        // ces brackets, même si Blaze3DText échoue : le SDF y est corrompu
        // de façon non-déterministe (confirmé sur toute la session, voir
        // historique) — un texte absent (échec silencieux, loggé côté
        // Blaze3DText) vaut mieux qu'un texte parfois illisible. Sur les
        // brackets antérieurs (1.8.9→1.21.4), Blaze3DCore.isAvailable() est
        // {@code false} (classes Blaze3D absentes) — le pipeline SDF
        // ci-dessous reste alors le SEUL chemin, INCHANGÉ, exactement comme
        // avant cette era E.
        if (Blaze3DCore.isAvailable()) {
            // queueDraw (pas drawText direct) : voir Blaze3DText pour le
            // pourquoi (rendu différé d'une frame, nécessaire pour que le
            // texte atterrisse dans la texture qui sera présentée).
            Blaze3DText.queueDraw(font, text, x, y, color, scale, vpWidth, vpHeight);
            return;
        }

        int texId = ensureFontTexture(font);
        if (texId < 0) return;
        ensureTextShaderInitModern();
        if (textInitFailedModern) return;
        try {
            // Voir drawEdgeVignetteModern : GL_TEXTURE_2D en tant que CAPACITÉ
            // (glEnable/glDisable) retiré — GL_INVALID_ENUM en Core Profile.
            // glBindTexture(GL_TEXTURE_2D, ...) juste en dessous reste lui
            // parfaitement valide : c'est une CIBLE de bind, pas une capacité
            // fixed-function, ces deux usages du même enum sont indépendants.
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE
            // PAS de glDisable(GL_SCISSOR_TEST) — BUG TROUVÉ (voir
            // UiScrollContainer, javadoc de classe) : défaisait le clip actif
            // d'un scroll container pour CHAQUE texte dessiné à l'intérieur.
            glEnable(0x0BE2);  // GL_BLEND
            glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA
            // BUG TROUVÉ (era E, 1.21.11 — texte corrompu/glyphes illisibles,
            // alors que les rects/couleurs unies restent parfaits) : notre
            // hook de dessin tourne désormais APRÈS Framebuffer.blitToScreen()
            // (voir GlobalUiPresentMixin), donc APRÈS TOUTE la composition de
            // frame interne de Blaze3D — qui utilise plusieurs UNITÉS de
            // texture actives (multi-texturing). glBindTexture() seul bind
            // sur l'unité COURANTE, pas forcément l'unité 0 — si Blaze3D a
            // laissé une unité différente active, notre atlas se bind au
            // mauvais endroit pendant que le shader (uTexModern, fixé à
            // l'unité 0 juste en dessous) lit une texture parasite laissée là
            // par le rendu vanilla. Jamais un problème sur les brackets C/D
            // (1.20.4/1.21.4), dont le hook tourne AVANT ce genre de
            // composition multi-unité tardive.
            glActiveTexture(0x84C0); // GL_TEXTURE0
            glBindTexture(0x0DE1, texId);
            glUseProgram(textProgramModern);
            glUniform1i(uTexModern, 0);
            glUniform4f(uColorTextModern, color.r, color.g, color.b, color.a);
            uploadProjectionModern(uProjectionTextModern, vpWidth, vpHeight);

            float cs = scale * UiFont.SIZE_CORRECTION;
            float penX = Math.round(x);
            float yTop = Math.round(y + font.ascent * cs);
            float yBottom = Math.round(y - font.descent * cs);

            // 6 sommets/glyphe (2 triangles, GL_TRIANGLES — pas de fan possible,
            // chaque glyphe est un quad DISJOINT des autres, contrairement au
            // rect/vignette qui n'ont besoin que d'UN seul quad).
            FloatBuffer verts = floatBuffer(text.length() * 6 * 4);
            for (int i = 0; i < text.length(); i++) {
                UiFont.Glyph g = font.glyph(text.charAt(i));
                float gw = Math.round(g.width * cs);
                float x0 = penX, x1 = penX + gw;
                // v0=haut-gauche, v1=bas-gauche, v2=bas-droite, v3=haut-droite — même ordre que le mode immédiat legacy.
                putVertex(verts, x0, yTop, g.u0, g.v0);
                putVertex(verts, x0, yBottom, g.u0, g.v1);
                putVertex(verts, x1, yBottom, g.u1, g.v1);
                putVertex(verts, x0, yTop, g.u0, g.v0);
                putVertex(verts, x1, yBottom, g.u1, g.v1);
                putVertex(verts, x1, yTop, g.u1, g.v0);
                penX += Math.round(g.advance * cs);
            }
            verts.flip();
            drawTrianglesModern(verts);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawTextModern: " + t);
        } finally {
            try { glUseProgram(0); } catch (Throwable ignored) {}
            try { glBindTexture(0x0DE1, 0); } catch (Throwable ignored) {}
        }
    }

    private void drawTextLegacy(UiFont font, String text, float x, float y, UiColor color, float scale,
                          int vpWidth, int vpHeight) {
        int texId = ensureFontTexture(font);
        if (texId < 0) return;
        ensureTextShaderInit();
        if (textInitFailed) return; // shader cassé : rien à faire de l'alpha-distance brute, mieux vaut ne rien dessiner

        // Legacy (1.8.9) — voir captureLegacyGlState()/drawEdgeVignetteLegacy.
        GlBridge.LegacyGlState savedGlState = null;
        boolean projPushed = false, modelPushed = false;
        try {
            savedGlState = captureLegacyGlState();
            glEnable(0x0DE1);  // GL_TEXTURE_2D
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE
            glDisable(0x0BC0); // GL_ALPHA_TEST — voir drawEdgeVignette pour le pourquoi
            // PAS de glDisable(GL_SCISSOR_TEST) — BUG TROUVÉ (voir
            // UiScrollContainer, javadoc de classe) : défaisait le clip actif
            // d'un scroll container pour CHAQUE texte dessiné à l'intérieur.
            glEnable(0x0BE2);  // GL_BLEND
            glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA
            glBindTexture(0x0DE1, texId);
            glUseProgram(textProgram);
            glUniform1i(uTex, 0); // texture unit 0 (celle qu'on vient de bind)

            matrixMode(0x1701); // GL_PROJECTION
            pushMatrix();
            projPushed = true;
            loadIdentity();
            glOrtho(0, vpWidth, 0, vpHeight, -1, 1);
            matrixMode(0x1700); // GL_MODELVIEW
            pushMatrix();
            modelPushed = true;
            loadIdentity();

            glColor4f(color.r, color.g, color.b, color.a);

            // cs ("scale corrigé") compense UiFont.RASTER_PX (résolution de
            // rasterisation, un curseur de QUALITÉ) pour que la taille
            // affichée ne dépende que de "scale", calibré une fois pour
            // toutes sur UiFont.REFERENCE_PX — voir UiFont pour le pourquoi.
            float cs = scale * UiFont.SIZE_CORRECTION;

            // Alignement pixel entier — LA vraie cause du flou observé (pas la
            // résolution de l'atlas, déjà testée x8 sans aucun effet visible) :
            // des coordonnées de quad en sous-pixel (ex: y=412.63) forcent le
            // GPU à échantillonner la texture ENTRE deux texels, brouillant le
            // bord des lettres même avec un filtrage parfait. Minecraft aligne
            // son propre texte sur des pixels entiers pour cette raison. Chaque
            // avance de plume est elle-même arrondie (pas juste la position de
            // départ) pour que l'arrondi ne dérive pas caractère après caractère.
            float penX = Math.round(x);
            float yTop = Math.round(y + font.ascent * cs);
            float yBottom = Math.round(y - font.descent * cs);
            glBegin(7); // GL_QUADS
            for (int i = 0; i < text.length(); i++) {
                UiFont.Glyph g = font.glyph(text.charAt(i));
                float gw = Math.round(g.width * cs);
                glTexCoord2f(g.u0, g.v0); glVertex2f(penX, yTop);
                glTexCoord2f(g.u0, g.v1); glVertex2f(penX, yBottom);
                glTexCoord2f(g.u1, g.v1); glVertex2f(penX + gw, yBottom);
                glTexCoord2f(g.u1, g.v0); glVertex2f(penX + gw, yTop);
                penX += Math.round(g.advance * cs);
            }
            glEnd();
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawText: " + t);
        } finally {
            try { glUseProgram(0); } catch (Throwable ignored) {}
            try { glBindTexture(0x0DE1, 0); } catch (Throwable ignored) {}
            try {
                if (modelPushed) { matrixMode(0x1700); popMatrix(); }
            } catch (Throwable ignored) {}
            try {
                if (projPushed) { matrixMode(0x1701); popMatrix(); }
            } catch (Throwable ignored) {}
            restoreLegacyGlState(savedGlState);
        }
    }

    private int ensureFontTexture(UiFont font) {
        Integer cached = fontTextures.get(font);
        if (cached != null) return cached;

        ensureNativeTextureApiResolved();
        if (nativeTextureApiAvailable) {
            try {
                int texId = createFontTextureViaNativeImage(font);
                syncAfterFontUpload(texId);
                fontTextures.put(font, texId);
                LauncherLog.ui(1, "[UiRenderer] atlas police uploadé via NativeImage/TextureManager, texId=" + texId);
                return texId;
            } catch (Throwable t) {
                LauncherLog.err("[UiRenderer] createFontTextureViaNativeImage a échoué, repli sur glTexImage2D brut : " + t);
                // repli ci-dessous
            }
        }
        try {
            int texId = createFontTextureRaw(font);
            syncAfterFontUpload(texId);
            fontTextures.put(font, texId);
            LauncherLog.ui(1, "[UiRenderer] atlas police uploadé (repli brut), texId=" + texId);
            return texId;
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] ensureFontTexture: " + t);
            fontTextures.put(font, -1);
            return -1;
        }
    }

    /**
     * BUG TROUVÉ (era E, texte REGULAR corrompu un lancement sur deux,
     * confirmé par capture d'écran : deux sessions IDENTIQUES de la même
     * instance, mêmes réglages, l'une nette et l'autre corrompue — donc pas
     * un bug déterministe de code, un vrai résultat différent produit par le
     * pilote GPU d'un lancement à l'autre) : le log de démarrage confirme
     * explicitement que CETTE machine a un contournement Sodium actif pour
     * "NVIDIA_THREADED_OPTIMIZATIONS_BROKEN" — la soumission de commandes en
     * thread séparé du pilote NVIDIA est documentée bogguée ICI. Sodium
     * applique ses propres contournements pour SES draws, mais notre
     * `glTexImage2D`/`glGenerateMipmap` (injectés via Mixin, hors du contrôle
     * de Sodium) n'en bénéficient pas : rien n'empêche le pilote de renvoyer
     * la main avant d'avoir RÉELLEMENT terminé l'upload/la génération des
     * mipmaps en arrière-plan, laissant échantillonner une texture
     * partiellement écrite (garbage) — l'atlas n'étant créé qu'une seule
     * fois par lancement, ce résultat de course reste figé pour toute la
     * session, cohérent avec TOUT ce qui a été observé. Fix : `glFinish()`
     * juste après l'upload, une seule fois par police par lancement (aucun
     * risque de perf) — force le pilote à réellement terminer avant qu'on
     * ne considère la texture prête à être échantillonnée.
     */
    private void syncAfterFontUpload(int texId) {
        if (texId < 0) return;
        try {
            glFinish();
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] syncAfterFontUpload (texId=" + texId + "): " + t);
        }
    }

    private static Class<?> nativeImageClass, nativeImageBackedTextureClass, textureManagerClass, abstractTextureClass, identifierClass;
    private static Object nativeImageFormatRgba;
    private static java.lang.reflect.Constructor<?> nativeImageCtor, nativeImageBackedTextureCtor;
    private static boolean nativeImageBackedTextureNeedsLabel;
    private static java.lang.reflect.Field nativeImagePointerField;
    private static Method nativeImageSetColor, nativeImageCloseMethod, textureUploadMethod, textureGetGlIdMethod,
        textureBindTextureMethod, textureManagerRegisterTextureMethod, identifierOfMethod, mcGetTextureManagerMethod,
        memCopyMethod, memAddressMethod;
    private static boolean nativeTextureApiResolveAttempted, nativeTextureApiAvailable, bulkCopyAvailable;
    private static int fontTextureCounter;

    /**
     * BUG TROUVÉ (1.21.4, crash natif confirmé par bissection — voir
     * historique de session) : créer notre PROPRE texture GL brute
     * (glGenTextures/glTexImage2D/glGenerateMipmap/glTexParameteri via
     * réflexion) pour l'atlas de police plantait le process de façon
     * imprévisible (parfois REGULAR, parfois BOLD, jamais un point de code
     * fixe) — le déplacement de l'ordre de création n'a fait que déplacer le
     * crash, pas le résoudre. Recherche sur les mods Fabric open-source
     * confirmée : AUCUN mod sérieux ne crée de texture dynamique en GL brut
     * — tous passent par {@code NativeImage} + {@code
     * NativeImageBackedTexture} + {@code TextureManager.registerTexture()},
     * le chemin de création de texture SUIVI par le système de gestion
     * d'état interne de Minecraft ({@code GlStateManager}/{@code
     * RenderSystem}). Notre ancien code contournait entièrement ce suivi —
     * hypothèse retenue : ça désynchronisait l'état GL que le rendu vanilla
     * (qui tourne dans la même frame) suppose cohérent, plantage
     * imprévisible selon ce qui se dessine à côté. Résolu dynamiquement
     * (aucune classe Minecraft compilée en dur) ; repli sur l'ancien chemin
     * brut UNIQUEMENT si cette résolution échoue entièrement (ex: signature
     * qui aurait changé sur une future version).
     */
    private void ensureNativeTextureApiResolved() {
        if (nativeTextureApiResolveAttempted) return;
        nativeTextureApiResolveAttempted = true;
        try {
            nativeImageClass = McReflect.yarnClass("net/minecraft/client/texture/NativeImage");
            nativeImageBackedTextureClass = McReflect.yarnClass("net/minecraft/client/texture/NativeImageBackedTexture");
            textureManagerClass = McReflect.yarnClass("net/minecraft/client/texture/TextureManager");
            abstractTextureClass = McReflect.yarnClass("net/minecraft/client/texture/AbstractTexture");
            identifierClass = McReflect.yarnClass("net/minecraft/util/Identifier");
            Class<?> formatClass = McReflect.yarnClass("net/minecraft/client/texture/NativeImage$Format");
            if (nativeImageClass == null || nativeImageBackedTextureClass == null || textureManagerClass == null
                    || abstractTextureClass == null || identifierClass == null || formatClass == null) {
                LauncherLog.warn("[UiRenderer] résolution NativeImage/TextureManager : une classe introuvable, repli brut");
                return;
            }

            String rgbaObf = MappingsRegistry.getObfFieldName("net/minecraft/client/texture/NativeImage$Format", "RGBA");
            java.lang.reflect.Field rgbaField = formatClass.getDeclaredField(rgbaObf);
            rgbaField.setAccessible(true);
            nativeImageFormatRgba = rgbaField.get(null);

            nativeImageCtor = nativeImageClass.getDeclaredConstructor(formatClass, int.class, int.class, boolean.class);
            nativeImageCtor.setAccessible(true);
            // BUG TROUVÉ (era E, 1.21.11) : NativeImageBackedTexture(NativeImage)
            // (le seul constructeur utilisé jusqu'à la 1.21.4) n'existe plus —
            // Blaze3D ajoute un label de debug obligatoire en 1er paramètre
            // (Supplier<String>), confirmé via mappings 1.21.11 :
            // "(Ljava/util/function/Supplier;Lfyh;)V <init>" (fyh=NativeImage) —
            // AUCUN constructeur 1-arg NativeImage-seul n'existe plus du tout sur
            // cette version. Essaie l'ancien d'abord (1.20.4/1.21.4), puis le
            // nouveau (Supplier<String>, NativeImage) en repli.
            try {
                nativeImageBackedTextureCtor = nativeImageBackedTextureClass.getDeclaredConstructor(nativeImageClass);
                nativeImageBackedTextureNeedsLabel = false;
            } catch (NoSuchMethodException e) {
                nativeImageBackedTextureCtor = nativeImageBackedTextureClass.getDeclaredConstructor(java.util.function.Supplier.class, nativeImageClass);
                nativeImageBackedTextureNeedsLabel = true;
            }
            nativeImageBackedTextureCtor.setAccessible(true);

            nativeImageSetColor = McReflect.method(nativeImageClass, "net/minecraft/client/texture/NativeImage", "setColor", int.class, int.class, int.class);
            nativeImageCloseMethod = McReflect.noArgMethod(nativeImageClass, "net/minecraft/client/texture/NativeImage", "close");

            // BUG DE PERFORMANCE TROUVÉ (v347, signalé par l'utilisateur : "je
            // lag à 8 FPS") : remplir un atlas 1024x2048 (2 millions de pixels)
            // via setColor() UN PAR UN — chaque appel étant une réflexion Java
            // (Method.invoke, avec autoboxing) — coûte des millions
            // d'invocations réflexives par police créée. Repli : accès direct
            // à la mémoire native de NativeImage (champ "pointer", un long
            // pointant vers le buffer hors-tas) via MemoryUtil.memCopy — une
            // SEULE copie mémoire brute au lieu de 2 millions d'appels
            // individuels. Notre ByteBuffer existant (octets R,G,B,A
            // consécutifs) a EXACTEMENT le même agencement mémoire que le
            // format "RGBA petit-boutiste" de NativeImage (petit-boutiste :
            // R d'abord en mémoire, A en dernier) — aucune conversion
            // supplémentaire nécessaire, juste une copie brute octet à octet.
            try {
                String pointerObf = MappingsRegistry.getObfFieldName("net/minecraft/client/texture/NativeImage", "pointer");
                nativeImagePointerField = nativeImageClass.getDeclaredField(pointerObf);
                nativeImagePointerField.setAccessible(true);
                memCopyMethod = gl.rawMethod("org.lwjgl.system.MemoryUtil", "memCopy", long.class, long.class, long.class);
                memAddressMethod = gl.rawMethod("org.lwjgl.system.MemoryUtil", "memAddress", java.nio.ByteBuffer.class);
                bulkCopyAvailable = true;
            } catch (Throwable t) {
                LauncherLog.warn("[UiRenderer] copie mémoire en bloc (MemoryUtil) indisponible, repli sur setColor() pixel par pixel (lent) : " + t);
                bulkCopyAvailable = false;
            }
            textureUploadMethod = McReflect.noArgMethod(nativeImageBackedTextureClass, "net/minecraft/client/texture/NativeImageBackedTexture", "upload");
            textureGetGlIdMethod = McReflect.noArgMethod(abstractTextureClass, "net/minecraft/client/texture/AbstractTexture", "getGlId");
            textureBindTextureMethod = McReflect.noArgMethod(abstractTextureClass, "net/minecraft/client/texture/AbstractTexture", "bindTexture");
            textureManagerRegisterTextureMethod = McReflect.method(textureManagerClass, "net/minecraft/client/texture/TextureManager",
                "registerTexture", identifierClass, abstractTextureClass);
            identifierOfMethod = McReflect.method(identifierClass, "net/minecraft/util/Identifier", "of", String.class, String.class);
            Object mc = McReflect.minecraftClient();
            mcGetTextureManagerMethod = mc != null
                ? McReflect.noArgMethod(mc.getClass(), "net/minecraft/client/MinecraftClient", "getTextureManager")
                : null;

            nativeTextureApiAvailable = nativeImageSetColor != null && textureUploadMethod != null
                && textureGetGlIdMethod != null && textureBindTextureMethod != null
                && textureManagerRegisterTextureMethod != null && identifierOfMethod != null
                && mcGetTextureManagerMethod != null && nativeImageFormatRgba != null;
            LauncherLog.info("[UiRenderer] API NativeImage/TextureManager résolue : disponible=" + nativeTextureApiAvailable);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] résolution API NativeImage/TextureManager échouée, repli brut : " + t);
            nativeTextureApiAvailable = false;
        }
    }

    private int createFontTextureViaNativeImage(UiFont font) throws Exception {
        java.awt.image.BufferedImage img = font.atlasImage();
        int w = img.getWidth(), h = img.getHeight();

        Object nativeImage = nativeImageCtor.newInstance(nativeImageFormatRgba, w, h, false);
        try {
            if (bulkCopyAvailable) {
                // Chemin rapide : une seule copie mémoire brute (voir
                // ensureNativeTextureApiResolved pour le pourquoi détaillé).
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
                long srcAddr = (long) memAddressMethod.invoke(null, buf);
                long dstAddr = (long) nativeImagePointerField.get(nativeImage);
                memCopyMethod.invoke(null, srcAddr, dstAddr, (long) buf.remaining());
            } else {
                // Repli lent (voir ensureNativeTextureApiResolved) — évite
                // juste de ne RIEN dessiner si MemoryUtil ne se résout pas.
                int[] row = new int[w];
                for (int y = 0; y < h; y++) {
                    img.getRGB(0, y, w, 1, row, 0, w);
                    for (int x = 0; x < w; x++) {
                        int argb = row[x];
                        int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                        // NativeImage.setColor attend du RGBA petit-boutiste, donc
                        // ABGR en notation big-endian habituelle (alpha=octet le
                        // plus significatif, rouge=le moins significatif) — voir
                        // sa javadoc Yarn ("little-endian RGBA, or big-endian ABGR").
                        int nativeColor = (a << 24) | (b << 16) | (g << 8) | r;
                        nativeImageSetColor.invoke(nativeImage, x, y, nativeColor);
                    }
                }
            }

            Object texture = nativeImageBackedTextureNeedsLabel
                ? nativeImageBackedTextureCtor.newInstance((java.util.function.Supplier<String>) () -> "yuyuframe_font", nativeImage)
                : nativeImageBackedTextureCtor.newInstance(nativeImage);
            textureUploadMethod.invoke(texture); // fait le VRAI glTexImage2D, via le chemin suivi par Minecraft
            int texId = (int) textureGetGlIdMethod.invoke(texture);

            // Filtre trilinéaire + clamp-to-edge (voir historique de session
            // pour le pourquoi) — appliqués APRÈS coup sur une texture déjà
            // créée par la voie sûre : bind via AbstractTexture.bindTexture()
            // (passe par GlStateManager, pas notre glBindTexture brut) avant
            // ces quelques réglages, qui eux restent des appels GL directs
            // mais bien plus anodins qu'une création de texture complète.
            textureBindTextureMethod.invoke(texture);
            glGenerateMipmap(0x0DE1);
            glTexParameteri(0x0DE1, 0x2801, 0x2703); // GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR
            glTexParameteri(0x0DE1, 0x2800, 0x2601); // GL_TEXTURE_MAG_FILTER, GL_LINEAR
            glTexParameteri(0x0DE1, 0x2802, 0x812F); // GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE
            glTexParameteri(0x0DE1, 0x2803, 0x812F); // GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE

            Object textureManager = mcGetTextureManagerMethod.invoke(McReflect.minecraftClient());
            Object id = identifierOfMethod.invoke(null, "yuyuframe", "font_" + (fontTextureCounter++));
            textureManagerRegisterTextureMethod.invoke(textureManager, id, texture);
            return texId;
        } finally {
            // NativeImage possède de la mémoire HORS-TAS (native) — doit être
            // explicitement libérée, contrairement au ByteBuffer direct de
            // l'ancien chemin (géré par le GC, voir Cleaner de
            // ByteBuffer.allocateDirect) : celui-ci ne l'est pas.
            if (nativeImageCloseMethod != null) {
                try { nativeImageCloseMethod.invoke(nativeImage); } catch (Throwable ignored) {}
            }
        }
    }

    /** Ancien chemin (GL brut via réflexion) — conservé UNIQUEMENT en repli si la résolution NativeImage échoue. */
    private int createFontTextureRaw(UiFont font) throws Exception {
        java.awt.image.BufferedImage img = font.atlasImage();
        int w = img.getWidth(), h = img.getHeight();

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

        // DIAG-FONTCORRUPT (era E, bug "texte corrompu une fois sur deux" —
        // stable sur TOUTE une session, jamais un flicker en cours de route,
        // donc lié à CETTE création unique/mise en cache, pas au dessin par
        // frame) : cette méthode n'est appelée QU'UNE SEULE FOIS par police
        // par lancement (voir ensureFontTexture, résultat mis en cache) — un
        // seul appel de log ici, aucun risque de perf, même motif que DIAG7
        // (voir historique de session) mais volontairement gardé cette fois
        // (pas par frame).
        int activeUnitBefore = glGetInteger(0x84E0); // GL_ACTIVE_TEXTURE
        int boundTexBefore = glGetInteger(0x8069);   // GL_TEXTURE_BINDING_2D
        int errBefore = drainGlErrors();

        int texId = glGenTextures();
        glBindTexture(0x0DE1, texId); // GL_TEXTURE_2D
        int errAfterBind = drainGlErrors();
        glTexImage2D(0x0DE1, 0, 0x1908, w, h, 0, 0x1908, 0x1401, buf); // GL_RGBA, GL_RGBA, GL_UNSIGNED_BYTE
        int errAfterTexImage = drainGlErrors();
        glGenerateMipmap(0x0DE1);
        int errAfterMipmap = drainGlErrors();
        glTexParameteri(0x0DE1, 0x2801, 0x2703); // GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR
        glTexParameteri(0x0DE1, 0x2800, 0x2601); // GL_TEXTURE_MAG_FILTER, GL_LINEAR
        glTexParameteri(0x0DE1, 0x2802, 0x812F); // GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE
        glTexParameteri(0x0DE1, 0x2803, 0x812F); // GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE
        int errAfterParams = drainGlErrors();

        // BUG TROUVÉ (era E, texte REGULAR corrompu de façon non-déterministe
        // — confirmé toujours présent MÊME en vanilla sans aucun mod, donc
        // rien à voir avec Sodium/le threading NVIDIA, hypothèse infirmée) :
        // `buf` est un ByteBuffer DIRECT (mémoire hors-tas, libérée par un
        // Cleaner quand l'objet Java devient inatteignable) — la JVM tourne
        // ici avec ZGC (voir JVM Arguments dans les logs de lancement,
        // -XX:+UseZGC -XX:+ZGenerational), un collecteur concurrent
        // particulièrement agressif. Rien n'empêche la JIT/le GC de
        // considérer `buf` "mort" dès la dernière ligne qui le RÉFÉRENCE
        // explicitement (le `glTexImage2D` juste au-dessus) : si le pilote
        // NVIDIA ne copie pas les octets de façon strictement synchrone
        // pendant cet appel (contrairement à ce qu'exige la spec OpenGL, mais
        // des bugs de pilote de ce type existent), un GC concurrent
        // déclenché entre-temps peut libérer cette mémoire AVANT que le
        // pilote ait fini de la lire — le pilote lit alors de la mémoire déjà
        // réutilisée/libérée = texture corrompue, sans qu'aucune erreur GL ne
        // soit levée (cohérent avec `DIAG-FONTCORRUPT` : jamais un seul
        // `glErr` observé). Un `glFinish()` seul (tenté juste avant, sans
        // effet) n'empêche PAS ça : il attend la fin d'une commande qui a
        // DÉJÀ lu la mauvaise mémoire, trop tard pour corriger le résultat.
        // Fix : `glFinish()` ICI (PAS seulement dans syncAfterFontUpload,
        // appelé APRÈS le retour de cette méthode — trop tard, `buf` ne
        // serait alors déjà plus protégé) suivi de
        // `Reference.reachabilityFence(buf)` — l'ordre est capital : glFinish
        // garantit que le pilote a RÉELLEMENT fini de lire `buf`, et la
        // reachabilityFence juste après garantit que la JVM n'a PAS pu
        // libérer sa mémoire native PENDANT cette attente, quelle que soit
        // l'agressivité du GC.
        glFinish();
        reachabilityFence(buf);

        // BUG TROUVÉ (era E) : le diagnostic glGetTexImage tenté ici en v362
        // a provoqué un CRASH JVM natif (EXCEPTION_ACCESS_VIOLATION, écriture
        // hors bornes côté pilote NVIDIA+DSA — voir hs_err_pid*.log,
        // confirmé pointer exactement sur cet appel). Retiré définitivement
        // — ne JAMAIS réintroduire un glGetTexImage ici sans un moyen plus
        // sûr de vérifier au préalable la taille réellement allouée côté
        // pilote (ex: glGetTexLevelParameteriv AVANT de dimensionner le
        // buffer de lecture, jamais en supposant que w/h côté Java
        // correspondent forcément à ce que le pilote a alloué).
        glBindTexture(0x0DE1, 0);

        LauncherLog.info("[LauncherAgent] DIAG-FONTCORRUPT: texId=" + texId + " atlasW=" + w + " atlasH=" + h
            + " activeUnitBefore=0x" + Integer.toHexString(activeUnitBefore)
            + " boundTex2DBefore=" + boundTexBefore
            + " glErr(before=" + errBefore + ", afterBind=" + errAfterBind
            + ", afterTexImage=" + errAfterTexImage + ", afterMipmap=" + errAfterMipmap
            + ", afterParams=" + errAfterParams + ")");
        return texId;
    }
}
