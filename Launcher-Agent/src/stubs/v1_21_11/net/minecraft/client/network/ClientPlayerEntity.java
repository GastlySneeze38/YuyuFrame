package net.minecraft.client.network;

import net.minecraft.entity.player.PlayerEntity;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code hnh}) — le joueur local.
 *
 * <p>AUCUN membre propre, volontairement : tout ce que les liaisons lisent
 * (position, rotation, santé, cooldown) est déclaré plus haut dans la
 * hiérarchie. Les appeler sur une variable typée ici ferait écrire au bytecode
 * {@code ClientPlayerEntity.getX()} au lieu de {@code Entity.getX()}, et la
 * traduction échouerait — voir {@code net.minecraft.entity.Entity} pour le
 * détail, et {@code AccessorBindings1211}, qui type donc ses variables locales
 * avec la classe DÉCLARANTE de chaque appel.
 */
public class ClientPlayerEntity extends PlayerEntity {

    private ClientPlayerEntity() {
    }
}
