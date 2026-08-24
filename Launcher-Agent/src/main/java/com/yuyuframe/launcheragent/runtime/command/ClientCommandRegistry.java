package com.yuyuframe.launcheragent.runtime.command;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registre plat des commandes client (voir {@link ClientCommand}) —
 * résolution par préfixe le PLUS LONG d'abord ("yf safe-mode" avant "yf"),
 * pour qu'un nom multi-mots ("yf safe-mode") et un nom simple
 * ("shader-reload") cohabitent sans dispatcher spécial par "namespace".
 *
 * {@link #bootstrap()} — appelé une seule fois depuis {@code LauncherAgent.premain0()}
 * (PAS depuis un Mixin — pur Java, aucune dépendance au jeu, sûr à exécuter
 * inconditionnellement dès le tout début) : enregistre le hook {@link
 * HookPoint#CHAT_SEND} (dispatché par {@code ChatSendMixin261}, apimixin,
 * PAS encore tissé — voir sa javadoc, comme tout le reste d'apimixin cette
 * session) + les commandes intégrées ({@link YfCommands}).
 */
public final class ClientCommandRegistry {
    private ClientCommandRegistry() {}

    private static final Map<String, ClientCommand> COMMANDS = new LinkedHashMap<>();
    private static boolean bootstrapped = false;

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;
        VanillaHookRegistry.register(HookPoint.CHAT_SEND, ctx -> dispatch(ctx instanceof String ? (String) ctx : null));
        YfCommands.registerAll();
        LauncherLog.agent(1, "[ClientCommandRegistry] bootstrap — " + COMMANDS.size() + " commande(s) enregistrée(s)");
    }

    public static void register(ClientCommand command) {
        COMMANDS.put(command.name(), command);
    }

    /**
     * @return {@code true} si {@code rawMessage} correspondait à une commande
     * enregistrée (l'appelant — {@code ChatSendMixin261} — doit alors annuler
     * l'envoi vanilla), {@code false} sinon (message envoyé normalement au
     * serveur, chemin inchangé).
     */
    public static boolean dispatch(String rawMessage) {
        if (rawMessage == null) return false;
        String trimmed = rawMessage.trim();
        if (!trimmed.startsWith("/")) return false;
        String[] tokens = trimmed.substring(1).split("\\s+");
        if (tokens.length == 0 || tokens[0].isEmpty()) return false;

        for (int len = tokens.length; len >= 1; len--) {
            String candidate = String.join(" ", Arrays.copyOfRange(tokens, 0, len));
            ClientCommand cmd = COMMANDS.get(candidate);
            if (cmd == null) continue;
            String[] args = Arrays.copyOfRange(tokens, len, tokens.length);
            try {
                cmd.execute(args);
            } catch (Throwable t) {
                LauncherLog.err("[ClientCommandRegistry] \"" + candidate + "\" a levé : " + t);
            }
            return true;
        }
        return false;
    }
}
