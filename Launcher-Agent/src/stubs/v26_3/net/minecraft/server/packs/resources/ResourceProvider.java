package net.minecraft.server.packs.resources;

import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Stub compile-only (26.1+) — porte {@code getResource}, la méthode qui
 * applique RÉELLEMENT les resource packs (elle rend la version du pack de plus
 * haute priorité, pas celle du jar).
 */
public interface ResourceProvider {
    Optional<Resource> getResource(Identifier id);
}
