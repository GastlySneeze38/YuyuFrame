package net.minecraft.resource;

import net.minecraft.util.Identifier;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code bni}) — interface qui
 * DÉCLARE {@code getResource} (pas de {@code ResourceFactory} sur cette
 * version) ; lève {@code IOException} au lieu de rendre un {@code Optional}.
 */
public interface ResourceManager {

    Resource getResource(Identifier id) throws java.io.IOException;
}
