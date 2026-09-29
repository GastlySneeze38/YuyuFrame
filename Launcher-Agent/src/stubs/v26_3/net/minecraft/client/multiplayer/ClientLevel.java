package net.minecraft.client.multiplayer;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.BiomeManager;

/** Stub compile-only (26.1+) — {@code getBiomeManager()} publique (vérifiée via javap, voir {@code CoordsModule}), ajoutée en plus du marqueur d'origine. */
public abstract class ClientLevel {
    public BiomeManager getBiomeManager() { return null; }
    /**
     * Clé de la dimension courante ({@code minecraft:overworld}…) — sert au
     * cloisonnement Mumble, voir {@code MumbleLinkModule}. Réellement déclarée
     * sur {@code Level}, dont {@code ClientLevel} hérite : stub « à plat »
     * comme partout ici, la JVM résout par la hiérarchie réelle. Descripteur
     * vérifié sur le jar 26.1.2 : {@code dimension ()Lnet/minecraft/resources/ResourceKey;}.
     */
    public ResourceKey<?> dimension() { return null; }
}
