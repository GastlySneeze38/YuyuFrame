package com.yuyuframe.launcheragent.runtime.game;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;

/**
 * Accès PARTAGÉ à la connexion au serveur — <b>zéro réflexion, zéro type du
 * jeu</b>. Quatrième façade, après {@link ClientData}, {@link PlayerData} et
 * {@link ChatData}.
 *
 * <p>Toute cette famille de noms change d'une version à l'autre :
 * {@code ClientPacketListener}/{@code ClientPlayNetworkHandler},
 * {@code sendChat}/{@code sendChatMessage},
 * {@code sendCommand}/{@code sendChatCommand},
 * {@code getServerData}/{@code getServerInfo},
 * {@code getCommands}/{@code getCommandDispatcher},
 * {@code getRemoteAddress}/{@code getAddress}. Aucun appelant n'a à le savoir.
 */
public final class NetworkData {
    private NetworkData() {}

    /**
     * Poignée OPAQUE de la connexion courante, ou {@code null} en solo.
     *
     * <p>À n'utiliser QUE pour l'identité (« est-ce toujours la même
     * connexion ? »). Tout le reste a son propre appel ci-dessous.
     */
    public static Object connection() {
        return AccessorRegistry.get(AccessPoint.NETWORK_CONNECTION, null);
    }

    /** Envoie un message de chat — {@code true} s'il est parti. */
    public static boolean sendChat(String message) {
        if (message == null || message.isEmpty()) return false;
        Object v = AccessorRegistry.invoke(AccessPoint.NETWORK_SEND_CHAT, null, message);
        return v instanceof Boolean && (Boolean) v;
    }

    /**
     * Envoie une COMMANDE — {@code true} si elle est partie.
     *
     * @param command sans le {@code /} initial. La distinction avec
     *                {@link #sendChat} n'est pas cosmétique : une commande
     *                passe par un paquet différent, et l'envoyer comme un
     *                message de chat l'afficherait au lieu de l'exécuter.
     */
    public static boolean sendCommand(String command) {
        if (command == null || command.isEmpty()) return false;
        Object v = AccessorRegistry.invoke(AccessPoint.NETWORK_SEND_COMMAND, null, command);
        return v instanceof Boolean && (Boolean) v;
    }

    /**
     * Le serveur déclare-t-il cette commande ? {@code false} en solo ou si
     * l'arbre de commandes n'est pas lisible.
     */
    public static boolean hasCommand(String name) {
        if (name == null || name.isEmpty()) return false;
        Object v = AccessorRegistry.invoke(AccessPoint.NETWORK_HAS_COMMAND, null, name);
        return v instanceof Boolean && (Boolean) v;
    }

    /**
     * Les deux sources BRUTES de l'adresse du serveur — {@code {String ip,
     * java.net.SocketAddress distante}}, l'une ou l'autre pouvant être nulle —
     * ou {@code null} en solo.
     *
     * <p>L'appelant choisit et normalise : voir
     * {@link AccessPoint#NETWORK_SERVER_ADDRESS}.
     */
    public static Object[] serverAddressSources() {
        Object v = AccessorRegistry.get(AccessPoint.NETWORK_SERVER_ADDRESS, null);
        return v instanceof Object[] && ((Object[]) v).length == 2 ? (Object[]) v : null;
    }
}
