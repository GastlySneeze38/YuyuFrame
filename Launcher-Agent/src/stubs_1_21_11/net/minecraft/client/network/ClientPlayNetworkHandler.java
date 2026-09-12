package net.minecraft.client.network;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code hig}) — connexion de jeu.
 * Pendant de {@code ClientPacketListener} en 26.1.2.
 *
 * <p>Seule la surcharge par PSEUDO est déclarée. La 26.1.2 cherche l'entrée par
 * UUID, mais {@code Entity} n'expose pas de {@code getUuid()} dans les mappings
 * Yarn 1.21.11 (vérifié : seul le champ {@code uuid} et {@code setUuid} y
 * figurent) — d'où ce chemin différent, que le point d'accès
 * {@code PLAYER_PING} rend invisible à l'appelant.
 */
public class ClientPlayNetworkHandler {

    private ClientPlayNetworkHandler() {
    }

    public PlayerListEntry getPlayerListEntry(String profileName) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
