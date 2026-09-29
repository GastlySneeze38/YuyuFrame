package net.minecraft.client.gui;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.render.TextureSetup;

/**
 * Stub compile-only (26.1+) — l'objet que vanilla passe à chaque méthode
 * {@code Gui.extract*}, et donc à nos hooks {@code HUD_EXTRACT_*}.
 *
 * <p>Était un marqueur vide (il ne servait qu'à typer un paramètre capturé
 * par {@code ClearOverlaysMixin261}). Étendu le 2026-08-30 pour l'étape 1 du
 * passage de notre rendu dans l'état de GUI vanilla : c'est par ces méthodes
 * qu'on peut émettre un élément À LA POSITION Z du hook où l'on se trouve,
 * au lieu de dessiner dans une passe séparée après coup — voir
 * {@code apigraphic/era/blaze3d/v26_1/vanillagui/VanillaGuiLayer} pour le pourquoi.
 *
 * <p>Signatures vérifiées sur {@code net/minecraft/client/gui/GuiGraphicsExtractor.class}
 * du jar client 26.1.2. La surcharge {@code fill(RenderPipeline, TextureSetup,
 * int, int, int, int)} est celle qui compte : elle accepte NOTRE propre
 * pipeline shader.
 */
public abstract class GuiGraphicsExtractor {

    /** Largeur de l'écran en pixels GUI (déjà divisée par l'échelle d'interface). */
    public int guiWidth() { return 0; }

    /** Hauteur de l'écran en pixels GUI. */
    public int guiHeight() { return 0; }

    /** Quad de couleur pleine, pipeline vanilla — {@code color} en ARGB. */
    public void fill(int x0, int y0, int x1, int y1, int color) {}

    /**
     * Quad rendu avec un pipeline ARBITRAIRE et un descripteur de texture
     * arbitraire. C'est le point d'entrée qui rend le portage possible : le
     * pipeline peut être un des nôtres ({@code ShaderPipelineFactory.buildPipeline}).
     */
    public void fill(RenderPipeline pipeline, TextureSetup textureSetup, int x0, int y0, int x1, int y1) {}

    /** Ouvre une nouvelle strate — tout ce qui suit passe au-dessus de ce qui précède. */
    public void nextStratum() {}

    /** Demande que le décor soit flouté sous la strate courante (mécanisme de flou natif de vanilla). */
    public void blurBeforeThisStratum() {}

    // ── Pont des icônes d'objet (2026-09-13) ─────────────────────────────
    //
    // Pour VanillaGuiSink261.drawVanillaItems, qui remplace le pont réflexif.
    // Tous PUBLICS sur le jar 26.1.2, descripteurs exacts. itemBar n'est PAS
    // déclaré : il est PRIVÉ — la barre passe par itemDecorations, public, qui
    // l'appelle (méthode de la vraie hotbar).

    /** Icône d'objet, taille native 16x16 — surcharge {@code (ItemStack, int, int)}. */
    public void item(net.minecraft.world.item.ItemStack stack, int x, int y) {}

    /** Décorations de pile : barre de durabilité, compteur (s'il diffère de 1), voile de recharge. */
    public void itemDecorations(Font font, net.minecraft.world.item.ItemStack stack, int x, int y) {}

    /** Blit BRUT, région source explicite : {@code (x, y, u, v, largeur, hauteur, largeurTexture, hauteurTexture)}. */
    public void blit(RenderPipeline pipeline, net.minecraft.resources.Identifier texture, int x, int y,
                     float u, float v, int width, int height, int textureWidth, int textureHeight) {}

    /** Sprite de l'atlas GUI. */
    public void blitSprite(RenderPipeline pipeline, net.minecraft.resources.Identifier sprite,
                           int x, int y, int width, int height) {}
}
