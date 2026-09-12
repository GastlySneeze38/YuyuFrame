package net.minecraft.resource;

import net.minecraft.util.Identifier;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code bbc}) — classe DÉCLARANTE de
 * {@code getResource}.
 *
 * <p>Même raison qu'{@code ComponentsAccess} : {@code ResourceManager} en
 * hérite, mais c'est ICI que Yarn range la méthode, et c'est donc ce nom que
 * doit porter l'appel.
 */
public class ResourceFactory {

    protected ResourceFactory() {
    }

    /** {@code Optional<Resource>} en jeu — brut ici, l'effacement suffit. */
    public java.util.Optional getResource(Identifier id) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
