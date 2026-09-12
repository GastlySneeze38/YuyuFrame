package net.minecraft.network;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code wu}) — pendant de
 * {@code Connection} en 26.1.2.
 *
 * <p>Un seul membre : l'adresse distante, repli quand le jeu n'a pas construit
 * d'entrée de liste de serveurs (« quick play »). Renommage à connaître :
 * {@code getRemoteAddress()} en 26.1.2.
 */
public class ClientConnection {

    private ClientConnection() {
    }

    public java.net.SocketAddress getAddress() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
