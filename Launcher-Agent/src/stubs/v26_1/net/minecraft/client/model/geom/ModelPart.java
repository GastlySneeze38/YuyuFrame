package net.minecraft.client.model.geom;

/**
 * Stub compile-only (26.1+) — une partie de modèle du jeu : pivot, angles,
 * cubes, enfants. Lue champ par champ par {@code ModelPartAccessor261} et ses
 * voisins pour recopier un modèle vanilla dans notre {@code UiModel}
 * (brique 3D de l'API graphique). Aucune méthode n'est appelée directement.
 *
 * <p>Classes imbriquées vérifiées par javap sur les jars 26.1.2 et 26.3 :
 * {@code Polygon} et {@code Vertex} sont des records, le genre exact n'entre
 * pas dans les descripteurs qu'on émet (tableaux et types de retour).
 */
public final class ModelPart {
    private ModelPart() {}

    /** Un pavé : ses six faces. */
    public static class Cube {
        private Cube() {}
    }

    /** Une face : quatre sommets. */
    public static final class Polygon {
        private Polygon() {}
    }

    /** Un sommet : position en pixels du modèle, coordonnées de texture de 0 à 1. */
    public static final class Vertex {
        private Vertex() {}
    }
}
