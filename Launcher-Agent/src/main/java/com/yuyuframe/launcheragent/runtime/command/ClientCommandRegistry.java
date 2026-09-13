package com.yuyuframe.launcheragent.runtime.command;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registre plat des commandes client (voir {@link ClientCommand}) —
 * résolution par préfixe le PLUS LONG d'abord ("yf safe-mode" avant "yf"),
 * pour qu'un nom multi-mots ("yf safe-mode") et un nom simple ("yf")
 * cohabitent sans dispatcher spécial par "namespace".
 *
 * <h2>Chemin d'une commande (réparé le 2026-09-13)</h2>
 *
 * {@link #bootstrap()} s'abonne à {@link HookPoint#COMMAND_SEND}, dispatché
 * par {@code CommandSendMixin261}/{@code CommandSendMixin1211} sur la méthode
 * d'envoi de COMMANDE du jeu. Il était jusqu'ici abonné à
 * {@link HookPoint#CHAT_SEND} — l'envoi de MESSAGE —, où aucune commande
 * n'arrive jamais : depuis la 1.19, {@code ChatScreen} route tout texte en
 * « / » vers {@code sendCommand}/{@code sendChatCommand}. Aucune commande
 * {@code /yf} ne fonctionnait, sur aucune version.
 *
 * <h2>Exécution à l'image suivante</h2>
 *
 * Le hook RECONNAÎT la commande tout de suite (pour annuler son envoi au
 * serveur) mais ne l'EXÉCUTE qu'au prochain {@link #tick()}. Relu dans le
 * bytecode de {@code ChatScreen.keyPressed} (26.1.2 et 1.21.11) : l'envoi est
 * suivi, dans le même appel, de la fermeture de l'écran de chat. Exécutée sur
 * place, {@code /yf} ouvrait le menu… aussitôt remplacé par cette fermeture.
 *
 * <h2>Suggestions</h2>
 *
 * L'écran de chat ne suggère que ce que contient l'arbre de commandes
 * brigadier de la connexion, envoyé par le serveur — nos commandes n'y sont
 * pas. À chaque arbre reçu ({@link HookPoint#COMMAND_TREE_RECEIVE}), on y
 * greffe nos noms et les valeurs de {@link ClientCommand#argumentSuggestions()}
 * ({@code AccessPoint.NETWORK_ADD_CLIENT_COMMANDS}). Le client ne remplace son
 * arbre qu'à ce moment-là (connexion, changement de monde, rechargement des
 * commandes côté serveur) : une greffe par réception, rien à chaque image.
 */
public final class ClientCommandRegistry {
    private ClientCommandRegistry() {}

    private static final Map<String, ClientCommand> COMMANDS = new LinkedHashMap<>();
    private static boolean bootstrapped = false;

    /** Commandes reconnues, en attente d'exécution — voir « Exécution à l'image suivante ». */
    private static final List<Runnable> PENDING = new ArrayList<>();

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;
        VanillaHookRegistry.register(HookPoint.COMMAND_SEND, ctx -> dispatch(ctx instanceof String ? (String) ctx : null));
        VanillaHookRegistry.register(HookPoint.COMMAND_TREE_RECEIVE, connection -> {
            addSuggestions(connection);
            return false;
        });
        YfCommands.registerAll();
        LauncherLog.agent(1, "[ClientCommandRegistry] bootstrap — " + COMMANDS.size() + " commande(s) enregistrée(s)");
    }

    public static void register(ClientCommand command) {
        COMMANDS.put(command.name(), command);
    }

    /** Greffe nos commandes sur l'arbre qui vient d'arriver — voir « Suggestions ». */
    private static void addSuggestions(Object connection) {
        try {
            Object result = AccessorRegistry.invoke(AccessPoint.NETWORK_ADD_CLIENT_COMMANDS, connection,
                (Object) suggestionPaths());
            if (!Boolean.TRUE.equals(result) && !Boolean.FALSE.equals(result)) {
                LauncherLog.err("[ClientCommandRegistry] suggestions : arbre de commandes inaccessible sur cette version");
            }
        } catch (Throwable t) {
            LauncherLog.err("[ClientCommandRegistry] suggestions : " + t);
        }
    }

    /** Un chemin de mots par commande, plus un par valeur d'argument suggérée. */
    private static String[][] suggestionPaths() {
        List<String[]> paths = new ArrayList<>();
        for (ClientCommand cmd : COMMANDS.values()) {
            String[] words = cmd.name().split(" ");
            paths.add(words);
            for (String arg : cmd.argumentSuggestions()) {
                String[] withArg = Arrays.copyOf(words, words.length + 1);
                withArg[words.length] = arg;
                paths.add(withArg);
            }
        }
        return paths.toArray(new String[0][]);
    }

    /**
     * @param command la commande telle que le jeu l'envoie, SANS le « / »
     *     ({@code "yf safe-mode activate"})
     * @return {@code true} si elle correspond à une commande enregistrée —
     *     l'appelant annule alors l'envoi au serveur, et l'exécution est
     *     planifiée pour le prochain {@link #tick()} ; {@code false} sinon
     *     (commande serveur, chemin vanilla inchangé)
     */
    public static boolean dispatch(String command) {
        if (command == null) return false;
        String trimmed = command.trim();
        if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length == 0 || tokens[0].isEmpty()) return false;

        for (int len = tokens.length; len >= 1; len--) {
            final String candidate = String.join(" ", Arrays.copyOfRange(tokens, 0, len));
            final ClientCommand cmd = COMMANDS.get(candidate);
            if (cmd == null) continue;
            final String[] args = Arrays.copyOfRange(tokens, len, tokens.length);
            synchronized (PENDING) {
                PENDING.add(() -> {
                    try {
                        cmd.execute(args);
                    } catch (Throwable t) {
                        LauncherLog.err("[ClientCommandRegistry] \"" + candidate + "\" a levé : " + t);
                    }
                });
            }
            return true;
        }
        return false;
    }

    /** Exécute les commandes reconnues depuis le dernier appel — appelé par {@code ModuleRegistry.tickAll}. */
    public static void tick() {
        List<Runnable> batch;
        synchronized (PENDING) {
            if (PENDING.isEmpty()) return;
            batch = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        for (Runnable r : batch) r.run();
    }
}
