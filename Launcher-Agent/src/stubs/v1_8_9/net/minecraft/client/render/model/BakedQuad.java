package net.minecraft.client.render.model;

/** Stub compile-only 1.8.9, nom Yarn legacy ({@code bgg}). */
public class BakedQuad {

    protected BakedQuad() {
    }

    /**
     * {@code bgg.a()} — 28 entiers, 7 par sommet : x, y, z (flottants), couleur
     * (ombrage de face déjà inclus), u, v (flottants), un entier inutilisé.
     */
    public int[] getVertexData() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bgg.d()} — la face vers laquelle le quad est tourné. */
    public net.minecraft.util.math.Direction getFace() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bgg.c()} — le numéro de la couche dont le quad prend la teinte. */
    public int getColorIndex() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code bgg.b()} — vrai si le quad prend sa teinte de l'objet (toute couche d'un modèle d'objet plat). */
    public boolean hasColor() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
