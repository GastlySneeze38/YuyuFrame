package net.minecraft.client.multiplayer;

import java.util.UUID;

/** Stub compile-only (26.1+) — méthode publique pour {@code PingModule}. */
public abstract class ClientPacketListener {
    public PlayerInfo getPlayerInfo(UUID uuid) { return null; }

    /**
     * Envoie une COMMANDE (sans le slash initial) — {@code MacroModule} y
     * fait passer ses macros et l'auto-login. Volontairement distinct de
     * {@link #sendChat(String)} : une commande envoyée comme message de chat
     * partirait en clair dans le canal public, ce qui pour un mot de passe
     * serait une fuite pure et simple.
     */
    public void sendCommand(String command) {}

    /** Envoie un message de chat ordinaire. Descripteur vérifié : {@code (Ljava/lang/String;)V}. */
    public void sendChat(String message) {}

    /**
     * Arbre des commandes DÉCLARÉES par le serveur — c'est lui qui alimente
     * la complétion. {@code MacroModule} y cherche « login » : pour que
     * {@code /login} fonctionne, le serveur doit l'y avoir déclaré, ce qui en
     * fait un signal structurel et indépendant de la langue.
     */
    public com.mojang.brigadier.CommandDispatcher<?> getCommands() { return null; }

    /** Entrée de liste des serveurs de la connexion en cours — {@code null} en solo. Descripteur vérifié : {@code ()Lnet/minecraft/client/multiplayer/ServerData;}. */
    public ServerData getServerData() { return null; }
}
