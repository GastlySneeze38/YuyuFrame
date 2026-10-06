package net.minecraft.client.render.model;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code boq}) — un modèle de bloc
 * ou d'objet déjà découpé en quads.
 */
public interface BakedModel {

    /** {@code boq.a(cq)} — les quads tournés vers une face. */
    java.util.List getByDirection(net.minecraft.util.math.Direction direction);

    /** {@code boq.a()} — les quads sans face attitrée. */
    java.util.List getQuads();

    /** {@code boq.b()} — le modèle accepte l'éclairage adouci. */
    boolean useAmbientOcclusion();
}
