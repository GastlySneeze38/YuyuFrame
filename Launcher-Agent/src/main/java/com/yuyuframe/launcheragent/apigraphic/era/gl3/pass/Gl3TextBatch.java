package com.yuyuframe.launcheragent.apigraphic.era.gl3.pass;

import com.yuyuframe.launcheragent.apigraphic.era.glsupport.FontAtlasTextures;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.lwjgl.opengl.GL20;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lot de texte gl3 — pendant de {@code Blaze3DText.beginBatch/endBatch}.
 *
 * <p>Entre {@link #begin} et {@link #end}, chaque chaîne est seulement
 * RETENUE ; à la fermeture, tout le texte d'une même police part en UN appel
 * de dessin (une police = un atlas = une texture à lier). Hors lot, chaque
 * chaîne coûtait son propre changement d'uniformes et son propre envoi de VBO
 * ({@link Gl3TextRenderer}).
 *
 * <p>La couleur voyage par sommet pour que des chaînes de couleurs
 * différentes partagent l'appel. La mise en page des glyphes et le shader SDF
 * sont ceux de {@link Gl3TextRenderer}, à l'identique : un texte dessiné en
 * lot ou non doit rester pixel pour pixel le même.
 *
 * <p>Même garde-fou que Blaze3D : un lot rouvert sans avoir été fermé est
 * journalisé et vidé plutôt que de laisser tout le texte disparaître.
 */
public final class Gl3TextBatch {

    private static final String VERTEX_SRC =
        "#version 150\n" +
        "in vec2 aPos;\n" +
        "in vec2 aTexCoord;\n" +
        "in vec4 aColor;\n" +
        "uniform mat4 uProjection;\n" +
        "out vec2 vTexCoord;\n" +
        "flat out vec4 vColor;\n" +
        "void main() {\n" +
        "    gl_Position = uProjection * vec4(aPos, 0.0, 1.0);\n" +
        "    vTexCoord = aTexCoord;\n" +
        "    vColor = aColor;\n" +
        "}\n";

    /** Formule SDF de {@link Gl3TextRenderer} (même biais), couleur par sommet. */
    private static final String FRAGMENT_SRC =
        "#version 150\n" +
        "uniform sampler2D u_Tex;\n" +
        "in vec2 vTexCoord;\n" +
        "flat in vec4 vColor;\n" +
        "out vec4 fragColor;\n" +
        "const float BIAS = 0.06;\n" +
        "void main() {\n" +
        "    float dist = texture(u_Tex, vTexCoord).a + BIAS;\n" +
        "    float w = fwidth(dist);\n" +
        "    float alpha = smoothstep(0.5 - w, 0.5 + w, dist);\n" +
        "    fragColor = vec4(vColor.rgb, vColor.a * alpha);\n" +
        "}\n";

    private static final class Entry {
        final String text;
        final float x, y, scale;
        final UiColor color;

        Entry(String text, float x, float y, UiColor color, float scale) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.color = color;
            this.scale = scale;
        }
    }

    private final FontAtlasTextures fonts;
    /** 2 position + 2 UV + 4 couleur. */
    private final Gl3VertexStream stream = new Gl3VertexStream(2, 2, 4);
    private final Map<UiFont, List<Entry>> pending = new LinkedHashMap<>();
    private boolean open;

    private int program = -1, uProjection = -1, uTex = -1;
    private boolean initFailed;

    public Gl3TextBatch(FontAtlasTextures fonts) {
        this.fonts = fonts;
    }

    public boolean isOpen() {
        return open;
    }

    public void begin() {
        if (open && !pending.isEmpty()) {
            LauncherLog.err("[Gl3TextBatch] lot de texte non refermé — endTextBatch() manquant chez l'appelant précédent ; "
                + pending.size() + " police(s) en attente jetée(s)");
        }
        pending.clear();
        open = true;
    }

    public void add(UiFont font, String text, float x, float y, UiColor color, float scale) {
        if (text == null || text.isEmpty()) return;
        pending.computeIfAbsent(font, k -> new ArrayList<>()).add(new Entry(text, x, y, color, scale));
    }

    public void end(int vpWidth, int vpHeight) {
        if (!open) return;
        open = false;
        if (pending.isEmpty()) return;
        try {
            if (!ensureProgram()) return;
            Gl3Core.uiState();
            GL20.glUseProgram(program);
            GL20.glUniform1i(uTex, 0);
            Gl3Core.ortho(uProjection, vpWidth, vpHeight);
            for (Map.Entry<UiFont, List<Entry>> group : pending.entrySet()) {
                drawFont(group.getKey(), group.getValue());
            }
        } catch (Throwable t) {
            LauncherLog.err("[Gl3TextBatch] end : " + t);
        } finally {
            pending.clear();
            GL20.glUseProgram(0);
            Gl3Core.bindTexture0(0);
        }
    }

    private void drawFont(UiFont font, List<Entry> entries) {
        int texId = fonts.ensureFontTexture(font);
        if (texId < 0) return;
        int glyphs = 0;
        for (Entry e : entries) glyphs += e.text.length();
        FloatBuffer out = stream.begin(glyphs * 6);
        for (Entry e : entries) layout(out, font, e);
        Gl3Core.bindTexture0(texId);
        stream.drawTriangles();
    }

    /** Copie conforme de la mise en page de {@link Gl3TextRenderer#draw} — voir la javadoc de classe. */
    private static void layout(FloatBuffer out, UiFont font, Entry e) {
        float cs = e.scale * UiFont.SIZE_CORRECTION;
        float penX = Math.round(e.x);
        float yTop = Math.round(e.y + font.ascent * cs);
        float yBottom = Math.round(e.y - font.descent * cs);
        UiColor c = e.color;
        for (int i = 0; i < e.text.length(); i++) {
            UiFont.Glyph g = font.glyph(e.text.charAt(i));
            float gw = Math.round(g.width * cs);
            float x0 = penX, x1 = penX + gw;
            vertex(out, x0, yTop, g.u0, g.v0, c);
            vertex(out, x0, yBottom, g.u0, g.v1, c);
            vertex(out, x1, yBottom, g.u1, g.v1, c);
            vertex(out, x0, yTop, g.u0, g.v0, c);
            vertex(out, x1, yBottom, g.u1, g.v1, c);
            vertex(out, x1, yTop, g.u1, g.v0, c);
            penX += Math.round(g.advance * cs);
        }
    }

    private static void vertex(FloatBuffer out, float x, float y, float u, float v, UiColor c) {
        out.put(x).put(y).put(u).put(v).put(c.r).put(c.g).put(c.b).put(c.a);
    }

    private boolean ensureProgram() {
        if (program != -1) return true;
        if (initFailed) return false;
        program = Gl3Core.program("textBatch", VERTEX_SRC, FRAGMENT_SRC, "aPos", "aTexCoord", "aColor");
        if (program == -1) {
            initFailed = true;
            return false;
        }
        uProjection = GL20.glGetUniformLocation(program, "uProjection");
        uTex = GL20.glGetUniformLocation(program, "u_Tex");
        return true;
    }
}
