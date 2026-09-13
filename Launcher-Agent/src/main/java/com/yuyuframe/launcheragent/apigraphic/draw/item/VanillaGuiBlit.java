package com.yuyuframe.launcheragent.apigraphic.draw.item;

/**
 * Un blit de texture GUI vanilla BRUTE en attente — jumeau de
 * {@link VanillaItemIcon} pour les fonds de fenêtre de conteneur (ex.
 * {@code textures/gui/container/shulker_box.png}).
 *
 * <p>« Brut » par opposition à l'atlas de sprites : la région source
 * ({@link #u}/{@link #v}) et les dimensions RÉELLES du fichier PNG
 * ({@link #texW}/{@link #texH}) sont explicites, ce que l'atlas ne permet pas.
 *
 * <p>Structure de données pure, comme {@link VanillaItemIcon} : aucune ère,
 * aucune version, aucun type du jeu.
 */
public final class VanillaGuiBlit {

    /** Chemin RELATIF, avec extension — ex. {@code "textures/gui/container/shulker_box.png"}. */
    public final String texturePath;

    /** Destination à l'écran, en pixels GUI vanilla (origine haut-gauche). */
    public final int guiX, guiY, guiW, guiH;

    /** Région source dans la texture, et dimensions RÉELLES du fichier PNG. */
    public final float u, v, texW, texH;

    public VanillaGuiBlit(String texturePath, int guiX, int guiY, int guiW, int guiH,
                          float u, float v, float texW, float texH) {
        this.texturePath = texturePath;
        this.guiX = guiX;
        this.guiY = guiY;
        this.guiW = guiW;
        this.guiH = guiH;
        this.u = u;
        this.v = v;
        this.texW = texW;
        this.texH = texH;
    }
}
