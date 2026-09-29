package net.minecraft.client.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code gir}) — contexte de dessin reçu
 * par les hooks {@code HUD_EXTRACT_*} sur cette version (la 26.1.2 y transporte
 * un {@code GuiGraphicsExtractor}).
 *
 * <p>Son {@code GuiRenderState} n'est PAS exposé (champ privé {@code state}) :
 * il est capté à la construction par {@code DrawContextStateMixin1211} et
 * retrouvé via {@code VanillaGuiStateBinding} — jamais par réflexion.
 *
 * <h2>Pont des icônes d'objet (2026-09-13)</h2>
 *
 * Membres ajoutés pour {@code VanillaGuiSink1211.drawVanillaItems}, qui
 * remplace le pont réflexif. Tous PUBLICS, vérifiés sur le jar
 * {@code client-intermediary.jar} réel, et désignés par leur descripteur
 * EXACT — plusieurs ont des surcharges aux noms d'exécution différents
 * ({@code drawItem} en a quatre : c'est ce qui avait fait disparaître les
 * icônes en v1105).
 *
 * <p>{@code drawItemBar} n'est PAS déclaré : il est PRIVÉ sur cette version
 * ({@code method_64860}). La barre passe par {@link #drawStackOverlay}, public,
 * qui l'appelle — c'est aussi la méthode de la vraie hotbar.
 */
public class DrawContext {

    /** Constructeur PUBLIC à 4 arguments (l'autre, à pile de matrices, est privé). */
    public DrawContext(MinecraftClient client, GuiRenderState state, int mouseX, int mouseY) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Pile de pose (Yarn {@code getMatrices}, {@code method_51448} — yarn-1.21.11-mergedv2). */
    public org.joml.Matrix3x2fStack getMatrices() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public int getScaledWindowWidth() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public int getScaledWindowHeight() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code method_51427} — la surcharge {@code (ItemStack, int, int)}, taille native 16x16. */
    public void drawItem(ItemStack stack, int x, int y) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /**
     * {@code method_51431} — décorations de pile : barre de durabilité, compteur
     * (seulement s'il est différent de 1) et voile de recharge.
     */
    public void drawStackOverlay(TextRenderer textRenderer, ItemStack stack, int x, int y) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /**
     * {@code method_25290} — blit BRUT d'une texture, région source explicite :
     * {@code (x, y, u, v, largeur, hauteur, largeurTexture, hauteurTexture)}.
     * Nommé « blit » en 26.1.2.
     */
    public void drawTexture(RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v,
                            int width, int height, int textureWidth, int textureHeight) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code method_52706} — sprite de l'atlas GUI. Nommé « blitSprite » en 26.1.2. */
    public void drawGuiTexture(RenderPipeline pipeline, Identifier sprite, int x, int y, int width, int height) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
