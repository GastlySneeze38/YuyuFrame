package com.yuyuframe.launcheragent.apigraphic.era.gl2;

import com.yuyuframe.launcheragent.apigraphic.era.glsupport.FontAtlasTextures;
import com.yuyuframe.launcheragent.apigraphic.era.glsupport.GlBridge;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

/**
 * Rendu de texte de l'ère gl2 — pipeline fixe (≤ 1.16) : pile de matrices,
 * {@code GL_ALPHA_TEST}, dessin immédiat {@code glBegin}/{@code glEnd}.
 *
 * <p>Extrait de {@code UiTextRenderer} le 2026-09-09 : c'était le chemin
 * {@code drawTextLegacy} et son shader, entrelacés avec ceux de gl3 dans le
 * même fichier. Le code lui-même est repris À L'IDENTIQUE — seul son
 * emplacement change.
 *
 * <p>Le shader SDF est bien présent ici aussi : l'ère gl2 n'est pas « sans
 * shader », elle est « sans Core Profile ». Ce qui diffère de gl3, c'est la
 * gestion d'état (pile de matrices, {@code GL_TEXTURE_2D} comme capacité,
 * {@code GL_ALPHA_TEST}), la soumission (immédiat vs VAO) et le dialecte GLSL.
 */
public final class Gl2TextRenderer {

    private final GlBridge gl;
    private final FontAtlasTextures fonts;

    public Gl2TextRenderer(GlBridge gl, FontAtlasTextures fonts) {
        this.gl = gl;
        this.fonts = fonts;
    }

    /** Réutilisé par {@link UiPrimitiveRenderer} pour le vertex shader legacy de l'icône générique (voir ensureIconShaderInit). */
    public static final String TEXT_VERTEX_SRC =
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

            uTex = gl.glGetUniformLocation(textProgram, "u_Tex");

            LauncherLog.ui(1, "[UiRenderer] shader texte (SDF) compilé, program=" + textProgram + " uTex=" + uTex);
        } catch (Throwable t) {
            textInitFailed = true;
            LauncherLog.err("[UiRenderer] échec compilation shader texte SDF — texte non affiché : " + t);
        }
    }

    public void draw(UiFont font, String text, float x, float y, UiColor color, float scale,
                          int vpWidth, int vpHeight) {
        int texId = fonts.ensureFontTexture(font);
        if (texId < 0) return;
        ensureTextShaderInit();
        if (textInitFailed) return; // shader cassé : rien à faire de l'alpha-distance brute, mieux vaut ne rien dessiner

        // Legacy (1.8.9) — voir captureLegacyGlState()/drawEdgeVignetteLegacy.
        GlBridge.LegacyGlState savedGlState = null;
        boolean projPushed = false, modelPushed = false;
        try {
            savedGlState = gl.captureLegacyGlState();
            gl.glEnable(0x0DE1);  // GL_TEXTURE_2D
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE
            gl.glDisable(0x0BC0); // GL_ALPHA_TEST — voir drawEdgeVignette pour le pourquoi
            // PAS de glDisable(GL_SCISSOR_TEST) — BUG TROUVÉ (voir
            // UiScrollContainer, javadoc de classe) : défaisait le clip actif
            // d'un scroll container pour CHAQUE texte dessiné à l'intérieur.
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA
            gl.glBindTexture(0x0DE1, texId);
            gl.glUseProgram(textProgram);
            gl.glUniform1i(uTex, 0); // texture unit 0 (celle qu'on vient de bind)

            gl.matrixMode(0x1701); // GL_PROJECTION
            gl.pushMatrix();
            projPushed = true;
            gl.loadIdentity();
            gl.glOrtho(0, vpWidth, 0, vpHeight, -1, 1);
            gl.matrixMode(0x1700); // GL_MODELVIEW
            gl.pushMatrix();
            modelPushed = true;
            gl.loadIdentity();

            gl.glColor4f(color.r, color.g, color.b, color.a);

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
            gl.glBegin(7); // GL_QUADS
            for (int i = 0; i < text.length(); i++) {
                UiFont.Glyph g = font.glyph(text.charAt(i));
                float gw = Math.round(g.width * cs);
                gl.glTexCoord2f(g.u0, g.v0); gl.glVertex2f(penX, yTop);
                gl.glTexCoord2f(g.u0, g.v1); gl.glVertex2f(penX, yBottom);
                gl.glTexCoord2f(g.u1, g.v1); gl.glVertex2f(penX + gw, yBottom);
                gl.glTexCoord2f(g.u1, g.v0); gl.glVertex2f(penX + gw, yTop);
                penX += Math.round(g.advance * cs);
            }
            gl.glEnd();
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawText: " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
            try { gl.glBindTexture(0x0DE1, 0); } catch (Throwable ignored) {}
            try {
                if (modelPushed) { gl.matrixMode(0x1700); gl.popMatrix(); }
            } catch (Throwable ignored) {}
            try {
                if (projPushed) { gl.matrixMode(0x1701); gl.popMatrix(); }
            } catch (Throwable ignored) {}
            gl.restoreLegacyGlState(savedGlState);
        }
    }
}
