package net.minecraft.world.entity.player;

/**
 * Stub compile-only (26.1+). D'abord simple marqueur pour les mixins de
 * {@code apimixin/v26_1/hud/} ; {@code getGameProfile} ajoutée pour le module
 * « Joueur » (vérifiée javap sur le jar client 26.1.2, déclarée sur {@code Player}).
 */
public abstract class Player {

    public com.mojang.authlib.GameProfile getGameProfile() { return null; }
}
