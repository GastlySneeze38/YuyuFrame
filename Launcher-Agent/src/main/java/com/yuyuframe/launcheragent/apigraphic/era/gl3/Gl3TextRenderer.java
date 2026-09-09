package com.yuyuframe.launcheragent.apigraphic.era.gl3;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.era.glsupport.FontAtlasTextures;
import com.yuyuframe.launcheragent.apigraphic.era.glsupport.GlBridge;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.nio.FloatBuffer;

/**
 * Rendu de texte de l'ère gl3 — Core Profile 3.2 (1.17 – 1.21.x) : plus de
 * pile de matrices, VAO/VBO obligatoires, GLSL {@code #version 150}.
 *
 * <p>Extrait de {@code UiTextRenderer} le 2026-09-09, où il s'appelait
 * {@code drawTextModern} et vivait entrelacé avec le chemin gl2.
 *
 * <h2>Ce que le découpage a fait tomber</h2>
 *
 * L'original commençait par :
 *
 * <pre>if (Blaze3DCore.isAvailable()) { Blaze3DText.queueDraw(...); return; }</pre>
 *
 * — l'ère Blaze3D était IMBRIQUÉE dans le chemin « moderne », parce que les
 * deux partageaient un fichier et que le seul moyen de les distinguer était un
 * test à l'exécution. Ce test a disparu : l'ère est résolue une fois, et le
 * texte Blaze3D est servi par {@code Blaze3DBackend}. Ce fichier n'a plus
 * jamais besoin de savoir que Blaze3D existe.
 */
public final class Gl3TextRenderer {

    private final UiRenderer owner;
    private final GlBridge gl;
    private final FontAtlasTextures fonts;

    public Gl3TextRenderer(UiRenderer owner, GlBridge gl, FontAtlasTextures fonts) {
        this.owner = owner;
        this.gl = gl;
        this.fonts = fonts;
    }

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

    private void ensureTextShaderInitModern() {
        if (textProgramModern != -1 || textInitFailedModern) return;
        try {
            textProgramModern = owner.compileModernProgram(UiRenderer.VERTEX_SRC_MODERN, TEXT_FRAGMENT_SRC_MODERN);
            uTexModern = gl.glGetUniformLocation(textProgramModern, "u_Tex");
            uColorTextModern = gl.glGetUniformLocation(textProgramModern, "uColor");
            uProjectionTextModern = gl.glGetUniformLocation(textProgramModern, "uProjection");
            LauncherLog.ui(1, "[UiRenderer] shader texte moderne (SDF) compilé, program=" + textProgramModern);
        } catch (Throwable t) {
            textInitFailedModern = true;
            LauncherLog.err("[UiRenderer] échec compilation shader texte SDF moderne — texte non affiché : " + t);
        }
    }

    public void draw(UiFont font, String text, float x, float y, UiColor color, float scale,
                                 int vpWidth, int vpHeight) {
        int texId = fonts.ensureFontTexture(font);
        if (texId < 0) return;
        ensureTextShaderInitModern();
        if (textInitFailedModern) return;
        try {
            // Voir drawEdgeVignetteModern : GL_TEXTURE_2D en tant que CAPACITÉ
            // (glEnable/glDisable) retiré — GL_INVALID_ENUM en Core Profile.
            // glBindTexture(GL_TEXTURE_2D, ...) juste en dessous reste lui
            // parfaitement valide : c'est une CIBLE de bind, pas une capacité
            // fixed-function, ces deux usages du même enum sont indépendants.
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE
            // PAS de glDisable(GL_SCISSOR_TEST) — BUG TROUVÉ (voir
            // UiScrollContainer, javadoc de classe) : défaisait le clip actif
            // d'un scroll container pour CHAQUE texte dessiné à l'intérieur.
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA
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
            gl.glActiveTexture(0x84C0); // GL_TEXTURE0
            gl.glBindTexture(0x0DE1, texId);
            gl.glUseProgram(textProgramModern);
            gl.glUniform1i(uTexModern, 0);
            gl.glUniform4f(uColorTextModern, color.r, color.g, color.b, color.a);
            owner.uploadProjectionModern(uProjectionTextModern, vpWidth, vpHeight);

            float cs = scale * UiFont.SIZE_CORRECTION;
            float penX = Math.round(x);
            float yTop = Math.round(y + font.ascent * cs);
            float yBottom = Math.round(y - font.descent * cs);

            // 6 sommets/glyphe (2 triangles, GL_TRIANGLES — pas de fan possible,
            // chaque glyphe est un quad DISJOINT des autres, contrairement au
            // rect/vignette qui n'ont besoin que d'UN seul quad).
            FloatBuffer verts = owner.floatBuffer(text.length() * 6 * 4);
            for (int i = 0; i < text.length(); i++) {
                UiFont.Glyph g = font.glyph(text.charAt(i));
                float gw = Math.round(g.width * cs);
                float x0 = penX, x1 = penX + gw;
                // v0=haut-gauche, v1=bas-gauche, v2=bas-droite, v3=haut-droite — même ordre que le mode immédiat legacy.
                owner.putVertex(verts, x0, yTop, g.u0, g.v0);
                owner.putVertex(verts, x0, yBottom, g.u0, g.v1);
                owner.putVertex(verts, x1, yBottom, g.u1, g.v1);
                owner.putVertex(verts, x0, yTop, g.u0, g.v0);
                owner.putVertex(verts, x1, yBottom, g.u1, g.v1);
                owner.putVertex(verts, x1, yTop, g.u1, g.v0);
                penX += Math.round(g.advance * cs);
            }
            verts.flip();
            owner.drawTrianglesModern(verts);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawTextModern: " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
            try { gl.glBindTexture(0x0DE1, 0); } catch (Throwable ignored) {}
        }
    }
}
