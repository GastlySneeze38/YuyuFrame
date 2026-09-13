package net.minecraft.client.gui;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code gir}) — contexte de dessin reçu
 * par les hooks {@code HUD_EXTRACT_*} sur cette version (la 26.1.2 y transporte
 * un {@code GuiGraphicsExtractor}).
 *
 * <p>Son {@code GuiRenderState} n'est PAS exposé (champ privé {@code state}) :
 * il est capté à la construction par {@code DrawContextStateMixin1211} et
 * retrouvé via {@code VanillaGuiStateBinding} — jamais par réflexion.
 */
public class DrawContext {

    public int getScaledWindowWidth() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public int getScaledWindowHeight() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    private DrawContext() {
    }
}
