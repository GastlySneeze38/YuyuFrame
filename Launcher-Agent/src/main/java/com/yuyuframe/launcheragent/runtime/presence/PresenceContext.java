package com.yuyuframe.launcheragent.runtime.presence;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.game.NetworkData;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.UUID;

/**
 * Dit au noyau natif où l'on joue et sous quelle identité
 * ({@link NativePresence#context}) — ce dont il a besoin sur un serveur de jeu
 * en mode hors ligne. Ces serveurs donnent à chacun un UUID dérivé de son
 * pseudo : deux homonymes ne s'y distinguent que par le serveur. L'adresse
 * entre donc dans l'empreinte des joueurs, et c'est le jeu qui s'annonce, lui
 * seul sachant où il est.
 *
 * <p>L'adresse a deux formes, parce que deux joueurs du même serveur n'ont
 * pas forcément tapé la même chose : le nom tapé, et l'adresse IP réellement
 * jointe. Deux joueurs se retrouvent dès que l'une des deux concorde. Aucune
 * ne quitte la machine en clair : le noyau n'en envoie que des empreintes.
 *
 * <p>Le réglage « Serveurs Microsoft uniquement » du module Joueur
 * ({@link PlayerStyle#onlineOnly}) coupe tout cela : aucune adresse n'est
 * donnée au noyau, qui ne reconnaît alors que les comptes Microsoft et
 * n'annonce rien de lui-même.
 *
 * <p>Appelé à chaque image, quel que soit l'état du module : apparaître aux
 * autres ne dépend pas de ce que l'on choisit d'afficher chez soi. Le travail
 * n'est fait qu'une fois par seconde.
 */
public final class PresenceContext {

    private PresenceContext() {
    }

    private static final long EVERY_NANOS = 1_000_000_000L;
    private static final int DEFAULT_PORT = 25565;

    private static long lastRun;
    private static boolean errorLogged;

    public static void tick() {
        long now = System.nanoTime();
        if (lastRun != 0L && now - lastRun < EVERY_NANOS) {
            return;
        }
        lastRun = now;
        if (!NativePresence.available()) {
            return;
        }
        try {
            refresh();
        } catch (Throwable error) {
            if (!errorLogged) {
                errorLogged = true;
                LauncherLog.err("[PRESENCE] Contexte du serveur illisible", error);
            }
        }
    }

    private static void refresh() {
        Object[] sources = PlayerStyle.onlineOnly ? null : NetworkData.serverAddressSources();
        String host = null;
        String ip = null;
        if (sources != null) {
            host = sources[0] instanceof String ? typedForm((String) sources[0]) : null;
            ip = sources[1] instanceof InetSocketAddress ? joinedForm((InetSocketAddress) sources[1]) : null;
        }
        UUID id = null;
        String name = null;
        if (host != null || ip != null) {
            Object profile = AccessorRegistry.get(AccessPoint.CLIENT_PLAYER_PROFILE, null);
            if (profile instanceof Object[] && ((Object[]) profile).length == 2) {
                Object[] parts = (Object[]) profile;
                if (parts[0] instanceof UUID && parts[1] instanceof String) {
                    id = (UUID) parts[0];
                    name = (String) parts[1];
                }
            }
        }
        NativePresence.context(host, ip, id, name);
    }

    /**
     * L'adresse tapée par le joueur, ramenée à « hôte:port » en minuscules —
     * {@code Play.Exemple.fr} et {@code play.exemple.fr:25565} sont le même
     * serveur. {@code null} pour ce qui ne désigne pas un serveur public
     * (machine locale, réseau privé : la même adresse y nomme des serveurs
     * différents d'une maison à l'autre).
     */
    static String typedForm(String typed) {
        String text = typed.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        String host = text;
        int port = DEFAULT_PORT;
        if (text.startsWith("[")) {
            // IPv6 entre crochets, port éventuel derrière.
            int close = text.indexOf(']');
            if (close < 0) {
                return null;
            }
            host = text.substring(1, close);
            String rest = text.substring(close + 1);
            if (rest.startsWith(":")) {
                port = parsePort(rest.substring(1));
            }
        } else {
            int colon = text.indexOf(':');
            // Un seul deux-points : « hôte:port ». Plusieurs : une IPv6 nue, sans port.
            if (colon >= 0 && colon == text.lastIndexOf(':')) {
                host = text.substring(0, colon);
                port = parsePort(text.substring(colon + 1));
            }
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || port <= 0 || host.equals("localhost")) {
            return null;
        }
        boolean literal = host.indexOf(':') >= 0 || host.matches("[0-9.]+");
        if (literal) {
            try {
                // Une adresse littérale : aucune requête DNS n'est faite.
                InetAddress address = InetAddress.getByName(host);
                if (isPrivate(address)) {
                    return null;
                }
                return ipText(address) + ":" + port;
            } catch (Exception malformed) {
                return null;
            }
        }
        return host + ":" + port;
    }

    /** L'adresse IP réellement jointe, « ip:port » ; {@code null} si elle est privée ou pas encore connue. */
    static String joinedForm(InetSocketAddress remote) {
        InetAddress address = remote.getAddress();
        if (address == null || isPrivate(address)) {
            return null;
        }
        return ipText(address) + ":" + remote.getPort();
    }

    private static boolean isPrivate(InetAddress address) {
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
            || address.isAnyLocalAddress();
    }

    /** L'adresse écrite toujours de la même façon : une IPv6 entre crochets, sans nom de zone. */
    private static String ipText(InetAddress address) {
        String text = address.getHostAddress();
        int zone = text.indexOf('%');
        if (zone >= 0) {
            text = text.substring(0, zone);
        }
        return text.indexOf(':') >= 0 ? "[" + text + "]" : text;
    }

    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text);
            return port > 0 && port <= 65535 ? port : -1;
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }
}
