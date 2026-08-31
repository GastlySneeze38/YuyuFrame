package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.mojang.brigadier.CommandDispatcher;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPollerModern;
import com.yuyuframe.launcheragent.apimixin.v26_1.core.ServerDataAccessor261;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingModal;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Macros de commandes liées à une touche, et connexion automatique aux
 * serveurs qui demandent un login.
 *
 * <p>Deux fonctions dans un seul module parce qu'elles partagent tout leur
 * outillage : l'envoi de commande, la notion de serveur courant, et l'écran
 * d'édition de listes.
 *
 * <h2>Détection du login — pourquoi elle est fiable</h2>
 *
 * La première idée était de guetter un message du genre « connectez-vous avec
 * /login ». Mauvaise idée, et l'utilisateur l'a écarté d'emblée : la
 * formulation dépend du plugin, de la langue et de la configuration du
 * serveur. Il fallait quelque chose que TOUT système de login possède par
 * construction.
 *
 * <p>C'est l'ARBRE DE COMMANDES. Pour que {@code /login <mdp>} fonctionne, le
 * serveur doit déclarer la commande au client
 * ({@code ClientboundCommandsPacket}) — c'est ce qui alimente la complétion
 * au Tab. Pas de déclaration, pas de commande. On lit donc
 * {@code ClientPacketListener.getCommands()} et on regarde si la racine a un
 * enfant nommé « login » : indépendant de la langue, de la mise en forme, et
 * du plugin.
 *
 * <p>Limite assumée : la présence de {@code /login} prouve que le serveur A un
 * système d'authentification, pas que le joueur est actuellement déconnecté.
 * Si la session est encore valide, le serveur répondra « déjà connecté » —
 * sans conséquence. C'est pour ça qu'on n'essaie qu'UNE fois par connexion.
 *
 * <h2>Le mot de passe ne peut pas partir en chat public</h2>
 *
 * Il est envoyé par {@link ClientPacketListener#sendCommand}, jamais par
 * {@code sendChat}. La distinction n'est pas cosmétique : une commande
 * envoyée comme message de chat part en clair dans le canal public. C'est le
 * seul vrai risque de cette fonctionnalité, et il est écarté par
 * construction plutôt que par précaution.
 *
 * <p>Le mot de passe est stocké EN CLAIR dans le fichier de configuration —
 * il n'y a pas de coffre-fort dans l'agent, et c'est ce que font tous les
 * mods équivalents. À dire à l'utilisateur, pas à cacher.
 */
public final class MacroModule extends LauncherModule {

    /** Une touche, une commande. La commande est stockée SANS le slash initial. */
    public static final class Macro {
        public String key;
        public String command;

        public Macro(String key, String command) {
            this.key = key == null ? "" : key;
            this.command = command == null ? "" : command;
        }
    }

    /** Édité ligne par ligne dans l'écran de configuration ; persisté en bloc par un réglage opaque, voir {@link #settings}. */
    public final List<Macro> macros = new ArrayList<Macro>();

    /**
     * Adresse de serveur → mot de passe. {@link LinkedHashMap} et non
     * {@code HashMap} : l'ordre d'affichage dans l'écran d'édition doit être
     * stable d'une ouverture à l'autre.
     */
    public final Map<String, String> logins = new LinkedHashMap<String, String>();

    public boolean autoLogin = true;

    @Override
    protected void settings(SettingList s) {
        s.toggle("autoLogin", "Connexion automatique",
            "Envoie /login sur les serveurs qui déclarent cette commande. Le mot de passe est enregistré par adresse de serveur, dans l'écran du module.",
            "Réglages", null, () -> autoLogin, v -> autoLogin = v);

        // Les deux listes : persistées ICI, en un seul réglage chacune.
        // Les lignes d'édition ci-dessous sont ENGENDRÉES à partir d'elles et
        // marquées non-persistantes — sans quoi elles seraient sauvegardées en
        // double, et relues alors que les listes sont encore vides.
        s.opaque("macros", this::serializeMacros, this::deserializeMacros);
        s.opaque("logins", this::serializeLogins, this::deserializeLogins);

        // ── Une ligne par macro, UNE SEULE ligne ──────────────────────────
        //
        // Setting.Inline porte tous les contrôles d'une entrée sur la même
        // ligne : touche, commande, suppression. La version précédente en
        // étalait trois, chacune avec son libellé — « ce qui est horrible
        // c'est la configuration de la macro dans l'écran de config […] ça
        // doit prendre 1 seule ligne pour toutes ses composantes ».
        for (int i = 0; i < macros.size(); i++) {
            final Macro macro = macros.get(i);
            final int index = i;
            s.inlineKeyed("macro" + i, "Macros", "/spawn",
                () -> macro.key, v -> macro.key = v,
                () -> macro.command, v -> macro.command = v,
                () -> { macros.remove(index); invalidateSettings(); });
        }
        s.action("macro.add", "Ajouter une macro",
            "Choisis la touche, puis la commande.", "Macros",
            () -> SettingModal.request(new SettingModal("Nouvelle macro", answers -> {
                macros.add(new Macro(answers.get(0), answers.get(1)));
                invalidateSettings();
            },
            SettingModal.Step.key("Sur quelle touche ?"),
            SettingModal.Step.text("Quelle commande envoyer ?", "/spawn"))));

        for (Map.Entry<String, String> entry : new ArrayList<Map.Entry<String, String>>(logins.entrySet())) {
            final String address = entry.getKey();
            s.inlineLabeled("login." + address, "Connexion automatique", address, "mot de passe",
                () -> logins.get(address), v -> logins.put(address, v),
                () -> { logins.remove(address); invalidateSettings(); });
        }
        s.action("login.add", "Ajouter un serveur",
            "L'adresse est pré-remplie avec le serveur en cours. Le mot de passe part par /login, jamais en chat public, et est stocké en clair dans la configuration.",
            "Connexion automatique",
            () -> {
                // Adresse PRÉ-REMPLIE avec le serveur courant plutôt qu'une
                // saisie libre : une faute de frappe donnerait une entrée qui
                // ne se déclenche jamais, sans rien pour l'expliquer. Elle
                // reste modifiable, pour préparer un serveur hors ligne.
                final String current = currentServerAddress();
                SettingModal.request(new SettingModal("Nouveau serveur", answers -> {
                    String address = answers.get(0).trim();
                    if (address.isEmpty()) return;
                    logins.put(address, answers.get(1));
                    invalidateSettings();
                },
                new SettingModal.Step("Adresse du serveur", current == null ? "jouer.exemple.fr" : current, false),
                SettingModal.Step.text("Mot de passe", "mot de passe")));
            });
    }


    public MacroModule() {
        super("macros", "Macros",
            "Lance des commandes avec une touche, et connecte automatiquement aux serveurs qui demandent un login",
            "Macros de commandes et connexion automatique", false);
        iconUrl = icons8("keyboard");
    }

    // ── Sérialisation ─────────────────────────────────────────────────────
    //
    // Le magasin de configuration est un .properties : une chaîne par
    // réglage, sur une ligne. Les séparateurs sont donc des caractères de
    // CONTRÔLE, que ni une touche ni une commande ne peuvent contenir — un
    // ';' ou un '|' aurait fini par apparaître dans une commande et couper
    // l'entrée en deux. Properties les réécrit sous forme d'échappement
    // Unicode à l'enregistrement, la ligne reste donc valide.
    //
    // ⚠️ Ne PAS écrire cette séquence d'échappement en toutes lettres dans un
    // commentaire : javac décode les échappements Unicode AVANT d'analyser le
    // source, commentaires compris — « illegal unicode escape », et le jar
    // tombe à 49 Ko.
    private static final char ENTRY_SEP = '\u0001';
    private static final char FIELD_SEP = '\u0002';

    private String serializeMacros() {
        StringBuilder sb = new StringBuilder();
        for (Macro m : macros) {
            if (m.key.isEmpty() && m.command.isEmpty()) continue;
            if (sb.length() > 0) sb.append(ENTRY_SEP);
            sb.append(m.key).append(FIELD_SEP).append(m.command);
        }
        return sb.toString();
    }

    private void deserializeMacros(String raw) {
        macros.clear();
        if (raw == null || raw.isEmpty()) return;
        for (String entry : raw.split(String.valueOf(ENTRY_SEP), -1)) {
            if (entry.isEmpty()) continue;
            int sep = entry.indexOf(FIELD_SEP);
            if (sep < 0) continue;
            macros.add(new Macro(entry.substring(0, sep), entry.substring(sep + 1)));
        }
    }

    private String serializeLogins() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : logins.entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty()) continue;
            if (sb.length() > 0) sb.append(ENTRY_SEP);
            sb.append(e.getKey()).append(FIELD_SEP).append(e.getValue() == null ? "" : e.getValue());
        }
        return sb.toString();
    }

    private void deserializeLogins(String raw) {
        logins.clear();
        if (raw == null || raw.isEmpty()) return;
        for (String entry : raw.split(String.valueOf(ENTRY_SEP), -1)) {
            if (entry.isEmpty()) continue;
            int sep = entry.indexOf(FIELD_SEP);
            if (sep < 0) continue;
            logins.put(entry.substring(0, sep), entry.substring(sep + 1));
        }
    }

    // ── Macros ────────────────────────────────────────────────────────────

    /**
     * Touches maintenues au tick précédent — une macro se déclenche au FRONT
     * MONTANT. Sans ça, garder la touche enfoncée enverrait la commande 20
     * fois par seconde, ce qu'un serveur lit comme du spam.
     */
    private final java.util.Set<String> heldLastTick = new java.util.HashSet<String>();

    @Override
    public void onTick() {
        try {
            tickMacros();
            tickAutoLogin();
        } catch (Throwable t) {
            if (!tickErrorLogged) {
                tickErrorLogged = true;
                LauncherLog.err("[MacroModule] onTick: " + t);
            }
        }
    }

    private static boolean tickErrorLogged;

    private void tickMacros() {
        // UiInputPollerModern et non UiInputPoller : seule la variante
        // moderne expose la lecture par NOM de touche (GLFW). Sur 1.8.9 elle
        // est nulle — ce module ne cible pas ce bracket.
        UiInputPollerModern poller = UiInputPollerModern.ACTIVE;
        if (poller == null) return;

        // AUCUNE macro tant qu'un écran est ouvert : le joueur y tape du
        // texte (tchat, renommage d'enclume, champ de recherche). Sans cette
        // garde, écrire un message contenant la lettre d'une macro enverrait
        // la commande au milieu de la frappe.
        if (ClientData.screen() != null) {
            heldLastTick.clear();
            return;
        }

        for (Macro macro : macros) {
            if (macro.key == null || macro.key.isEmpty()) continue;
            if (macro.command == null || macro.command.trim().isEmpty()) continue;

            boolean down = poller.isKeyDownByName(macro.key);
            boolean wasDown = heldLastTick.contains(macro.key);
            if (down && !wasDown) run(macro.command);

            if (down) heldLastTick.add(macro.key);
            else heldLastTick.remove(macro.key);
        }
    }

    /**
     * Exécute le texte d'une macro : commande si elle commence par « / »,
     * message de chat sinon — la convention que le joueur a déjà en tête
     * quand il tape dans le tchat.
     */
    private void run(String text) {
        ClientPacketListener connection = ClientData.connection();
        if (connection == null) return;
        String trimmed = text.trim();
        try {
            if (trimmed.startsWith("/")) connection.sendCommand(trimmed.substring(1));
            else connection.sendChat(trimmed);
        } catch (Throwable t) {
            if (!runErrorLogged) {
                runErrorLogged = true;
                LauncherLog.err("[MacroModule] envoi de « " + trimmed + " » : " + t);
            }
        }
    }

    private static boolean runErrorLogged;

    // ── Connexion automatique ─────────────────────────────────────────────

    /** Noms sous lesquels la commande de connexion est déclarée, par ordre de préférence. */
    private static final String[] LOGIN_COMMANDS = { "login", "l", "connexion" };

    /**
     * La connexion pour laquelle on a déjà tenté — comparée par IDENTITÉ.
     * Chaque connexion à un serveur crée un nouvel objet, ce qui donne un
     * « une seule fois par connexion » exact et gratuit, sans avoir à
     * détecter les déconnexions.
     */
    private Object lastAttemptedConnection;

    private void tickAutoLogin() {
        if (!autoLogin) return;
        ClientPacketListener connection = ClientData.connection();
        if (connection == null) {
            lastAttemptedConnection = null;
            return;
        }
        if (connection == lastAttemptedConnection) return;

        String address = serverAddress(connection);
        if (address == null) return;                 // solo, ou serveur pas encore connu
        String password = logins.get(address);
        if (password == null || password.isEmpty()) return;

        String command = declaredLoginCommand(connection);
        if (command == null) return;                 // pas de système de login ici

        // Marqué AVANT l'envoi : si sendCommand lève, on ne veut surtout pas
        // réessayer à chaque tick avec un mot de passe.
        lastAttemptedConnection = connection;
        try {
            connection.sendCommand(command + " " + password);
            LauncherLog.info("[MacroModule] connexion automatique envoyée à " + address
                + " (commande /" + command + ")");
        } catch (Throwable t) {
            LauncherLog.err("[MacroModule] connexion automatique : " + t);
        }
    }

    /**
     * Nom de la commande de connexion DÉCLARÉE par le serveur, ou
     * {@code null} s'il n'en déclare aucune — voir la javadoc de classe pour
     * pourquoi ce signal vaut mieux que la lecture du chat.
     */
    private String declaredLoginCommand(ClientPacketListener connection) {
        try {
            CommandDispatcher<?> commands = connection.getCommands();
            if (commands == null || commands.getRoot() == null) return null;
            for (String name : LOGIN_COMMANDS) {
                if (commands.getRoot().getChild(name) != null) return name;
            }
        } catch (Throwable t) {
            if (!commandsErrorLogged) {
                commandsErrorLogged = true;
                LauncherLog.err("[MacroModule] lecture de l'arbre de commandes : " + t);
            }
        }
        return null;
    }

    private static boolean commandsErrorLogged;

    /**
     * Adresse du serveur courant, telle qu'elle apparaît dans la liste des
     * serveurs — c'est la clé sous laquelle le mot de passe est rangé.
     * {@code null} en solo.
     */
    public static String currentServerAddress() {
        ClientPacketListener connection = ClientData.connection();
        return connection == null ? null : serverAddress(connection);
    }

    /**
     * {@code Minecraft} n'a AUCUN champ portant le serveur courant (vérifié
     * en listant ses champs) — {@code getCurrentServer()} le dérive de la
     * connexion. On part donc directement de celle-ci, qu'on a déjà en main.
     */
    private static String serverAddress(ClientPacketListener connection) {
        try {
            ServerData server = connection.getServerData();
            if (!(server instanceof ServerDataAccessor261)) return null;   // solo, ou hors 26.1.2
            String ip = ((ServerDataAccessor261) server).la$ip();
            return (ip == null || ip.isEmpty()) ? null : ip;
        } catch (Throwable t) {
            if (!addressErrorLogged) {
                addressErrorLogged = true;
                LauncherLog.err("[MacroModule] adresse du serveur : " + t);
            }
            return null;
        }
    }

    private static boolean addressErrorLogged;
}
