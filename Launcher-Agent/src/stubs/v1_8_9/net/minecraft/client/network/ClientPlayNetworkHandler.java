package net.minecraft.client.network;

import net.minecraft.network.ClientConnection;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code bcy}).
 *
 * <p>Pas de Brigadier sur cette version : aucun {@code getCommandDispatcher()}.
 */
public class ClientPlayNetworkHandler {

    private ClientPlayNetworkHandler() {
    }

    public PlayerListEntry getPlayerListEntry(String name) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public ClientConnection getClientConnection() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
