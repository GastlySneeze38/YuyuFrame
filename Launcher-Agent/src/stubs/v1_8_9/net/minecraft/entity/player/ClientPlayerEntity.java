package net.minecraft.entity.player;

import net.minecraft.client.network.AbstractClientPlayerEntity;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code bew}).
 *
 * <p>PIÈGE DE PAQUET : Yarn legacy range cette classe sous
 * {@code entity/player}, pas sous {@code client/network} comme en moderne.
 * {@code sendChatMessage} envoie AUSSI les commandes (texte préfixé « / »).
 */
public class ClientPlayerEntity extends AbstractClientPlayerEntity {

    private ClientPlayerEntity() {
    }

    public void sendChatMessage(String message) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
