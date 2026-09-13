package net.minecraft.network;

import java.net.SocketAddress;

/**
 * Stub compile-only (26.1+) — juste {@code getRemoteAddress()}, le repli de
 * {@code MacroModule} quand le jeu ne fournit pas d'entrée de liste de
 * serveurs (connexion directe au lancement).
 */
public abstract class Connection {
    public SocketAddress getRemoteAddress() { return null; }
}
