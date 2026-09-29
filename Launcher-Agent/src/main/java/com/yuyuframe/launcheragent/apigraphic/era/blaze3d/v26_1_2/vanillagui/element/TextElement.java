package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1_2.vanillagui.element;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1_2.vanillagui.pipeline.Blaze3DGuiText;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

/**
 * Une chaîne de texte SDF soumise à l'état de GUI de vanilla — pendant de
 * {@link RoundedRectElement} pour le texte.
 *
 * <p>Un seul élément produit TOUS les quads de la chaîne (un par glyphe) :
 * {@link #buildVertices} peut écrire autant de sommets qu'il veut tant que le
 * mode de primitive reste {@code QUADS}. Ça évite un élément par caractère, et
 * donc autant d'entrées dans l'arbre de strates de vanilla.
 *
 * <h2>Axe Y — le point à ne pas rater</h2>
 *
 * Le moteur travaille en <b>Y-UP</b> (origine en bas, {@code ascent} vers les
 * Y croissants — voir {@code Blaze3DText.appendGlyphs}), la GUI vanilla en
 * <b>Y-DOWN</b> (origine en haut). Cette classe raisonne en coordonnées
 * VANILLA : {@code y} est la ligne de base, l'ascendante monte donc vers les Y
 * DÉCROISSANTS. C'est l'inverse du chemin historique, et c'est voulu — la
 * conversion se fait une fois, ici, plutôt que chez chaque appelant.
 *
 * <p>Conséquence sur l'ordre des sommets : l'orientation du quad reste la même
 * qu'en Y-UP une fois les deux inversions combinées (axe et appariement des
 * UV), donc le winding accepté par le culling est préservé — c'est le piège
 * qui avait fait disparaître tout le texte en v403, voir la javadoc de
 * {@code Blaze3DText.appendGlyphs}.
 */
public final class TextElement implements GuiElementRenderState {

    private final UiFont font;
    private final String text;
    private final float x, baselineY, scale;
    private final int argb;
    private final TextureSetup textureSetup;

    /** Bornes calculées UNE FOIS ici — voir {@link #bounds()}. */
    private final ScreenRectangle bounds;

    public TextElement(UiFont font, String text, float x, float baselineY, float scale,
                       int argb, TextureSetup textureSetup) {
        this.font = font;
        this.text = text;
        this.x = x;
        this.baselineY = baselineY;
        this.scale = scale;
        this.argb = argb;
        this.textureSetup = textureSetup;

        // Largeur mesurée au constructeur et non dans bounds() : vanilla
        // appelle bounds() à chaque insertion, donc à chaque frame, et la
        // mesure parcourt toute la chaîne glyphe par glyphe. Les paramètres
        // étant tous finaux, le résultat ne peut pas changer.
        float cs = scale * UiFont.SIZE_CORRECTION;
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += Math.round(font.glyph(text.charAt(i)).advance * cs);
        }
        this.bounds = new ScreenRectangle(
            Math.round(x),
            Math.round(baselineY - font.ascent * cs),
            Math.max(1, width),
            Math.max(1, Math.round((font.ascent + font.descent) * cs)));
    }

    @Override
    public void buildVertices(VertexConsumer c) {
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
            c.addVertex(x0, yTop, 0f).setColor(argb).setUv(g.u0, g.v0);
            c.addVertex(x0, yBottom, 0f).setColor(argb).setUv(g.u0, g.v1);
            c.addVertex(x1, yBottom, 0f).setColor(argb).setUv(g.u1, g.v1);
            c.addVertex(x1, yTop, 0f).setColor(argb).setUv(g.u1, g.v0);

            penX += Math.round(g.advance * cs);
        }
    }

    @Override
    public RenderPipeline pipeline() {
        return (RenderPipeline) Blaze3DGuiText.pipeline();
    }

    @Override
    public TextureSetup textureSetup() {
        return textureSetup;
    }

    @Override
    public ScreenRectangle scissorArea() {
        return null;
    }

    @Override
    public ScreenRectangle bounds() {
        return bounds;
    }
}
