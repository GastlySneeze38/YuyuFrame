package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v1_21_11;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;

/**
 * Chaîne de texte SDF soumise à l'état de GUI de vanilla sur 1.21.11 — pendant
 * exact de {@code TextElement} (26.1.2).
 *
 * <p>Un seul élément produit TOUS les quads de la chaîne (un par glyphe) :
 * {@link #setupVertices} peut écrire autant de sommets qu'il veut tant que le
 * mode de primitive reste {@code QUADS}. Ça évite un élément par caractère, et
 * autant d'entrées dans l'arbre de strates de vanilla.
 *
 * <h2>Axe Y — le point à ne pas rater</h2>
 *
 * Le moteur travaille en Y-UP (origine en bas), la GUI vanilla en Y-DOWN. Cette
 * classe raisonne en coordonnées VANILLA : {@code baselineY} est la ligne de
 * base, l'ascendante monte donc vers les Y DÉCROISSANTS. L'ordre d'émission des
 * sommets et le pairage des UV sont repris TELS QUELS de la 26.1.2 — les deux
 * inversions se compensent, le winding accepté par le culling est préservé
 * (c'est le piège qui avait fait disparaître tout le texte en v403).
 */
final class TextElement1211 implements SimpleGuiElementRenderState {

    private final UiFont font;
    private final String text;
    private final float x, baselineY, scale;
    private final int argb;
    private final RenderPipeline pipeline;
    private final TextureSetup textureSetup;
    private final ScreenRect bounds;

    TextElement1211(UiFont font, String text, float x, float baselineY, float scale,
                    int argb, RenderPipeline pipeline, TextureSetup textureSetup) {
        this.font = font;
        this.text = text;
        this.x = x;
        this.baselineY = baselineY;
        this.scale = scale;
        this.argb = argb;
        this.pipeline = pipeline;
        this.textureSetup = textureSetup;

        // Largeur mesurée au constructeur et non dans bounds() : vanilla appelle
        // bounds() à chaque insertion, donc à chaque frame, et la mesure
        // parcourt toute la chaîne glyphe par glyphe. Tous les paramètres sont
        // finaux, le résultat ne peut pas changer.
        float cs = scale * UiFont.SIZE_CORRECTION;
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += Math.round(font.glyph(text.charAt(i)).advance * cs);
        }
        this.bounds = new ScreenRect(
            Math.round(x),
            Math.round(baselineY - font.ascent * cs),
            Math.max(1, width),
            Math.max(1, Math.round((font.ascent + font.descent) * cs)));
    }

    @Override
    public void setupVertices(VertexConsumer c) {
        float cs = scale * UiFont.SIZE_CORRECTION;
        float penX = Math.round(x);
        // Y-DOWN : au-dessus de la ligne de base = Y PLUS PETIT.
        float yTop = Math.round(baselineY - font.ascent * cs);
        float yBottom = Math.round(baselineY + font.descent * cs);

        for (int i = 0; i < text.length(); i++) {
            UiFont.Glyph g = font.glyph(text.charAt(i));
            float gw = Math.round(g.width * cs);
            float x0 = penX, x1 = penX + gw;

            // v0 = haut de l'atlas, apparié au haut géométrique (yTop).
            c.vertex(x0, yTop, 0f).color(argb).texture(g.u0, g.v0);
            c.vertex(x0, yBottom, 0f).color(argb).texture(g.u0, g.v1);
            c.vertex(x1, yBottom, 0f).color(argb).texture(g.u1, g.v1);
            c.vertex(x1, yTop, 0f).color(argb).texture(g.u1, g.v0);

            penX += Math.round(g.advance * cs);
        }
    }

    @Override
    public RenderPipeline pipeline() {
        return pipeline;
    }

    @Override
    public TextureSetup textureSetup() {
        return textureSetup;
    }

    @Override
    public ScreenRect scissorArea() {
        return null;
    }

    @Override
    public ScreenRect bounds() {
        return bounds;
    }
}
