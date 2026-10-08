package net.minecraft.world.entity;

/**
 * Stub compile-only (26.1+). D'abord simple marqueur pour typer un paramètre
 * capturé ; {@code isInvisible} ajoutée pour le nametag du module « Joueur »
 * (vérifiée javap sur le jar client 26.1.2, déclarée sur {@code Entity}).
 */
public abstract class Entity {

    public boolean isInvisible() { return false; }
}
