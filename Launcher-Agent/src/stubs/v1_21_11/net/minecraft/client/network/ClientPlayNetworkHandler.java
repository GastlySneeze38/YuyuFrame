package net.minecraft.client.network;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code hig}) — connexion de jeu.
 * Pendant de {@code ClientPacketListener} en 26.1.2.
 *
 * <p>Pour la liste des joueurs, seule la surcharge par PSEUDO est déclarée. La 26.1.2 cherche l'entrée par
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

    /** Renommage : {@code sendChat(String)} en 26.1.2. */
    public void sendChatMessage(String message) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Commande SANS le {@code /} initial — {@code sendCommand(String)} en 26.1.2. */
    public void sendChatCommand(String command) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Entrée de liste de serveurs, {@code null} en « quick play » — {@code getServerData()} en 26.1.2. */
    public ServerInfo getServerInfo() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Connexion réseau brute — {@code getConnection()} des deux côtés, mais le TYPE rendu change de nom. */
    public net.minecraft.network.ClientConnection getConnection() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /**
     * Arbre des commandes déclarées par le serveur — {@code getCommands()} en
     * 26.1.2.
     *
     * <p>{@code CommandDispatcher} vient de brigadier, bibliothèque externe
     * NON obfusquée : son nom est le même partout, et le remappeur le laisse
     * tel quel à juste titre.
     */
    public com.mojang.brigadier.CommandDispatcher<?> getCommandDispatcher() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
